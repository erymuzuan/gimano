import Foundation
import Testing
@testable import Gimano

/// Writer side of event packs (docs/events.md §3–§5) against the shared vectors
/// (`desktop/crates/event-core/tests/vectors/README.md`: what writers must reproduce byte for byte).
private final class WriterVectorToken {}

private enum WV {
    static let positive = ["kenduri-one-day", "ride-two-day"]

    static func load(_ name: String) throws -> [String: Any] {
        let url = try #require(Bundle(for: WriterVectorToken.self).url(forResource: name, withExtension: "json"))
        return try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    static func bytes(_ v: [String: Any], _ key: String) throws -> [UInt8] {
        let s = try #require(v[key] as? String)
        return try #require(B64u.decode(s))
    }

    static func string(_ v: [String: Any], _ key: String) throws -> String { try #require(v[key] as? String) }

    static func date(_ s: String) -> Date { EventTime.parse(s)!.date }
}

@Suite("Event pack writer: vectors")
struct EventWriterVectorTests {
    @Test("Seed → public key and signature; envelope; encrypting the vector's deflated bytes; link and file framing; codes",
          arguments: WV.positive)
    func deterministicLayers(_ name: String) throws {
        let v = try WV.load(name)
        let seed = try WV.bytes(v, "ed25519SeedB64u")
        let key = try WV.bytes(v, "keyB64u")
        let nonce = try WV.bytes(v, "nonceB64u")
        let eventBytes = Array(try WV.string(v, "eventJson").utf8)
        #expect(eventBytes == (try WV.bytes(v, "eventB64u")))

        let org = try #require(EventOrganizerKey(seed: seed))
        #expect(B64u.encode(org.publicKey) == (try WV.string(v, "organizerPubB64u")))
        let sig = try #require(org.sign(eventBytes: eventBytes))
        #expect(B64u.encode(sig) == (try WV.string(v, "signatureB64u")))
        let envelope = EventPack.envelopeBytes(eventBytes: eventBytes, organizerPub: org.publicKey, signature: sig)
        #expect(envelope == Array(try WV.string(v, "envelopeJson").utf8))
        #expect(envelope == (try WV.bytes(v, "envelopeB64u")))

        // The vector's DEFLATE output encrypted with the vector key + nonce gives the vector ciphertext.
        let deflated = try WV.bytes(v, "deflatedB64u")
        let ciphertext = try #require(EventPack.encrypt(key: key, nonce: nonce, plaintext: deflated))
        #expect(ciphertext == (try WV.bytes(v, "ciphertextB64u")))
        let sealed = SealedEventPack(nonce: nonce, ciphertext: ciphertext)
        #expect(EventPack.fragment(key: key, sealed: sealed) == (try WV.string(v, "linkFragment")))
        #expect(try EventPack.link(site: try WV.string(v, "site"), key: key, sealed: sealed) == (try WV.string(v, "link")))
        #expect(try EventPack.link(site: try WV.string(v, "site") + "/", key: key, sealed: sealed) == (try WV.string(v, "link")))
        // Suite 1 is read only: the vector file parses to the same nonce and ciphertext.
        #expect(try EventPack.parseFile(try WV.bytes(v, "fileB64u")) == ParsedEventFile(suite: .rawKey, sealed: sealed))
        #expect(SecretCode.encode(key) == (try WV.string(v, "secretCode")))
        #expect(EventCodes.safetyCode(org.publicKey) == (try WV.string(v, "safetyCode")))
        #expect(B64u.encode(EventCodes.revisionHash(eventBytes)) == (try WV.string(v, "revisionHashB64u")))

        // Our own DEFLATE differs from miniz but round-trips; the full writer path opens with the reader.
        let parts = try EventPack.sealParts(eventBytes: eventBytes, organizer: org, key: key, nonce: nonce)
        #expect(parts.signature == sig && parts.envelopeBytes == envelope)
        #expect(try EventPack.inflateRaw(parts.deflated) == envelope)
        let opened = try EventPack.open(key: key, sealed: parts.sealed)
        #expect(opened.eventBytes == eventBytes && opened.organizerPub == org.publicKey)
    }

    @Test("Event JSON writer: a model read from the vector re-serialises to the exact signed bytes", arguments: WV.positive)
    func jsonMatchesSerde(_ name: String) throws {
        let v = try WV.load(name)
        let json = Array(try WV.string(v, "eventJson").utf8)
        let doc = try EventDoc.parse(json)
        #expect(EventJSON.encode(doc) == json)
        #expect(try doc.bytes() == json)
    }

    @Test("serde_json number and string formatting")
    func formatting() {
        #expect(EventJSON.formatDouble(102) == "102.0")
        #expect(EventJSON.formatDouble(2.98805) == "2.98805")
        #expect(EventJSON.formatDouble(-0.5) == "-0.5")
        #expect(EventJSON.formatDouble(0.000001) == "1e-6")
        var doc = try! EventDoc.parse(Array(try! WV.string(try! WV.load("kenduri-one-day"), "eventJson").utf8))
        doc.title = "A \"quoted\" \\ line\nnext\u{01} é 🙂"
        let s = String(decoding: EventJSON.encode(doc), as: UTF8.self)
        #expect(s.contains(#""title":"A \"quoted\" \\ line\nnext\u0001 é 🙂""#))
        #expect((try? EventDoc.parse(EventJSON.encode(doc)))?.title == doc.title)
    }
}

@Suite("Event organizer: drafts, publishing, revisions")
struct EventHostTests {
    static let now = WV.date("2026-11-01T02:15:00Z")
    static let kl = TimeZone(identifier: "Asia/Kuala_Lumpur")!

    static func draft() -> HostedEventDraft {
        var d = HostedEventDraft.new(now: now, timeZone: kl, preferredLanguage: "ms")
        d.title = "  Kenduri Aqil & Sofea "
        d.start = WV.date("2026-11-15T11:00:00+08:00")
        d.end = WV.date("2026-11-15T16:00:00+08:00")
        var parking = HostedStopDraft(kind: .parking, label: "Parkir tetamu", location: LatLng(lat: 2.98710049, lng: 101.7930001))
        parking.notes = "Masuk ikut pagar belakang sekolah."
        parking.audience = "Semua"
        parking.organizerNote = "RAHSIA-PENGANJUR: kunci pagar 4321"
        parking.entrance = LatLng(lat: 2.9866, lng: 101.7942)
        let hall = HostedStopDraft(kind: .venue, label: "Dewan Serbaguna", location: LatLng(lat: 2.9884, lng: 101.7919))
        d.stops = [parking, hall]
        return d
    }

    @Test("Draft → event JSON: rev 1 has parent null, optional fields omitted, 6 decimals, expiry end + 24 h, no organizer notes")
    func draftToJson() throws {
        var d = Self.draft()
        let (doc0, issues) = d.event(rev: 1, parent: nil, publishedAt: Self.now)
        #expect(issues.isEmpty)
        let doc = try #require(doc0)
        let bytes = try doc.bytes()
        let s = String(decoding: bytes, as: UTF8.self)
        #expect(s.hasPrefix("{\"id\":\"\(d.id)\",\"rev\":1,\"parent\":null,\"publishedAt\":\"2026-11-01T02:15:00Z\",\"template\":\"kenduri\",\"title\":\"Kenduri Aqil & Sofea\",\"lang\":\"ms\",\"tz\":\"Asia/Kuala_Lumpur\",\"start\":\"2026-11-15T11:00:00+08:00\",\"end\":\"2026-11-15T16:00:00+08:00\",\"expiresAt\":\"2026-11-16T08:00:00Z\",\"stops\":["))
        #expect(s.contains("\"lat\":2.987100,") == false && s.contains("\"lat\":2.9871,\"lng\":101.793,"))
        #expect(s.contains("\"entrance\":{\"lat\":2.9866,\"lng\":101.7942}"))
        for absent in ["description", "organizer", "sessions", "routes", "arrive", "dwellMin", "RAHSIA", "4321"] { #expect(!s.contains(absent), "\(absent)") }
        #expect(s.contains("{\"id\":\"\(d.stops[1].id)\",\"kind\":\"venue\",\"label\":\"Dewan Serbaguna\",\"lat\":2.9884,\"lng\":101.7919}"))
        // The reader accepts it.
        let parsed = try EventDoc.parse(bytes)
        #expect(parsed.parent == nil && parsed.organizer == nil && parsed.stops.count == 2)
        // With an alias and a description, both are written.
        d.alias = "Keluarga Aqil"
        d.description = "Majlis"
        let s2 = String(decoding: try d.event(rev: 1, parent: nil, publishedAt: Self.now).0!.bytes(), as: UTF8.self)
        #expect(s2.contains("\"title\":\"Kenduri Aqil & Sofea\",\"description\":\"Majlis\",\"lang\""))
        #expect(s2.contains("\"expiresAt\":\"2026-11-16T08:00:00Z\",\"organizer\":{\"alias\":\"Keluarga Aqil\"},\"stops\""))
    }

    @Test("Validation against the §4 limits before publishing")
    func validation() {
        var d = Self.draft()
        d.title = " "
        d.stops[0].label = String(repeating: "x", count: 81)
        d.stops[1].audience = String(repeating: "y", count: 41)
        d.end = d.start
        let (doc, issues) = d.event(rev: 1, parent: nil, publishedAt: Self.now)
        #expect(doc == nil)
        #expect(issues.contains(.titleMissing) && issues.contains(.endBeforeStart))
        #expect(issues.contains(.stopLabel(d.stops[0].id)) && issues.contains(.stopAudience(d.stops[1].id)))
        var e = Self.draft()
        e.stops = []
        e.end = e.start.addingTimeInterval(15 * 86_400)
        #expect(e.event(rev: 1, parent: nil, publishedAt: Self.now).1.contains(.noStops))
        #expect(e.event(rev: 1, parent: nil, publishedAt: Self.now).1.contains(.tooLong))
        // Access already ended.
        let late = WV.date("2026-11-17T00:00:00Z")
        guard case .failure(.issues(let i)) = EventPublisher.prepare(Self.draft(), previousBytes: nil, now: late) else {
            Testing.Issue.record("expected expired"); return
        }
        #expect(i.contains(.expired))
    }

    @Test("Publish: link opens; same content re-uses rev; an edit bumps rev with parent = SHA-256(previous), same id and key")
    func revisions() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        var d = Self.draft()
        try store.saveDraft(d)
        guard case .success(let v1) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: Self.now) else {
            Testing.Issue.record("publish failed"); return
        }
        #expect(v1.doc.rev == 1 && v1.doc.parent == nil && !v1.reused)
        #expect(secrets.count == 1)
        let link = try #require(v1.link)
        #expect(link.hasPrefix("https://manotu.app/a/#1."))
        let g1 = try EventPack.openLink(link)
        #expect(g1.eventBytes == v1.eventBytes)
        let file = try EventPack.openFile(v1.fileBytes, secretCode: v1.secretCode)
        #expect(file.eventBytes == v1.eventBytes)

        // Nothing changed: same bytes, same rev, a fresh nonce.
        let later = Self.now.addingTimeInterval(3600)
        guard case .success(let again) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: later) else {
            Testing.Issue.record("republish failed"); return
        }
        #expect(again.reused && again.eventBytes == v1.eventBytes && again.link != v1.link)

        // Move the parking, publish: rev 2.
        d.stops[0].location = LatLng(lat: 2.9901, lng: 101.7955)
        d.stops[0].entrance = nil
        try store.saveDraft(d)
        guard case .success(let v2) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: later) else {
            Testing.Issue.record("publish v2 failed"); return
        }
        #expect(v2.doc.rev == 2 && v2.doc.id == v1.doc.id && !v2.reused)
        #expect(v2.doc.parent == B64u.encode(EventCodes.revisionHash(v1.eventBytes)))
        #expect(v2.doc.publishedAt.raw == "2026-11-01T03:15:00Z")
        #expect(v2.changes.map(\.kind) == [.stopMoved])
        let g2 = try EventPack.openLink(try #require(v2.link))
        #expect(g2.organizerPub == g1.organizerPub)
        #expect(v2.secretCode == v1.secretCode)   // one file code per event, like the desktop
        #expect(SecretCodeV2.isWellFormed(v1.secretCode) && v1.secretCode.count == 9)

        // A guest who has rev 1 sees an update whose parent matches.
        let guest = try EventStore(directory: nil)
        try guest.save(g1, decision: .new(safetyCode: g1.safetyCode), now: Self.now)
        let decision = EventImport.decide(EventImportCandidate(g2), stored: try guest.record(eventId: g2.event.id), now: later)
        #expect(decision == .newer(storedRev: 1, parent: .matches))

        // Another event never shares the key.
        var other = HostedEventDraft.new(now: Self.now, timeZone: Self.kl)
        other.title = "Makan malam"
        other.stops = [HostedStopDraft(kind: .meal, label: "Restoran", location: LatLng(lat: 3.1, lng: 101.6))]
        try store.saveDraft(other)
        guard case .success(let o1) = EventPublisher.publish(eventId: other.id, store: store, secrets: secrets, now: Self.now) else {
            Testing.Issue.record("other failed"); return
        }
        let og = try EventPack.openLink(try #require(o1.link))
        #expect(og.organizerPub != g1.organizerPub && o1.secretCode != v1.secretCode && other.id != d.id)
    }

    @Test("An already published event whose key is gone can't be re-signed with a new key")
    func keyMissing() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        let d = Self.draft()
        try store.saveDraft(d)
        _ = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: Self.now)
        secrets.delete(eventId: d.id)
        guard case .failure(let e) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: Self.now) else {
            Testing.Issue.record("expected failure"); return
        }
        #expect(e == .keyMissing)
    }

    @Test("Share messages: link and download URL in the link message; the file message never carries the key or code")
    func shareMessages() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        var d = Self.draft()
        try store.saveDraft(d)
        guard case .success(let inv) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: Self.now) else {
            Testing.Issue.record("publish failed"); return
        }
        let link = try #require(inv.link)
        let ms = EventShareMessage.link(inv.doc, link: link)
        #expect(ms.hasPrefix("Jemputan: Kenduri Aqil & Sofea\n"))
        #expect(ms.hasPrefix("Jemputan: \(inv.doc.title)\n"))
        #expect(ms.contains("\nBuka pelan, parkir & arah: \(link)\n"))
        #expect(ms.hasSuffix("\nBelum ada Manotu? \(AppConfig.downloadURL)"))
        #expect(ms.split(separator: "\n").count == 4)
        #expect(ms.contains("11:00"))
        #expect(!ms.contains(inv.secretCode))
        let file = EventShareMessage.file(inv.doc)
        let key = try #require(secrets.load(eventId: d.id)).packKey
        for secret in [inv.secretCode, B64u.encode(key), String(link.split(separator: "#")[1]), inv.secretCode.replacingOccurrences(of: "-", with: "")] {
            #expect(!file.contains(secret))
        }
        #expect(file.contains(AppConfig.downloadURL) && file.contains("Kod rahsia akan dihantar berasingan"))
        // English follows the event's lang.
        d.lang = .en
        let en = try #require(d.event(rev: 1, parent: nil, publishedAt: Self.now).0)
        #expect(EventShareMessage.link(en, link: link).contains("\nOpen the plan, parking & directions: \(link)\nNo Manotu yet? \(AppConfig.downloadURL)"))
        #expect(EventShareMessage.file(en).contains("No Manotu yet? Download: \(AppConfig.downloadURL)"))
    }

    @Test("Prints a freshly sealed sample link, file and secret code (MANOTU_SAMPLE_DIR also gets the files)")
    func sampleOutput() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        var d = Self.draft()
        d.alias = "Keluarga Aqil (iOS)"
        let start = Calendar(identifier: .gregorian).date(byAdding: .day, value: 7, to: .now)!
        let length = d.end.timeIntervalSince(d.start)
        d.start = start
        d.end = start.addingTimeInterval(length)
        try store.saveDraft(d)
        guard case .success(let inv) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets) else {
            Testing.Issue.record("publish failed"); return
        }
        let link = try #require(inv.link)
        print("MANOTU_SAMPLE_LINK \(link)")
        print("MANOTU_SAMPLE_SECRET_CODE \(inv.secretCode)")
        print("MANOTU_SAMPLE_FILE_B64U \(B64u.encode(inv.fileBytes))")
        if let dir = ProcessInfo.processInfo.environment["MANOTU_SAMPLE_DIR"], !dir.isEmpty {
            let base = URL(fileURLWithPath: dir)
            let text = """
                link: \(link)
                file: \(base.appendingPathComponent("ios-sample.manotu-event").path)
                secretCode: \(inv.secretCode)
                eventId: \(inv.doc.id)  rev: \(inv.doc.rev)  safetyCode: \(EventCodes.safetyCode(try EventPack.openLink(link).organizerPub))
                eventJson: \(String(decoding: inv.eventBytes, as: UTF8.self))

                """
            try Data(text.utf8).write(to: base.appendingPathComponent("ios-sample-link.txt"))
            try Data(inv.fileBytes).write(to: base.appendingPathComponent("ios-sample.manotu-event"))
        }
        #expect(try EventPack.openLink(link).eventBytes == inv.eventBytes)
    }
}

