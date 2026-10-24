import CryptoKit
import Foundation

// Short links through the end-to-end encrypted relay (docs/events.md §5.6), mirroring
// `desktop/crates/event-core/src/drop.rs`:
//
//   https://manotu.app/j/#<dropId>.<b64u(secret)>      manotu://event?drop=<dropId>#<b64u(secret)>
//   key  = HKDF-SHA256(ikm = secret (16 bytes), salt = empty, info = "manotu-drop-v1", L = 32)
//   blob = nonce (24) ‖ XChaCha20-Poly1305(key, nonce, AD "manotu-event-v1", deflated signed envelope)
//
// The server keeps the blob under `dropId` and never sees the secret: it only travels after `#` (browsers never send
// the fragment) and stays on the phones. The blob is always a full event and opens with every link rule (§7).
// Nothing here logs drop ids, secrets or tokens.

/// A short link: which blob to fetch and the 16-byte secret that opens it.
struct EventDropRef: Equatable, Hashable, Sendable {
    var dropId: String
    var secret: [UInt8]

    /// `<dropId>.<b64u(secret)>`, the part after `#`.
    var fragment: String { "\(dropId).\(B64u.encode(secret))" }

    /// `https://manotu.app/j/#<dropId>.<secret>` (≈ 55 characters).
    func link(site: String = EventLinks.site) -> String {
        var base = site
        while base.hasSuffix("/") { base.removeLast() }
        return "\(base)\(EventDrop.path)#\(fragment)"
    }

    /// `manotu://event?drop=<dropId>#<secret>`, the web page's "Buka di Manotu".
    var appLink: String { "manotu://event?drop=\(dropId)#\(B64u.encode(secret))" }
}

