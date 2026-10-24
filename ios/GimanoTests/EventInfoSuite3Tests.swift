import Foundation
import Testing
@testable import Gimano

/// docs/events.md §4.1 (info blocks, new templates) and §5.2 suite 3 (open file, the default share), against the shared
/// vectors and the organizer/guest flows.
private final class Suite3VectorToken {}

private enum V3 {
    static func load(_ name: String) throws -> [String: Any] {
        let url = try #require(Bundle(for: Suite3VectorToken.self).url(forResource: name, withExtension: "json"),
                               "vector \(name).json missing from the test bundle")
        return try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    static func bytes(_ v: [String: Any], _ key: String) throws -> [UInt8] {
        let s = try #require(v[key] as? String)
        return try #require(B64u.decode(s), "\(key) is not strict base64url")
    }

    static func code(_ body: () throws -> Void) -> String? {
        do { try body(); return nil } catch let e as EventPackError { return e.code } catch { return "other: \(error)" }
    }

    static func path(_ body: () throws -> Void) -> String? {
        do { try body(); return nil } catch let e as EventPackError {
            if case .invalidEvent(let p, _) = e { return p }
            return e.code
        } catch { return "other" }
    }

    /// The suite-3 file of a link fragment: `"MNTE" ‖ 01 ‖ 03 ‖ key ‖ nonce ‖ ciphertext`, built here from the link.
    static func file(fromFragment f: String) throws -> [UInt8] {
        let (key, sealed) = try EventPack.parseLink(f)
        return try EventPack.openFileBytes(key: key, sealed: sealed)
    }

    static let now = EventTime.parse("2026-11-01T02:15:00Z")!.date

    static func draft() -> HostedEventDraft {
        let kl = TimeZone(identifier: "Asia/Kuala_Lumpur")!
        var d = HostedEventDraft.new(now: now, timeZone: kl, preferredLanguage: "ms")
        d.title = "Kenduri Aqil & Sofea"
        d.stops = [HostedStopDraft(kind: .venue, label: "Dewan Seri Melati", location: LatLng(lat: 2.9866, lng: 101.7942))]
        return d
    }
}

@Suite("Suite 3 open file (§5.2) and info blocks (§4.1)")
struct EventInfoSuite3Tests {
    @Test("Suite 3 from the link fragment opens with no code to the link's event bytes; matches fileV3B64u byte for byte",
          arguments: ["kenduri-one-day", "ride-two-day"])
    func suite3FromLink(_ name: String) throws {
        let v = try V3.load(name)
        let fragment = try #require(v["linkFragment"] as? String)
        let file = try V3.file(fromFragment: fragment)
        #expect(Array(file.prefix(6)) == Array("MNTE".utf8) + [0x01, 0x03])
        #expect(try EventPack.fileNeedsCode(file) == false)
        let opened = try EventPack.openFileWithoutCode(file)
        let link = try EventPack.openLink(fragment)
        #expect(opened.eventBytes == link.eventBytes)
        #expect(opened.eventBytes == Array(try #require(v["eventJson"] as? String).utf8))
        #expect(opened.organizerPub == link.organizerPub && opened.safetyCode == v["safetyCode"] as? String)
        #expect(B64u.encode(opened.revisionHash) == v["revisionHashB64u"] as? String)
        // Any (or no) code opens it too; the code is ignored.
        #expect(try EventPack.openFile(file, secretCode: "").eventBytes == opened.eventBytes)
        #expect(try EventPack.openFile(file, secretCode: "K7M4-QX9P").eventBytes == opened.eventBytes)
        if v["fileV3B64u"] != nil {
            #expect(file == (try V3.bytes(v, "fileV3B64u")))
        }
        // Suites 2 and 1 still need a code; the suite is read from byte 5 before anything else.
        #expect(try EventPack.fileNeedsCode(try V3.bytes(v, "fileV2B64u")))
        #expect(try EventPack.fileNeedsCode(try V3.bytes(v, "fileB64u")))
        #expect(V3.code { _ = try EventPack.openFileWithoutCode(try V3.bytes(v, "fileV2B64u")) } == "invalid_secret_code")
    }

