import Foundation
import GRDB

// "Laluan saya": routes the user builds for themselves, not tied to an event. Private data (AGENTS.md): kept in the
// on-device personal store (`LocalStore.personal_routes`, schema 3) and in the user's own backup, never sent to a
// Manotu server. They leave the phone otherwise only when the user shares a GPX file or copies the route into one of
// their events.

// MARK: - route_json v1 (docs/privacy-and-backup.md, identical on Android)

/// The cross-platform `personal_routes.route_json`:
/// `{"v":1,"points":[{"lat","lng"}],"legs":[{"source","polyline","distanceM"?,"durationS"?}]}`.
/// Leg `source` is routed | matched | sketch | pending; polylines are precision 6 ("" when pending); distance and
/// duration are optional (absent = unknown, never 0). Unknown fields are ignored.
enum PersonalRouteJSON {
    static let version = 1

    private struct Doc: Codable {
        var v: Int
        var points: [Point]
        var legs: [Leg]
    }

    private struct Point: Codable {
        var lat: Double
        var lng: Double
    }

    private struct Leg: Codable {
        var source: String
        var polyline: String?
        var distanceM: Double?
        var durationS: Double?

        func encode(to encoder: any Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(source, forKey: .source)
            try c.encode(polyline ?? "", forKey: .polyline)
            // Whole metres / seconds; absent (never 0) when unknown.
            if let d = distanceM.map(PersonalRouteJSON.whole), d > 0 { try c.encode(d, forKey: .distanceM) }
            if let t = durationS.map(PersonalRouteJSON.whole), t > 0 { try c.encode(t, forKey: .durationS) }
        }
    }

    static func whole(_ v: Double) -> Int { v.isFinite && v > 0 ? Int(min(v.rounded(), Double(Int32.max))) : 0 }

    /// The builder route as `route_json` (writer rules, docs/privacy-and-backup.md). Legs not calculated yet, or that
    /// failed (offline, no route), are `pending` with no geometry; a drawing matched to roads is `matched` (the finger
    /// stroke itself is not kept), or `sketch` when parts of it matched no road.
    static func encode(_ r: RouteDraft) throws -> String {
        let doc = Doc(
            v: version,
            points: r.points.map { Point(lat: RouteGeo.round6($0.lat), lng: RouteGeo.round6($0.lng)) },
            legs: r.legs.map { l in
                let line = Polyline.encode(l.geometry.map(RouteGeo.round6))
                switch l.state {
                case .matched where !l.unmatched.isEmpty, .sketch:
                    // Not a road all the way: a sketch, with no duration.
                    return Leg(source: "sketch", polyline: line, distanceM: l.distanceM, durationS: nil)
                case .routed, .matched:
                    return Leg(source: l.state.rawValue, polyline: line, distanceM: l.distanceM, durationS: l.durationS)
                case .pending, .unresolved:
                    return Leg(source: "pending", polyline: "", distanceM: nil, durationS: nil)
                }
            })
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return String(decoding: try e.encode(doc), as: UTF8.self)
    }

