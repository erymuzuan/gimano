import Foundation
import GRDB

// "Laluan saya": routes the user builds for themselves, not tied to an event. Private data (AGENTS.md): kept only
// in the on-device events database, never sent to a server. They leave the phone only when the user shares a GPX
// file or copies the route into one of their events.

extension EventStore {
    func personalRoutes() throws -> [RouteDraft] {
        try dbQueue.read { db in
            try Row.fetchAll(db, sql: "SELECT draft FROM personal_routes ORDER BY updated_at DESC").compactMap { r in
                try? JSONDecoder().decode(RouteDraft.self, from: r["draft"] as Data)
            }
        }
    }

    func savePersonalRoute(_ route: RouteDraft, now: Date = .now) throws {
        let data = try JSONEncoder().encode(route)
        try dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO personal_routes (route_id, draft, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(route_id) DO UPDATE SET draft = excluded.draft, updated_at = excluded.updated_at
                """, arguments: [route.id, data, LocalStore.timestamp(now)])
        }
    }

    func deletePersonalRoute(id: String) throws {
        try dbQueue.write { db in try db.execute(sql: "DELETE FROM personal_routes WHERE route_id = ?", arguments: [id]) }
    }
}

extension EventCenter {
    /// "Laluan saya" from the side menu.
    func openMyRoutes() {
        refresh()
        path = [.myRoutes]
        detent = .large
        isPresented = true
    }

    func savePersonalRoute(_ r: RouteDraft) {
        var r = r
        if r.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            r.title = String(localized: "Route \(personalRoutes.count + 1)")
        }
        try? store.savePersonalRoute(r, now: now())
        reloadPersonalRoutes()
    }

    func deletePersonalRoute(_ id: String) {
        try? store.deletePersonalRoute(id: id)
        reloadPersonalRoutes()
    }

    /// "Guna dalam acara": a copy of a personal route in one of the events this phone organizes (new id, no stops linked).
    func usePersonalRoute(_ r: RouteDraft, inEvent eventId: String) {
        guard var d = hostedEvent(eventId)?.draft else { return }
        var copy = r
        copy.id = EventRandom.itemId()
        for i in copy.points.indices { copy.points[i].stopId = nil }
        d.routes.append(copy)
        saveDraft(d)
        refresh()
        path = [.host(eventId)]
    }

    /// A GPX 1.1 file of a route in a temporary folder for the share sheet (plaintext; removed after a day).
    func gpxFileURL(_ r: RouteDraft, name: String, stops: [HostedStopDraft] = []) -> URL? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("manotu-event-share", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let safe = String(String.UnicodeScalarView(name.unicodeScalars.filter {
            CharacterSet.alphanumerics.contains($0) || $0 == " " || $0 == "-"
        })).trimmingCharacters(in: .whitespaces)
        let url = dir.appendingPathComponent("\(safe.isEmpty ? "laluan" : String(safe.prefix(60))).gpx")
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try Data(GPXWriter.gpx(r, name: name, stops: stops).utf8).write(to: url, options: [.atomic, .completeFileProtection])
            return url
        } catch {
            return nil
        }
    }
}
