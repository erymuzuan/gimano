import Foundation
import GRDB
import Testing
import ZIPFoundation
@testable import Gimano

// "Laluan saya" in the backed-up personal store (docs/privacy-and-backup.md, schema 3): the cross-platform
// `route_json` v1, the 2 → 3 migration, backup/restore (Replace, Merge, schema-2 files), the one-time move out of the
// events database, "Guna dalam acara", and the "Lukis" stroke with a second finger.

private final class PRFixtureToken {}

private enum PR {
    static let a = LatLng(lat: 3.1, lng: 101.6)
    static let b = LatLng(lat: 3.11, lng: 101.61)
    static let c = LatLng(lat: 3.12, lng: 101.6)
    static let d = LatLng(lat: 3.13, lng: 101.62)
    static let e = LatLng(lat: 3.14, lng: 101.63)

    static func store() throws -> LocalStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pr-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("photos"))
    }

    static func object(_ json: String) throws -> [String: Any] {
        try #require(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
    }

    /// Five points, one leg of every kind: routed, matched (from a drawing), drawn sketch, pending (a drawing waiting
    /// offline) and unresolved.
    static func mixed(id: String = "r-mixed", title: String = "Kayuh pagi") -> RouteDraft {
        var r = RouteDraft(id: id, title: title, modeRaw: "bicycle")
        r.points = [a, b, c, d, e].map { BuilderPoint($0) }
        let mid = Geo.interpolate(a, b, 0.5)
        r.legs = [
            BuilderLeg(state: .routed, geometry: [a, RouteGeo.round6(mid), b], distanceM: 1650.4, durationS: 240),
            BuilderLeg(state: .matched, geometry: [b, LatLng(lat: 3.115, lng: 101.605), c], distanceM: 2100, durationS: 300,
                       drawn: [b, LatLng(lat: 3.116, lng: 101.604), c]),
            BuilderLeg(state: .sketch, geometry: [c, LatLng(lat: 3.125, lng: 101.611), d], distanceM: 1180, durationS: nil,
                       drawn: [c, LatLng(lat: 3.125, lng: 101.611), d]),
            BuilderLeg(drawn: [d, LatLng(lat: 3.135, lng: 101.625), e]),
        ]
        r.points.append(BuilderPoint(LatLng(lat: 3.15, lng: 101.64)))
        r.legs.append(BuilderLeg(state: .unresolved, problem: .offline))
        return r
    }

    /// A backup file with the given snapshot rows, built the way Android does (shared DDL up to `schema`).
    static func backupFile(schema: Int, platform: String = "android", rows: (Database) throws -> Void) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pr-bk-\(UUID().uuidString)")
        let payload = dir.appendingPathComponent("payload")
        try FileManager.default.createDirectory(at: payload, withIntermediateDirectories: true)
        let db = try DatabaseQueue(path: payload.appendingPathComponent("data.sqlite").path)
        try db.write { db in
            try db.execute(sql: LocalStore.schemaV1)
            if schema >= 2 { try db.execute(sql: LocalStore.schemaV2) }
            if schema >= 3 { try db.execute(sql: LocalStore.schemaV3) }
            try db.execute(sql: "DROP TABLE outbox")
            try rows(db)
            try db.execute(sql: "PRAGMA user_version = \(schema)")
        }
        try db.close()
        let manifest = #"{"format":1,"schemaVersion":\#(schema),"appVersion":"0.3.0","platform":"\#(platform)","createdAt":"2026-10-24T01:00:00.000Z","counts":{},"encryption":"none"}"#
        try Data(manifest.utf8).write(to: payload.appendingPathComponent("manifest.json"))
        let file = dir.appendingPathComponent("backup.manotu")
        try FileManager.default.zipItem(at: payload, to: file, shouldKeepParent: false)
        return file
    }

    static func insertRoute(_ db: Database, id: String, name: String, updatedAt: String) throws {
        var r = RouteDraft(id: id, title: name)
        r.addPoint(a); r.addPoint(b)
        try db.execute(sql: """
            INSERT INTO personal_routes (id, name, mode, route_json, distance_m, duration_s, created_at, updated_at)
            VALUES (?, ?, 'car', ?, NULL, NULL, ?, ?)
            """, arguments: [id, name, try PersonalRouteJSON.encode(r), updatedAt, updatedAt])
    }

    static func names(_ s: LocalStore) throws -> [String: String] {
        try s.dbQueue.read { db in
            Dictionary(uniqueKeysWithValues: try Row.fetchAll(db, sql: "SELECT id, name FROM personal_routes").map { ($0["id"] as String, $0["name"] as String) })
        }
    }
}

