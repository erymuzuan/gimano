import Foundation
import Testing
@testable import Gimano

/// `.manotu-event` file suite 2 (docs/events.md §5.2): 8-character secret code, Argon2id file key, against the shared
/// vectors (`argon2-kat.json`, the `…V2…` fields of the positive vectors, the secret-code negatives).
private final class FileSuiteVectorToken {}

private enum FV {
    static let positive = ["kenduri-one-day", "ride-two-day"]

    static func load(_ name: String) throws -> [String: Any] {
        let url = try #require(Bundle(for: FileSuiteVectorToken.self).url(forResource: name, withExtension: "json"),
                               "vector \(name).json missing from the test bundle")
        return try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    static func bytes(_ v: [String: Any], _ key: String) throws -> [UInt8] {
        let s = try #require(v[key] as? String)
        return try #require(B64u.decode(s), "\(key) is not strict base64url")
    }

    static func string(_ v: [String: Any], _ key: String) throws -> String { try #require(v[key] as? String) }

    static func code(_ body: () throws -> Void) -> String? {
        do { try body(); return nil } catch let e as EventPackError { return e.code } catch { return "other: \(error)" }
    }

    static let now = EventTime.parse("2026-11-01T02:15:00Z")!.date

    static func draft() -> HostedEventDraft {
        let kl = TimeZone(identifier: "Asia/Kuala_Lumpur")!
        var d = HostedEventDraft.new(now: now, timeZone: kl, preferredLanguage: "ms")
        d.title = "Kenduri Aqil & Sofea"
        d.stops = [HostedStopDraft(kind: .venue, label: "Dewan Seri Melati", location: LatLng(lat: 2.9866, lng: 101.7942))]
        return d
    }

    /// The salt of a suite-2 file.
    static func salt(_ file: [UInt8]) throws -> [UInt8] {
        guard case .code(let salt) = try EventPack.parseFile(file).suite else { throw EventPackError.unsupportedSuite(file[5]) }
        return salt
    }
}

@Suite("Event file suite 2: vectors")
struct EventFileSuiteVectorTests {
    @Test("argon2-kat: every code is read leniently to its password and derives its key (prints the time per derivation)")
    func argon2Kat() throws {
        let v = try FV.load("argon2-kat")
        let params = try #require(v["params"] as? [String: Any])
        #expect(params["opslimit"] as? Int == EventFileKey.opsLimit)
        #expect(params["memlimitBytes"] as? Int == EventFileKey.memLimit)
        #expect(params["outlen"] as? Int == EventPack.keyBytes && params["alg"] as? String == "argon2id13")
        let kats = try #require(v["argon2Kat"] as? [[String: Any]])
        #expect(kats.count >= 5)
        var times: [Duration] = []
        for k in kats {
            let code = try FV.string(k, "code")
            #expect(try SecretCodeV2.parse(code) == (try FV.string(k, "password")), "\(code)")
            let salt = try FV.bytes(k, "saltB64u")
            #expect(salt.count == EventFileKey.saltBytes)
            var key: [UInt8] = []
            times.append(try ContinuousClock().measure { key = try EventFileKey.derive(code: code, salt: salt) })
            #expect(B64u.encode(key) == (try FV.string(k, "keyB64u")), "\(code)")
            #expect(key.map { String(format: "%02x", $0) }.joined() == (try FV.string(k, "keyHex")), "\(code)")
        }
        let ms = times.map { Int(($0 / .milliseconds(1)).rounded()) }
        print("MANOTU_ARGON2_MS \(ms) mean \(ms.reduce(0, +) / max(ms.count, 1))")
    }

