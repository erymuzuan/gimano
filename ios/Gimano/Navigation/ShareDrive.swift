import Foundation
import Observation
import Sodium

// MARK: - Contract

struct ShareCreateRequest: Codable, Sendable { var expiresInMinutes: Int }

struct ShareCreated: Codable, Sendable, Hashable {
    var shareId: String
    var writeToken: String
    var expiresAt: Date
}

/// `PUT /shares/{id}` body: base64 nonce + ciphertext (≤ 1 KB).
struct ShareBlob: Codable, Sendable, Hashable {
    var nonce: String
    var ciphertext: String
}

/// What the recipient's browser decrypts (docs/api.md "Share my drive").
struct SharePayload: Codable, Sendable, Equatable {
    enum Status: String, Codable, Sendable { case driving, arrived, ended }
    var lat: Double
    var lng: Double
    var heading: Double
    var speedKmh: Double
    var etaIso: String
    var remainingM: Double
    var destinationName: String
    var status: Status
    var ts: String
}

// MARK: - Crypto (libsodium, compatible with libsodium.js in the viewer page)

enum ShareCrypto {
    static let viewerBase = "https://www.bukitbunga.com/gimano/s/"

    /// Random 256-bit key generated on the device; never sent to any server.
    static func newKey() -> Bytes {
        let s = Sodium()
        return s.randomBytes.buf(length: s.aead.xchacha20poly1305ietf.KeyBytes)!
    }

    static func base64url(_ bytes: Bytes) -> String {
        Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func fromBase64url(_ s: String) -> Bytes? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b).map { Bytes($0) }
    }

    /// `https://www.bukitbunga.com/gimano/s/#<shareId>.<base64url(K)>`; the fragment never reaches a server.
    static func link(shareId: String, key: Bytes) -> URL {
        URL(string: "\(viewerBase)#\(shareId).\(base64url(key))")!
    }

    /// Parses a share link back into (shareId, key) (used by tests / a future in-app viewer).
    static func parse(_ url: URL) -> (shareId: String, key: Bytes)? {
        guard let frag = url.fragment, let dot = frag.lastIndex(of: ".") else { return nil }
        guard let key = fromBase64url(String(frag[frag.index(after: dot)...])), key.count == 32 else { return nil }
        return (String(frag[..<dot]), key)
    }

    /// XChaCha20-Poly1305 IETF with a random 24-byte nonce and associated data = shareId.
    static func seal(_ payload: SharePayload, key: Bytes, shareId: String) throws -> ShareBlob {
        let plain = try JSONEncoder().encode(payload)
        guard let (cipher, nonce) = Sodium().aead.xchacha20poly1305ietf.encrypt(message: Bytes(plain), secretKey: key,
                                                                                 additionalData: Bytes(shareId.utf8)) else {
            throw BackupError.corrupt
        }
        return ShareBlob(nonce: Data(nonce).base64EncodedString(), ciphertext: Data(cipher).base64EncodedString())
    }

    static func open(_ blob: ShareBlob, key: Bytes, shareId: String) -> SharePayload? {
        guard let n = Data(base64Encoded: blob.nonce), let c = Data(base64Encoded: blob.ciphertext),
              let plain = Sodium().aead.xchacha20poly1305ietf.decrypt(authenticatedCipherText: Bytes(c), secretKey: key,
                                                                        nonce: Bytes(n), additionalData: Bytes(shareId.utf8)) else { return nil }
        return try? JSONDecoder().decode(SharePayload.self, from: Data(plain))
    }

    /// Share lifetime: remaining trip + 30 min, clamped to the contract's 15…720 minutes.
    static func expiresInMinutes(remainingS: Double) -> Int {
        min(720, max(15, Int((remainingS / 60).rounded(.up)) + 30))
    }

    /// The BM + EN message sent with the link.
    static func message(destination: String, eta: String, link: URL) -> String {
        """
        Saya sedang memandu ke \(destination) (anggaran tiba \(eta)). Ikuti perjalanan saya secara langsung: \(link.absoluteString)
        I'm driving to \(destination) (ETA \(eta)). Follow my trip live: \(link.absoluteString)
        """
    }
}

