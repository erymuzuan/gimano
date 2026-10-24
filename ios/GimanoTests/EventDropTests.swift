import Foundation
import Testing
@testable import Gimano

// Short links through the encrypted relay (docs/events.md §5.6), against the shared vectors in
// `desktop/crates/event-core/tests/vectors/` (copied into GimanoTests/EventVectors; EventPackTests checks the copies).

private final class DropVectorToken {}

private enum DropVectors {
    static func load(_ name: String) throws -> [String: Any] {
        let url = try #require(Bundle(for: DropVectorToken.self).url(forResource: name, withExtension: "json"), "vector \(name).json missing")
        return try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    static func string(_ v: [String: Any], _ key: String) throws -> String {
        try #require(v[key] as? String, "\(key) missing")
    }

    static func bytes(_ v: [String: Any], _ key: String) throws -> [UInt8] {
        try #require(B64u.decode(try string(v, key)), "\(key) is not base64url")
    }

    static func now(_ v: [String: Any]) throws -> Date {
        try #require(EventTime.parse(try string(v, "now"))).date
    }

    static func code(_ body: () throws -> Void) -> String? {
        do { try body(); return nil } catch let e as EventDropError { return e.code } catch let e as EventPackError { return e.code } catch {
            return "other: \(error)"
        }
    }
}

/// An in-memory relay: records requests, serves blobs, and can be told to fail.
private final class FakeDropRelay: EventDropTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var blobs: [String: [UInt8]] = [:]
    private var tokens: [String: String] = [:]
    private var expiries: [String: Date] = [:]
    private(set) var calls: [String] = []
    var failNext: Error?
    var delay: Duration?
    private var counter = 0

    func record(_ s: String) -> Error? {
        lock.withLock {
            calls.append(s)
            defer { failNext = nil }
            return failNext
        }
    }

    func put(_ id: String, blob: [UInt8], token: String = "t") {
        lock.withLock { blobs[id] = blob; tokens[id] = token }
    }

    func remove(_ id: String) { _ = lock.withLock { blobs.removeValue(forKey: id) } }
    func blob(_ id: String) -> [UInt8]? { lock.withLock { blobs[id] } }
    func expiry(_ id: String) -> Date? { lock.withLock { expiries[id] } }

    func createDrop(blob: [UInt8], expiresAt: Date) async throws -> EventDropCreated {
        if let delay { try await Task.sleep(for: delay) }
        if let e = record("POST") { throw e }
        let id: String = lock.withLock {
            counter += 1
            let id = "Drop\(String(repeating: "x", count: 5))\(counter)"
            blobs[id] = blob
            tokens[id] = "token-\(counter)"
            expiries[id] = expiresAt
            return id
        }
        return EventDropCreated(dropId: id, writeToken: lock.withLock { tokens[id]! }, expiresAt: expiresAt)
    }

    func putDrop(id: String, writeToken: String, blob: [UInt8], expiresAt: Date) async throws {
        if let e = record("PUT \(id)") { throw e }
        try lock.withLock {
            guard blobs[id] != nil else { throw APIError.http(status: 410, problem: nil) }
            guard tokens[id] == writeToken else { throw APIError.http(status: 403, problem: nil) }
            blobs[id] = blob
            expiries[id] = expiresAt
        }
    }

    func getDrop(id: String) async throws -> [UInt8] {
        if let e = record("GET \(id)") { throw e }
        return try lock.withLock {
            guard let b = blobs[id] else { throw APIError.http(status: 404, problem: nil) }
            return b
        }
    }

    func deleteDrop(id: String, writeToken: String) async throws {
        if let e = record("DELETE \(id)") { throw e }
        try lock.withLock {
            guard blobs[id] != nil else { throw APIError.http(status: 404, problem: nil) }
            guard tokens[id] == writeToken else { throw APIError.http(status: 403, problem: nil) }
            blobs[id] = nil
        }
    }
}

@Suite("Short links (docs/events.md §5.6): vectors")
struct EventDropVectorTests {
    @Test("HKDF-SHA256(secret, empty salt, \"manotu-drop-v1\") gives dropKeyB64u (positive and wrong-secret vectors)")
    func hkdf() throws {
        for name in ["kenduri-one-day", "negative-drop-wrong-secret"] {
            let v = try DropVectors.load(name)
            let secret = try DropVectors.bytes(v, "secretB64u")
            #expect(secret.count == EventDrop.secretBytes)
            #expect(B64u.encode(EventDrop.key(secret: secret)) == (try DropVectors.string(v, "dropKeyB64u")), "\(name)")
        }
    }