    @Test("Suite-2 file + secretCodeV2 opens to the signed event; lenient code reading; the suite-1 file still opens",
          arguments: FV.positive)
    func openSuite2(_ name: String) throws {
        let v = try FV.load(name)
        let eventBytes = Array(try FV.string(v, "eventJson").utf8)
        let file = try FV.bytes(v, "fileV2B64u")
        let code = try FV.string(v, "secretCodeV2")
        #expect(file[4] == 1 && file[5] == 2)
        #expect(try FV.salt(file) == (try FV.bytes(v, "fileSaltB64u")))
        let typed = code.lowercased().replacingOccurrences(of: "-", with: " ")
        for c in [code, typed, " \(code.replacingOccurrences(of: "-", with: "")) "] {
            let p = try EventPack.openFile(file, secretCode: c)
            #expect(p.eventBytes == eventBytes)
            #expect(B64u.encode(p.organizerPub) == (try FV.string(v, "organizerPubB64u")))
            #expect(p.safetyCode == (try FV.string(v, "safetyCode")))
            #expect(B64u.encode(p.revisionHash) == (try FV.string(v, "revisionHashB64u")))
        }
        // Suite 1 (read only) with its 52-character code.
        let old = try EventPack.openFile(try FV.bytes(v, "fileB64u"), secretCode: try FV.string(v, "secretCode"))
        #expect(old.eventBytes == eventBytes)
        #expect(try EventPack.parseFile(try FV.bytes(v, "fileB64u")).suite == .rawKey)
    }

    @Test("Writer: the file key, the ciphertext under it and the suite-2 framing are reproduced byte for byte",
          arguments: FV.positive)
    func writeSuite2(_ name: String) throws {
        let v = try FV.load(name)
        let salt = try FV.bytes(v, "fileSaltB64u")
        let nonce = try FV.bytes(v, "fileNonceV2B64u")
        let key = try EventFileKey.derive(code: try FV.string(v, "secretCodeV2"), salt: salt)
        #expect(key == (try FV.bytes(v, "fileKeyV2B64u")))
        #expect(key != (try FV.bytes(v, "keyB64u")))   // independent of the link key
        let ciphertext = try #require(EventPack.encrypt(key: key, nonce: nonce, plaintext: try FV.bytes(v, "deflatedB64u")))
        let file = try EventPack.fileBytes(salt: salt, sealed: SealedEventPack(nonce: nonce, ciphertext: ciphertext))
        #expect(file == (try FV.bytes(v, "fileV2B64u")))
        #expect(Array(file.prefix(6)) == Array("MNTE".utf8) + [1, 2])

        // The deterministic writer (own DEFLATE, so only the round trip is specified) with the vector's salt and nonce.
        let org = try #require(EventOrganizerKey(seed: try FV.bytes(v, "ed25519SeedB64u")))
        let eventBytes = Array(try FV.string(v, "eventJson").utf8)
        let (parts, own) = try EventPack.sealFileParts(eventBytes: eventBytes, organizer: org, code: try FV.string(v, "secretCodeV2"),
                                                       salt: salt, nonce: nonce)
        #expect(Array(own.prefix(46)) == Array(file.prefix(46)))   // header, salt, nonce
        #expect(parts.sealed.nonce == nonce)
        #expect(try EventPack.openFile(own, secretCode: try FV.string(v, "secretCodeV2")).eventBytes == eventBytes)
    }

    @Test("A well-formed but wrong 8-character code derives, then fails with decrypt")
    func wrongCode() throws {
        let v = try FV.load("negative-wrong-secret-code")
        #expect(v["expectedError"] as? String == "decrypt")
        let file = try FV.bytes(v, "fileB64u")
        #expect(FV.code { _ = try EventPack.openFile(file, secretCode: try FV.string(v, "secretCode")) } == "decrypt")
    }

    @Test("Codes with 0, O, 1 or I are invalid_secret_code, before Argon2id runs")
    func invalidCodes() throws {
        let v = try FV.load("negative-invalid-secret-code")
        #expect(v["expectedError"] as? String == "invalid_secret_code")
        let file = try FV.bytes(v, "fileB64u")
        let codes = try #require(v["secretCodes"] as? [String])
        #expect(codes.count == 5)
        for c in codes {
            var code: String?
            let t = ContinuousClock().measure { code = FV.code { _ = try EventPack.openFile(file, secretCode: c) } }
            #expect(code == "invalid_secret_code", "\(c)")
            #expect(t < .milliseconds(20), "\(c) took \(t): Argon2id must not run")
            // The code is checked before anything else: even an impossible salt doesn't get that far.
            #expect(FV.code { _ = try EventFileKey.derive(code: c, salt: []) } == "invalid_secret_code", "\(c)")
        }
    }