@Suite("Personal routes: route_json v1")
struct PersonalRouteJSONTests {
    @Test("Encodes every leg kind per the contract: pending has no geometry, matched keeps no stroke, no 0 values")
    func encode() throws {
        let json = try PersonalRouteJSON.encode(PR.mixed())
        let o = try PR.object(json)
        #expect(o["v"] as? Int == 1)
        #expect(Set(o.keys) == ["v", "points", "legs"])
        let points = try #require(o["points"] as? [[String: Any]])
        #expect(points.count == 6 && points[0]["lat"] as? Double == 3.1 && points[0]["lng"] as? Double == 101.6)
        #expect(points.allSatisfy { Set($0.keys) == ["lat", "lng"] })
        let legs = try #require(o["legs"] as? [[String: Any]])
        #expect(legs.count == points.count - 1)
        #expect(legs.map { $0["source"] as? String } == ["routed", "matched", "sketch", "pending", "pending"])
        // Routed: precision-6 polyline, whole metres/seconds.
        #expect(Polyline.decode(legs[0]["polyline"] as! String, precision: 6).first == PR.a)
        #expect(legs[0]["distanceM"] as? Int == 1650 && legs[0]["durationS"] as? Int == 240)
        // Matched: the road geometry only (the finger stroke is not kept).
        #expect(Set(legs[1].keys) == ["source", "polyline", "distanceM", "durationS"])
        #expect(Polyline.decode(legs[1]["polyline"] as! String).count == 3)
        // Sketch: no duration (absent, never 0).
        #expect(legs[2]["durationS"] == nil && legs[2]["distanceM"] as? Int == 1180)
        // Pending and unresolved: "" and no numbers.
        for l in legs[3...] { #expect(l["polyline"] as? String == "" && l["distanceM"] == nil && l["durationS"] == nil) }
    }

    @Test("Round trip: same JSON again; pending legs are calculated again on opening")
    func roundTrip() throws {
        let r = PR.mixed()
        let json = try PersonalRouteJSON.encode(r)
        let back = try #require(PersonalRouteJSON.decode(json, id: r.id, name: r.title, mode: r.modeRaw))
        #expect(back.id == r.id && back.title == "Kayuh pagi" && back.mode == .bicycle)
        #expect(back.points.map(\.location) == r.points.map(\.location))
        #expect(back.legs.map(\.state) == [.routed, .matched, .sketch, .pending, .pending])
        #expect(back.legs[0].geometry == r.legs[0].geometry && back.legs[0].distanceM == 1650 && back.legs[0].durationS == 240)
        #expect(back.legs[1].geometry == r.legs[1].geometry && back.legs[1].drawn == nil)
        #expect(back.legs[2].drawn == r.legs[2].geometry && back.legs[2].durationS == nil)
        #expect(back.pendingLegs.map(\.index) == [3, 4])
        #expect(try PersonalRouteJSON.encode(back) == json)
        // Calculating the pending legs completes the route.
        var done = back
        for l in done.pendingLegs {
            done.apply(.routed(geometry: [l.from, l.to], distanceM: 500, durationS: 60), generation: l.generation)
        }
        #expect(done.isComplete)
    }

    @Test("Unknown fields are ignored; another version or broken JSON is not read; leg count is repaired")
    func tolerant() throws {
        let line = Polyline.encode([PR.a, PR.b])
        let json = #"{"v":1,"x":{"y":[1]},"points":[{"lat":3.1,"lng":101.6,"alt":12},{"lat":3.11,"lng":101.61},{"lat":3.12,"lng":101.6}],"legs":[{"source":"routed","polyline":"\#(line)","distanceM":1500.0,"durationS":120.7,"confidence":0.9},{"source":"teleport","polyline":"\#(line)"}]}"#
        let r = try #require(PersonalRouteJSON.decode(json, id: "x", name: "N", mode: "hovercraft"))
        #expect(r.mode == .car)
        #expect(r.legs.map(\.state) == [.routed, .pending])
        #expect(r.legs[0].distanceM == 1500 && r.legs[0].durationS == 120.7)
        #expect(PersonalRouteJSON.decode(#"{"v":2,"points":[],"legs":[]}"#, id: "x", name: "", mode: "car") == nil)
        #expect(PersonalRouteJSON.decode("not json", id: "x", name: "", mode: "car") == nil)
        let short = try #require(PersonalRouteJSON.decode(#"{"v":1,"points":[{"lat":1,"lng":2},{"lat":1.1,"lng":2},{"lat":1.2,"lng":2}],"legs":[]}"#,
                                                          id: "x", name: "", mode: "walk"))
        #expect(short.legs.count == 2 && short.legs.allSatisfy { $0.state == .pending })
        let routedNoLine = try #require(PersonalRouteJSON.decode(#"{"v":1,"points":[{"lat":1,"lng":2},{"lat":1.1,"lng":2}],"legs":[{"source":"routed","polyline":""}]}"#,
                                                                id: "x", name: "", mode: "walk"))
        #expect(routedNoLine.legs.map(\.state) == [.pending])
    }

    @Test("Android's shared fixture (android/app/src/test/resources/personal-route-v1.json) decodes")
    func androidFixture() throws {
        let url = try #require(Bundle(for: PRFixtureToken.self).url(forResource: "personal-route-v1", withExtension: "json"))
        let json = try String(contentsOf: url, encoding: .utf8)
        let r = try #require(PersonalRouteJSON.decode(json, id: "fixture", name: "Fixture", mode: "motorcycle"))
        #expect(r.points.count == 5 && r.legs.count == 4)
        #expect(r.points[0].location == LatLng(lat: 3.139003, lng: 101.686855))
        #expect(r.legs.map(\.state) == [.routed, .matched, .sketch, .pending])
        #expect(r.legs[0].distanceM == 1650 && r.legs[0].durationS == 240)
        #expect(r.legs[1].distanceM == 2100 && r.legs[1].durationS == 300)
        #expect(r.legs[2].distanceM == 1180 && r.legs[2].durationS == nil)
        // Each calculated leg starts at its point (precision 6).
        for i in 0..<3 { #expect(r.legs[i].geometry.first == r.points[i].location) }
        #expect(r.needsCalculation == 1 && r.durationS == nil)
        // Written back, it carries the same legs (unknown fields dropped).
        let o = try PR.object(try PersonalRouteJSON.encode(r))
        let src = try PR.object(json)
        let legs = try #require(o["legs"] as? [[String: Any]]), srcLegs = try #require(src["legs"] as? [[String: Any]])
        #expect(legs.map { $0["polyline"] as? String } == srcLegs.map { $0["polyline"] as? String })
        #expect(legs.map { $0["source"] as? String } == srcLegs.map { $0["source"] as? String })
        #expect(legs.map { $0["distanceM"] as? Int } == srcLegs.map { $0["distanceM"] as? Int })
        #expect(legs.map { $0["durationS"] as? Int } == srcLegs.map { $0["durationS"] as? Int })
    }
}