    @Test("The kenduri blob opens to the signed event; it is nonce ‖ XChaCha20-Poly1305(dropKey, deflated envelope)")
    func openBlob() throws {
        let v = try DropVectors.load("kenduri-one-day")
        let blob = try DropVectors.bytes(v, "dropBlobB64u")
        let secret = try DropVectors.bytes(v, "secretB64u")
        let p = try EventDrop.open(blob: blob, secret: secret)
        #expect(p.eventBytes == Array((try DropVectors.string(v, "eventJson")).utf8))
        #expect(B64u.encode(p.organizerPub) == (try DropVectors.string(v, "organizerPubB64u")))
        #expect(B64u.encode(p.revisionHash) == (try DropVectors.string(v, "revisionHashB64u")))
        #expect(!p.isStopsOnly)
        // Byte for byte from the deflated envelope, the derived key and the fixed nonce.
        let nonce = try DropVectors.bytes(v, "dropNonceB64u")
        let ct = try #require(EventPack.encrypt(key: try DropVectors.bytes(v, "dropKeyB64u"), nonce: nonce,
                                                plaintext: try DropVectors.bytes(v, "deflatedB64u")))
        #expect(nonce + ct == blob)
        #expect(Array(blob.prefix(EventPack.nonceBytes)) == nonce)
    }

    @Test("The three link forms parse to the vector's dropId and secret, also from URLs and pasted messages")
    func linkForms() throws {
        let v = try DropVectors.load("kenduri-one-day")
        let id = try DropVectors.string(v, "dropId")
        let secret = try DropVectors.bytes(v, "secretB64u")
        let fragment = try DropVectors.string(v, "dropFragment")
        let link = try DropVectors.string(v, "dropLink")
        let appLink = try DropVectors.string(v, "dropAppLink")
        let expected = EventDropRef(dropId: id, secret: secret)
        #expect(expected.link() == link)
        #expect(expected.appLink == appLink)
        #expect(expected.fragment == fragment)
        let secretB64 = try DropVectors.string(v, "secretB64u")
        for s in [link, appLink, fragment, "#\(fragment)", " \(link)\n",
                  "gimano://event?drop=\(id)#\(secretB64)",
                  "manotu://event?stop=YRI9V1quIdg&drop=\(id)#\(secretB64)",
                  "https://www.bukitbunga.com/gimano/j/#\(fragment)",
                  "https://manotu.app/j#\(fragment)"] {
            #expect(EventDrop.parse(s) == expected, "\(s)")
            if let url = URL(string: s.trimmingCharacters(in: .whitespacesAndNewlines)), url.scheme != nil {
                #expect(EventDrop.parse(url.absoluteString) == expected, "URL \(s)")
            }
        }
        let message = "Jemputan: Kenduri\nAhad 15 Nov · 11:00–16:00\nBuka pelan, parkir & arah: \(link)\nBelum ada Manotu? https://manotu.app/download/"
        #expect(EventDrop.find(inText: message) == expected)
        #expect(EventDrop.find(inText: "<\(link)>") == expected)
        // A full /a/ link is never a short link.
        let full = try DropVectors.string(v, "link")
        #expect(EventDrop.parse(full) == nil)
        #expect(EventDrop.find(inText: "Buka: \(full)") == nil)
        #expect(EventDrop.parse("https://manotu.app/download/") == nil)
    }

    @Test("negative-drop-link: every link fails before any request (malformed_drop_link)")
    func negativeLinks() throws {
        let v = try DropVectors.load("negative-drop-link")
        #expect(v["expectedError"] as? String == "malformed_drop_link")
        let links = try #require(v["links"] as? [String])
        let reasons = try #require(v["reasons"] as? [String])
        #expect(links.count == reasons.count && !links.isEmpty)
        for (l, why) in zip(links, reasons) {
            #expect(EventDrop.parse(l) == nil, "\(why): \(l)")
            #expect(EventDrop.parse("https://manotu.app/j/#\(l)") == nil || l.contains("#"), "\(why) under /j/")
        }
    }

    @Test("negative-drop-blob-truncated: malformed_drop before any decryption")
    func truncatedBlobs() throws {
        let v = try DropVectors.load("negative-drop-blob-truncated")
        let secret = try DropVectors.bytes(v, "secretB64u")
        let blobs = try #require(v["blobsB64u"] as? [String])
        let lengths = try #require(v["lengths"] as? [Int])
        #expect(blobs.count == lengths.count)
        for (b, n) in zip(blobs, lengths) {
            let bytes = try #require(B64u.decode(b))
            #expect(bytes.count == n)
            #expect(DropVectors.code { _ = try EventDrop.open(blob: bytes, secret: secret) } == v["expectedError"] as? String)
        }
        #expect(DropVectors.code { _ = try EventDrop.parseBlob([UInt8](repeating: 0, count: 40)) } == nil)
        #expect(DropVectors.code { _ = try EventDrop.open(blob: [UInt8](repeating: 0, count: 40), secret: secret) } == "decrypt")
        #expect(DropVectors.code { _ = try EventDrop.parseBlob([UInt8](repeating: 0, count: EventDrop.maxBlobBytes + 1)) } == "drop_too_large")
    }

    @Test("negative-drop-wrong-secret: the kenduri blob with another secret fails with decrypt")
    func wrongSecret() throws {
        let v = try DropVectors.load("negative-drop-wrong-secret")
        let blob = try DropVectors.bytes(v, "dropBlobB64u")
        let secret = try DropVectors.bytes(v, "secretB64u")
        #expect(DropVectors.code { _ = try EventDrop.open(blob: blob, secret: secret) } == v["expectedError"] as? String)
        #expect(EventDrop.parse(try DropVectors.string(v, "dropLink"))?.secret == secret)
    }

    @Test("Writer: a sealed blob opens again; a stops-only variant is refused (not_full_event)")
    func writer() throws {
        let v = try DropVectors.load("kenduri-one-day")
        let organizer = try #require(EventOrganizerKey(seed: try DropVectors.bytes(v, "ed25519SeedB64u")))
        let eventBytes = Array((try DropVectors.string(v, "eventJson")).utf8)
        let secret = EventDrop.newSecret()
        let blob = try EventDrop.seal(eventBytes: eventBytes, organizer: organizer, secret: secret)
        #expect(try EventDrop.open(blob: blob, secret: secret).eventBytes == eventBytes)
        let again = try EventDrop.seal(eventBytes: eventBytes, organizer: organizer, secret: secret)
        #expect(Array(again.prefix(24)) != Array(blob.prefix(24)), "a fresh nonce for every upload")
        let variant = try DropVectors.load("ride-two-day-stops-only")
        let rideKey = try #require(EventOrganizerKey(seed: try DropVectors.bytes(variant, "ed25519SeedB64u")))
        let variantBytes = Array((try DropVectors.string(variant, "eventJson")).utf8)
        #expect(DropVectors.code { _ = try EventDrop.seal(eventBytes: variantBytes, organizer: rideKey, secret: secret) } == "not_full_event")
    }

    @Test("expiresAt = the event's last day + 14 days, in the event's time zone")
    func expiry() throws {
        let kl = try #require(TimeZone(identifier: "Asia/Kuala_Lumpur"))
        func d(_ s: String) throws -> Date { try #require(EventTime.parse(s)).date }
        // One-day event ending at 16:00 on 15 Nov: access through 29 Nov, so the drop ends at 30 Nov 00:00 local.
        #expect(EventDrop.expiresAt(end: try d("2026-11-15T16:00:00+08:00"), timeZone: kl) == (try d("2026-11-30T00:00:00+08:00")))
        // Ending exactly at midnight: that day doesn't count.
        #expect(EventDrop.expiresAt(end: try d("2026-11-16T00:00:00+08:00"), timeZone: kl) == (try d("2026-11-30T00:00:00+08:00")))
        #expect(EventDrop.expiresAt(end: try d("2026-11-16T00:00:01+08:00"), timeZone: kl) == (try d("2026-12-01T00:00:00+08:00")))
        // The local day, not the UTC one: 23:30 local is still 15 Nov.
        #expect(EventDrop.expiresAt(end: try d("2026-11-15T23:30:00+08:00"), timeZone: kl) == (try d("2026-11-30T00:00:00+08:00")))
        let v = try DropVectors.load("kenduri-one-day")
        let doc = try EventDoc.parse(Array((try DropVectors.string(v, "eventJson")).utf8))
        #expect(EventDrop.expiresAt(doc) > doc.end.date.addingTimeInterval(14 * 86_400))
        #expect(EventDrop.expiresAt(doc) <= doc.end.date.addingTimeInterval(15 * 86_400))
        #expect(EventDropHTTP.expiresHeader(try d("2026-11-30T00:00:00+08:00")) == "2026-11-29T16:00:00Z")
    }
}

