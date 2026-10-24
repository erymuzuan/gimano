import Foundation
import Testing
@testable import Gimano

/// Store readiness (Apple 1.2 / 5.1.1(v)): blocking on the phone, community rules, account deletion API.
@Suite("Community: block list, rules, account")
struct CommunityTests {
    func review(_ id: String, ref: String?, nested: String? = nil) -> Review {
        Review(id: id, placeId: "p", rating: 4, text: "x", photos: nil,
               author: PersonRef(displayName: "Rusa \(id)", level: 1, reviewCount: 1, authorRef: nested),
               helpfulCount: 0, createdAt: .now, editedAt: nil, status: nil, authorRef: ref)
    }

    @Test("Blocked authors' reviews and photos are hidden; unknown authors stay visible")
    func hide() {
        let rs = [review("1", ref: "a1"), review("2", ref: nil, nested: "a2"), review("3", ref: nil)]
        #expect(BlockList.visible(rs, blocked: ["a1"]).map(\.id) == ["2", "3"])
        #expect(BlockList.visible(rs, blocked: ["a2"]).map(\.id) == ["1", "3"])
        let photos = [Photo(id: "x", url: "u", authorRef: "a1"), Photo(id: "y", url: "u", uploadedBy: PersonRef(displayName: "B", authorRef: "b"))]
        #expect(BlockList.visible(photos, blocked: ["b"]).map(\.id) == ["x"])
    }

    @Test("The block list round-trips through the settings table (so it is in backups) and is never empty JSON")
    func storage() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("blk-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("p"))
        try store.setBlockedAuthors([BlockedAuthor(ref: "a1", name: "Rusa 1", blockedAt: Date(timeIntervalSince1970: 1_800_000_000))])
        #expect(store.blockedAuthors().map(\.ref) == ["a1"])
        #expect(LocalStore.backedUpTables.contains("settings"))
        try store.setBlockedAuthors([])
        #expect(try store.setting(BlockList.settingsKey) == nil)
    }

    @Test("authorRef decodes at the top level or inside author / uploadedBy")
    func decode() throws {
        let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601
        let r = try d.decode(Review.self, from: Data(#"{"id":"r","placeId":"p","rating":5,"author":{"displayName":"A","authorRef":"h1"},"helpfulCount":0,"createdAt":"2026-10-24T00:00:00Z"}"#.utf8))
        #expect(r.blockRef == "h1")
        let p = try d.decode(Photo.self, from: Data(#"{"id":"x","url":"/m/x.webp","authorRef":"h2"}"#.utf8))
        #expect(p.blockRef == "h2")
    }

    @Test("Community rules: accepted only for the current terms version")
    func rules() {
        #expect(!CommunityRules.isAccepted(storedVersion: nil))
        #expect(!CommunityRules.isAccepted(storedVersion: "2020-01-01"))
        #expect(CommunityRules.isAccepted(storedVersion: CommunityRules.version))
        #expect(CommunityRules.termsURL.absoluteString == "https://manotu.app/terms/")
    }

    @Test("authorRef is also read from authorHandle / handle / ref (as on Android), and on issues' reportedBy")
    func alternateKeys() throws {
        let d = JSONDecoder()
        for key in ["authorHandle", "handle", "ref"] {
            let p = try d.decode(PersonRef.self, from: Data("{\"displayName\":\"A\",\"\(key)\":\"h9\"}".utf8))
            #expect(p.authorRef == "h9", "\(key)")
        }
        let i = try d.decode(Issue.self, from: Data(#"{"id":"i","kind":"place_closed_temp","state":"suspected","lat":3,"lng":101,"reportedBy":{"displayName":"B","authorRef":"r1"}}"#.utf8))
        #expect(i.reportedBy?.authorRef == "r1")
    }

    @Test("A report with a contact keeps its contactToken on the phone to withdraw the contact")
    func contactToken() throws {
        let d = JSONDecoder()
        #expect(try d.decode(FeedbackCreated.self, from: Data(#"{"id":"f1","contactToken":"t1"}"#.utf8)).contactToken == "t1")
        #expect(try d.decode(CrashCreated.self, from: Data(#"{"id":"c1","contactToken":"t2"}"#.utf8)).contactToken == "t2")
        var item = OutboxItem(request: FeedbackRequest(category: .bug, message: "x", rating: nil, screen: .home,
                                                       contact: ContactDetails(email: "a@b.co", consent: true)))
        #expect(!item.sentWithContact)
        item.serverId = "f1"
        #expect(item.sentWithContact)
    }

    @Test("403 on a contribution says the account was suspended, in plain words")
    func banned() {
        let msg = APIError.http(status: 403, problem: nil).errorDescription ?? ""
        #expect(msg.contains("suspended"))
    }
}
