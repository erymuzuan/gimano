import Compression
import Foundation
import Sodium

// Organizer side of event packs: keys, sealing, link and file framing (docs/events.md §3, §5), mirroring
// `desktop/crates/event-core` keys.rs, pack.rs (`seal_parts`, `seal`), link.rs and file.rs.
// Nothing here touches the network or logs keys or event content.

// MARK: - Keys (keys.rs)

/// The organizer's Ed25519 key for one event (libsodium `crypto_sign_seed_keypair`); never reused for another event.
struct EventOrganizerKey: Sendable {
    let publicKey: [UInt8]
    private let secretKey: [UInt8]

    init?(seed: [UInt8]) {
        guard seed.count == 32, let kp = Sodium().sign.keyPair(seed: seed) else { return nil }
        publicKey = kp.publicKey
        secretKey = kp.secretKey
    }

    /// Ed25519 over `"manotu-event-v1\n" ‖ eventBytes` (deterministic).
    func sign(eventBytes: [UInt8]) -> [UInt8]? {
        Sodium().sign.signature(message: EventPack.signaturePrefix + eventBytes, secretKey: secretKey)
    }
}

enum EventRandom {
    static func bytes(_ n: Int) -> [UInt8] {
        Sodium().randomBytes.buf(length: n)!
    }

    /// b64u of 16 random bytes.
    static func eventId() -> String { B64u.encode(bytes(EventLimits.eventIdBytes)) }
    /// b64u of 8 random bytes (stops, sessions, routes).
    static func itemId() -> String { B64u.encode(bytes(EventLimits.itemIdBytes)) }
}

// MARK: - Seal (pack.rs)

/// Every intermediate layer of one sealing (vector tests and diagnostics).
struct SealedEventParts: Sendable {
    var eventBytes: [UInt8]
    var signature: [UInt8]
    var envelopeBytes: [UInt8]
    var deflated: [UInt8]
    var sealed: SealedEventPack
}

extension EventPack {
    /// `{"v":1,"event":b64u,"pub":b64u(32),"sig":b64u(64)}`, in that order (serde field order).
    static func envelopeBytes(eventBytes: [UInt8], organizerPub: [UInt8], signature: [UInt8]) -> [UInt8] {
        Array("{\"v\":1,\"event\":\"\(B64u.encode(eventBytes))\",\"pub\":\"\(B64u.encode(organizerPub))\",\"sig\":\"\(B64u.encode(signature))\"}".utf8)
    }

    /// Raw DEFLATE (RFC 1951, no header) with Apple's Compression (`COMPRESSION_ZLIB` is raw DEFLATE).
    /// The output differs from miniz/zlib byte-wise; only the round trip is specified.
    static func deflateRaw(_ input: [UInt8]) -> [UInt8]? {
        guard !input.isEmpty else { return nil }
        let cap = input.count + input.count / 2 + 1024
        var out = [UInt8](repeating: 0, count: cap)
        let n = compression_encode_buffer(&out, cap, input, input.count, nil, COMPRESSION_ZLIB)
        return n == 0 ? nil : Array(out.prefix(n))
    }

    /// Signs, compresses and encrypts already-serialised event bytes with an explicit nonce (`seal_parts`).
    /// The bytes must form a valid event. Never reuse a nonce with the same key.
    static func sealParts(eventBytes: [UInt8], organizer: EventOrganizerKey, key: [UInt8], nonce: [UInt8]) throws(EventPackError) -> SealedEventParts {
        _ = try EventDoc.parse(eventBytes)
        guard let signature = organizer.sign(eventBytes: eventBytes), signature.count == 64 else { throw .badSignature }
        let envelope = envelopeBytes(eventBytes: eventBytes, organizerPub: organizer.publicKey, signature: signature)
        guard let deflated = deflateRaw(envelope) else { throw .inflate }
        guard let ciphertext = encrypt(key: key, nonce: nonce, plaintext: deflated) else { throw .decrypt }
        return SealedEventParts(eventBytes: eventBytes, signature: signature, envelopeBytes: envelope, deflated: deflated,
                                sealed: SealedEventPack(nonce: nonce, ciphertext: ciphertext))
    }