@MainActor
@Suite("Short links in the app")
struct EventDropCenterTests {
    private func center(_ relay: FakeDropRelay, now: Date) throws -> EventCenter {
        let c = EventCenter(store: try EventStore(directory: nil), secrets: InMemoryEventSecretStore())
        c.now = { now }
        c.dropTransport = { relay }
        return c
    }

    @Test("Guest: a /j/ link fetches, decrypts and asks to import; the drop stays with the event; 410 marks it ended")
    func guestOpensShortLink() async throws {
        let v = try DropVectors.load("kenduri-one-day")
        let relay = FakeDropRelay()
        let id = try DropVectors.string(v, "dropId")
        relay.put(id, blob: try DropVectors.bytes(v, "dropBlobB64u"))
        let c = try center(relay, now: try DropVectors.now(v))
        #expect(c.handle(url: try #require(URL(string: try DropVectors.string(v, "dropLink")))))
        #expect(c.isPresented)
        while c.fetchingDrop || (c.pending == nil && c.errorMessage == nil) { await Task.yield() }
        #expect(c.errorMessage == nil)
        #expect(c.pending?.decision.code == "new")
        #expect(c.pending?.drop?.dropId == id)
        #expect(c.events.isEmpty, "never imported without the user's confirmation")
        let eventId = try #require(c.confirmPending())
        #expect(c.isDropEvent(eventId))
        #expect(relay.calls == ["GET \(id)"])

        // Opening the event again re-fetches (forced): the same revision changes nothing.
        await c.refreshDrop(eventId, force: true)
        #expect(c.pending == nil && c.path == [.detail(eventId)])
        // Without force, at most once a minute.
        await c.refreshDrop(eventId)
        #expect(relay.calls.count == 2)

        relay.remove(id)
        relay.failNext = APIError.http(status: 410, problem: nil)
        await c.refreshDrop(eventId, force: true)
        #expect(c.endedDrops.contains(eventId))
        #expect(c.event(eventId) != nil, "the guest keeps the copy until its access ends")

        // Deleting the event forgets the link.
        c.delete(eventId)
        #expect(!c.isDropEvent(eventId))
    }

