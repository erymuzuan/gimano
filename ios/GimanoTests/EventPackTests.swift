import Compression
import Foundation
import GRDB
import Sodium
import Testing
import ZIPFoundation
@testable import Gimano

/// Shared test vectors (docs/events.md, `desktop/crates/event-core/tests/vectors/`), copied into this bundle from
/// `GimanoTests/EventVectors/`. `vectorsInSync` fails if the copies drift from the desktop originals.
private final class VectorBundleToken {}

private enum Vectors {
    static let names = ["kenduri-one-day", "ride-two-day", "negative-tampered-ciphertext", "negative-bad-signature",
                        "negative-wrong-key", "negative-expired", "negative-oversized-fragment",
                        "ride-two-day-stops-only", "negative-variant-with-polyline", "negative-missing-polyline",
                        "negative-unknown-variant", "import-stops-only-sequence"]
    static let positive = ["kenduri-one-day", "ride-two-day", "ride-two-day-stops-only"]
    /// Correctly signed events that break a §5.5 variant rule, with the error each must give.
    static let variantNegatives = ["negative-variant-with-polyline", "negative-missing-polyline", "negative-unknown-variant"]

    static func url(_ name: String) -> URL? {
        Bundle(for: VectorBundleToken.self).url(forResource: name, withExtension: "json")
    }

