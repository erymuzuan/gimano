import CryptoKit
import DeviceCheck
import Foundation

// MARK: - Geohash (proximity cells, ~1.2 × 0.6 km at precision 6)

enum Geohash {
    private static let alphabet = Array("0123456789bcdefghjkmnpqrstuvwxyz")

    static func encode(lat: Double, lng: Double, precision: Int = 6) -> String {
        var latR = (-90.0, 90.0), lngR = (-180.0, 180.0)
        var out = "", bit = 0, ch = 0, even = true
        while out.count < precision {
            if even {
                let mid = (lngR.0 + lngR.1) / 2
                if lng >= mid { ch |= 1 << (4 - bit); lngR.0 = mid } else { lngR.1 = mid }
            } else {
                let mid = (latR.0 + latR.1) / 2
                if lat >= mid { ch |= 1 << (4 - bit); latR.0 = mid } else { latR.1 = mid }
            }
            even.toggle()
            if bit < 4 { bit += 1 } else { out.append(alphabet[ch]); bit = 0; ch = 0 }
        }
        return out
    }

    static func encode(_ p: LatLng, precision: Int = 6) -> String { encode(lat: p.lat, lng: p.lng, precision: precision) }
}

// MARK: - Contract (docs/identity-and-reputation.md §4)

enum VoteAnswer: String, Codable, Sendable { case yes, no, unsure }

struct ProximityClaim: Codable, Sendable, Equatable {
    var cell: String
    var withinM: Int
}

struct AttestationClaim: Codable, Sendable, Equatable {
    var platform: String = "ios"
    var token: String
}

struct VerifyPlaceRequest: Codable, Sendable, Equatable {
    var answer: VoteAnswer
    var nameCorrect: Bool?
    var locationCorrect: Bool?
    var proximity: ProximityClaim
    var attestation: AttestationClaim?
}

struct VerifyResult: Codable, Sendable, Equatable {
    var status: PlaceStatus
    var confirmationsWeighted: Double
    var required: Double
}

enum Proximity {
    /// 1 km in towns, 5 km where the place is rural (tag `rural=yes`). Computed ON THE PHONE; only the
    /// bucket and the phone's own geohash-6 cell are sent, never a coordinate.
    static func claim(me: LatLng, place: LatLng, rural: Bool = false) -> ProximityClaim? {
        let limit = rural ? 5000 : 1000
        guard Geo.distance(me, place) <= Double(limit) else { return nil }
        return ProximityClaim(cell: Geohash.encode(me), withinM: limit)
    }
}

// MARK: - Prompt gating: only when stopped, not navigating, in the foreground, ≤ 1 per 30 min

struct VettingPromptGate: Sendable {
    static let stationaryKmh = 5.0
    static let stationaryFor: TimeInterval = 60
    static let minInterval: TimeInterval = 30 * 60

    private(set) var stationarySince: Date?
    var lastPromptAt: Date?

    /// Feed every location fix; returns true when it's OK to look for a nearby pending place now.
    mutating func shouldPrompt(speedKmh: Double, at now: Date, navigating: Bool, foreground: Bool) -> Bool {
        if speedKmh >= Self.stationaryKmh || navigating || !foreground {
            stationarySince = nil
            return false
        }
        if stationarySince == nil { stationarySince = now }
        guard let since = stationarySince, now.timeIntervalSince(since) >= Self.stationaryFor else { return false }
        if let last = lastPromptAt, now.timeIntervalSince(last) < Self.minInterval { return false }
        return true
    }

    mutating func didPrompt(at now: Date) {
        lastPromptAt = now
        stationarySince = nil
    }
}

// MARK: - Signing clock: the request signature and the attestation must use the same ts

enum SigningClock {
    @TaskLocal static var fixedTs: Int64?
    static func now() -> Int64 { fixedTs ?? Int64(Date().timeIntervalSince1970) }
}

// MARK: - App Attest (iOS). Unsupported (simulator) → votes carry no attestation.

actor AppAttestor {
    static let shared = AppAttestor()
    private let keyAccount = "appattest.keyId"
    private let attestedAccount = "appattest.attested"

    var isSupported: Bool { DCAppAttestService.shared.isSupported }

    /// base64(keyId) + "." + base64(assertion) over sha256("GIMANO-ATTEST\n" + placeId + "\n" + ts).
    func voteToken(placeId: String, ts: Int64, repo: any GimanoRepository) async -> String? {
        guard isSupported, let keyId = await attestedKey(repo: repo) else { return nil }
        let hash = Data(SHA256.hash(data: Data(Canonical.attestClientData(placeId: placeId, ts: ts).utf8)))
        do {
            let assertion = try await DCAppAttestService.shared.generateAssertion(keyId, clientDataHash: hash)
            return keyId + "." + assertion.base64EncodedString()
        } catch {
            // The key may have been invalidated (e.g. restore to a new phone): attest a new one next time.
            Keychain.set(nil, for: keyAccount)
            Keychain.set(nil, for: attestedAccount)
            return nil
        }
    }

    /// Generates and attests the key once (GET /me/attest/challenge → attestKey → POST /me/attest).
    private func attestedKey(repo: any GimanoRepository) async -> String? {
        if let k = Keychain.get(keyAccount), Keychain.get(attestedAccount) == "1" { return k }
        do {
            let keyId: String
            if let k = Keychain.get(keyAccount) { keyId = k } else { keyId = try await DCAppAttestService.shared.generateKey() }
            Keychain.set(keyId, for: keyAccount, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            guard let challenge = Data(base64Encoded: try await repo.attestChallenge()) else { return nil }
            let attestation = try await DCAppAttestService.shared.attestKey(keyId, clientDataHash: Data(SHA256.hash(data: challenge)))
            try await repo.attest(keyId: keyId, attestation: attestation.base64EncodedString())
            Keychain.set("1", for: attestedAccount)
            return keyId
        } catch {
            return nil
        }
    }
}

enum VettingError: LocalizedError {
    case tooFar
    var errorDescription: String? { String(localized: "Get within 1 km of the place to confirm it.") }
}

extension Place {
    init(summary s: PlaceSummary) {
        self.init(id: s.id, name: s.name, category: s.category, address: s.address, lat: s.lat, lng: s.lng,
                  source: s.source, status: s.status)
    }
}