@Suite("Personal routes: storage, backup, move")
struct PersonalRouteStorageTests {
    @Test("Save fills the documented columns; an update keeps created_at; name capped at 80")
    func columns() throws {
        let s = try PR.store()
        var r = RouteDraft(id: "r1", title: String(repeating: "x", count: 100), modeRaw: "walk")
        r.addPoint(PR.a); r.addPoint(PR.b)
        try s.savePersonalRoute(r, now: Date(timeIntervalSince1970: 1_000))
        var row = try #require(try s.dbQueue.read { try Row.fetchOne($0, sql: "SELECT * FROM personal_routes") })
        #expect((row["name"] as String).count == 80 && row["mode"] as String == "walk")
        #expect(row["distance_m"] as Int? == nil && row["duration_s"] as Int? == nil)   // not calculated yet
        r.apply(.routed(geometry: [PR.a, PR.b], distanceM: 1234.4, durationS: 99.6), generation: r.legs[0].generation)
        try s.savePersonalRoute(r, now: Date(timeIntervalSince1970: 2_000))
        row = try #require(try s.dbQueue.read { try Row.fetchOne($0, sql: "SELECT * FROM personal_routes") })
        #expect(row["distance_m"] as Int? == 1234 && row["duration_s"] as Int? == 100)
        #expect(row["created_at"] as String == LocalStore.timestamp(Date(timeIntervalSince1970: 1_000)))
        #expect(row["updated_at"] as String == LocalStore.timestamp(Date(timeIntervalSince1970: 2_000)))
        #expect(try s.counts()["personal_routes"] == 1)
        #expect(try s.lastChangeAt() == LocalStore.timestamp(Date(timeIntervalSince1970: 2_000)))
    }