enum EventDrop {
    /// The server's dropId alphabet (base58: no `0`, `O`, `I`, `l`).
    static let idAlphabet = Set("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
    static let idLength = 10
    static let secretBytes = 16
    static let hkdfInfo = Array("manotu-drop-v1".utf8)
    /// Largest blob the server accepts (256 KiB).
    static let maxBlobBytes = 256 * 1024
    static let path = "/j/"
    /// The drop outlives the event's last day by two weeks (§5.6), so late guests still open it.
    static let daysAfterEvent = 14

    static func isValidId(_ id: String) -> Bool {
        id.count == idLength && id.allSatisfy { idAlphabet.contains($0) }
    }

    static func newSecret() -> [UInt8] { EventRandom.bytes(secretBytes) }

    /// `HKDF-SHA256(ikm = secret, salt = empty, info = "manotu-drop-v1", L = 32)` (CryptoKit).
    static func key(secret: [UInt8]) -> [UInt8] {
        let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: secret), salt: Data(), info: Data(hkdfInfo),
                                       outputByteCount: EventPack.keyBytes)
        return k.withUnsafeBytes { Array($0) }
    }

    // MARK: links

    /// Accepts `https://<any site>/j/#<dropId>.<secret>`, `manotu://event?drop=<dropId>#<secret>` (also `gimano://`, and
    /// other query items such as `stop`), or a bare `<dropId>.<secret>` with or without `#`. An `/a/` link
    /// (`#1.<key>.<data>`) is never a drop link. nil when it isn't a well-formed short link.
    static func parse(_ link: String) -> EventDropRef? {
        let s = link.trimmingCharacters(in: .whitespacesAndNewlines)
        let before: Substring
        let fragment: Substring
        if let hash = s.firstIndex(of: "#") {
            before = s[..<hash]
            fragment = s[s.index(after: hash)...]
        } else {
            before = ""
            fragment = s[...]
        }
        let lower = before.lowercased()
        let id: Substring
        let secret: Substring
        if let prefix = ["manotu://event", "gimano://event"].first(where: { lower.hasPrefix($0) }) {
            let rest = before.dropFirst(prefix.count)
            guard rest.first == "?" else { return nil }
            guard let item = rest.dropFirst().split(separator: "&").first(where: { $0.hasPrefix("drop=") }) else { return nil }
            id = item.dropFirst("drop=".count)
            secret = fragment
        } else {
            if !before.isEmpty {
                // Any site, but only the `/j/` path (with or without the trailing slash; never `/a/`).
                guard lower.hasPrefix("https://") || lower.hasPrefix("http://") else { return nil }
                let path = URLComponents(string: String(before))?.path ?? ""
                guard path.hasSuffix("/j/") || path.hasSuffix("/j") else { return nil }
            }
            let parts = fragment.split(separator: ".", omittingEmptySubsequences: false)
            guard parts.count == 2 else { return nil }
            id = parts[0]
            secret = parts[1]
        }
        guard isValidId(String(id)), let bytes = strictB64u(String(secret)), bytes.count == secretBytes else { return nil }
        return EventDropRef(dropId: String(id), secret: bytes)
    }

    /// A short link anywhere in pasted text ("Buka pelan, parkir & arah: https://manotu.app/j/#… Belum ada…").
    static func find(inText text: String) -> EventDropRef? {
        if let r = parse(text) { return r }
        for token in text.split(whereSeparator: { $0.isWhitespace }) {
            // Chat apps sometimes wrap links in <> or add punctuation after them.
            let t = token.trimmingCharacters(in: CharacterSet(charactersIn: "<>()[]\"'"))
            if let r = parse(t) { return r }
        }
        return nil
    }

    /// Exactly the canonical unpadded b64u of its bytes (22 characters for 16 bytes), so look-alike encodings and
    /// padding are rejected like the reference core.
    private static func strictB64u(_ s: String) -> [UInt8]? {
        guard let b = B64u.decode(s), B64u.encode(b) == s else { return nil }
        return b
    }

    // MARK: blob

    /// Splits a blob into nonce and ciphertext: over 256 KiB is too large, shorter than nonce + tag is malformed.
    static func parseBlob(_ blob: [UInt8]) throws(EventDropError) -> SealedEventPack {
        if blob.count > maxBlobBytes { throw .tooLarge }
        guard blob.count >= EventPack.nonceBytes + EventPack.tagBytes else { throw .malformed }
        return SealedEventPack(nonce: Array(blob[..<EventPack.nonceBytes]), ciphertext: Array(blob[EventPack.nonceBytes...]))
    }

    /// Decrypt → bounded inflate → envelope → signature → event, exactly like a link (§7 steps 1–2).
    static func open(blob: [UInt8], secret: [UInt8]) throws(EventDropError) -> OpenedEventPack {
        let sealed = try parseBlob(blob)
        do {
            return try EventPack.open(key: key(secret: secret), sealed: sealed)
        } catch {
            throw .pack(error)
        }
    }

    /// Deterministic writer (vectors): signs, compresses and encrypts these full event bytes under the derived key.
    static func sealParts(eventBytes: [UInt8], organizer: EventOrganizerKey, secret: [UInt8],
                          nonce: [UInt8]) throws(EventDropError) -> (parts: SealedEventParts, blob: [UInt8]) {
        do {
            guard try EventDoc.parse(eventBytes).variant == nil else { throw EventPackError.notFullEvent }
            let parts = try EventPack.sealParts(eventBytes: eventBytes, organizer: organizer, key: key(secret: secret), nonce: nonce)
            let blob = parts.sealed.nonce + parts.sealed.ciphertext
            if blob.count > maxBlobBytes { throw EventDropError.tooLarge }
            return (parts, blob)
        } catch let e as EventDropError {
            throw e
        } catch let e as EventPackError {
            throw .pack(e)
        } catch {
            throw .malformed
        }
    }

    /// The writer path (upload and every republish): a fresh nonce, opened again to prove it verifies.
    static func seal(eventBytes: [UInt8], organizer: EventOrganizerKey, secret: [UInt8]) throws(EventDropError) -> [UInt8] {
        let (_, blob) = try sealParts(eventBytes: eventBytes, organizer: organizer, secret: secret, nonce: EventRandom.bytes(EventPack.nonceBytes))
        let check = try open(blob: blob, secret: secret)
        guard check.eventBytes == eventBytes, check.organizerPub == organizer.publicKey else { throw .pack(.badSignature) }
        return blob
    }

    // MARK: expiry

    /// `expiresAt` = the event's last day + 14 days (§5.6): the end of the 14th calendar day after the day the event
    /// ends, in the event's own time zone. An event ending exactly at midnight ends on the day before.
    static func expiresAt(end: Date, timeZone: TimeZone) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let lastDay = cal.startOfDay(for: end.addingTimeInterval(-1))
        return cal.date(byAdding: .day, value: daysAfterEvent + 1, to: lastDay) ?? end.addingTimeInterval(Double(daysAfterEvent + 1) * 86_400)
    }

    static func expiresAt(_ doc: EventDoc) -> Date { expiresAt(end: doc.end.date, timeZone: doc.timeZone) }
}

