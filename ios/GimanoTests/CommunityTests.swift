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

    // MARK: shared settings keys in backups (docs/privacy-and-backup.md "Shared settings keys")

    @Test("blocked_authors written by Android decodes; entries without a ref are skipped, not the whole list")
    func tolerantDecode() {
        let android = #"[{"ref":"aR1","name":"Rusa 1","blockedAt":"2026-10-24T03:04:05Z"},{"name":"no ref"},{"ref":"","name":"empty"},{"ref":"aR2"},{"ref":"aR1","name":"dup"}]"#
        let list = BlockList.decode(android)
        #expect(list.map(\.ref) == ["aR1", "aR2"])
        #expect(list[0].name == "Rusa 1")
        #expect(BlockList.dateString(list[0].blockedAt) == "2026-10-24T03:04:05Z")
        #expect(BlockList.decode("not json").isEmpty)
    }

    @Test("blocked_authors is written as {ref, name, blockedAt} with ISO-8601 UTC and no fractional seconds")
    func encodeFormat() throws {
        let raw = try #require(BlockList.encode([BlockedAuthor(ref: "x", name: "Kancil", blockedAt: Date(timeIntervalSince1970: 1_793_000_000.789))]))
        #expect(raw.range(of: #""blockedAt":"2026-10-2\dT\d\d:\d\d:\d\dZ""#, options: .regularExpression) != nil, "\(raw)")
        #expect(!raw.contains("."), "no fractional seconds: \(raw)")
        #expect(raw.contains(#""ref":"x""#) && raw.contains(#""name":"Kancil""#))
    }

    @Test("Merge: blocked_authors is the union by ref, this phone's entry wins")
    func mergeBlocked() {
        let mine = #"[{"ref":"a","name":"Mine A","blockedAt":"2026-10-01T00:00:00Z"}]"#
        let theirs = #"[{"ref":"a","name":"Theirs A","blockedAt":"2026-09-01T00:00:00Z"},{"ref":"b","name":"B","blockedAt":"2026-09-02T00:00:00Z"}]"#
        let m = BlockList.decode(BlockList.merged(local: mine, backup: theirs))
        #expect(m.map(\.ref) == ["a", "b"])
        #expect(m.first?.name == "Mine A")
        #expect(BlockList.decode(BlockList.merged(local: nil, backup: theirs)).count == 2)
    }

    @Test("Merge: community rules keep whichever side accepted the later version, with its date")
    func mergeRules() {
        let old = (version: Optional("2026-01-01"), acceptedAt: Optional("2026-01-02T00:00:00.000Z"))
        let new = (version: Optional("2026-10-24"), acceptedAt: Optional("2026-10-24T05:00:00.000Z"))
        #expect(CommunityRules.merged(local: old, backup: new) == new)
        #expect(CommunityRules.merged(local: new, backup: old) == new)
        #expect(CommunityRules.merged(local: (nil, nil), backup: old) == old)
        #expect(CommunityRules.merged(local: old, backup: (nil, nil)) == old)
    }

    func store() throws -> (LocalStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("mrg-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("p")), dir)
    }

    @Test("Restore: Merge applies the shared-key rules; Replace takes the backup as-is")
    func restoreMergeAndReplace() throws {
        let (src, d1) = try store(); defer { try? FileManager.default.removeItem(at: d1) }
        // The backup (as Android would write it): two blocks, newer rules, another setting.
        try src.setSetting(BlockList.settingsKey, #"[{"ref":"a","name":"Theirs A","blockedAt":"2026-09-01T00:00:00Z"},{"ref":"b","name":"B","blockedAt":"2026-09-02T00:00:00Z"},{"name":"broken"}]"#)
        try src.setSetting(CommunityRules.versionKey, "2026-10-24")
        try src.setSetting(CommunityRules.acceptedAtKey, "2026-10-24T05:00:00Z")
        try src.setSetting("theme", "dark")
        let file = try BackupService(store: src, kdfLimits: .interactive).makeBackup()

        let (dst, d2) = try store(); defer { try? FileManager.default.removeItem(at: d2) }
        try dst.setSetting(BlockList.settingsKey, #"[{"ref":"a","name":"Mine A","blockedAt":"2026-10-01T00:00:00Z"},{"ref":"c","name":"C","blockedAt":"2026-10-02T00:00:00Z"}]"#)
        try dst.setSetting(CommunityRules.versionKey, "2026-01-01")
        try dst.setSetting(CommunityRules.acceptedAtKey, "2026-01-02T00:00:00Z")
        try dst.setSetting("theme", "light")
        _ = try BackupService(store: dst).restore(file, mode: .merge)
        let merged = dst.blockedAuthors()
        #expect(Set(merged.map(\.ref)) == ["a", "b", "c"])
        #expect(merged.first { $0.ref == "a" }?.name == "Mine A")
        #expect(try dst.setting(CommunityRules.versionKey) == "2026-10-24")
        #expect(try dst.setting(CommunityRules.acceptedAtKey) == "2026-10-24T05:00:00Z")
        #expect(try dst.setting("theme") == "light", "other keys keep this phone's value")

        let (dst2, d3) = try store(); defer { try? FileManager.default.removeItem(at: d3) }
        try dst2.setSetting(BlockList.settingsKey, #"[{"ref":"z","name":"Z","blockedAt":"2026-10-01T00:00:00Z"}]"#)
        _ = try BackupService(store: dst2).restore(file, mode: .replace)
        #expect(Set(dst2.blockedAuthors().map(\.ref)) == ["a", "b"], "Replace takes the backup as-is")
        #expect(try dst2.setting("theme") == "dark")
    }
}