    @Test("kenduri-one-day carries info blocks dress and bring; the writer re-serializes the vector byte for byte")
    func kenduriInfo() throws {
        let v = try V3.load("kenduri-one-day")
        let bytes = Array(try #require(v["eventJson"] as? String).utf8)
        let doc = try EventDoc.parse(bytes)
        #expect(doc.info.map(\.kind) == [.dress, .bring])
        #expect(doc.info.allSatisfy { !$0.text.isEmpty })
        #expect(EventJSON.encode(doc) == bytes)
    }

    @Test("Truncated suite-3 files are malformed_file")
    func truncated() throws {
        let v = try V3.load("negative-file-v3-truncated")
        let files = try #require(v["filesB64u"] as? [String])
        let lengths = try #require(v["lengths"] as? [Int])
        #expect(files.count == lengths.count && !files.isEmpty)
        for (f, n) in zip(files, lengths) {
            let b = try #require(B64u.decode(f))
            #expect(b.count == n)
            #expect(V3.code { _ = try EventPack.parseFile(b) } == "malformed_file", "\(n)")
            #expect(V3.code { _ = try EventPack.fileNeedsCode(b) } == "malformed_file", "\(n)")
            #expect(V3.code { _ = try EventPack.openFileWithoutCode(b) } == "malformed_file", "\(n)")
        }
    }

    @Test("Info negatives are invalid_event at their path, as a link and as a suite-3 file",
          arguments: ["negative-info-text-too-long", "negative-too-many-info"])
    func infoNegatives(_ name: String) throws {
        let v = try V3.load(name)
        #expect(v["expectedError"] as? String == "invalid_event")
        let path = try #require(v["expectedPath"] as? String)
        let fragment = try #require(v["linkFragment"] as? String)
        #expect(V3.code { _ = try EventPack.openLink(fragment) } == "invalid_event")
        #expect(V3.path { _ = try EventPack.openLink(fragment) } == path)
        let file = try V3.bytes(v, "fileV3B64u")
        #expect(file == (try V3.file(fromFragment: fragment)))
        #expect(V3.path { _ = try EventPack.openFileWithoutCode(file) } == path)
        #expect(V3.path { _ = try EventDoc.parse(Array(try #require(v["eventJson"] as? String).utf8)) } == path)
    }

    @Test("Unknown template and info kind read as other; texts in order")
    func unknownKinds() throws {
        let v = try V3.load("unknown-template-and-info-kind")
        let expected = try #require(v["expected"] as? [String: Any])
        let fragment = try #require(v["linkFragment"] as? String)
        for p in [try EventPack.openLink(fragment), try EventPack.openFileWithoutCode(try V3.bytes(v, "fileV3B64u"))] {
            #expect(p.event.template.rawValue == expected["template"] as? String)
            #expect(p.event.info.map(\.kind.rawValue) == expected["infoKinds"] as? [String])
            #expect(p.event.info.map(\.text) == expected["infoTexts"] as? [String])
            #expect(B64u.encode(p.revisionHash) == v["revisionHashB64u"] as? String)
        }
    }

    @Test("Info rules: blank text invalid, 1000 code points ok, 1001 not, 12 blocks ok, 13 not; empty list allowed")
    func infoRules() throws {
        let v = try V3.load("kenduri-one-day")
        var doc = try EventDoc.parse(Array(try #require(v["eventJson"] as? String).utf8))
        func check() -> String? { V3.path { _ = try doc.bytes() } }
        doc.info = [EventInfoBlock(kind: .dress, text: "   ")]
        #expect(check() == "info[0].text")
        doc.info = [EventInfoBlock(kind: .other, text: String(repeating: "é", count: 1000))]
        #expect(check() == nil)
        doc.info = [EventInfoBlock(kind: .other, text: String(repeating: "😀", count: 1001))]
        #expect(check() == "info[0].text")
        doc.info = Array(repeating: EventInfoBlock(kind: .bring, text: "Payung"), count: 12)
        #expect(check() == nil)
        doc.info.append(EventInfoBlock(kind: .contact, text: "x"))
        #expect(check() == "info")
        doc.info = []
        let bytes = try doc.bytes()
        #expect(!String(decoding: bytes, as: UTF8.self).contains("\"info\""))
        // info sits after sessions and before routes, with kind then text.
        doc.info = [EventInfoBlock(kind: .safety, text: "Topi keledar wajib.\nLampu selepas 7 malam.")]
        let s = String(decoding: try doc.bytes(), as: UTF8.self)
        #expect(s.contains(#""info":[{"kind":"safety","text":"Topi keledar wajib.\nLampu selepas 7 malam."}]"#))
        if let i = s.range(of: "\"info\""), let st = s.range(of: "\"stops\"") { #expect(st.lowerBound < i.lowerBound) }
        #expect(try EventDoc.parse(try doc.bytes()).info == doc.info)
    }

    @Test("New templates round-trip; an unknown template reads as other")
    func templates() throws {
        let v = try V3.load("kenduri-one-day")
        var doc = try EventDoc.parse(Array(try #require(v["eventJson"] as? String).utf8))
        for t in [EventTemplate.meeting, .seminar, .picnic, .hike] {
            doc.template = t
            #expect(try EventDoc.parse(try doc.bytes()).template == t)
        }
        #expect(EventTemplate.journeys.allSatisfy(\.isJourney) && !EventTemplate.oneVenue.contains(where: \.isJourney))
        #expect(Set(EventTemplate.journeys + EventTemplate.oneVenue) == Set(EventTemplate.allCases))
    }

    @Test("Draft → event: info and agenda written, empty ones left out, organizer notes never in the bytes")
    func draftInfoAndAgenda() throws {
        var d = V3.draft()
        d.template = .seminar
        d.organizerNote = "SECRET-ORGANIZER-NOTE"
        d.stops[0].organizerNote = "SECRET-STOP-NOTE"
        d.info = [HostedInfoDraft(kind: .dress, text: "  Batik  "), HostedInfoDraft(kind: .bring, text: "  ")]
        d.sessions = [HostedSessionDraft(title: "Jamuan", start: d.start.addingTimeInterval(3600)),
                      HostedSessionDraft(title: "Ketibaan", start: d.start), HostedSessionDraft(title: " ", start: d.start)]
        let (doc, issues) = d.event(rev: 1, parent: nil, publishedAt: V3.now)
        #expect(issues.isEmpty)
        let e = try #require(doc)
        #expect(e.template == .seminar)
        #expect(e.info == [EventInfoBlock(kind: .dress, text: "Batik")])
        #expect(e.sessions.map(\.title) == ["Ketibaan", "Jamuan"])
        #expect(e.sessions[0].end == e.sessions[1].start && e.sessions[1].end == e.end)
        let s = String(decoding: try e.bytes(), as: UTF8.self)
        #expect(!s.contains("SECRET"))

        d.info = [HostedInfoDraft(kind: .other, text: String(repeating: "a", count: 1001))]
        #expect(d.event(rev: 1, parent: nil, publishedAt: V3.now).1.contains { if case .infoTooLong = $0 { true } else { false } })
        d.info = Array(repeating: HostedInfoDraft(kind: .other, text: "a"), count: 13)
        #expect(d.event(rev: 1, parent: nil, publishedAt: V3.now).1.contains(.tooManyInfoBlocks))
        #expect(EventDraftIssue.tooManyInfoBlocks.wizardStep == .info && EventDraftIssue.noStops.wizardStep == .place)
    }

    @Test("Older drafts (no wizard fields) still decode")
    func oldDraftDecodes() throws {
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(V3.draft())) as! [String: Any]
        for k in ["sessionDrafts", "infoDrafts", "organizerNote", "sendAsCodeFile"] { json[k] = nil }
        let d = try JSONDecoder().decode(HostedEventDraft.self, from: JSONSerialization.data(withJSONObject: json))
        #expect(d.info.isEmpty && d.sessions.isEmpty && !d.sensitive && d.organizerNote == nil)
    }

    @Test("Publish: the default file is suite 3 with the link's exact key, nonce and ciphertext; no Argon2 unless asked")
    func publishOpenFile() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        let d = V3.draft()
        try store.saveDraft(d)
        guard case .success(let inv) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: V3.now) else {
            Testing.Issue.record("publish"); return
        }
        let link = try #require(inv.link)
        #expect(inv.codeFileBytes == nil)
        #expect(inv.fileBytes == (try V3.file(fromFragment: link)))
        #expect(try EventPack.openFileWithoutCode(inv.fileBytes).eventBytes == inv.eventBytes)
        let s = try #require(secrets.load(eventId: d.id))
        #expect(Array(inv.fileBytes[6..<38]) == s.packKey)
        // "Acara sensitif" afterwards: a suite-2 file for the same bytes and the stable code.
        guard case .success(let coded) = EventPublisher.codeFile(eventId: d.id, eventBytes: inv.eventBytes, secrets: secrets) else {
            Testing.Issue.record("codeFile"); return
        }
        #expect(try EventPack.fileNeedsCode(coded))
        #expect(try EventPack.openFile(coded, secretCode: inv.secretCode).eventBytes == inv.eventBytes)
        // Asked for at publish time.
        guard case .success(let both) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, codeFile: true, now: V3.now) else {
            Testing.Issue.record("publish codeFile"); return
        }
        #expect(try EventPack.openFile(try #require(both.codeFileBytes), secretCode: both.secretCode).eventBytes == both.eventBytes)
    }

    @Test("Share text (§5.2.1): BM / EN, title, date line, the download URL, no key, no link, no code")
    func shareText() throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        var d = V3.draft()
        try store.saveDraft(d)
        guard case .success(let inv) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: V3.now) else {
            Testing.Issue.record("publish"); return
        }
        let ms = EventShareMessage.openFile(inv.doc)
        let lines = ms.split(separator: "\n").map(String.init)
        #expect(lines.count == 4)
        #expect(lines[0] == "Jemputan: Kenduri Aqil & Sofea")
        #expect(lines[2] == "Ketik fail untuk buka di Manotu.")
        #expect(lines[3] == "Belum ada Manotu? \(AppConfig.downloadURL)")
        #expect(lines[1].hasSuffix(" · 11:00–16:00") && lines[1].hasPrefix("Isnin 2 Nov"), "\(lines[1])")
        let link = try #require(inv.link)
        for secret in [inv.secretCode, String(link.split(separator: "#")[1]), "manotu.app/a/"] { #expect(!ms.contains(secret)) }
        d.lang = .en
        try store.saveDraft(d)
        guard case .success(let en) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: V3.now.addingTimeInterval(60)) else {
            Testing.Issue.record("publish en"); return
        }
        #expect(EventShareMessage.openFile(en.doc).hasPrefix("Invitation: Kenduri Aqil & Sofea\n"))
        #expect(EventShareMessage.openFile(en.doc).hasSuffix("No Manotu yet? \(AppConfig.downloadURL)"))
    }