enum EventDropError: Error, Equatable, Sendable {
    /// Blob shorter than nonce + tag.
    case malformed
    /// Over 256 KiB (the server answers 413).
    case tooLarge
    /// 404 (unknown) or 410 (expired / ended): "Jemputan ini telah tamat atau dipadam".
    case gone
    /// Offline, timeout or a server error: retry.
    case network
    /// Decrypt, signature or validation failure (§7 message).
    case pack(EventPackError)

    /// Stable codes, as `event_core::Error::code()` for the reference cases.
    var code: String {
        switch self {
        case .malformed: "malformed_drop"
        case .tooLarge: "drop_too_large"
        case .gone: "drop_gone"
        case .network: "network"
        case .pack(let e): e.code
        }
    }

    var userMessage: String {
        switch self {
        case .gone: String(localized: "This invitation has ended or was deleted.")
        case .network: String(localized: "Couldn't fetch the invitation. Check your connection and try again.")
        case .pack(let e): e.userMessage
        case .malformed, .tooLarge: EventPackError.decrypt.userMessage
        }
    }
}

// MARK: - Organizer's drop (Keychain, with the event's keys)

/// The organizer's short link for one hosted event: kept in the Keychain next to the event's keys
/// (`WhenUnlockedThisDeviceOnly`, never synchronized or backed up). The secret is made on this phone and stays the same
/// for every revision, so the same link always shows the latest one.
struct HostedEventDrop: Codable, Equatable, Sendable {
    var dropId: String
    var secret: String
    var writeToken: String
    /// SHA-256 of the event bytes last uploaded (skip the upload when nothing changed).
    var uploadedHash: String?
    var expiresAt: Date?

    var ref: EventDropRef? {
        B64u.decode(secret).map { EventDropRef(dropId: dropId, secret: $0) }
    }
}

// MARK: - Transport (docs/api.md "Event short links")

struct EventDropCreated: Decodable, Sendable {
    var dropId: String
    var writeToken: String
    var expiresAt: Date?
}

/// `POST/PUT/GET/DELETE /api/v1/drops`. Bodies are the encrypted blob (`application/octet-stream`); nothing else about
/// the event is sent.
protocol EventDropTransport: Sendable {
    /// `POST /drops` with the app's JWT and `X-Drop-Expires`.
    func createDrop(blob: [UInt8], expiresAt: Date) async throws -> EventDropCreated
    /// `PUT /drops/{id}` with `Authorization: Drop <writeToken>` (a republish).
    func putDrop(id: String, writeToken: String, blob: [UInt8], expiresAt: Date) async throws
    /// `GET /drops/{id}` (public).
    func getDrop(id: String) async throws -> [UInt8]
    /// `DELETE /drops/{id}` with `Authorization: Drop <writeToken>`.
    func deleteDrop(id: String, writeToken: String) async throws
}

enum EventDropHTTP {
    /// `X-Drop-Expires`: ISO 8601 UTC with seconds.
    static func expiresHeader(_ d: Date) -> String {
        d.formatted(Date.ISO8601FormatStyle(timeZone: TimeZone(identifier: "UTC")!))
    }

    /// 404 / 410 → gone; anything unreachable or 5xx → network.
    static func classify(_ error: Error) -> EventDropError {
        if let e = error as? EventDropError { return e }
        if case .http(let status, _)? = error as? APIError {
            if status == 404 || status == 410 { return .gone }
            if status == 413 { return .tooLarge }
        }
        return .network
    }
}