    /// `route_json` → builder route (`nil` for another version or unreadable JSON). Pending legs are calculated again
    /// when the route is opened; a leg count that doesn't fit the points is padded with pending legs or trimmed.
    static func decode(_ json: String, id: String, name: String, mode: String) -> RouteDraft? {
        guard let doc = try? JSONDecoder().decode(Doc.self, from: Data(json.utf8)), doc.v == version else { return nil }
        var r = RouteDraft(id: id, title: name, modeRaw: EventTravelMode(rawValue: mode)?.rawValue ?? EventTravelMode.car.rawValue)
        r.points = doc.points.map { BuilderPoint(LatLng(lat: $0.lat, lng: $0.lng)) }
        let n = max(0, r.points.count - 1)
        r.legs = (0..<n).map { i in
            guard i < doc.legs.count else { return BuilderLeg() }
            let l = doc.legs[i]
            let geometry = Polyline.decode(l.polyline ?? "")
            let state: LegState? = switch l.source {
            case "routed": .routed
            case "matched": .matched
            case "sketch": .sketch
            default: nil
            }
            guard let state, geometry.count >= 2 else { return BuilderLeg() }
            let distance = l.distanceM.flatMap { $0 > 0 ? $0 : nil } ?? RouteGeo.length(geometry)
            // Sketches have no duration on this phone; a drawn sketch's line is its drawing ("Ikut jalan" leaves it as is).
            return BuilderLeg(state: state, geometry: geometry, distanceM: distance,
                              durationS: state == .sketch ? nil : l.durationS.flatMap { $0 > 0 ? $0 : nil },
                              drawn: state == .sketch && geometry.count > 2 ? geometry : nil)
        }
        // Reader rule: "Ikut jalan" opens off only when every leg is a sketch.
        r.followRoads = !(!r.legs.isEmpty && r.legs.allSatisfy { $0.state == .sketch })
        return r
    }
}

// MARK: - LocalStore (backed up with the rest of the user's personal data)

extension LocalStore {
    static let personalRouteNameChars = 80

    func personalRoutes() throws -> [RouteDraft] {
        try dbQueue.read { db in
            try Row.fetchAll(db, sql: "SELECT id, name, mode, route_json FROM personal_routes ORDER BY updated_at DESC").compactMap {
                PersonalRouteJSON.decode($0["route_json"], id: $0["id"], name: $0["name"], mode: $0["mode"])
            }
        }
    }

    func savePersonalRoute(_ r: RouteDraft, now: Date = .now) throws {
        let json = try PersonalRouteJSON.encode(r)
        let ts = Self.timestamp(now)
        try dbQueue.write { db in try Self.upsertPersonalRoute(db, r, json: json, createdAt: ts, updatedAt: ts, onlyIfNewer: false) }
    }

    func deletePersonalRoute(id: String) throws {
        try dbQueue.write { db in try db.execute(sql: "DELETE FROM personal_routes WHERE id = ?", arguments: [id]) }
    }

    /// Insert, or update everything but `created_at` (only when `updatedAt` is newer, with `onlyIfNewer`).
    fileprivate static func upsertPersonalRoute(_ db: Database, _ r: RouteDraft, json: String, createdAt: String, updatedAt: String,
                                                onlyIfNewer: Bool) throws {
        let name = String(r.title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(personalRouteNameChars))
        let mode = EventTravelMode(rawValue: r.modeRaw)?.rawValue ?? EventTravelMode.car.rawValue
        let distance: Int? = r.isComplete ? PersonalRouteJSON.whole(r.distanceM) : nil
        let duration: Int? = r.durationS.map(PersonalRouteJSON.whole)
        try db.execute(sql: """
            INSERT INTO personal_routes (id, name, mode, route_json, distance_m, duration_s, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET name = excluded.name, mode = excluded.mode, route_json = excluded.route_json,
              distance_m = excluded.distance_m, duration_s = excluded.duration_s, updated_at = excluded.updated_at
            \(onlyIfNewer ? "WHERE julianday(excluded.updated_at) > julianday(personal_routes.updated_at)" : "")
            """, arguments: [r.id, name, mode, json, distance.flatMap { $0 > 0 ? $0 : nil }, duration.flatMap { $0 > 0 ? $0 : nil },
                             createdAt, updatedAt])
    }
}

// MARK: - One-time move out of the events database (schema 3)

enum PersonalRouteMove {
    /// Moves "Laluan saya" saved by earlier builds in the events database (never backed up) into `LocalStore`, then
    /// removes them there. Idempotent: a row already moved is only replaced by a newer one, rows that can't be read
    /// stay where they are, and the old table is dropped once empty. Returns how many routes were copied.
    @discardableResult
    static func run(from events: EventStore, to store: LocalStore) throws -> Int {
        let legacy = try events.legacyPersonalRoutes()
        guard !legacy.isEmpty else {
            try events.dropLegacyPersonalRoutesIfEmpty()
            return 0
        }
        try store.dbQueue.write { db in
            for (r, updatedAt) in legacy {
                try LocalStore.upsertPersonalRoute(db, r, json: PersonalRouteJSON.encode(r), createdAt: updatedAt, updatedAt: updatedAt,
                                                   onlyIfNewer: true)
            }
        }
        try events.deleteLegacyPersonalRoutes(ids: legacy.map(\.route.id))
        try events.dropLegacyPersonalRoutesIfEmpty()
        return legacy.count
    }
}