@Suite("Guest update: what changed")
struct EventChangeTests {
    @Test("Moved, renamed, added, removed stops and a new time are listed; a trip to a moved stop is flagged")
    func changes() throws {
        let v = try WV.load("kenduri-one-day")
        let old = try EventDoc.parse(Array(try WV.string(v, "eventJson").utf8))
        var new = old
        new.stops[0].lat = 2.9901                     // parking moved ~330 m
        new.stops[0].entrance = nil
        new.stops[1].label = "Turun penumpang"        // renamed
        new.stops.remove(at: 2)                       // venue removed
        new.stops.append(EventStop(id: "AAAAAAAAAAA", kind: .meal, label: "Khemah makan", lat: 2.98, lng: 101.79))
        new.start = EventTime.parse("2026-11-15T12:00:00+08:00")!
        let c = EventChange.between(old, new)
        #expect(c.map(\.kind) == [.time, .stopMoved, .stopRenamed, .stopAdded, .stopRemoved])
        let moved = try #require(c.first { $0.kind == .stopMoved })
        #expect((moved.movedM ?? 0) > 100 && moved.before?.first == "Parkir tetamu")
        #expect(EventChange.affectsTrip(c, stopId: old.stops[0].id)?.kind == .stopMoved)
        #expect(EventChange.affectsTrip(c, stopId: old.stops[2].id)?.kind == .stopRemoved)
        #expect(EventChange.affectsTrip(c, stopId: old.stops[1].id) == nil)
        #expect(EventChange.between(old, old).isEmpty)
    }
}

