import Foundation
import GRDB
import UserNotifications

/// A planned drive (on device, `planned_drives` table + schedule in `settings`).
///
/// Shared-table mapping (docs/privacy-and-backup.md):
/// - `destination_json` = `{"lat","lng","name","address","placeId"}`, `origin_json` = null (current location) or `{"lat","lng","name"}`
/// - `waypoints_json`   = `[{"lat","lng","name","placeId"}]`
/// - `depart_at`        = one-off: the computed leave-by instant (ISO-8601 UTC); repeating: null
/// - schedule (not in the table): `settings["planned_drive:<id>"]` =
///   `{"arriveBy":"08:30","days":["Mo","Tu",…]}` (repeating) or `{"arriveAt":"<ISO-8601>"}` (one-off)
struct PlannedDrive: Identifiable, Hashable, Sendable {
    struct Place: Codable, Hashable, Sendable {
        var lat: Double
        var lng: Double
        var name: String?
        var address: String?
        var placeId: String?
        var location: LatLng { LatLng(lat: lat, lng: lng) }
    }

    struct Schedule: Codable, Hashable, Sendable {
        /// Repeating: local arrival time "HH:mm".
        var arriveBy: String?
        /// Repeating weekdays (OSM-style "Mo"…"Su").
        var days: [String]?
        /// One-off arrival instant.
        var arriveAt: Date?

        var isRepeating: Bool { !(days ?? []).isEmpty && arriveBy != nil }
    }

    var id: String
    var name: String?
    var origin: Place?
    var destination: Place
    var waypoints: [TripStop]
    var vehicle: Vehicle
    var departAt: Date?
    var schedule: Schedule
    var createdAt: Date

    var title: String { name?.isEmpty == false ? name! : (destination.name ?? String(localized: "Planned drive")) }

    /// Arrival instants of the next occurrences after `now` (one-off: just its arrival if still ahead).
    func nextArrivals(after now: Date, count: Int = 3, timeZone: TimeZone = TimeZone(identifier: "Asia/Kuala_Lumpur")!) -> [Date] {
        if let at = schedule.arriveAt { return at > now ? [at] : [] }
        guard schedule.isRepeating, let hhmm = schedule.arriveBy, let minutes = OpeningHours.Range.minutes(hhmm) else { return [] }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let days = Set((schedule.days ?? []).compactMap(OpeningHours.Day.init(osm:)))
        var out: [Date] = []
        var day = cal.startOfDay(for: now)
        for _ in 0..<15 where out.count < count {
            // Calendar weekday: 1 = Sunday … 7 = Saturday → Day: mo = 0 … su = 6
            let wd = cal.component(.weekday, from: day)
            let d = OpeningHours.Day(rawValue: (wd + 5) % 7)!
            let arrival = day.addingTimeInterval(TimeInterval(minutes * 60))
            if days.contains(d), arrival > now { out.append(arrival) }
            day = cal.date(byAdding: .day, value: 1, to: day)!
        }
        return out
    }

    /// "Weekdays · arrive 08:30", "Mo, We · arrive 07:15", "Fri 24 Oct · arrive 08:30".
    func scheduleText(timeZone: TimeZone = .current) -> String {
        if let at = schedule.arriveAt {
            return String(localized: "\(at.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))) · arrive \(at.formatted(date: .omitted, time: .shortened))")
        }
        let days = schedule.days ?? []
        let label: String
        if Set(days) == ["Mo", "Tu", "We", "Th", "Fr"] { label = String(localized: "Weekdays") }
        else if days.count == 7 { label = String(localized: "Every day") }
        else { label = days.joined(separator: ", ") }
        return String(localized: "\(label) · arrive \(schedule.arriveBy ?? "")")
    }
}

/// Latest departure that still arrives on time, from `GET /routes/departure-times` slots
/// (hourly; durations interpolated linearly between slots).
enum LeaveByCalculator {
    static func duration(at t: Date, slots: [DepartureSlot]) -> Double? {
        let s = slots.sorted { $0.departAt < $1.departAt }
        guard let first = s.first, let last = s.last else { return nil }
        if t <= first.departAt { return first.durationS }
        if t >= last.departAt { return last.durationS }
        for (a, b) in zip(s, s.dropFirst()) where t >= a.departAt && t <= b.departAt {
            let f = t.timeIntervalSince(a.departAt) / b.departAt.timeIntervalSince(a.departAt)
            return a.durationS + (b.durationS - a.durationS) * f
        }
        return nil
    }