// MARK: - Live session

/// One active share: posts an encrypted update at most every 15 s while navigating, ends on arrival / Stop /
/// "Stop sharing" / expiry. The key lives only in memory on this device and in the link.
@MainActor
@Observable
final class DriveShareSession {
    static let interval: TimeInterval = 15
    /// How long the final "arrived" update stays readable before the share is deleted: longer than the viewer's
    /// 10 s poll, so recipients see "Arrived" instead of going straight to "Sharing ended" (410).
    static let arrivedLinger: TimeInterval = 30

    let created: ShareCreated
    let link: URL
    let destinationName: String
    private(set) var lastSentAt: Date?
    private(set) var ended = false
    private(set) var lastError: String?

    @ObservationIgnored private let key: Bytes
    @ObservationIgnored private let repo: any GimanoRepository
    @ObservationIgnored private var sending = false

    init(created: ShareCreated, key: Bytes, destinationName: String, repo: any GimanoRepository) {
        self.created = created
        self.key = key
        self.link = ShareCrypto.link(shareId: created.shareId, key: key)
        self.destinationName = destinationName
        self.repo = repo
    }

    static func start(destinationName: String, remainingS: Double, repo: any GimanoRepository) async throws -> DriveShareSession {
        let created = try await repo.createShare(ShareCreateRequest(expiresInMinutes: ShareCrypto.expiresInMinutes(remainingS: remainingS)))
        return DriveShareSession(created: created, key: ShareCrypto.newKey(), destinationName: destinationName, repo: repo)
    }

    var isExpired: Bool { Date() >= created.expiresAt }

    func shouldSend(now: Date) -> Bool {
        !ended && !sending && (lastSentAt.map { now.timeIntervalSince($0) >= Self.interval } ?? true)
    }

    func payload(fix: LocationFix, remainingS: Double, remainingM: Double, status: SharePayload.Status, now: Date = .now) -> SharePayload {
        // Coordinates leave the phone only inside the ciphertext.
        SharePayload(lat: fix.coordinate.lat, lng: fix.coordinate.lng, heading: fix.course ?? 0, speedKmh: fix.speedKmh,
                     etaIso: LocalStore.timestamp(now.addingTimeInterval(remainingS)), remainingM: remainingM,
                     destinationName: destinationName, status: status, ts: LocalStore.timestamp(now))
    }

    /// Called on every fix while navigating; sends when 15 s have passed.
    func update(fix: LocationFix, remainingS: Double, remainingM: Double, now: Date = .now) {
        if isExpired { Task { await end(fix: fix, status: .ended) }; return }
        guard shouldSend(now: now) else { return }
        send(payload(fix: fix, remainingS: remainingS, remainingM: remainingM, status: .driving, now: now), at: now)
    }

    private func send(_ p: SharePayload, at now: Date) {
        guard let blob = try? ShareCrypto.seal(p, key: key, shareId: created.shareId) else { return }
        sending = true
        lastSentAt = now
        Task {
            defer { sending = false }
            do {
                try await repo.putShare(id: created.shareId, writeToken: created.writeToken, blob)
                lastError = nil
            } catch {
                lastError = error.localizedDescription
            }
        }
    }

    /// Sends a final status (arrived / ended) so viewers see it, then deletes the share. "Arrived" stays up for
    /// `linger` (default `arrivedLinger`) so polling viewers get to see it; "ended" is deleted at once.
    func end(fix: LocationFix?, status: SharePayload.Status, linger: TimeInterval? = nil) async {
        guard !ended else { return }
        ended = true
        if let fix, let blob = try? ShareCrypto.seal(payload(fix: fix, remainingS: 0, remainingM: 0, status: status),
                                                       key: key, shareId: created.shareId),
           (try? await repo.putShare(id: created.shareId, writeToken: created.writeToken, blob)) != nil {
            let wait = linger ?? (status == .arrived ? Self.arrivedLinger : 0)
            if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
        }
        try? await repo.deleteShare(id: created.shareId, writeToken: created.writeToken)
    }
}