    @Test("Schema 2 → 3: an existing store gains personal_routes and keeps its data")
    func migration() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pr-mig-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("db.sqlite").path
        let old = try DatabaseQueue(path: path)
        try old.write { db in
            try db.execute(sql: LocalStore.schemaV1)
            try db.execute(sql: LocalStore.schemaV2)
            try db.execute(sql: "INSERT INTO saved_places (id, kind, name, icon, lat, lng, created_at, updated_at) VALUES ('h', 'home', 'Rumah', 'home', 3.1, 101.6, '2026-10-20T01:00:00.000Z', '2026-10-20T01:00:00.000Z')")
            try db.execute(sql: "PRAGMA user_version = 2")
        }
        try old.close()
        let s = try LocalStore(path: path, photosDirectory: dir.appendingPathComponent("photos"))
        #expect(try s.dbQueue.read { try Int.fetchOne($0, sql: "PRAGMA user_version") } == 3)
        #expect(try s.dbQueue.read { try $0.tableExists("personal_routes") })
        #expect(try s.savedPlaces().map(\.name) == ["Rumah"])
        // Opening again changes nothing.
        try LocalStore.migrate(s.dbQueue)
        #expect(try s.personalRoutes().isEmpty && s.savedPlaces().count == 1)
    }

    @Test("Backup carries personal_routes (manifest count, snapshot); Replace restores exactly the backup's routes")
    func backupReplace() throws {
        let src = try PR.store()
        try src.savePersonalRoute(PR.mixed(id: "a", title: "Satu"), now: Date(timeIntervalSince1970: 1_000))
        try src.savePersonalRoute(PR.mixed(id: "b", title: "Dua"), now: Date(timeIntervalSince1970: 1_100))
        let file = try BackupService(store: src, kdfLimits: .interactive).makeBackup()
        let manifest = try BackupService.readManifest(file)
        #expect(manifest.schemaVersion == 3 && manifest.counts["personal_routes"] == 2)

        let dst = try PR.store()
        try dst.savePersonalRoute(PR.mixed(id: "mine", title: "Hilang"))
        try BackupService(store: dst).restore(file, mode: .replace)
        #expect(try PR.names(dst) == ["a": "Satu", "b": "Dua"])
        let back = try dst.personalRoutes()
        #expect(back.map(\.id) == ["b", "a"])
        #expect(try back.map(PersonalRouteJSON.encode) == [PR.mixed(id: "b"), PR.mixed(id: "a")].map(PersonalRouteJSON.encode))

        // Encrypted backups carry them too.
        let enc = try BackupService(store: src, kdfLimits: .interactive).makeBackup(passphrase: "rahsia")
        let dst2 = try PR.store()
        try BackupService(store: dst2).restore(enc, mode: .replace, passphrase: "rahsia")
        #expect(try dst2.personalRoutes().count == 2)
    }

    @Test("Merge: upsert by id keeping the newer updated_at; routes only on this phone stay")
    func backupMerge() throws {
        let file = try PR.backupFile(schema: 3) { db in
            try PR.insertRoute(db, id: "older-here", name: "From backup (newer)", updatedAt: "2026-10-24T10:00:00.000Z")
            try PR.insertRoute(db, id: "newer-here", name: "From backup (older)", updatedAt: "2026-10-20T10:00:00.000Z")
            try PR.insertRoute(db, id: "only-backup", name: "Only in backup", updatedAt: "2026-10-21T10:00:00.000Z")
        }
        let dst = try PR.store()
        try dst.dbQueue.write { db in
            try PR.insertRoute(db, id: "older-here", name: "Here (older)", updatedAt: "2026-10-22T10:00:00.000Z")
            try PR.insertRoute(db, id: "newer-here", name: "Here (newer)", updatedAt: "2026-10-23T10:00:00.000Z")
            try PR.insertRoute(db, id: "only-here", name: "Only here", updatedAt: "2026-10-23T10:00:00.000Z")
        }
        try BackupService(store: dst).restore(file, mode: .merge)
        #expect(try PR.names(dst) == ["older-here": "From backup (newer)", "newer-here": "Here (newer)",
                                      "only-backup": "Only in backup", "only-here": "Only here"])
        // Android-written route_json reads here.
        #expect(try dst.personalRoutes().count == 4)
    }

    @Test("A schema-2 backup restores into schema 3 with no routes; newer schemas are refused")
    func schema2Backup() throws {
        let v2 = try PR.backupFile(schema: 2) { db in
            try db.execute(sql: "INSERT INTO saved_places (id, kind, name, icon, lat, lng, created_at, updated_at) VALUES ('h', 'home', 'Rumah', 'home', 3.1, 101.6, '2026-10-20T01:00:00.000Z', '2026-10-20T01:00:00.000Z')")
        }
        let merged = try PR.store()
        try merged.savePersonalRoute(PR.mixed(id: "keep"))
        try BackupService(store: merged).restore(v2, mode: .merge)
        #expect(try merged.personalRoutes().map(\.id) == ["keep"] && merged.savedPlaces().count == 1)

        let replaced = try PR.store()
        try replaced.savePersonalRoute(PR.mixed(id: "gone"))
        try BackupService(store: replaced).restore(v2, mode: .replace)
        #expect(try replaced.personalRoutes().isEmpty && replaced.savedPlaces().map(\.name) == ["Rumah"])

        let v4 = try PR.backupFile(schema: 3) { _ in }
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("pr-v4-\(UUID().uuidString)")
        try FileManager.default.unzipItem(at: v4, to: tmp)
        var m = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: tmp.appendingPathComponent("manifest.json"))) as? [String: Any])
        m["schemaVersion"] = 4
        try JSONSerialization.data(withJSONObject: m).write(to: tmp.appendingPathComponent("manifest.json"))
        let newer = FileManager.default.temporaryDirectory.appendingPathComponent("pr-v4-\(UUID().uuidString).manotu")
        try FileManager.default.zipItem(at: tmp, to: newer, shouldKeepParent: false)
        #expect(throws: BackupError.newerSchema(4)) { try BackupService(store: replaced).restore(newer, mode: .merge) }
    }

    @Test("One-time move from the events database: copied, removed there, table dropped, idempotent")
    func move() throws {
        let events = try EventStore(directory: nil)
        let store = try PR.store()
        let moved = PR.mixed(id: "legacy-1", title: "Lama")
        var newerHere = RouteDraft(id: "legacy-2", title: "Sudah di sini")
        newerHere.addPoint(PR.a); newerHere.addPoint(PR.c)
        try store.savePersonalRoute(newerHere, now: Date(timeIntervalSince1970: 2_000_000_000))
        var staleCopy = newerHere
        staleCopy.title = "Salinan lama"
        try events.dbQueue.write { db in
            for (r, at) in [(moved, "2026-10-01T00:00:00.000Z"), (staleCopy, "2026-10-02T00:00:00.000Z")] {
                try db.execute(sql: "INSERT INTO personal_routes (route_id, draft, updated_at) VALUES (?, ?, ?)",
                               arguments: [r.id, try JSONEncoder().encode(r), at])
            }
        }
        #expect(try PersonalRouteMove.run(from: events, to: store) == 2)
        #expect(try PR.names(store) == ["legacy-1": "Lama", "legacy-2": "Sudah di sini"])
        let row = try #require(try store.dbQueue.read { try Row.fetchOne($0, sql: "SELECT * FROM personal_routes WHERE id = 'legacy-1'") })
        #expect(row["created_at"] as String == "2026-10-01T00:00:00.000Z" && row["updated_at"] as String == "2026-10-01T00:00:00.000Z")
        let movedBack = try #require(try store.personalRoutes().first { $0.id == "legacy-1" })
        #expect(try PersonalRouteJSON.encode(movedBack) == PersonalRouteJSON.encode(moved))
        #expect(try events.dbQueue.read { try $0.tableExists("personal_routes") } == false)
        // Again (next launch): nothing to do, nothing changes; the events database still opens and migrates.
        #expect(try PersonalRouteMove.run(from: events, to: store) == 0)
        #expect(try store.personalRoutes().count == 2)
        try EventStore.migrator.migrate(events.dbQueue)
        #expect(try events.dbQueue.read { try $0.tableExists("personal_routes") } == false)
    }

    @Test("Move keeps rows it can't read in the events database (nothing is lost)")
    func moveUnreadable() throws {
        let events = try EventStore(directory: nil)
        let store = try PR.store()
        try events.dbQueue.write { db in
            try db.execute(sql: "INSERT INTO personal_routes (route_id, draft, updated_at) VALUES ('bad', ?, '2026-10-01T00:00:00.000Z')",
                           arguments: [Data("{".utf8)])
        }
        #expect(try PersonalRouteMove.run(from: events, to: store) == 0)
        #expect(try events.dbQueue.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM personal_routes") } == 1)
        #expect(try store.personalRoutes().isEmpty)
    }

    @Test("Wiping personal data removes routes too")
    func wipe() throws {
        let s = try PR.store()
        try s.savePersonalRoute(PR.mixed())
        try s.wipePersonalData()
        #expect(try s.personalRoutes().isEmpty)
    }
}

