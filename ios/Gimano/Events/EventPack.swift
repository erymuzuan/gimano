import Compression
import CryptoKit
import Foundation
import Clibsodium
import Sodium

// Manotu event packs (docs/events.md §2, §3, §5). Mirrors the desktop reference `desktop/crates/event-core`
// and must accept every vector in `desktop/crates/event-core/tests/vectors/` (copied into GimanoTests/EventVectors).
//
// Privacy: an event, its key and its contents never leave this phone. Nothing here touches the network.

/// Stable error codes shared with the test vectors (`expectedError`, `event_core::Error::code()`).
enum EventPackError: Error, Equatable, Sendable {
    case invalidEvent(path: String, reason: String)
    case malformedEvent(String)
    case fragmentTooLong(len: Int)
    case malformedLink
    case unsupportedLinkVersion
    case fileTooLarge
    case malformedFile
    case unsupportedFileFormat(UInt8)
    case unsupportedSuite(UInt8)
    case invalidSecretCode(String)
    /// Wrong key or tampered ciphertext; indistinguishable by design.
    case decrypt
    case inflate
    case inflateTooLarge
    case malformedEnvelope
    case unsupportedEnvelopeVersion(UInt64)
    case badSignature
    /// `variant.kind` is not `stops-only` (§5.5): a future kind must not be read as a full event.
    case unsupportedVariant(String)
    /// A stops-only variant still carries `routes[route].polyline` (§5.5).
    case variantHasPolyline(route: Int)
    /// A full event lacks `routes[route].polyline` (§5.5).
    case missingPolyline(route: Int)
    /// A stops-only variant can only be made from a full event.
    case notFullEvent

    var code: String {
        switch self {
        case .invalidEvent: "invalid_event"
        case .malformedEvent: "malformed_event"
        case .fragmentTooLong: "fragment_too_long"
        case .malformedLink: "malformed_link"
        case .unsupportedLinkVersion: "unsupported_link_version"
        case .fileTooLarge: "file_too_large"
        case .malformedFile: "malformed_file"
        case .unsupportedFileFormat: "unsupported_file_format"
        case .unsupportedSuite: "unsupported_suite"
        case .invalidSecretCode: "invalid_secret_code"
        case .decrypt: "decrypt"
        case .inflate: "inflate"
        case .inflateTooLarge: "inflate_too_large"
        case .malformedEnvelope: "malformed_envelope"
        case .unsupportedEnvelopeVersion: "unsupported_envelope_version"
        case .badSignature: "signature"
        case .unsupportedVariant: "unsupported_variant"
        case .variantHasPolyline: "variant_has_polyline"
        case .missingPolyline: "missing_polyline"
        case .notFullEvent: "not_full_event"
        }
    }

    /// What the user sees (§7.1: one message for anything that cannot be opened or verified).
    var userMessage: String {
        switch self {
        case .fragmentTooLong:
            String(localized: "This invitation link is too long. Ask the organizer for the .manotu-event file instead.")
        case .invalidSecretCode:
            String(localized: "The secret code is not valid. Check it and try again.")
        default:
            String(localized: "This invitation can't be opened or verified.")
        }
    }
}

enum EventLimits {
    static let maxEventDays = 14
    static let maxStops = 200
    static let maxSessions = 50
    static let maxRoutes = 20
    static let maxPolylinePoints = 50_000
    static let maxFileBytes = 20 * 1024 * 1024
    static let maxFragmentChars = 16_000
    static let maxInflatedBytes = 20 * 1024 * 1024

    static let titleChars = 120
    static let descriptionChars = 2000
    static let stopLabelChars = 80
    static let stopNotesChars = 500
    static let audienceChars = 40
    static let routeTitleChars = 80
    static let sessionTitleChars = 120
    static let aliasChars = 80
    static let tzChars = 64
    static let maxDwellMin: UInt32 = 14 * 24 * 60

    static let eventIdBytes = 16
    static let itemIdBytes = 8
}

// MARK: - Encodings