    @Test("Share file name: sanitized title, .manotu-event extension")
    func shareFileName() {
        #expect(EventCenter.shareFileName("Kenduri Aqil & Sofea") == "Kenduri Aqil & Sofea.manotu-event")
        #expect(EventCenter.shareFileName("../../etc/passwd") == "etcpasswd.manotu-event")
        #expect(EventCenter.shareFileName("  ") == "acara.manotu-event")
        #expect(EventCenter.shareFileName(String(repeating: "x", count: 90)).count == 60 + ".manotu-event".count)
    }

    @Test("Guest: a suite-3 file opens straight to the confirm step with no code; a suite-2 file asks for its code")
    @MainActor
    func guestOpensSuite3() async throws {
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        let d = V3.draft()
        try store.saveDraft(d)
        guard case .success(let inv) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, codeFile: true, now: V3.now) else {
            Testing.Issue.record("publish"); return
        }
        let guest = EventCenter(store: try EventStore(directory: nil), secrets: InMemoryEventSecretStore())
        guest.now = { V3.now }
        guest.receiveFile(inv.fileBytes)
        #expect(guest.pendingFile == nil && guest.errorMessage == nil)
        #expect(!guest.path.contains(.secretCode))
        #expect(guest.pending?.pack.eventBytes == inv.eventBytes)
        #expect(guest.pending?.decision.code == "new")
        guest.cancelPending()