    static func load(_ name: String) throws -> [String: Any] {
        let url = try #require(url(name), "vector \(name).json missing from the test bundle")
        return try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    static func bytes(_ v: [String: Any], _ key: String) throws -> [UInt8] {
        let s = try #require(v[key] as? String)
        return try #require(B64u.decode(s), "\(key) is not strict base64url")
    }

    static func date(_ v: [String: Any]) throws -> Date {
        let s = try #require(v["now"] as? String)
        return try #require(EventTime.parse(s)).date
    }

    static func code(_ body: () throws -> Void) -> String? {
        do { try body(); return nil } catch let e as EventPackError { return e.code } catch { return "other: \(error)" }
    }
}

@Suite("Event pack vectors (docs/events.md)")
struct EventVectorTests {
    @Test("Every committed vector is bundled, and the copies match desktop/crates/event-core/tests/vectors")
    func vectorsInSync() throws {
        for n in Vectors.names { #expect(Vectors.url(n) != nil, "missing \(n)") }
        // The simulator can read the Mac's checkout: compare byte for byte when it's there.
        let src = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("desktop/crates/event-core/tests/vectors")
        guard let files = try? FileManager.default.contentsOfDirectory(atPath: src.path) else { return }
        let jsons = files.filter { $0.hasSuffix(".json") }.map { String($0.dropLast(5)) }
        #expect(Set(jsons) == Set(Vectors.names), "vector set changed: update GimanoTests/EventVectors and this test")
        for n in jsons {
            let a = try Data(contentsOf: src.appendingPathComponent("\(n).json"))
            let b = try Data(contentsOf: try #require(Vectors.url(n)))
            #expect(a == b, "\(n).json differs from the desktop copy")
        }
    }

    @Test("Positive vectors: link, fragment and file + secret code all open to the signed event bytes",
          arguments: Vectors.positive)
    func positiveRead(_ name: String) throws {
        let v = try Vectors.load(name)
        let eventJson = try #require(v["eventJson"] as? String)
        let link = try #require(v["link"] as? String)
        let fragment = try #require(v["linkFragment"] as? String)
        let fileBytes = try Vectors.bytes(v, "fileB64u")
        let secret = try #require(v["secretCode"] as? String)
        let expected = try #require(v["expected"] as? [String: Any])

        let opened = [try EventPack.openLink(link), try EventPack.openLink(fragment), try EventPack.openFile(fileBytes, secretCode: secret),
                      try EventPack.openLink("manotu://event#\(fragment)"), try EventPack.openLink("gimano://event?stop=x#\(fragment)")]
        for p in opened {
            #expect(p.eventBytes == Array(eventJson.utf8))
            #expect(B64u.encode(p.organizerPub) == v["organizerPubB64u"] as? String)
            #expect(p.safetyCode == v["safetyCode"] as? String)
            #expect(B64u.encode(p.revisionHash) == v["revisionHashB64u"] as? String)
            #expect(p.event.id == expected["id"] as? String)
            #expect(Int(p.event.rev) == expected["rev"] as? Int)
            #expect(p.event.title == expected["title"] as? String)
            #expect(p.event.stops.count == expected["stops"] as? Int)
            #expect(p.event.sessions.count == expected["sessions"] as? Int)
            #expect(p.event.routes.count == expected["routes"] as? Int)
            #expect(p.event.polylinePointCount == expected["polylinePoints"] as? Int)
            #expect(p.isStopsOnly == (expected["stopsOnly"] as? Bool ?? false))
            let decision = EventImport.decide(EventImportCandidate(p), stored: nil, now: try Vectors.date(v))
            #expect(decision.code == expected["decision"] as? String)
            #expect(decision == .new(safetyCode: try #require(v["safetyCode"] as? String)))
        }
    }

    @Test("Positive vectors: every deterministic layer is reproduced byte for byte", arguments: Vectors.positive)
    func positiveWrite(_ name: String) throws {
        let v = try Vectors.load(name)
        let key = try Vectors.bytes(v, "keyB64u")
        let nonce = try Vectors.bytes(v, "nonceB64u")
        let deflated = try Vectors.bytes(v, "deflatedB64u")
        let ciphertext = try Vectors.bytes(v, "ciphertextB64u")
        let eventBytes = try Vectors.bytes(v, "eventB64u")
        let envelope = try #require(v["envelopeJson"] as? String)
        #expect(eventBytes == Array(try #require(v["eventJson"] as? String).utf8))
        #expect(try Vectors.bytes(v, "envelopeB64u") == Array(envelope.utf8))

        // Ed25519 key and signature from the seed.
        let kp = try #require(Sodium().sign.keyPair(seed: try Vectors.bytes(v, "ed25519SeedB64u")))
        #expect(B64u.encode(kp.publicKey) == v["organizerPubB64u"] as? String)
        let sig = try #require(Sodium().sign.signature(message: EventPack.signaturePrefix + eventBytes, secretKey: kp.secretKey))
        #expect(B64u.encode(sig) == v["signatureB64u"] as? String)
        // Envelope bytes from the event bytes.
        let built = #"{"v":1,"event":"\#(B64u.encode(eventBytes))","pub":"\#(B64u.encode(kp.publicKey))","sig":"\#(B64u.encode(sig))"}"#
        #expect(built == envelope)
        // Raw inflate of the committed DEFLATE stream.
        #expect(try EventPack.inflateRaw(deflated) == Array(envelope.utf8))
        // XChaCha20-Poly1305 of the vector's DEFLATE bytes with its key and nonce.
        #expect(EventPack.encrypt(key: key, nonce: nonce, plaintext: deflated) == ciphertext)
        #expect(try EventPack.decrypt(key: key, nonce: nonce, ciphertext: ciphertext) == deflated)
        // Framing and codes.
        #expect(EventPack.fileMagic + [1, 1] + nonce + ciphertext == (try Vectors.bytes(v, "fileB64u")))
        #expect("1.\(B64u.encode(key)).\(B64u.encode(nonce + ciphertext))" == v["linkFragment"] as? String)
        #expect("\(try #require(v["site"] as? String))/a/#\(try #require(v["linkFragment"] as? String))" == v["link"] as? String)
        #expect(SecretCode.encode(key) == v["secretCode"] as? String)
        #expect(try SecretCode.decode(try #require(v["secretCode"] as? String)) == key)
        #expect(EventCodes.safetyCode(kp.publicKey) == v["safetyCode"] as? String)
        #expect(B64u.encode(EventCodes.revisionHash(eventBytes)) == v["revisionHashB64u"] as? String)
    }

    @Test("Stops-only variant: no polylines, revision hash = fullHash = the full event's, rebuilt byte for byte from it")
    func stopsOnlyVector() throws {
        let v = try Vectors.load("ride-two-day-stops-only")
        let full = try Vectors.load(try #require(v["fullVector"] as? String))
        let p = try EventPack.openLink(try #require(v["linkFragment"] as? String))
        #expect(p.isStopsOnly && p.event.routes.allSatisfy { $0.polyline == nil && $0.points.isEmpty })
        #expect(p.event.variant?.fullHash == v["fullHashB64u"] as? String)
        #expect(B64u.encode(p.revisionHash) == v["fullHashB64u"] as? String)
        #expect(B64u.encode(p.revisionHash) == full["revisionHashB64u"] as? String)
        #expect(B64u.encode(EventCodes.bytesHash(p.eventBytes)) == v["eventBytesHashB64u"] as? String)
        #expect(B64u.encode(p.revisionHash) != v["eventBytesHashB64u"] as? String)
        // Distances, times and stops of the routes are kept.
        let fullPack = try EventPack.openLink(try #require(full["linkFragment"] as? String))
        #expect(p.event.routes.map(\.distanceM) == fullPack.event.routes.map(\.distanceM))
        #expect(p.event.routes.map(\.stopIds) == fullPack.event.routes.map(\.stopIds))
        #expect(p.organizerPub == fullPack.organizerPub && p.event.rev == fullPack.event.rev)
        // Our writer makes the same variant bytes from the full bytes (not required, but it does).
        let fullBytes = Array(try #require(full["eventJson"] as? String).utf8)
        #expect(try EventDoc.stopsOnlyVariant(fullEventBytes: fullBytes) == p.eventBytes)
        #expect(Vectors.code { _ = try EventDoc.stopsOnlyVariant(fullEventBytes: p.eventBytes) } == "not_full_event")
    }

    @Test("Variant rules: signed events that break them are rejected with their own codes", arguments: Vectors.variantNegatives)
    func variantNegative(_ name: String) throws {
        let v = try Vectors.load(name)
        let code = try #require(v["expectedError"] as? String)
        let fragment = try #require(v["linkFragment"] as? String)
        #expect(Vectors.code { _ = try EventPack.openLink(fragment) } == code)
        // The parser alone gives the same code.
        let json = try #require(v["eventJson"] as? String)
        #expect(Vectors.code { _ = try EventDoc.parse(Array(json.utf8)) } == code)
    }

    @Test("Variant edge cases: null variant is a full event; a malformed fullHash is invalid_event")
    func variantEdges() throws {
        let full = try #require(try Vectors.load("ride-two-day")["eventJson"] as? String)
        let variant = try #require(try Vectors.load("ride-two-day-stops-only")["eventJson"] as? String)
        func parse(_ s: String) -> String? { Vectors.code { _ = try EventDoc.parse(Array(s.utf8)) } }
        #expect(parse(String(full.dropLast()) + #","variant":null}"#) == nil)
        let hash = try #require(try Vectors.load("ride-two-day-stops-only")["fullHashB64u"] as? String)
        #expect(parse(variant.replacingOccurrences(of: hash, with: "AAAA")) == "invalid_event")
        // Revision hash from bytes: fullHash for a variant, SHA-256 otherwise.
        #expect(EventCodes.revisionHash(Array(variant.utf8)) == B64u.decode(hash))
        #expect(EventCodes.revisionHash(Array(full.utf8)) == EventCodes.bytesHash(Array(full.utf8)))
    }

    @Test("Import sequence: variant and full event are one revision (upgrade, already imported, parent via fullHash)")
    func importSequence() throws {
        let v = try Vectors.load("import-stops-only-sequence")
        let now = try Vectors.date(v)
        let packs = try #require(v["packs"] as? [String: [String: Any]])
        var opened: [String: OpenedEventPack] = [:]
        for (name, p) in packs {
            let pack = try EventPack.openLink(try #require(p["linkFragment"] as? String))
            #expect(B64u.encode(pack.revisionHash) == p["revisionHashB64u"] as? String, "\(name)")
            #expect(Int(pack.event.rev) == p["rev"] as? Int && pack.isStopsOnly == p["stopsOnly"] as? Bool, "\(name)")
            opened[name] = pack
        }
        #expect(opened["rev2"]?.event.parent == packs["variant"]?["revisionHashB64u"] as? String)
        let sequences = try #require(v["sequences"] as? [[String: Any]])
        #expect(sequences.count == 4)
        for seq in sequences {
            let name = seq["name"] as? String ?? "?"
            let store = try EventStore(directory: nil)
            for step in try #require(seq["steps"] as? [[String: Any]]) {
                let packName = try #require(step["import"] as? String)
                let pack = try #require(opened[packName])
                let decision = EventImport.decide(EventImportCandidate(pack), stored: try store.record(eventId: pack.event.id), now: now)
                #expect(decision.code == step["decision"] as? String, "\(name)")
                let parent: String? = if case .newer(_, let p) = decision {
                    switch p { case .matches: "matches"; case .missedRevisions: "missed_revisions"; case .mismatch: "mismatch" }
                } else { nil }
                #expect(parent == step["parentCheck"] as? String, "\(name)")
                try store.save(pack, decision: decision, now: now)
                let after = try #require(try store.record(eventId: pack.event.id)).revisions.sorted { $0.rev < $1.rev }
                let expected = try #require(step["storedAfter"] as? [[String: Any]])
                #expect(after.map { Int($0.rev) } == expected.map { $0["rev"] as? Int ?? -1 }, "\(name)")
                #expect(after.map { B64u.encode($0.hash) } == expected.map { $0["revisionHashB64u"] as? String ?? "" }, "\(name)")
                #expect(after.map(\.stopsOnly) == expected.map { $0["stopsOnly"] as? Bool ?? false }, "\(name)")
            }
            // What the guest sees at the end: the current revision is full unless only the variant ever arrived.
            let current = try #require(try store.events(now: now).first)
            let last = try #require((seq["steps"] as? [[String: Any]])?.last?["storedAfter"] as? [[String: Any]])
            #expect(current.doc.isStopsOnly == (last.last?["stopsOnly"] as? Bool ?? false), "\(name)")
        }
    }

    @Test("Tampered ciphertext fails with decrypt")
    func tampered() throws {
        let v = try Vectors.load("negative-tampered-ciphertext")
        #expect(v["expectedError"] as? String == "decrypt")
        let fragment = try #require(v["linkFragment"] as? String)
        #expect(Vectors.code { _ = try EventPack.openLink(fragment) } == "decrypt")
    }

    @Test("Bad signature: decrypts and inflates, then fails with signature")
    func badSignature() throws {
        let v = try Vectors.load("negative-bad-signature")
        #expect(v["expectedError"] as? String == "signature")
        let fragment = try #require(v["linkFragment"] as? String)
        let (key, sealed) = try EventPack.parseLink(fragment)
        let deflated = try EventPack.decrypt(key: key, nonce: sealed.nonce, ciphertext: sealed.ciphertext)
        #expect(deflated == (try Vectors.bytes(v, "deflatedB64u")))
        #expect(try EventPack.inflateRaw(deflated) == Array(try #require(v["envelopeJson"] as? String).utf8))
        #expect(Vectors.code { _ = try EventPack.openLink(fragment) } == "signature")
    }

    @Test("Wrong key fails with decrypt, as a link and as a file + secret code")
    func wrongKey() throws {
        let v = try Vectors.load("negative-wrong-key")
        let fragment = try #require(v["linkFragment"] as? String)
        let file = try Vectors.bytes(v, "fileB64u")
        let secret = try #require(v["secretCode"] as? String)
        #expect(Vectors.code { _ = try EventPack.openLink(fragment) } == "decrypt")
        #expect(Vectors.code { _ = try EventPack.openFile(file, secretCode: secret) } == "decrypt")
    }

    @Test("Expired: decrypts and verifies, then the decision is expired and nothing is stored")
    func expired() throws {
        let v = try Vectors.load("negative-expired")
        #expect(v["expectedError"] as? String == "expired")
        let pack = try EventPack.openLink(try #require(v["linkFragment"] as? String))
        let now = try Vectors.date(v)
        let decision = EventImport.decide(EventImportCandidate(pack), stored: nil, now: now)
        #expect(decision == .expired)
        let store = try EventStore(directory: nil)
        #expect(try store.save(pack, decision: decision, now: now) == false)
        #expect(try store.save(pack, decision: .new(safetyCode: pack.safetyCode), now: now) == false)
        #expect(try store.events(now: now).isEmpty)
    }

    @Test("Oversized fragment is rejected before decoding; the same pack opens as a file")
    func oversized() throws {
        let v = try Vectors.load("negative-oversized-fragment")
        let fragment = try #require(v["linkFragment"] as? String)
        #expect(fragment.count == v["fragmentLength"] as? Int)
        #expect(Vectors.code { _ = try EventPack.openLink(fragment) } == "fragment_too_long")
        // Rejected even when the rest is garbage: the length check comes first.
        #expect(Vectors.code { _ = try EventPack.openLink(String(repeating: "!", count: 16_001)) } == "fragment_too_long")
        let pack = try EventPack.openFile(try Vectors.bytes(v, "fileB64u"), secretCode: try #require(v["secretCode"] as? String))
        #expect(pack.event.id == v["fileEventId"] as? String)
        #expect(EventImport.decide(EventImportCandidate(pack), stored: nil, now: try Vectors.date(v)).code == "new")
    }
}

@Suite("Event pack reading rules")
struct EventPackRuleTests {
    @Test("base64url is strict: no padding, no foreign characters, canonical trailing bits")
    func strictB64u() {
        #expect(B64u.decode("AQ") == [1])
        #expect(B64u.decode("AQ==") == nil)
        #expect(B64u.decode("AR") == nil)      // non-zero trailing bits
        #expect(B64u.decode("A+Q") == nil)
        #expect(B64u.decode("A") == nil)
        #expect(B64u.decode("_-8") == [0xFF, 0xEF])
        for n in 0..<40 {
            let b = (0..<n).map { UInt8(($0 * 37 + n) & 0xFF) }
            #expect(B64u.decode(B64u.encode(b)) == b)
        }
    }

    @Test("Secret code: lenient reading, strict length and padding")
    func secretCode() throws {
        #expect(SecretCode.encode([UInt8](repeating: 0, count: 32)) == "0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000")
        #expect(SecretCode.encode([UInt8](repeating: 0xFF, count: 32)) == "ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZG")
        let key = (0..<32).map { UInt8($0) }
        let code = SecretCode.encode(key)
        #expect(try SecretCode.decode(code.lowercased().replacingOccurrences(of: "-", with: " ")) == key)
        #expect(try SecretCode.decode(code.replacingOccurrences(of: "0", with: "o").replacingOccurrences(of: "1", with: "l")) == key)
        #expect(try SecretCode.decode(code.replacingOccurrences(of: "1", with: "I")) == key)
        #expect(Vectors.code { _ = try SecretCode.decode(String(code.dropLast())) } == "invalid_secret_code")
        #expect(Vectors.code { _ = try SecretCode.decode(code + "0") } == "invalid_secret_code")
        #expect(Vectors.code { _ = try SecretCode.decode("U" + code.dropFirst()) } == "invalid_secret_code")
        var bad = SecretCode.encode([UInt8](repeating: 0, count: 32))
        bad.removeLast()
        bad.append("1")
        #expect(throws: EventPackError.invalidSecretCode("non-canonical padding")) { try SecretCode.decode(bad) }
    }

    @Test("Safety code of 32 zero bytes")
    func safetyCode() {
        #expect(EventCodes.safetyCode([UInt8](repeating: 0, count: 32)) == "ETN9PD")
    }

    @Test("Inflate is bounded at 20 MiB: an error, not a truncation")
    func inflateBound() throws {
        let big = [UInt8](repeating: 0x41, count: 21 * 1024 * 1024)
        let deflated = try #require(Self.deflateRaw(big))
        #expect(deflated.count < 100_000)
        #expect(Vectors.code { _ = try EventPack.inflateRaw(deflated) } == "inflate_too_large")
        #expect(try EventPack.inflateRaw(deflated, max: big.count) == big)
        #expect(Vectors.code { _ = try EventPack.inflateRaw(Array(deflated.prefix(10))) } == "inflate")
        #expect(Vectors.code { _ = try EventPack.inflateRaw([0xFF, 0xFF, 0xFF]) } == "inflate")
    }

    @Test("Links: malformed, unsupported version, and pasted messages")
    func links() throws {
        #expect(Vectors.code { _ = try EventPack.parseLink("https://x/a/#1.abc") } == "malformed_link")
        #expect(Vectors.code { _ = try EventPack.parseLink("2.AA.AA") } == "unsupported_link_version")
        #expect(EventPack.extractLink(fromPasted: "Jemputan: https://www.bukitbunga.com/gimano/a/#1.AB.CD Jumpa nanti") == "1.AB.CD")
        #expect(EventPack.extractLink(fromPasted: "  1.AB.CD \n") == "1.AB.CD")
        #expect(Vectors.code { _ = try EventPack.parseFile(Array("MNTX".utf8) + [UInt8](repeating: 1, count: 60)) } == "malformed_file")
        #expect(Vectors.code { _ = try EventPack.parseFile(Array("MNTE".utf8) + [2, 1] + [UInt8](repeating: 1, count: 60)) } == "unsupported_file_format")
        #expect(Vectors.code { _ = try EventPack.parseFile(Array("MNTE".utf8) + [1, 2] + [UInt8](repeating: 1, count: 60)) } == "unsupported_suite")
    }

    @Test("Event JSON: unknown template/kind read as other, unknown lang rejected, text counts code points")
    func eventRules() throws {
        let kenduri = try #require(try Vectors.load("kenduri-one-day")["eventJson"] as? String)
        func parse(_ s: String) -> String? { Vectors.code { _ = try EventDoc.parse(Array(s.utf8)) } }
        #expect(parse(kenduri) == nil)
        let unknown = kenduri.replacingOccurrences(of: #""template":"kenduri""#, with: #""template":"festival","extra":{"x":1}"#)
            .replacingOccurrences(of: #""kind":"venue""#, with: #""kind":"stage""#)
        let doc = try EventDoc.parse(Array(unknown.utf8))
        #expect(doc.template == .other && doc.stops.last?.kind == .other)
        #expect(parse(kenduri.replacingOccurrences(of: #""lang":"ms""#, with: #""lang":"fr""#)) == "malformed_event")
        #expect(parse(kenduri.replacingOccurrences(of: #""rev":1,"parent":null"#, with: #""rev":2,"parent":null"#)) == "invalid_event")
        // 120 code points (emoji are 2 UTF-16 units each) is fine, 121 is not.
        let title = String(repeating: "😀", count: 120)
        #expect(parse(kenduri.replacingOccurrences(of: "Kenduri Aqil & Sofea", with: title)) == nil)
        #expect(parse(kenduri.replacingOccurrences(of: "Kenduri Aqil & Sofea", with: title + "a")) == "invalid_event")
        #expect(parse(kenduri.replacingOccurrences(of: #""lat":2.9871"#, with: #""lat":2.98712345"#)) == "invalid_event")
        #expect(parse(kenduri.replacingOccurrences(of: #""stopId":"YRI9V1quIdg""#, with: #""stopId":"AAAAAAAAAAA""#)) == "invalid_event")
        #expect(parse(kenduri.replacingOccurrences(of: #""end":"2026-11-15T16:00:00+08:00""#, with: #""end":"2026-12-15T16:00:00+08:00""#)) == "invalid_event")
    }

    @Test("Navigation target: the vehicle entrance if present, else the pin")
    func navigationTarget() throws {
        let pack = try EventPack.openLink(try #require(try Vectors.load("kenduri-one-day")["linkFragment"] as? String))
        #expect(pack.event.stops[0].navigationTarget == LatLng(lat: 2.9866, lng: 101.7942))
        #expect(pack.event.stops[2].navigationTarget == LatLng(lat: 2.9884, lng: 101.7919))
        let now = try #require(EventTime.parse("2026-11-15T10:00:00+08:00")).date
        #expect(EventCenter.nextStop(pack.event, now: now)?.id == pack.event.stops[0].id)
        let after = try #require(EventTime.parse("2026-11-15T17:00:00+08:00")).date
        #expect(EventCenter.nextStop(pack.event, now: after) == nil)
    }

    static func deflateRaw(_ input: [UInt8]) -> [UInt8]? {
        let cap = input.count + 4096
        var out = [UInt8](repeating: 0, count: cap)
        let n = compression_encode_buffer(&out, cap, input, input.count, nil, COMPRESSION_ZLIB)
        return n == 0 ? nil : Array(out.prefix(n))
    }
}

@Suite("Event import decision (mirrors event-core import.rs)")
struct EventImportDecisionTests {
    let pub = [UInt8](repeating: 1, count: 32)
    let otherPub = [UInt8](repeating: 2, count: 32)
    let now = EventTime.parse("2026-11-01T00:00:00Z")!.date

    func cand(_ rev: UInt32, _ hash: UInt8, _ parent: UInt8?) -> EventImportCandidate {
        EventImportCandidate(rev: rev, parent: parent.map { [UInt8](repeating: $0, count: 32) },
                             expiresAt: EventTime.parse("2026-11-16T08:00:00Z")!.date, organizerPub: pub,
                             revisionHash: [UInt8](repeating: hash, count: 32))
    }

    func stored(_ revs: [(UInt32, UInt8)]) -> StoredEventRecord {
        StoredEventRecord(organizerPub: pub, revisions: revs.map { StoredEventRevision(rev: $0.0, hash: [UInt8](repeating: $0.1, count: 32)) })
    }

    @Test func newShowsSafetyCode() {
        #expect(EventImport.decide(cand(1, 10, nil), stored: nil, now: now) == .new(safetyCode: EventCodes.safetyCode(pub)))
    }

    @Test func expiredWinsOverEverything() {
        var c = cand(3, 10, 9)
        c.expiresAt = now
        #expect(EventImport.decide(c, stored: nil, now: now) == .expired)
        #expect(EventImport.decide(c, stored: stored([(2, 9)]), now: now) == .expired)
        c.expiresAt = now.addingTimeInterval(1)
        #expect(EventImport.decide(c, stored: nil, now: now) != .expired)
    }

    @Test func differentOrganizerRejected() {
        var s = stored([(1, 10)])
        s.organizerPub = otherPub
        #expect(EventImport.decide(cand(2, 11, 10), stored: s, now: now) == .differentOrganizer)
    }

    @Test func alreadyImported() {
        #expect(EventImport.decide(cand(2, 11, 10), stored: stored([(1, 10), (2, 11)]), now: now) == .alreadyImported)
    }

    @Test func olderKeepsStored() {
        #expect(EventImport.decide(cand(1, 9, nil), stored: stored([(1, 10), (2, 11)]), now: now) == .older(storedRev: 2))
    }

    @Test func sameRevDifferentBytesIsFork() {
        #expect(EventImport.decide(cand(2, 12, 10), stored: stored([(1, 10), (2, 11)]), now: now) == .fork(rev: 2))
    }

    @Test func newerWithMatchingParent() {
        #expect(EventImport.decide(cand(3, 12, 11), stored: stored([(1, 10), (2, 11)]), now: now) == .newer(storedRev: 2, parent: .matches))
    }

    @Test func newerParentMatchesEitherForkSide() {
        #expect(EventImport.decide(cand(3, 13, 12), stored: stored([(2, 11), (2, 12)]), now: now) == .newer(storedRev: 2, parent: .matches))
    }

    @Test func newerWithParentMismatchWarns() {
        #expect(EventImport.decide(cand(3, 12, 99), stored: stored([(2, 11)]), now: now) == .newer(storedRev: 2, parent: .mismatch))
    }

    @Test func newerAfterMissedRevisions() {
        #expect(EventImport.decide(cand(5, 12, 40), stored: stored([(2, 11)]), now: now) == .newer(storedRev: 2, parent: .missedRevisions))
    }

    @Test func pinnedWithoutRevisions() {
        #expect(EventImport.decide(cand(1, 12, nil), stored: stored([]), now: now) == .newer(storedRev: 0, parent: .matches))
    }
}

@Suite("Event storage: on-device only, expiring, never backed up")
struct EventStoreTests {
    private func kenduri() throws -> (OpenedEventPack, Date) {
        let v = try Vectors.load("kenduri-one-day")
        return (try EventPack.openLink(try #require(v["linkFragment"] as? String)), try Vectors.date(v))
    }

    @Test("Import pins the organizer; re-import is already-imported; expiry purges everything")
    func lifecycle() throws {
        let store = try EventStore(directory: nil)
        let (pack, now) = try kenduri()
        let d1 = EventImport.decide(EventImportCandidate(pack), stored: try store.record(eventId: pack.event.id), now: now)
        #expect(d1.code == "new")
        #expect(try store.save(pack, decision: d1, now: now))
        let rec = try #require(try store.record(eventId: pack.event.id))
        #expect(rec.organizerPub == pack.organizerPub && rec.revisions == [StoredEventRevision(rev: 1, hash: pack.revisionHash)])
        let listed = try store.events(now: now)
        #expect(listed.count == 1 && listed[0].doc.title == "Kenduri Aqil & Sofea")
        #expect(EventImport.decide(EventImportCandidate(pack), stored: rec, now: now) == .alreadyImported)
        // A pack signed by someone else for the same id.
        var forged = EventImportCandidate(pack)
        forged.organizerPub = [UInt8](repeating: 9, count: 32)
        forged.revisionHash = [UInt8](repeating: 8, count: 32)
        #expect(EventImport.decide(forged, stored: rec, now: now) == .differentOrganizer)
        // At expiresAt the event, its revisions and the pinned key are gone.
        #expect(try store.events(now: pack.event.expiresAt.date).isEmpty)
        #expect(try store.record(eventId: pack.event.id) == nil)
        #expect(try store.dbQueue.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM event_revisions") } == 0)
    }

    @Test("Fork keeps both sides until the user chooses")
    func fork() throws {
        let store = try EventStore(directory: nil)
        let (pack, now) = try kenduri()
        try store.save(pack, decision: .new(safetyCode: pack.safetyCode), now: now)
        // Same rev, different bytes: the same event with a different title (stored as-is for the test).
        var other = pack
        other.eventBytes = Array(String(decoding: pack.eventBytes, as: UTF8.self)
            .replacingOccurrences(of: "Kenduri Aqil & Sofea", with: "Kenduri Aqil dan Sofea").utf8)
        other.revisionHash = EventCodes.revisionHash(other.eventBytes)
        let d = EventImport.decide(EventImportCandidate(other), stored: try store.record(eventId: pack.event.id), now: now)
        #expect(d == .fork(rev: 1))
        try store.save(other, decision: d, now: now)
        let listed = try store.events(now: now)
        #expect(listed.count == 1 && listed[0].forkAlternatives == 1)
        let alt = try #require(try store.forkAlternatives(eventId: pack.event.id).first)
        #expect(alt.doc.title == "Kenduri Aqil dan Sofea")
        try store.choose(eventId: pack.event.id, hash: alt.hash)
        let after = try store.events(now: now)
        #expect(after.first?.doc.title == "Kenduri Aqil dan Sofea" && after.first?.forkAlternatives == 0)
        try store.delete(eventId: pack.event.id)
        #expect(try store.record(eventId: pack.event.id) == nil)
    }

    @Test("The events directory is excluded from device backups")
    func excludedFromDeviceBackup() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("events-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = try EventStore(directory: dir)
        let (pack, now) = try kenduri()
        try store.save(pack, decision: .new(safetyCode: pack.safetyCode), now: now)
        #expect(try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("events.sqlite").path))
    }

    @Test("A .manotu backup never contains event data")
    func notInAppBackup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("evbk-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let local = try LocalStore(path: root.appendingPathComponent("gimano.sqlite").path, photosDirectory: root.appendingPathComponent("photos"))
        try local.addFavorite(name: "Rumah Mak", icon: .star, lat: 3.1, lng: 101.6, address: nil, placeId: nil)
        let events = try EventStore(directory: root.appendingPathComponent("events"))
        let (pack, now) = try kenduri()
        try events.save(pack, decision: .new(safetyCode: pack.safetyCode), now: now)

        let file = try BackupService(store: local, kdfLimits: .interactive).makeBackup()
        let out = root.appendingPathComponent("unzipped")
        try FileManager.default.unzipItem(at: file, to: out)
        let snap = try DatabaseQueue(path: out.appendingPathComponent("data.sqlite").path)
        let tables = try snap.read { try String.fetchAll($0, sql: "SELECT name FROM sqlite_master WHERE type = 'table'") }
        #expect(!tables.contains { $0.hasPrefix("event") })
        try snap.close()
        // No byte of the event (title, stop label) anywhere in the archive.
        var all = Data()
        for case let f as URL in FileManager.default.enumerator(at: out, includingPropertiesForKeys: nil)! {
            all.append((try? Data(contentsOf: f)) ?? Data())
        }
        #expect(all.range(of: Data("Parkir tetamu".utf8)) == nil)
        #expect(all.range(of: Data("Kenduri Aqil".utf8)) == nil)
    }
}

@MainActor
@Suite("Event deep links")
struct EventDeepLinkTests {
    @Test("manotu://event?stop=…#fragment, gimano://event#…, and /a/ links open the confirm step; others are ignored")
    func deepLinks() throws {
        let v = try Vectors.load("kenduri-one-day")
        let fragment = try #require(v["linkFragment"] as? String)
        let now = try Vectors.date(v)
        for (s, stop) in [("manotu://event?stop=YRI9V1quIdg#\(fragment)", "YRI9V1quIdg"), ("gimano://event#\(fragment)", nil),
                          ("https://www.bukitbunga.com/gimano/a/#\(fragment)", nil)] {
            let center = EventCenter(store: try EventStore(directory: nil))
            center.now = { now }
            #expect(center.handle(url: try #require(URL(string: s))))
            #expect(center.isPresented && center.path == [.confirm])
            #expect(center.pending?.decision.code == "new")
            #expect(center.focusStopId == stop)
            let id = try #require(center.confirmPending())
            #expect(center.path == [.detail(id)] && center.events.count == 1)
            // Opening it again: already imported, nothing changes.
            #expect(center.handle(url: try #require(URL(string: s))))
            #expect(center.pending?.decision == .alreadyImported)
        }
        let center = EventCenter(store: try EventStore(directory: nil))
        #expect(!center.handle(url: URL(string: "manotu://settings")!))
        #expect(!center.handle(url: URL(string: "https://example.com/x#1.a.b")!))
        // A broken invitation shows the contract's message and imports nothing.
        #expect(center.handle(url: URL(string: "manotu://event#1.AAAA.BBBB")!))
        #expect(center.errorMessage != nil && center.pending == nil)
    }
}