    @Test("Guest: manotu://event?drop=…#secret opens like the /j/ link; 404 shows the ended message")
    func appLinkAndGone() async throws {
        let v = try DropVectors.load("kenduri-one-day")
        let relay = FakeDropRelay()
        let c = try center(relay, now: try DropVectors.now(v))
        #expect(c.handle(url: try #require(URL(string: try DropVectors.string(v, "dropAppLink")))))
        while c.fetchingDrop || (c.pending == nil && c.errorMessage == nil) { await Task.yield() }
        #expect(c.errorMessage == EventDropError.gone.userMessage)
        #expect(c.pending == nil && c.events.isEmpty)

        // A wrong secret is the §7 message.
        let wrong = try DropVectors.load("negative-drop-wrong-secret")
        relay.put(try DropVectors.string(wrong, "dropId"), blob: try DropVectors.bytes(wrong, "dropBlobB64u"))
        c.errorMessage = nil
        await c.openDrop(try #require(EventDrop.parse(try DropVectors.string(wrong, "dropLink"))))
        #expect(c.errorMessage == EventPackError.decrypt.userMessage)
        // Pasted text with a short link goes the same way.
        c.errorMessage = nil
        relay.put(try DropVectors.string(v, "dropId"), blob: try DropVectors.bytes(v, "dropBlobB64u"))
        c.importPasted("Buka pelan: \(try DropVectors.string(v, "dropLink")) jumpa nanti")
        while c.fetchingDrop || (c.pending == nil && c.errorMessage == nil) { await Task.yield() }
        #expect(c.pending?.decision.code == "new")
    }

    @Test("Guest: a newer revision behind the same short link is offered through the usual rules")
    func newerRevision() async throws {
        let v = try DropVectors.load("kenduri-one-day")
        let organizer = try #require(EventOrganizerKey(seed: try DropVectors.bytes(v, "ed25519SeedB64u")))
        let bytes1 = Array((try DropVectors.string(v, "eventJson")).utf8)
        let doc1 = try EventDoc.parse(bytes1)
        var doc2 = doc1.nextRevision(previousBytes: bytes1, publishedAt: doc1.publishedAt.date.addingTimeInterval(60))
        doc2.title = doc1.title + " (dikemas kini)"
        let bytes2 = try doc2.bytes()
        let ref = try #require(EventDrop.parse(try DropVectors.string(v, "dropLink")))
        let relay = FakeDropRelay()
        relay.put(ref.dropId, blob: try DropVectors.bytes(v, "dropBlobB64u"))
        let c = try center(relay, now: try DropVectors.now(v))
        await c.openDrop(ref)
        let eventId = try #require(c.confirmPending())
        relay.put(ref.dropId, blob: try EventDrop.seal(eventBytes: bytes2, organizer: organizer, secret: ref.secret))
        await c.refreshDrop(eventId, force: true)
        #expect(c.pending?.decision.code == "newer")
        #expect(c.path.last == .confirm)
        _ = c.confirmPending()
        #expect(c.event(eventId)?.doc.rev == 2)
        #expect(c.event(eventId)?.parentWarning == nil)
    }

    @Test("Organizer: POST once, reuse while unchanged, PUT after a republish, a new link when the old one is gone")
    func organizerUpload() async throws {
        let v = try DropVectors.load("kenduri-one-day")
        let bytes1 = Array((try DropVectors.string(v, "eventJson")).utf8)
        let doc1 = try EventDoc.parse(bytes1)
        let bytes2 = try doc1.nextRevision(previousBytes: bytes1, publishedAt: doc1.publishedAt.date.addingTimeInterval(60)).bytes()
        let secrets = InMemoryEventSecretStore()
        #expect(secrets.save(EventSecrets(seed: try DropVectors.bytes(v, "ed25519SeedB64u"), packKey: EventRandom.bytes(32)), eventId: doc1.id))
        let relay = FakeDropRelay()
        let exp = EventDrop.expiresAt(doc1)

        let r1 = try await EventDropPublisher.upload(eventId: doc1.id, eventBytes: bytes1, expiresAt: exp, secrets: secrets, transport: relay)
        #expect(relay.calls == ["POST"])
        #expect(relay.expiry(r1.dropId) == exp)
        let stored = try #require(secrets.loadDrop(eventId: doc1.id))
        #expect(stored.ref == r1 && !stored.writeToken.isEmpty)
        #expect(try EventDrop.open(blob: try #require(relay.blob(r1.dropId)), secret: r1.secret).eventBytes == bytes1)
        #expect(r1.link().hasPrefix("https://manotu.app/j/#") && r1.link().count < 70)

        let same = try await EventDropPublisher.upload(eventId: doc1.id, eventBytes: bytes1, expiresAt: exp, secrets: secrets, transport: relay)
        #expect(same == r1 && relay.calls.count == 1, "unchanged: no upload")

        let r2 = try await EventDropPublisher.upload(eventId: doc1.id, eventBytes: bytes2, expiresAt: exp, secrets: secrets, transport: relay)
        #expect(r2 == r1, "the same link shows the latest revision")
        #expect(relay.calls.last == "PUT \(r1.dropId)")
        #expect(try EventDrop.open(blob: try #require(relay.blob(r1.dropId)), secret: r1.secret).eventBytes == bytes2)

        relay.remove(r1.dropId)
        let r3 = try await EventDropPublisher.upload(eventId: doc1.id, eventBytes: bytes1, expiresAt: exp, secrets: secrets, transport: relay)
        #expect(r3.dropId != r1.dropId && r3.secret != r1.secret)
        #expect(Array(relay.calls.suffix(2)) == ["PUT \(r1.dropId)", "POST"])

        // A server error is not hidden: the caller falls back to the full link.
        relay.failNext = APIError.http(status: 503, problem: nil)
        await #expect(throws: APIError.self) {
            try await EventDropPublisher.upload(eventId: doc1.id, eventBytes: bytes2, expiresAt: exp, secrets: secrets, transport: relay)
        }
        #expect(secrets.loadDrop(eventId: doc1.id)?.ref == r3)
    }