@MainActor
@Suite("Personal routes: Guna dalam acara")
struct PersonalRouteEventTests {
    private func center() throws -> EventCenter {
        EventCenter(store: try EventStore(directory: nil), personalStore: try PR.store(), secrets: InMemoryEventSecretStore())
    }

    @Test("A new event with a start place (Mula) and a finish place (Tamat), route linked, template by mode")
    func newEvent() throws {
        let c = try center()
        var r = RouteDraft(id: "p1", title: "Kayuh Ahad", modeRaw: "bicycle")
        r.addPoint(PR.a); r.addPoint(PR.b); r.addPoint(PR.c)
        c.savePersonalRoute(r)
        let id = try #require(c.useRouteInNewEvent(r))
        let d = try #require(c.hostedEvent(id)?.draft)
        #expect(d.template == .ride && d.title == "Kayuh Ahad")
        #expect(d.stops.map(\.kind) == [.start, .finish])
        #expect(d.stops.map(\.label) == [String(localized: "Start"), String(localized: "Finish")])
        #expect(d.stops[0].location == PR.a && d.stops[1].location == PR.c)
        let route = try #require(d.routes.first)
        #expect(d.routes.count == 1 && route.id != r.id && route.title.isEmpty)
        #expect(route.points.map(\.stopId) == [d.stops[0].id, nil, d.stops[1].id])
        if case .success(let er) = route.eventRoute(knownStops: Set(d.stops.map(\.id))) {
            Issue.record("route isn't calculated yet, got \(er)")
        }
        // The personal route stays as it was.
        #expect(c.personalRoutes.map(\.id) == ["p1"])
        #expect(c.path.last == .host(id))
    }