@MainActor
@Suite("Organizer in the app")
struct EventHostCenterTests {
    @Test("The host opening their own link sees their event, not an import from someone else")
    func ownLink() async throws {
        let center = EventCenter(store: try EventStore(directory: nil), secrets: InMemoryEventSecretStore())
        center.now = { EventHostTests.now }
        let id = center.newEvent()
        #expect(center.path == [.create(id)] && center.hosted.count == 1)
        var d = EventHostTests.draft()
        d.id = id
        center.saveDraft(d)
        #expect(await center.publish(id) == nil)
        #expect(center.publishing == nil)
        #expect(center.path == [.create(id), .share(id)])
        let link = try #require(center.invites[id]?.link)
        #expect(center.inviteFileURL(id).map { FileManager.default.fileExists(atPath: $0.path) } == true)
        center.path = []
        #expect(center.handle(url: try #require(URL(string: link))))
        let p = try #require(center.pending)
        #expect(p.isOwn && center.path == [.confirm])
        #expect(center.confirmPending() == nil)
        #expect(center.path == [.host(id)] && center.events.isEmpty)
        // Deleting removes the draft and its keys.
        center.deleteHosted(id)
        #expect(center.hosted.isEmpty && center.secrets.load(eventId: id) == nil)
    }

    @Test("A guest opening a newer revision gets the changes; Keep imports nothing")
    func guestUpdate() throws {
        let hostStore = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        var d = EventHostTests.draft()
        try hostStore.saveDraft(d)
        guard case .success(let v1) = EventPublisher.publish(eventId: d.id, store: hostStore, secrets: secrets, now: EventHostTests.now) else {
            Testing.Issue.record("v1"); return
        }
        d.stops[0].location = LatLng(lat: 2.9901, lng: 101.7955)
        d.stops[0].entrance = nil
        try hostStore.saveDraft(d)
        guard case .success(let v2) = EventPublisher.publish(eventId: d.id, store: hostStore, secrets: secrets, now: EventHostTests.now) else {
            Testing.Issue.record("v2"); return
        }
        let guest = EventCenter(store: try EventStore(directory: nil), secrets: InMemoryEventSecretStore())
        guest.now = { EventHostTests.now }
        guest.openLink(try #require(v1.link))
        #expect(guest.confirmPending() == d.id)
        guest.activeTrip = EventActiveTrip(eventId: d.id, stopId: d.stops[0].id, target: LatLng(lat: 2.9866, lng: 101.7942))
        guest.openLink(try #require(v2.link))
        let p = try #require(guest.pending)
        #expect(p.decision == .newer(storedRev: 1, parent: .matches) && !p.isOwn)
        #expect(p.changes.map(\.kind) == [.stopMoved])
        #expect(guest.pendingAffectsTrip(p, trip: guest.activeTrip)?.kind == .stopMoved)
        guest.cancelPending()   // "Kekal"
        #expect(guest.events.first?.doc.rev == 1)
        guest.openLink(try #require(v2.link))
        #expect(guest.confirmPending() == d.id)   // "Guna pelan baharu"
        #expect(guest.events.first?.doc.rev == 2)
    }
}