        guest.receiveFile(try #require(inv.codeFileBytes))
        #expect(guest.pendingFile != nil && guest.path.last == .secretCode && guest.pending == nil)
        #expect(await guest.openPendingFile(secretCode: inv.secretCode) == nil)
        #expect(guest.pending?.pack.eventBytes == inv.eventBytes)

        // A file URL from another app (e.g. WhatsApp → Files): the "MNTE" magic is enough, whatever the extension.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("Jemputan.bin")
        try Data(inv.fileBytes).write(to: url)
        guest.cancelPending()
        #expect(guest.handle(url: url))
        #expect(guest.pending?.pack.eventBytes == inv.eventBytes)
        let other = dir.appendingPathComponent("notes.txt")
        try Data("hello".utf8).write(to: other)
        #expect(!guest.handle(url: other))
    }

    @Test("Template defaults: time presets apply to the event day; journeys pick their mode")
    func templateDefaults() {
        var d = V3.draft()
        let p = EventTemplate.dinner.timePresets[0]
        p.apply(to: &d, keepEndDay: false)
        #expect(p.matches(d))
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = d.timeZone
        #expect(cal.component(.hour, from: d.start) == 19 && cal.component(.minute, from: d.end) == 30)
        #expect(EventTemplate.ride.defaultMode == .bicycle && EventTemplate.hike.defaultMode == .walk)
        #expect(EventTemplate.trip.timePresets == [.allDayPreset])
        #expect(EventTemplate.kenduri.suggestsAgenda && EventTemplate.kenduri.suggestedInfo == [.dress])
    }

    @Test("Route timeline: Mula first, stops before Tamat, arrivals from calculated legs")
    func routeTimeline() {
        var d = V3.draft()
        let start = HostedStopDraft(kind: .start, label: "Putrajaya", location: LatLng(lat: 2.94, lng: 101.69))
        let finish = HostedStopDraft(kind: .finish, label: "PD", location: LatLng(lat: 2.52, lng: 101.80))
        let water = HostedStopDraft(kind: .water, label: "Air", location: LatLng(lat: 2.80, lng: 101.75))
        d.stops = [start, finish, water]
        var r = RouteDraft()
        let finishes: Set<String> = [finish.id]
        r.addStopPoint(finish, finishIds: finishes)
        r.addStopPoint(start, finishIds: finishes)
        r.addStopPoint(water, finishIds: finishes)
        #expect(d.timeline(r).map(\.id) == [start.id, water.id, finish.id])
        #expect(r.legs.count == 2)
        r.legs[0].durationS = 600
        r.legs[1].durationS = 1200
        let t = d.arrivals(r, dayStart: d.start)
        #expect(t[start.id] == d.start && t[water.id] == d.start.addingTimeInterval(600) && t[finish.id] == d.start.addingTimeInterval(1800))
    }
}