extension EventStore {
    private func hasLegacyPersonalRoutes(_ db: Database) throws -> Bool {
        try db.tableExists("personal_routes")
    }

    /// Rows of the old `personal_routes(route_id, draft, updated_at)` table that still decode.
    func legacyPersonalRoutes() throws -> [(route: RouteDraft, updatedAt: String)] {
        try dbQueue.read { db in
            guard try hasLegacyPersonalRoutes(db) else { return [] }
            return try Row.fetchAll(db, sql: "SELECT draft, updated_at FROM personal_routes").compactMap { r in
                (try? JSONDecoder().decode(RouteDraft.self, from: r["draft"] as Data)).map { (route: $0, updatedAt: r["updated_at"] as String) }
            }
        }
    }

    func deleteLegacyPersonalRoutes(ids: [String]) throws {
        try dbQueue.write { db in
            guard try hasLegacyPersonalRoutes(db) else { return }
            for id in ids { try db.execute(sql: "DELETE FROM personal_routes WHERE route_id = ?", arguments: [id]) }
        }
    }

    /// The events database keeps event data only: the old table goes once nothing is left in it. Its migration
    /// (`v3-personal-routes`) stays registered, so the migrator never runs it again.
    func dropLegacyPersonalRoutesIfEmpty() throws {
        try dbQueue.write { db in
            guard try hasLegacyPersonalRoutes(db),
                  try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM personal_routes") == 0 else { return }
            try db.execute(sql: "DROP TABLE personal_routes")
        }
    }
}

// MARK: - EventCenter

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
        try? personalStore.savePersonalRoute(r, now: now())
        reloadPersonalRoutes()
    }

    func deletePersonalRoute(_ id: String) {
        try? personalStore.deletePersonalRoute(id: id)
        reloadPersonalRoutes()
    }

    /// "Guna dalam acara" (same as Android): a new event (ride / convoy / trip by mode) named after the route, with a
    /// start place "Mula" at the first point and a finish place "Tamat" at the last (none for a loop: it ends at
    /// "Mula"), and a copy of the route linked to them. The personal route stays as it is. Returns the event id.
    @discardableResult
    func useRouteInNewEvent(_ r: RouteDraft) -> String? {
        guard r.points.count >= 2, let first = r.points.first, let last = r.points.last else { return nil }
        let id = newEvent()
        guard var d = hostedEvent(id)?.draft else { return nil }
        let start = HostedStopDraft(kind: .start, label: String(localized: "Start"), location: first.location)
        let loop = first.lat == last.lat && first.lng == last.lng
        let finish = loop ? nil : HostedStopDraft(kind: .finish, label: String(localized: "Finish"), location: last.location)
        var copy = r
        copy.id = EventRandom.itemId()
        copy.title = ""
        let lastIndex = copy.points.count - 1
        for i in copy.points.indices {
            copy.points[i].stopId = i == 0 ? start.id : i == lastIndex ? (finish?.id ?? start.id) : nil
        }
        d.template = switch r.mode {
        case .bicycle: .ride
        case .motorcycle: .convoy
        default: .trip
        }
        let title = r.title.trimmingCharacters(in: .whitespacesAndNewlines)
        d.title = String(String.UnicodeScalarView(title.unicodeScalars.prefix(EventLimits.titleChars)))
        d.stops = [start] + (finish.map { [$0] } ?? [])
        d.routes = [copy]
        saveDraft(d)
        return id
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