    @Test("Organizer in the app: short link after publish, fallback on failure/timeout, end link, delete event")
    func organizerCenter() async throws {
        let relay = FakeDropRelay()
        let c = EventCenter(store: try EventStore(directory: nil), secrets: InMemoryEventSecretStore())
        c.dropTransport = { relay }
        var d = HostedEventDraft.new(now: .now)
        d.title = "Kenduri"
        d.stops = [HostedStopDraft(kind: .venue, label: "Dewan", location: LatLng(lat: 3.1, lng: 101.7))]
        c.saveDraft(d)
        #expect(await c.publish(d.id) == nil)

        // No connection: the share screen falls back to the full link.
        relay.failNext = URLError(.notConnectedToInternet)
        #expect(await c.shortLink(d.id) == .failure(.network))
        #expect(c.existingShortLink(d.id) == nil)

        let link = try (await c.shortLink(d.id)).get()
        #expect(EventDrop.parse(link) != nil)
        #expect(c.existingShortLink(d.id) == link)
        #expect(c.preparingShortLink == nil)

        // "Tamatkan pautan": DELETE on the server, the link is gone here too.
        #expect(await c.endShortLink(d.id) == nil)
        #expect(c.existingShortLink(d.id) == nil)
        #expect(relay.calls.last?.hasPrefix("DELETE") == true)

        // Slow server: give up after the timeout; the upload finishes and keeps its link for the next tap.
        relay.delay = .milliseconds(400)
        #expect(await c.shortLink(d.id, timeout: 0.05) == .failure(.network))
        relay.delay = nil
        for _ in 0..<100 where c.existingShortLink(d.id) == nil { try await Task.sleep(for: .milliseconds(20)) }
        let kept = try #require(c.existingShortLink(d.id))
        #expect(try (await c.shortLink(d.id)).get() == kept)

        // Deleting the hosted event deletes its drop (best effort, in the background).
        let dropId = try #require(EventDrop.parse(kept)?.dropId)
        c.deleteHosted(d.id)
        for _ in 0..<100 where relay.blob(dropId) != nil { try await Task.sleep(for: .milliseconds(20)) }
        #expect(relay.blob(dropId) == nil)
        #expect(c.existingShortLink(d.id) == nil)
    }