    /// Searches back from the arrival in 1-minute steps (max 6 h) for the latest departure t with t + duration(t) ≤ arrival.
    static func leaveBy(arrival: Date, slots: [DepartureSlot]) -> Date? {
        guard !slots.isEmpty else { return nil }
        var t = arrival.addingTimeInterval(-60)
        let limit = arrival.addingTimeInterval(-6 * 3600)
        while t > limit {
            if let d = duration(at: t, slots: slots), t.addingTimeInterval(d) <= arrival {
                // Round down to the minute.
                return Date(timeIntervalSince1970: (t.timeIntervalSince1970 / 60).rounded(.down) * 60)
            }
            t = t.addingTimeInterval(-60)
        }
        return nil
    }
}

extension LocalStore {
    static func scheduleKey(_ id: String) -> String { "planned_drive:\(id)" }

    func savePlannedDrive(_ d: PlannedDrive) throws {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        func json<T: Encodable>(_ v: T?) throws -> String? { try v.map { String(decoding: try enc.encode($0), as: UTF8.self) } }
        let waypoints = try json(d.waypoints.map { PlannedDrive.Place(lat: $0.location.lat, lng: $0.location.lng, name: $0.name, placeId: $0.placeId) })
        try dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO planned_drives (id, name, origin_json, destination_json, waypoints_json, vehicle, depart_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name = excluded.name, origin_json = excluded.origin_json, destination_json = excluded.destination_json,
                  waypoints_json = excluded.waypoints_json, vehicle = excluded.vehicle, depart_at = excluded.depart_at
                """, arguments: [d.id, d.name, try json(d.origin), try json(d.destination), d.waypoints.isEmpty ? nil : waypoints,
                                 d.vehicle.rawValue, d.departAt.map { Self.timestamp($0) }, Self.timestamp(d.createdAt)])
        }
        try setSetting(Self.scheduleKey(d.id), try json(d.schedule))
    }

    func plannedDrives() throws -> [PlannedDrive] {
        let rows = try dbQueue.read { try Row.fetchAll($0, sql: "SELECT * FROM planned_drives ORDER BY COALESCE(depart_at, created_at)") }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        return rows.compactMap { r -> PlannedDrive? in
            let id: String = r["id"]
            guard let destJSON: String = r["destination_json"],
                  let dest = try? dec.decode(PlannedDrive.Place.self, from: Data(destJSON.utf8)) else { return nil }
            let origin = (r["origin_json"] as String?).flatMap { try? dec.decode(PlannedDrive.Place.self, from: Data($0.utf8)) }
            let wps = (r["waypoints_json"] as String?).flatMap { try? dec.decode([PlannedDrive.Place].self, from: Data($0.utf8)) } ?? []
            let sched = ((try? setting(Self.scheduleKey(id))) ?? nil).flatMap { try? dec.decode(PlannedDrive.Schedule.self, from: Data($0.utf8)) }
                ?? PlannedDrive.Schedule(arriveAt: Self.date(r["depart_at"]))
            return PlannedDrive(id: id, name: r["name"], origin: origin, destination: dest,
                                waypoints: wps.map { TripStop(name: $0.name ?? "", location: $0.location, placeId: $0.placeId) },
                                vehicle: Vehicle(rawValue: r["vehicle"]) ?? .car, departAt: Self.date(r["depart_at"]), schedule: sched,
                                createdAt: Self.date(r["created_at"]) ?? .now)
        }
    }

    func deletePlannedDrive(id: String) throws {
        try dbQueue.write { try $0.execute(sql: "DELETE FROM planned_drives WHERE id = ?", arguments: [id]) }
        try setSetting(Self.scheduleKey(id), nil)
    }
}

/// The only call planned drives make to the server.
protocol DepartureTimesSource: Sendable {
    func departureTimes(origin: LatLng, destination: LatLng, date: Date, vehicle: Vehicle) async throws -> [DepartureSlot]
}

/// Why a planned drive's time is being computed.
enum PlannedDriveTrigger: Equatable, Sendable {
    /// App launch, opening the list: never with the user's position.
    case automatic
    /// The user saved the drive or tapped "Open for the best time": the fresh fix (if any) may be the origin.
    case userAsked(fix: LatLng?)
}

/// Schedules "Leave by" local notifications for planned drives.
@MainActor
enum PlannedDriveNotifier {
    static let category = "planned-drive"
    /// Notify this long before the leave-by time.
    static let leadTime: TimeInterval = 15 * 60

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Where a drive's leave-by is computed from. A saved fixed start may be used any time (it is a saved place, not the
    /// user); the live fix only when the user asked for this drive's time (never at launch or in the background).
    static func origin(for drive: PlannedDrive, trigger: PlannedDriveTrigger) -> LatLng? {
        if let o = drive.origin?.location { return o }
        if case .userAsked(let fix) = trigger { return fix }
        return nil
    }

    /// Reminder lead for a drive without a computed time ("Open for the best time").
    static let noTimeLead: TimeInterval = 60 * 60

    /// Computes leave-by times for the next occurrences and replaces this drive's pending notifications.
    /// Returns the next leave-by (for display), or nil when there is no origin to compute from: then one reminder an hour
    /// before the next arrival asks the user to open the drive for the best time.
    @discardableResult
    static func reschedule(_ drive: PlannedDrive, trigger: PlannedDriveTrigger, repo: any DepartureTimesSource,
                           now: Date = .now) async -> Date? {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix("planned:\(drive.id):") }
        center.removePendingNotificationRequests(withIdentifiers: pending)
        var firstLeaveBy: Date?
        guard let start = origin(for: drive, trigger: trigger) else {
            if let arrival = drive.nextArrivals(after: now, count: 1).first {
                let fire = arrival.addingTimeInterval(-noTimeLead)
                if fire > now {
                    let content = UNMutableNotificationContent()
                    content.title = drive.title
                    content.body = String(localized: "Open Manotu for the best time to leave for \(drive.destination.name ?? drive.title) (arrive \(arrival.formatted(date: .omitted, time: .shortened)))")
                    content.sound = .default
                    content.categoryIdentifier = category
                    content.userInfo = ["plannedDriveId": drive.id]
                    let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fire)
                    try? await center.add(UNNotificationRequest(identifier: "planned:\(drive.id):\(Int(arrival.timeIntervalSince1970))",
                                                                content: content, trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)))
                }
            }
            return nil
        }
        for arrival in drive.nextArrivals(after: now) {
            // Origin/destination go to the server for this calculation only (never stored there).
            let slots = (try? await repo.departureTimes(origin: start, destination: drive.destination.location, date: arrival,
                                                        vehicle: drive.vehicle)) ?? []
            guard let leaveBy = LeaveByCalculator.leaveBy(arrival: arrival, slots: slots) else { continue }
            if firstLeaveBy == nil { firstLeaveBy = leaveBy }
            let fire = max(now.addingTimeInterval(5), leaveBy.addingTimeInterval(-leadTime))
            guard fire < arrival else { continue }
            let content = UNMutableNotificationContent()
            content.title = drive.title
            content.body = String(localized: "Leave by \(leaveBy.formatted(date: .omitted, time: .shortened)) to arrive at \(drive.destination.name ?? drive.title) at \(arrival.formatted(date: .omitted, time: .shortened))")
            content.sound = .default
            content.categoryIdentifier = category
            content.userInfo = ["plannedDriveId": drive.id]
            let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fire)
            let req = UNNotificationRequest(identifier: "planned:\(drive.id):\(Int(arrival.timeIntervalSince1970))", content: content,
                                            trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false))
            try? await center.add(req)
        }
        return firstLeaveBy
    }

    static func cancel(_ id: String) async {
        let center = UNUserNotificationCenter.current()
        let ids = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix("planned:\(id):") }
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }
}