/// Runs `op`, giving up waiting after `seconds` (the work itself keeps running, so a slow upload still saves its link).
@MainActor
func withDropTimeout<T: Sendable>(seconds: Double, _ op: @escaping @Sendable () async throws -> T) async throws -> T {
    let work = Task { try await op() }
    let gate = DropTimeoutGate<T>()
    return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<T, Error>) in
        gate.continuation = cont
        Task { @MainActor in
            do { gate.finish(.success(try await work.value)) } catch { gate.finish(.failure(error)) }
        }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
            gate.finish(.failure(EventDropError.network))
        }
    }
}

/// Resumes the continuation once: whichever of the work and the timer finishes first.
@MainActor
private final class DropTimeoutGate<T: Sendable> {
    var continuation: CheckedContinuation<T, Error>?

    func finish(_ result: Result<T, Error>) {
        guard let c = continuation else { return }
        continuation = nil
        c.resume(with: result)
    }
}

// MARK: - Organizer upload

enum EventDropPublisher {
    /// Seals `eventBytes` (the full published event) under the event's drop secret and uploads it: `PUT` when the event
    /// already has a short link (a republish; the link stays the same), else `POST` with a new secret made on this
    /// phone. Skips the upload when these bytes were already uploaded. A link the server no longer has (404/410, e.g.
    /// expired) is replaced by a new one. Returns the link's reference; the record is kept with the event's keys.
    static func upload(eventId: String, eventBytes: [UInt8], expiresAt: Date, secrets: any EventSecretStore,
                       transport: any EventDropTransport) async throws -> EventDropRef {
        guard let s = secrets.load(eventId: eventId), let organizer = EventOrganizerKey(seed: s.seed) else {
            throw EventDropError.pack(.badSignature)
        }
        let hash = B64u.encode(EventCodes.bytesHash(eventBytes))
        if var d = secrets.loadDrop(eventId: eventId), let ref = d.ref {
            if d.uploadedHash == hash { return ref }
            let blob = try EventDrop.seal(eventBytes: eventBytes, organizer: organizer, secret: ref.secret)
            do {
                try await transport.putDrop(id: d.dropId, writeToken: d.writeToken, blob: blob, expiresAt: expiresAt)
                d.uploadedHash = hash
                d.expiresAt = expiresAt
                _ = secrets.saveDrop(d, eventId: eventId)
                return ref
            } catch {
                guard EventDropHTTP.classify(error) == .gone else { throw error }
                secrets.deleteDrop(eventId: eventId)
            }
        }
        let secret = EventDrop.newSecret()
        let blob = try EventDrop.seal(eventBytes: eventBytes, organizer: organizer, secret: secret)
        try Task.checkCancellation()
        let created = try await transport.createDrop(blob: blob, expiresAt: expiresAt)
        guard EventDrop.isValidId(created.dropId), !created.writeToken.isEmpty else { throw EventDropError.malformed }
        let record = HostedEventDrop(dropId: created.dropId, secret: B64u.encode(secret), writeToken: created.writeToken,
                                     uploadedHash: hash, expiresAt: created.expiresAt ?? expiresAt)
        guard secrets.saveDrop(record, eventId: eventId) else { throw EventDropError.network }
        return EventDropRef(dropId: created.dropId, secret: secret)
    }
}

// MARK: - Deferred link (after install, §5.6)

/// "Tampal pautan jemputan" on the first launches: until an event is imported, or 7 days after the first launch that
/// knew about it. No tracking, no install referrer: the guest pastes the link the web page copied.
enum EventDeferredLink {
    static let firstLaunchKey = "events.deferredLink.firstLaunch"
    static let doneKey = "events.deferredLink.done"
    static let window: TimeInterval = 7 * 86_400

    static func isEligible(defaults: UserDefaults = .standard, now: Date, hasEvents: Bool) -> Bool {
        if defaults.bool(forKey: doneKey) { return false }
        if hasEvents {
            defaults.set(true, forKey: doneKey)
            return false
        }
        guard let first = defaults.object(forKey: firstLaunchKey) as? Date else {
            defaults.set(now, forKey: firstLaunchKey)
            return true
        }
        if now.timeIntervalSince(first) >= window {
            defaults.set(true, forKey: doneKey)
            return false
        }
        return true
    }

    static func markImported(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: doneKey)
    }
}