    @Test("A loop gets only Mula (the route ends there); motorcycles make a convoy, cars a trip")
    func loopAndTemplates() throws {
        let c = try center()
        var loop = RouteDraft(title: "", modeRaw: "motorcycle")
        loop.addPoint(PR.a); loop.addPoint(PR.b)
        try loop.closeLoop()
        let d = try #require(c.useRouteInNewEvent(loop).flatMap { c.hostedEvent($0)?.draft })
        #expect(d.template == .convoy && d.stops.map(\.kind) == [.start])
        #expect(d.routes[0].points.map(\.stopId) == [d.stops[0].id, nil, d.stops[0].id])

        var car = RouteDraft(modeRaw: "car")
        car.addPoint(PR.a); car.addPoint(PR.b)
        #expect(try #require(c.useRouteInNewEvent(car).flatMap { c.hostedEvent($0)?.draft }).template == .trip)
        #expect(c.useRouteInNewEvent(RouteDraft()) == nil)
    }

    @Test("Event routes from the phone never carry climb/descent")
    func noElevation() throws {
        var r = RouteDraft(modeRaw: "car")
        r.addPoint(PR.a); r.addPoint(PR.b)
        r.apply(.routed(geometry: [PR.a, PR.b], distanceM: 1500, durationS: 120), generation: r.legs[0].generation)
        guard case .success(let er) = r.eventRoute(knownStops: []) else { Issue.record("not assembled"); return }
        #expect(er.ascentM == nil && er.descentM == nil)
    }
}