    /// A fresh random nonce, then opens the result again to prove it verifies before anything is offered (`seal`).
    static func seal(eventBytes: [UInt8], organizer: EventOrganizerKey, key: [UInt8]) throws(EventPackError) -> SealedEventPack {
        let parts = try sealParts(eventBytes: eventBytes, organizer: organizer, key: key, nonce: EventRandom.bytes(nonceBytes))
        let check = try open(key: key, sealed: parts.sealed)
        guard check.eventBytes == eventBytes, check.organizerPub == organizer.publicKey else { throw .badSignature }
        return parts.sealed
    }

    // MARK: link / file (link.rs, file.rs)

    /// `1.<b64u(key)>.<b64u(nonce ‖ ciphertext)>` without `#`, even when it is over the 16 000-character limit.
    static func fragment(key: [UInt8], sealed: SealedEventPack) -> String {
        "\(linkVersion).\(B64u.encode(key)).\(B64u.encode(sealed.nonce + sealed.ciphertext))"
    }

    /// `<site>/a/#<fragment>`; fails with `fragmentTooLong` above 16 000 characters (offer the file instead).
    static func link(site: String, key: [UInt8], sealed: SealedEventPack) throws(EventPackError) -> String {
        let f = fragment(key: key, sealed: sealed)
        if f.count > EventLimits.maxFragmentChars { throw .fragmentTooLong(len: f.count) }
        var base = site
        while base.hasSuffix("/") { base.removeLast() }
        return "\(base)/a/#\(f)"
    }

    /// `"MNTE" ‖ 0x01 ‖ 0x01 ‖ nonce ‖ ciphertext`.
    static func fileBytes(_ sealed: SealedEventPack) throws(EventPackError) -> [UInt8] {
        let bytes = fileMagic + [fileFormatV1, fileSuiteRawKey] + sealed.nonce + sealed.ciphertext
        if bytes.count > EventLimits.maxFileBytes { throw .fileTooLarge }
        return bytes
    }
}

/// Where invitation links point: the site the app already uses for "Share my drive" (`ShareCrypto.viewerBase`
/// without its `/s/`), i.e. `https://www.bukitbunga.com/gimano` today (docs/events.md §5.1).
enum EventLinks {
    static var site: String {
        var s = ShareCrypto.viewerBase
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix("/s") { s.removeLast(2) }
        return s
    }
}

// MARK: - Share messages

/// The ready-to-post texts for "Kongsi…" (WhatsApp etc.), in the event's own language (`lang`).
/// The secret code is never part of these; it has its own copy/share button.
enum EventShareMessage {
    private static func dateLine(_ doc: EventDoc) -> String {
        let locale = Locale(identifier: doc.lang == .ms ? "ms_MY" : "en_MY")
        let tz = doc.timeZone
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        let day = Date.FormatStyle(date: .complete, time: .omitted, locale: locale, calendar: cal, timeZone: tz)
        let time = Date.FormatStyle(date: .omitted, time: .shortened, locale: locale, calendar: cal, timeZone: tz)
        if cal.isDate(doc.start.date, inSameDayAs: doc.end.date) {
            return "\(doc.start.date.formatted(day)), \(doc.start.date.formatted(time))–\(doc.end.date.formatted(time))"
        }
        return "\(doc.start.date.formatted(day)), \(doc.start.date.formatted(time)) – \(doc.end.date.formatted(day)), \(doc.end.date.formatted(time))"
    }

    /// Invitation link share.
    static func link(_ doc: EventDoc, link: String, downloadURL: String = AppConfig.downloadURL) -> String {
        switch doc.lang {
        case .ms:
            """
            Jemputan: \(doc.title)
            \(dateLine(doc))
            Buka pelan, tempat parkir & arah: \(link)
            Belum ada Manotu? Muat turun: \(downloadURL)
            """
        case .en:
            """
            Invitation: \(doc.title)
            \(dateLine(doc))
            Open the plan, parking & directions: \(link)
            No Manotu yet? Download: \(downloadURL)
            """
        }
    }

    /// Text next to the attached `.manotu-event` file. Never contains the key or the secret code.
    static func file(_ doc: EventDoc, downloadURL: String = AppConfig.downloadURL) -> String {
        switch doc.lang {
        case .ms:
            "Jemputan \(doc.title) (buka dengan Manotu). Kod rahsia akan dihantar berasingan. Belum ada Manotu? Muat turun: \(downloadURL)"
        case .en:
            "Invitation: \(doc.title) (open with Manotu). The secret code will be sent separately. No Manotu yet? Download: \(downloadURL)"
        }
    }
}