enum B64u {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)
    private static let table: [Int8] = {
        var t = [Int8](repeating: -1, count: 256)
        for (i, c) in alphabet.enumerated() { t[Int(c)] = Int8(i) }
        return t
    }()

    static func encode(_ bytes: [UInt8]) -> String {
        var out = [UInt8]()
        out.reserveCapacity((bytes.count * 4 + 2) / 3)
        var i = 0
        while i + 2 < bytes.count {
            let v = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8 | UInt32(bytes[i + 2])
            out += [alphabet[Int(v >> 18 & 63)], alphabet[Int(v >> 12 & 63)], alphabet[Int(v >> 6 & 63)], alphabet[Int(v & 63)]]
            i += 3
        }
        let rest = bytes.count - i
        if rest == 1 {
            let v = UInt32(bytes[i]) << 16
            out += [alphabet[Int(v >> 18 & 63)], alphabet[Int(v >> 12 & 63)]]
        } else if rest == 2 {
            let v = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8
            out += [alphabet[Int(v >> 18 & 63)], alphabet[Int(v >> 12 & 63)], alphabet[Int(v >> 6 & 63)]]
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Strict base64url: no padding, no characters outside the alphabet, canonical (zero) trailing bits.
    static func decode(_ s: String) -> [UInt8]? {
        let chars = Array(s.utf8)
        if chars.count % 4 == 1 { return nil }
        var out = [UInt8]()
        out.reserveCapacity(chars.count * 3 / 4)
        var acc: UInt32 = 0
        var bits = 0
        for c in chars {
            let v = table[Int(c)]
            if v < 0 { return nil }
            acc = (acc << 6) | UInt32(v)
            bits += 6
            if bits >= 8 {
                bits -= 8
                out.append(UInt8((acc >> UInt32(bits)) & 0xFF))
                acc &= (1 << UInt32(bits)) - 1
            }
        }
        // Leftover bits (2 or 4) must be zero.
        if acc != 0 { return nil }
        return out
    }
}

/// Crockford base32 secret code for the file key (§5.2): 52 characters in groups of 4.
enum SecretCode {
    private static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    static let keyChars = 52

    static func encode(_ key: [UInt8]) -> String {
        var chars: [Character] = []
        var acc: UInt32 = 0
        var bits: UInt32 = 0
        for b in key {
            acc = (acc << 8) | UInt32(b)
            bits += 8
            while bits >= 5 {
                bits -= 5
                chars.append(alphabet[Int((acc >> bits) & 31)])
            }
            acc &= (1 << bits) - 1
        }
        if bits > 0 { chars.append(alphabet[Int((acc << (5 - bits)) & 31)]) }
        return stride(from: 0, to: chars.count, by: 4).map { String(chars[$0..<min($0 + 4, chars.count)]) }.joined(separator: "-")
    }

    /// Case-insensitive; `I`/`L` read as 1 and `O` as 0; hyphens and whitespace are ignored; padding bits must be 0.
    static func decode(_ code: String) throws(EventPackError) -> [UInt8] {
        var values: [UInt32] = []
        for c in code {
            if c == "-" || c.isWhitespace { continue }
            let u = Character(c.uppercased())
            let v: UInt32
            switch u {
            case "O": v = 0
            case "I", "L": v = 1
            default:
                guard let i = alphabet.firstIndex(of: u) else { throw .invalidSecretCode("invalid character") }
                v = UInt32(i)
            }
            values.append(v)
            if values.count > keyChars { throw .invalidSecretCode("too long") }
        }
        guard values.count == keyChars else { throw .invalidSecretCode("wrong length") }
        var out = [UInt8]()
        var acc: UInt32 = 0
        var bits: UInt32 = 0
        for v in values {
            acc = (acc << 5) | v
            bits += 5
            if bits >= 8 {
                bits -= 8
                out.append(UInt8((acc >> bits) & 0xFF))
                acc &= (1 << bits) - 1
            }
        }
        guard acc == 0 else { throw .invalidSecretCode("non-canonical padding") }
        return out
    }
}

enum EventCodes {
    /// Digits and upper-case letters without the look-alikes 0/O and 1/I.
    private static let safetyAlphabet = Array("23456789ABCDEFGHJKLMNPQRSTUVWXYZ")

    /// First 30 bits of SHA-256(organizer public key), 5 bits per character, e.g. "S8EVC7" (§7.3).
    static func safetyCode(_ organizerPub: [UInt8]) -> String {
        let h = Array(SHA256.hash(data: Data(organizerPub)))
        let v = UInt32(h[0]) << 24 | UInt32(h[1]) << 16 | UInt32(h[2]) << 8 | UInt32(h[3])
        return String((0..<6).map { safetyAlphabet[Int((v >> (27 - 5 * UInt32($0))) & 31)] })
    }

    /// SHA-256 of the exact bytes.
    static func bytesHash(_ bytes: [UInt8]) -> [UInt8] {
        Array(SHA256.hash(data: Data(bytes)))
    }

    /// The revision hash (§7, what the next revision's `parent` refers to): SHA-256 of the exact signed event bytes,
    /// except for a stops-only variant (§5.5), whose revision hash is its `variant.fullHash`. Meant for bytes that
    /// already passed `EventDoc.parse` (`event_core::revision_hash`).
    static func revisionHash(_ eventBytes: [UInt8]) -> [UInt8] {
        struct Probe: Decodable { var variant: EventVariant? }
        if let v = (try? JSONDecoder().decode(Probe.self, from: Data(eventBytes)))?.variant, v.isStopsOnly, let h = v.fullHashBytes {
            return h
        }
        return bytesHash(eventBytes)
    }
}

// MARK: - Pack

/// What travels in a link or file next to the key.
struct SealedEventPack: Equatable, Sendable {
    var nonce: [UInt8]
    /// Ciphertext followed by the 16-byte Poly1305 tag.
    var ciphertext: [UInt8]
}

/// A decrypted pack whose signature verified and whose event passed validation.
struct OpenedEventPack: Equatable, Sendable {
    var event: EventDoc
    /// The exact signed bytes: store these, never a re-serialization.
    var eventBytes: [UInt8]
    var organizerPub: [UInt8]
    /// Revision hash: SHA-256 of `eventBytes`, or `variant.fullHash` for a stops-only variant (§5.5).
    var revisionHash: [UInt8]

    var safetyCode: String { EventCodes.safetyCode(organizerPub) }
    var isStopsOnly: Bool { event.isStopsOnly }
}

enum EventPack {
    static let aeadAD = Array("manotu-event-v1".utf8)
    static let signaturePrefix = Array("manotu-event-v1\n".utf8)
    static let keyBytes = 32
    static let nonceBytes = 24
    static let tagBytes = 16

    static let fileMagic = Array("MNTE".utf8)
    static let fileFormatV1: UInt8 = 0x01
    static let fileSuiteRawKey: UInt8 = 0x01
    static let fileExtension = "manotu-event"
    static let fileUTType = "my.manotu.event"
    static let linkVersion = "1"

    private struct Envelope: Decodable {
        var v: UInt64
        var event: String
        var pub: String
        var sig: String
    }

    // MARK: link (§5.1)

    /// Accepts a full link (any site or scheme) or a bare fragment. The length is checked before decoding.
    static func parseLink(_ link: String) throws(EventPackError) -> (key: [UInt8], sealed: SealedEventPack) {
        let raw = link.firstIndex(of: "#").map { String(link[link.index(after: $0)...]) } ?? link
        let fragment = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let len = fragment.unicodeScalars.count
        if len > EventLimits.maxFragmentChars { throw .fragmentTooLong(len: len) }
        let parts = fragment.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { throw .malformedLink }
        guard parts[0] == linkVersion else { throw .unsupportedLinkVersion }
        guard let key = B64u.decode(String(parts[1])), key.count == keyBytes,
              let data = B64u.decode(String(parts[2])), data.count >= nonceBytes + tagBytes else { throw .malformedLink }
        return (key, SealedEventPack(nonce: Array(data[..<nonceBytes]), ciphertext: Array(data[nonceBytes...])))
    }

    static func openLink(_ link: String) throws(EventPackError) -> OpenedEventPack {
        let (key, sealed) = try parseLink(link)
        return try open(key: key, sealed: sealed)
    }

    /// The fragment of a pasted message: everything after `#` up to the first whitespace, so
    /// "Jemputan: https://…/a/#1.x.y Jumpa nanti" still opens. Text without `#` is taken as a bare fragment.
    static func extractLink(fromPasted text: String) -> String {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let hash = t.firstIndex(of: "#") else { return t }
        let after = t[t.index(after: hash)...]
        return String(after.prefix { !$0.isWhitespace })
    }

    // MARK: file (§5.2)

    static func parseFile(_ bytes: [UInt8]) throws(EventPackError) -> SealedEventPack {
        if bytes.count > EventLimits.maxFileBytes { throw .fileTooLarge }
        let header = 6
        guard bytes.count >= header + nonceBytes + tagBytes, Array(bytes[0..<4]) == fileMagic else { throw .malformedFile }
        guard bytes[4] == fileFormatV1 else { throw .unsupportedFileFormat(bytes[4]) }
        guard bytes[5] == fileSuiteRawKey else { throw .unsupportedSuite(bytes[5]) }
        return SealedEventPack(nonce: Array(bytes[header..<header + nonceBytes]), ciphertext: Array(bytes[(header + nonceBytes)...]))
    }

    /// Opens a file with the secret code the organizer sent through another channel.
    static func openFile(_ bytes: [UInt8], secretCode: String) throws(EventPackError) -> OpenedEventPack {
        let sealed = try parseFile(bytes)
        let key = try SecretCode.decode(secretCode)
        return try open(key: key, sealed: sealed)
    }

    /// Cheap check for "is this a .manotu-event file" before asking for the secret code.
    static func looksLikeFile(_ bytes: [UInt8]) -> Bool {
        (try? parseFile(bytes)) != nil
    }

    // MARK: layers (§3)

    static func decrypt(key: [UInt8], nonce: [UInt8], ciphertext: [UInt8]) throws(EventPackError) -> [UInt8] {
        guard key.count == keyBytes, nonce.count == nonceBytes, ciphertext.count >= tagBytes,
              let plain = Sodium().aead.xchacha20poly1305ietf.decrypt(authenticatedCipherText: ciphertext, secretKey: key,
                                                                       nonce: nonce, additionalData: aeadAD) else { throw .decrypt }
        return plain
    }

    /// Writer side (tests: the vector ciphertext must be reproduced byte for byte).
    static func encrypt(key: [UInt8], nonce: [UInt8], plaintext: [UInt8]) -> [UInt8]? {
        _ = Sodium() // sodium_init
        guard key.count == keyBytes, nonce.count == nonceBytes else { return nil }
        var out = [UInt8](repeating: 0, count: plaintext.count + tagBytes)
        var outLen: UInt64 = 0
        let rc = crypto_aead_xchacha20poly1305_ietf_encrypt(&out, &outLen, plaintext, UInt64(plaintext.count), aeadAD, UInt64(aeadAD.count),
                                                             nil, nonce, key)
        return rc == 0 ? Array(out.prefix(Int(outLen))) : nil
    }

    /// Raw DEFLATE (RFC 1951) decode with Apple's Compression (`COMPRESSION_ZLIB` is raw DEFLATE, no header),
    /// refusing output larger than `max` (an error, never a truncation).
    static func inflateRaw(_ data: [UInt8], max: Int = EventLimits.maxInflatedBytes) throws(EventPackError) -> [UInt8] {
        guard !data.isEmpty else { throw .inflate }
        let result: Result<[UInt8], EventPackError> = data.withUnsafeBufferPointer { src in
            let chunk = 64 * 1024
            let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
            defer { dst.deallocate() }
            let streamPtr = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
            defer { streamPtr.deallocate() }
            guard compression_stream_init(streamPtr, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
                return .failure(.inflate)
            }
            defer { compression_stream_destroy(streamPtr) }
            streamPtr.pointee.src_ptr = src.baseAddress!
            streamPtr.pointee.src_size = src.count
            var out = [UInt8]()
            while true {
                streamPtr.pointee.dst_ptr = dst
                streamPtr.pointee.dst_size = chunk
                let status = compression_stream_process(streamPtr, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let produced = chunk - streamPtr.pointee.dst_size
                if produced > 0 {
                    if out.count + produced > max { return .failure(.inflateTooLarge) }
                    out.append(contentsOf: UnsafeBufferPointer(start: dst, count: produced))
                }
                switch status {
                case COMPRESSION_STATUS_END:
                    return .success(out)
                case COMPRESSION_STATUS_OK:
                    // No progress with all input consumed: truncated stream.
                    if produced == 0 && streamPtr.pointee.src_size == 0 { return .failure(.inflate) }
                default:
                    return .failure(.inflate)
                }
            }
        }
        return try result.get()
    }

    /// Decrypt → bounded inflate → envelope → signature → event (§7 steps 1–2).
    /// The event JSON is parsed only after the signature verified.
    static func open(key: [UInt8], sealed: SealedEventPack) throws(EventPackError) -> OpenedEventPack {
        let deflated = try decrypt(key: key, nonce: sealed.nonce, ciphertext: sealed.ciphertext)
        let envelopeBytes = try inflateRaw(deflated)
        guard let env = try? JSONDecoder().decode(Envelope.self, from: Data(envelopeBytes)) else { throw .malformedEnvelope }
        guard env.v == 1 else { throw .unsupportedEnvelopeVersion(env.v) }
        guard let pub = B64u.decode(env.pub), pub.count == 32,
              let sig = B64u.decode(env.sig), sig.count == 64,
              let eventBytes = B64u.decode(env.event) else { throw .malformedEnvelope }
        guard Sodium().sign.verify(message: signaturePrefix + eventBytes, publicKey: pub, signature: sig) else { throw .badSignature }
        let event = try EventDoc.parse(eventBytes)
        let revisionHash = event.variant?.fullHashBytes ?? EventCodes.bytesHash(eventBytes)
        return OpenedEventPack(event: event, eventBytes: eventBytes, organizerPub: pub, revisionHash: revisionHash)
    }
}