@Suite("Lukis: one finger draws, a second finger cancels the stroke")
struct DrawStrokeTests {
    @Test("One finger: points at least 4 pt apart make the line")
    func oneFinger() {
        var s = DrawStroke<Int>()
        s.begin(at: .zero, 0, touches: 1)
        let tooClose = s.move(to: CGPoint(x: 2, y: 0), 1, touches: 1)
        let first = s.move(to: CGPoint(x: 10, y: 0), 2, touches: 1)
        let second = s.move(to: CGPoint(x: 20, y: 0), 3, touches: 1)
        #expect(!tooClose && first && second)
        let line = s.finish()
        #expect(line == [0, 2, 3])
    }

    @Test("A second finger mid-stroke cancels it: no line, no zig-zag, even if the first finger keeps moving")
    func secondFinger() {
        var s = DrawStroke<Int>()
        s.begin(at: .zero, 0, touches: 1)
        _ = s.move(to: CGPoint(x: 10, y: 0), 1, touches: 1)
        let cancelled = s.move(to: CGPoint(x: 200, y: 300), 99, touches: 2)   // centroid jump: dropped
        #expect(cancelled && !s.isActive && s.line.isEmpty)
        let after = s.move(to: CGPoint(x: 30, y: 0), 3, touches: 1)
        #expect(!after && s.line.isEmpty)
        let none = s.finish()
        #expect(none == nil)
        // The next stroke starts clean.
        s.begin(at: .zero, 10, touches: 1)
        _ = s.move(to: CGPoint(x: 10, y: 0), 11, touches: 1)
        let next = s.finish()
        #expect(next == [10, 11])
    }

    @Test("Two fingers from the start never draw (the map pans/zooms)")
    func twoFingersFromStart() {
        var s = DrawStroke<Int>()
        s.begin(at: .zero, 0, touches: 2)
        let moved = s.move(to: CGPoint(x: 50, y: 0), 1, touches: 2)
        let line = s.finish()
        #expect(!moved && line == nil)
    }
}