    @Test("The other suite's code is invalid_secret_code: 52 characters on a suite-2 file, 8 on a suite-1 file")
    func crossSuite() throws {
        let v = try FV.load("kenduri-one-day")
        #expect(FV.code { _ = try EventPack.openFile(try FV.bytes(v, "fileV2B64u"), secretCode: try FV.string(v, "secretCode")) }
                == "invalid_secret_code")
        #expect(FV.code { _ = try EventPack.openFile(try FV.bytes(v, "fileB64u"), secretCode: try FV.string(v, "secretCodeV2")) }
                == "invalid_secret_code")
    }

    @Test("Wrong lengths, foreign characters and an empty code are invalid_secret_code")
    func codeRules() throws {
        let file = try FV.bytes(try FV.load("kenduri-one-day"), "fileV2B64u")
        for bad in ["", "K7M4-QX9", "K7M4-QX9PP", "K7M4_QX9P", "K7M4-QX9É", "K7M4.QX9P", "KKKK-KKKK-KKKK"] {
            let code = FV.code { _ = try EventPack.openFile(file, secretCode: bad) }
            #expect(code == "invalid_secret_code", "\(bad)")
        }
    }
}

@Suite("Event file suite 2: codes and writer")
struct EventFileSuiteTests {
    @Test("SecretCodeV2: 40 bits MSB first as XXXX-XXXX; generated codes read back")
    func codeFormat() throws {
        #expect(SecretCodeV2.encode([0, 0, 0, 0, 0]) == "2222-2222")
        #expect(SecretCodeV2.encode([0xFF, 0xFF, 0xFF, 0xFF, 0xFF]) == "ZZZZ-ZZZZ")
        #expect(SecretCodeV2.encode([0x08, 0x86, 0x42, 0x98, 0xE8]) == "3456-789A")
        var seen = Set<String>()
        for _ in 0..<256 {
            let c = SecretCodeV2.generate()
            #expect(c.count == 9 && Array(c)[4] == "-")
            #expect(!c.contains(where: { "01OI".contains($0) }))
            #expect(try SecretCodeV2.parse(c) == c.replacingOccurrences(of: "-", with: ""))
            #expect(SecretCodeV2.display(try SecretCodeV2.parse(c)) == c)
            seen.insert(c)
        }
        #expect(seen.count > 250)
        #expect(try SecretCodeV2.parse("k7m4-qx9p") == "K7M4QX9P")
        #expect(try SecretCodeV2.parse(" K7M4 QX9P ") == "K7M4QX9P")
        #expect(try SecretCodeV2.parse("K-7-M-4QX9P") == "K7M4QX9P")
        #expect(throws: EventPackError.invalidSecretCode("wrong length")) { try SecretCodeV2.parse("K7M4-QX9") }
        #expect(throws: EventPackError.invalidSecretCode("too long")) { try SecretCodeV2.parse("K7M4-QX9PP") }
        #expect(!SecretCodeV2.isWellFormed(SecretCode.encode([UInt8](repeating: 7, count: 32))))
    }

