import Foundation
import GRDB
import Testing
@testable import Gimano

@Suite("Planned drives")
struct PlannedDriveTests {
    let myt = TimeZone(identifier: "Asia/Kuala_Lumpur")!

    private func at(_ d: Int, _ h: Int, _ m: Int = 0) -> Date {
        var c = DateComponents(year: 2026, month: 10, day: d, hour: h, minute: m)
        c.timeZone = myt
        return Calendar(identifier: .gregorian).date(from: c)!
    }

    private func store() throws -> LocalStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pd-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("p"))
    }

    @Test("Leave-by: latest departure that still arrives on time, interpolating hourly slots")
    func leaveBy() throws {
        // 07:00 → 40 min, 08:00 → 60 min (rush hour), 09:00 → 30 min
        let slots = [DepartureSlot(departAt: at(23, 7), durationS: 2400), DepartureSlot(departAt: at(23, 8), durationS: 3600),
                     DepartureSlot(departAt: at(23, 9), durationS: 1800)]
        let lb = try #require(LeaveByCalculator.leaveBy(arrival: at(23, 8, 30), slots: slots))
        let d = try #require(LeaveByCalculator.duration(at: lb, slots: slots))
        #expect(lb.addingTimeInterval(d) <= at(23, 8, 30))
        #expect(lb.addingTimeInterval(60 + (LeaveByCalculator.duration(at: lb.addingTimeInterval(60), slots: slots) ?? 0)) > at(23, 8, 30))
        // Somewhere between 07:30 and 07:40 given the interpolated 50–56 min trip.
        #expect(lb >= at(23, 7, 30) && lb <= at(23, 7, 40))
        #expect(LeaveByCalculator.leaveBy(arrival: at(23, 8), slots: []) == nil)
        #expect(LeaveByCalculator.duration(at: at(23, 6), slots: slots) == 2400)
        #expect(LeaveByCalculator.duration(at: at(23, 8, 30), slots: slots) == 2700)
    }

    @Test("Repeating weekdays give the next arrivals in Malaysia time; one-off only if ahead")
    func occurrences() {
        // Fri 23 Oct 2026, 09:00 MYT → next weekday 08:30 arrivals: Mon 26, Tue 27, Wed 28
        let d = PlannedDrive(id: "x", name: "Kerja", origin: nil, destination: .init(lat: 3, lng: 101), waypoints: [], vehicle: .car,
                             departAt: nil, schedule: .init(arriveBy: "08:30", days: ["Mo", "Tu", "We", "Th", "Fr"]), createdAt: .now)
        #expect(d.nextArrivals(after: at(23, 9), timeZone: myt) == [at(26, 8, 30), at(27, 8, 30), at(28, 8, 30)])
        // Before 08:30 on Friday, today counts.
        #expect(d.nextArrivals(after: at(23, 7), timeZone: myt).first == at(23, 8, 30))
        var one = d
        one.schedule = .init(arriveAt: at(24, 10))
        #expect(one.nextArrivals(after: at(23, 9)) == [at(24, 10)])
        #expect(one.nextArrivals(after: at(25, 9)).isEmpty)
        #expect(d.scheduleText().hasPrefix("Weekdays"))
    }

    @Test("Stored in the shared planned_drives table, schedule in settings, included in backups")
    func storage() throws {
        let s = try store()
        let d = PlannedDrive(id: "d1", name: "Pergi kerja", origin: nil,
                             destination: .init(lat: 3.111, lng: 101.665, name: "Work", address: "Bangsar South", placeId: nil),
                             waypoints: [TripStop(name: "Tadika Aina", location: LatLng(lat: 3.13, lng: 101.67))], vehicle: .motorcycle,
                             departAt: nil, schedule: .init(arriveBy: "08:30", days: ["Mo", "We"]), createdAt: .now)
        try s.savePlannedDrive(d)
        let back = try #require(try s.plannedDrives().first)
        #expect(back.name == "Pergi kerja" && back.vehicle == .motorcycle && back.schedule.days == ["Mo", "We"])
        #expect(back.waypoints.first?.name == "Tadika Aina")
        let raw = try s.dbQueue.read { try Row.fetchOne($0, sql: "SELECT * FROM planned_drives")! }
        #expect((raw["destination_json"] as String).contains("\"lat\":3.111"))
        #expect((raw["vehicle"] as String) == "motorcycle")

        let file = try BackupService(store: s, kdfLimits: .interactive).makeBackup()
        #expect(try BackupService.readManifest(file).counts["planned_drives"] == 1)
        let other = try store()
        try BackupService(store: other).restore(file, mode: .replace)
        #expect(try other.plannedDrives().first?.schedule.arriveBy == "08:30")

        try s.deletePlannedDrive(id: "d1")
        #expect(try s.plannedDrives().isEmpty)
        #expect(try s.setting(LocalStore.scheduleKey("d1")) == nil)
    }

    @Test("Rows written by Android (destination {lat,lng} only, no schedule) still load")
    func androidRow() throws {
        let s = try store()
        try s.dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO planned_drives VALUES ('d1','Balik kampung',NULL,'{"lat":2.2,"lng":102.2}',NULL,'car','2026-10-30T01:00:00Z','2026-10-23T00:00:00Z')
                """)
        }
        let d = try #require(try s.plannedDrives().first)
        #expect(d.title == "Balik kampung")
        #expect(d.schedule.arriveAt == JSON.parseDate("2026-10-30T01:00:00Z"))
    }
}
