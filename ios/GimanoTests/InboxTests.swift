import Foundation
import Testing
@testable import Gimano

@Suite("Inbox")
struct InboxTests {
    private func item(_ id: String, _ t: TimeInterval, read: Bool? = nil) -> InboxItem {
        InboxItem(id: id, kind: .reviewHelpful, title: "x", body: nil, refType: nil, refId: nil,
                  createdAt: Date(timeIntervalSince1970: t), read: read, count: 3)
    }

    @Test("Unread = not read on the server and newer than the local read mark")
    func unread() {
        let items = [item("a", 100), item("b", 200), item("c", 300, read: true)]
        #expect(InboxReadState.unreadCount(items, localReadUpTo: nil) == 2)
        #expect(InboxReadState.unreadCount(items, localReadUpTo: Date(timeIntervalSince1970: 150)) == 1)
        #expect(InboxReadState.unreadCount(items, localReadUpTo: Date(timeIntervalSince1970: 300)) == 0)
    }

    @Test("Contract JSON decodes; unknown kinds don't break the list")
    func decode() throws {
        let json = """
        {"items":[{"id":"1","kind":"report_thanked","title":"24 drivers thanked you","body":null,"refType":null,"refId":null,
                   "createdAt":"2026-10-23T02:00:00Z","read":false,"count":24},
                  {"id":"2","kind":"place_published","title":"Gerai Pak Abu is on the map","body":"3 drivers confirmed it",
                   "refType":"place","refId":"c1","createdAt":"2026-10-22T02:00:00Z","read":true,"count":1},
                  {"id":"3","kind":"something_new","title":"?","body":null,"refType":null,"refId":null,
                   "createdAt":"2026-10-21T02:00:00Z","read":false,"count":1}],
         "next":null,"unread":2}
        """
        let page = try JSON.makeDecoder().decode(InboxPage.self, from: Data(json.utf8))
        #expect(page.items.map(\.kind) == [.reportThanked, .placePublished, .unknown])
        #expect(page.items[0].count == 24 && page.unread == 2)
    }

    @Test("Demo inbox only reports the user's own public contributions")
    func demo() async throws {
        let repo = DemoRepository(latency: .zero)
        let before = try await repo.inbox(cursor: nil)
        #expect(before.items.allSatisfy { $0.kind == .announcement })
        let p = try await repo.createPlace(NewPlace(name: "Kedai Inbox Test", category: .convenience, lat: 3.2, lng: 101.6))
        try await repo.simulateOtherDriversVerify(placeId: p.id, count: 3)
        let after = try await repo.inbox(cursor: nil)
        #expect(after.items.contains { $0.kind == .placePublished && $0.refId == p.id })
        try await repo.markInboxRead(upTo: .now)
        #expect(try await repo.inbox(cursor: nil).unread == 0)
    }

    @Test("A new device identity forgets my review ids, my places and the inbox mark, but keeps other settings")
    func identitySettings() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("p"))
        try store.setSetting("review.p1", "r1")
        try store.setSetting("ownPlaces", "[\"c1\"]")
        try store.setSetting("inbox.readUpTo", "2026-10-23T00:00:00.000Z")
        try store.setSetting("theme", "dark")
        try store.clearIdentitySettings()
        #expect(try store.setting("review.p1") == nil && store.setting("ownPlaces") == nil && store.setting("inbox.readUpTo") == nil)
        #expect(try store.setting("theme") == "dark")
    }
}