    @Test("Writer round trip: sealFile makes a suite-2 file that opens with its code, with a fresh salt and nonce each time")
    func roundTrip() throws {
        let org = try #require(EventOrganizerKey(seed: EventRandom.bytes(32)))
        let eventBytes = try #require(FV.draft().event(rev: 1, parent: nil, publishedAt: FV.now).0).bytes()
        let code = SecretCodeV2.generate()
        let a = try EventPack.sealFile(eventBytes: eventBytes, organizer: org, code: code)
        let b = try EventPack.sealFile(eventBytes: eventBytes, organizer: org, code: code)
        #expect(Array(a.prefix(6)) == Array("MNTE".utf8) + [1, 2])
        #expect(try FV.salt(a) != (try FV.salt(b)))
        #expect(Array(a[22..<46]) != Array(b[22..<46]))   // nonces
        for f in [a, b] {
            let p = try EventPack.openFile(f, secretCode: code.lowercased())
            #expect(p.eventBytes == eventBytes && p.organizerPub == org.publicKey)
        }
        let other = SecretCodeV2.generate()
        if other != code { #expect(FV.code { _ = try EventPack.openFile(a, secretCode: other) } == "decrypt") }
        #expect(FV.code { _ = try EventPack.sealFile(eventBytes: eventBytes, organizer: org, code: "O000-0000") } == "invalid_secret_code")
    }

    @Test("One code per event: every revision's file uses the same code with a different salt; the link key is separate")
    func stableCodeAcrossRevisions() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        var d = FV.draft()
        try store.saveDraft(d)
        guard case .success(let v1) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: FV.now) else {
            Testing.Issue.record("v1"); return
        }
        guard case .success(let again) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: FV.now) else {
            Testing.Issue.record("again"); return
        }
        d.stops[0].location = LatLng(lat: 2.9901, lng: 101.7955)
        try store.saveDraft(d)
        guard case .success(let v2) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: FV.now.addingTimeInterval(60)) else {
            Testing.Issue.record("v2"); return
        }
        #expect(again.reused && v2.doc.rev == 2)
        let stored = try #require(secrets.load(eventId: d.id))
        #expect(stored.fileCode == v1.secretCode)
        #expect([again.secretCode, v2.secretCode].allSatisfy { $0 == v1.secretCode })
        #expect(SecretCodeV2.isWellFormed(v1.secretCode))
        let salts = try [v1, again, v2].map { try FV.salt($0.fileBytes) }
        #expect(Set(salts.map(B64u.encode)).count == 3)
        #expect(try EventPack.openFile(v1.fileBytes, secretCode: v1.secretCode).event.rev == 1)
        #expect(try EventPack.openFile(again.fileBytes, secretCode: v1.secretCode).event.rev == 1)
        #expect(try EventPack.openFile(v2.fileBytes, secretCode: v1.secretCode).event.rev == 2)
        // The file key isn't the link key.
        let parsed = try EventPack.parseFile(v2.fileBytes)
        #expect(FV.code { _ = try EventPack.open(key: stored.packKey, sealed: parsed.sealed) } == "decrypt")
    }

    @Test("Migration: an event published before suite 2 (no file code) gets one on its next file, kept afterwards")
    func migration() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        var d = FV.draft()
        try store.saveDraft(d)
        guard case .success(let v1) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: FV.now) else {
            Testing.Issue.record("v1"); return
        }
        // What an older build left in the Keychain: seed ‖ pack key (64 bytes), no file code.
        let full = try #require(secrets.load(eventId: d.id))
        let legacy = try #require(EventSecrets(itemBytes: full.seed + full.packKey))
        #expect(legacy.fileCode == nil && legacy.seed == full.seed && legacy.packKey == full.packKey)
        _ = secrets.save(legacy, eventId: d.id)

        d.title = "Kenduri Aqil & Sofea (dikemas kini)"
        try store.saveDraft(d)
        guard case .success(let v2) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: FV.now.addingTimeInterval(60)) else {
            Testing.Issue.record("v2"); return
        }
        let migrated = try #require(secrets.load(eventId: d.id))
        #expect(SecretCodeV2.isWellFormed(v2.secretCode) && migrated.fileCode == v2.secretCode)
        #expect(migrated.seed == full.seed && migrated.packKey == full.packKey)   // same organizer and link key
        let (l1, l2) = (try #require(v1.link), try #require(v2.link))
        #expect(try EventPack.openLink(l2).organizerPub == (try EventPack.openLink(l1).organizerPub))
        #expect(try EventPack.openFile(v2.fileBytes, secretCode: v2.secretCode).event.rev == 2)

        guard case .success(let v3) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: FV.now.addingTimeInterval(120)) else {
            Testing.Issue.record("v3"); return
        }
        #expect(v3.secretCode == v2.secretCode)
    }

    @Test("Keychain item bytes: 64 bytes (no code) and 72 bytes (with code) read back; anything else is rejected")
    func itemBytes() throws {
        let s = EventSecrets.generate()
        #expect(s.itemBytes.count == 72)
        #expect(EventSecrets(itemBytes: s.itemBytes) == s)
        let legacy = EventSecrets(seed: s.seed, packKey: s.packKey)
        #expect(legacy.itemBytes.count == 64 && EventSecrets(itemBytes: legacy.itemBytes) == legacy)
        #expect(EventSecrets(itemBytes: Array(s.itemBytes.prefix(70))) == nil)
        #expect(EventSecrets(itemBytes: s.seed + s.packKey + Array("O0000000".utf8)) == nil)
    }
}