    @Test("Demo mode (no relay): no short link, the share screen uses the full link")
    func demoModeHasNoShortLink() async throws {
        let c = EventCenter(store: try EventStore(directory: nil), secrets: InMemoryEventSecretStore())
        var d = HostedEventDraft.new(now: .now)
        d.title = "Kenduri"
        d.stops = [HostedStopDraft(kind: .venue, label: "Dewan", location: LatLng(lat: 3.1, lng: 101.7))]
        c.saveDraft(d)
        #expect(await c.publish(d.id) == nil)
        #expect(await c.shortLink(d.id) == .failure(.network))
        #expect(c.invites[d.id]?.link != nil)
    }

    @Test("Deferred link: only on the first launches, until an event is imported or 7 days pass")
    func deferredEligibility() throws {
        let suite = "drop-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        #expect(EventDeferredLink.isEligible(defaults: defaults, now: t0, hasEvents: false))
        #expect(EventDeferredLink.isEligible(defaults: defaults, now: t0.addingTimeInterval(6 * 86_400), hasEvents: false))
        #expect(!EventDeferredLink.isEligible(defaults: defaults, now: t0.addingTimeInterval(7 * 86_400), hasEvents: false))
        #expect(!EventDeferredLink.isEligible(defaults: defaults, now: t0, hasEvents: false), "stays off")

        let d2 = try #require(UserDefaults(suiteName: suite + "b"))
        defer { d2.removePersistentDomain(forName: suite + "b") }
        #expect(EventDeferredLink.isEligible(defaults: d2, now: t0, hasEvents: false))
        EventDeferredLink.markImported(defaults: d2)
        #expect(!EventDeferredLink.isEligible(defaults: d2, now: t0, hasEvents: false))

        let d3 = try #require(UserDefaults(suiteName: suite + "c"))
        defer { d3.removePersistentDomain(forName: suite + "c") }
        #expect(!EventDeferredLink.isEligible(defaults: d3, now: t0, hasEvents: true))
    }
}
