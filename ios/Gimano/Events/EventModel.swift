import Foundation

// Event JSON v1 (docs/events.md §4). Unknown fields are ignored; optional fields may be missing or null;
// unknown `template` / stop `kind` read as `other`; unknown `lang` / `mode` / `source` are rejected.

/// An RFC 3339 timestamp kept with its original offset (`start`/`end`/session/arrival times carry the event's
/// local offset; `publishedAt`/`expiresAt` are UTC).
struct EventTime: Decodable, Hashable, Sendable, Comparable {
    let raw: String
    let date: Date
    /// Seconds east of UTC as written.
    let offsetSeconds: Int

    init(from decoder: any Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let t = EventTime.parse(s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "not RFC 3339"))
        }
        self = t
    }

    init(raw: String, date: Date, offsetSeconds: Int) {
        self.raw = raw
        self.date = date
        self.offsetSeconds = offsetSeconds
    }

    static func < (a: EventTime, b: EventTime) -> Bool { a.date < b.date }
    static func == (a: EventTime, b: EventTime) -> Bool { a.raw == b.raw }
    func hash(into h: inout Hasher) { h.combine(raw) }

    /// `YYYY-MM-DDTHH:MM:SS[.fraction](Z|±HH:MM)`.
    static func parse(_ s: String) -> EventTime? {
        let b = Array(s.utf8)
        func num(_ from: Int, _ len: Int) -> Int? {
            guard from + len <= b.count else { return nil }
            var v = 0
            for i in from..<from + len {
                guard b[i] >= 48 && b[i] <= 57 else { return nil }
                v = v * 10 + Int(b[i] - 48)
            }
            return v
        }
        guard b.count >= 20, let y = num(0, 4), b[4] == 45, let mo = num(5, 2), b[7] == 45, let d = num(8, 2),
              b[10] == 84 || b[10] == 116 || b[10] == 32,
              let h = num(11, 2), b[13] == 58, let mi = num(14, 2), b[16] == 58, let sec = num(17, 2) else { return nil }
        var i = 19
        var fraction = 0.0
        if i < b.count, b[i] == 46 {
            i += 1
            var scale = 0.1
            let start = i
            while i < b.count, b[i] >= 48, b[i] <= 57 {
                fraction += Double(b[i] - 48) * scale
                scale /= 10
                i += 1
            }
            if i == start { return nil }
        }
        guard i < b.count else { return nil }
        var offset = 0
        if b[i] == 90 || b[i] == 122 {
            i += 1
        } else if b[i] == 43 || b[i] == 45 {
            guard let oh = num(i + 1, 2), i + 3 < b.count, b[i + 3] == 58, let om = num(i + 4, 2), oh < 24, om < 60 else { return nil }
            offset = (oh * 3600 + om * 60) * (b[i] == 45 ? -1 : 1)
            i += 6
        } else {
            return nil
        }
        guard i == b.count, (1...12).contains(mo), (1...31).contains(d), h < 24, mi < 60, sec <= 60 else { return nil }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        let comps = DateComponents(year: y, month: mo, day: d, hour: h, minute: mi, second: min(sec, 59))
        guard let base = cal.date(from: comps) else { return nil }
        // Reject impossible dates (e.g. 31 February), which Calendar would roll over.
        let back = cal.dateComponents([.year, .month, .day], from: base)
        guard back.year == y, back.month == mo, back.day == d else { return nil }
        let date = base.addingTimeInterval(fraction + Double(sec == 60 ? 1 : 0) - Double(offset))
        return EventTime(raw: s, date: date, offsetSeconds: offset)
    }
}

enum EventTemplate: String, Sendable, CaseIterable {
    case kenduri, dinner, company, ride, convoy, trip, meeting, seminar, picnic, hike, other
}

/// `info[].kind` (§4.1). Unknown values from newer writers read as `.other`.
enum EventInfoKind: String, Sendable, CaseIterable {
    case dress, safety, documents, bring, contact, other
}

enum EventLang: String, Decodable, Sendable { case ms, en }

enum EventStopKind: String, Sendable, CaseIterable {
    case parking, dropoff, entrance, venue, meal, checkpoint, fuel, water, rest, prayer, accommodation, start, finish, other
}

enum EventTravelMode: String, Decodable, Sendable { case car, motorcycle, bicycle, walk }

enum EventGeometrySource: String, Decodable, Sendable { case routed, sketch, gpx }

/// Unknown values from newer writers read as `.other` (display only).
extension EventTemplate: Decodable {
    init(from decoder: any Decoder) throws {
        self = EventTemplate(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .other
    }
}

extension EventInfoKind: Decodable {
    init(from decoder: any Decoder) throws {
        self = EventInfoKind(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .other
    }
}

extension EventStopKind: Decodable {
    init(from decoder: any Decoder) throws {
        self = EventStopKind(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .other
    }
}

struct EventPoint: Decodable, Hashable, Sendable {
    var lat: Double
    var lng: Double
    var latLng: LatLng { LatLng(lat: lat, lng: lng) }
}

struct EventWindow: Decodable, Hashable, Sendable {
    var from: EventTime
    var to: EventTime
}

struct EventOrganizer: Decodable, Hashable, Sendable {
    var alias: String?
}

struct EventStop: Decodable, Hashable, Sendable, Identifiable {
    var id: String
    var kind: EventStopKind
    var label: String
    var lat: Double
    var lng: Double
    /// Vehicle entrance; navigate here when present.
    var entrance: EventPoint?
    var arrive: EventWindow?
    var dwellMin: UInt32?
    /// Guest-visible.
    var notes: String?
    var audience: String?

    var pin: LatLng { LatLng(lat: lat, lng: lng) }
    /// Where navigation goes: the entrance if present, else the pin (§7.7).
    var navigationTarget: LatLng { entrance?.latLng ?? pin }
}

struct EventSession: Decodable, Hashable, Sendable, Identifiable {
    var id: String
    var title: String
    var start: EventTime
    var end: EventTime
    var stopId: String?
}

/// One guest info block (§4.1): plain guest-visible text, 1–1000 code points, newlines allowed, no markup.
struct EventInfoBlock: Decodable, Hashable, Sendable {
    var kind: EventInfoKind
    var text: String
}

struct EventRouteLeg: Decodable, Hashable, Sendable {
    var fromStopId: String
    var toStopId: String
    var distanceM: UInt32
    var durationS: UInt32?
    var source: EventGeometrySource
}

struct EventRoute: Decodable, Hashable, Sendable, Identifiable {
    var id: String
    var title: String?
    var mode: EventTravelMode
    var day: String?
    var stopIds: [String]
    /// Precision-6 polyline. Required on a full event; absent on every route of a stops-only variant (§5.5).
    var polyline: String?
    var source: EventGeometrySource
    var distanceM: UInt32
    var durationS: UInt32?
    var ascentM: UInt32?
    var descentM: UInt32?
    var legs: [EventRouteLeg]

    enum CodingKeys: String, CodingKey {
        case id, title, mode, day, stopIds, polyline, source, distanceM, durationS, ascentM, descentM, legs
    }

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        mode = try c.decode(EventTravelMode.self, forKey: .mode)
        day = try c.decodeIfPresent(String.self, forKey: .day)
        stopIds = try c.decodeIfPresent([String].self, forKey: .stopIds) ?? []
        polyline = try c.decodeIfPresent(String.self, forKey: .polyline)
        source = try c.decode(EventGeometrySource.self, forKey: .source)
        distanceM = try c.decode(UInt32.self, forKey: .distanceM)
        durationS = try c.decodeIfPresent(UInt32.self, forKey: .durationS)
        ascentM = try c.decodeIfPresent(UInt32.self, forKey: .ascentM)
        descentM = try c.decodeIfPresent(UInt32.self, forKey: .descentM)
        legs = try c.decodeIfPresent([EventRouteLeg].self, forKey: .legs) ?? []
    }

    /// Decoded geometry (validated on import, so this does not fail for stored events); empty in a stops-only variant.
    var points: [LatLng] { polyline.flatMap { try? EventPolyline.decode($0, maxPoints: EventLimits.maxPolylinePoints) } ?? [] }
}

/// `"variant": {"kind": "stops-only", "fullHash": "<b64u SHA-256 of the full event bytes>"}` (§5.5). `kind` stays a
/// string so an unknown kind is reported as `unsupported_variant` rather than as malformed JSON.
struct EventVariant: Codable, Hashable, Sendable {
    static let stopsOnlyKind = "stops-only"

    var kind: String
    var fullHash: String

    static func stopsOnly(fullHash: [UInt8]) -> EventVariant { EventVariant(kind: stopsOnlyKind, fullHash: B64u.encode(fullHash)) }

    var isStopsOnly: Bool { kind == Self.stopsOnlyKind }
    /// The decoded `fullHash`, when it is a valid SHA-256 value.
    var fullHashBytes: [UInt8]? { B64u.decode(fullHash).flatMap { $0.count == 32 ? $0 : nil } }
}

/// The signed event document.
struct EventDoc: Decodable, Hashable, Sendable, Identifiable {
    var id: String
    var rev: UInt32
    var parent: String?
    var publishedAt: EventTime
    var template: EventTemplate
    var title: String
    var description: String?
    var lang: EventLang
    var tz: String
    var start: EventTime
    var end: EventTime
    var expiresAt: EventTime
    var organizer: EventOrganizer?
    var stops: [EventStop]
    var sessions: [EventSession]
    /// Guest info blocks (§4.1), shown in order after the agenda.
    var info: [EventInfoBlock]
    var routes: [EventRoute]
    /// Present only on a stops-only variant (§5.5); `null` or absent = the full event.
    var variant: EventVariant?

    enum CodingKeys: String, CodingKey {
        case id, rev, parent, publishedAt, template, title, description, lang, tz, start, end, expiresAt, organizer, stops, sessions, info, routes
        case variant
    }

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        rev = try c.decode(UInt32.self, forKey: .rev)
        parent = try c.decodeIfPresent(String.self, forKey: .parent)
        publishedAt = try c.decode(EventTime.self, forKey: .publishedAt)
        template = try c.decode(EventTemplate.self, forKey: .template)
        title = try c.decode(String.self, forKey: .title)
        description = try c.decodeIfPresent(String.self, forKey: .description)
        lang = try c.decode(EventLang.self, forKey: .lang)
        tz = try c.decode(String.self, forKey: .tz)
        start = try c.decode(EventTime.self, forKey: .start)
        end = try c.decode(EventTime.self, forKey: .end)
        expiresAt = try c.decode(EventTime.self, forKey: .expiresAt)
        organizer = try c.decodeIfPresent(EventOrganizer.self, forKey: .organizer)
        stops = try c.decodeIfPresent([EventStop].self, forKey: .stops) ?? []
        sessions = try c.decodeIfPresent([EventSession].self, forKey: .sessions) ?? []
        info = try c.decodeIfPresent([EventInfoBlock].self, forKey: .info) ?? []
        routes = try c.decodeIfPresent([EventRoute].self, forKey: .routes) ?? []
        variant = try c.decodeIfPresent(EventVariant.self, forKey: .variant)
    }

    /// A stops-only variant (§5.5): the same revision without route lines.
    var isStopsOnly: Bool { variant?.isStopsOnly ?? false }

    var alias: String? { organizer?.alias.flatMap { $0.isEmpty ? nil : $0 } }
    var timeZone: TimeZone { TimeZone(identifier: tz) ?? TimeZone(secondsFromGMT: start.offsetSeconds) ?? .current }
    var polylinePointCount: Int { routes.reduce(0) { $0 + $1.points.count } }

    func stop(_ id: String) -> EventStop? { stops.first { $0.id == id } }

    /// Parses and validates signed event bytes (a full event or a stops-only variant).
    static func parse(_ bytes: [UInt8]) throws(EventPackError) -> EventDoc {
        let event: EventDoc
        do {
            event = try JSONDecoder().decode(EventDoc.self, from: Data(bytes))
        } catch {
            throw .malformedEvent(String(describing: error))
        }
        try EventValidation.check(event)
        return event
    }
}

// MARK: - Polyline (precision 6, strict like route-core)

enum EventPolyline {
    enum Failure: Error { case invalid, tooManyPoints }

    /// Decodes at most `maxPoints` points; every point must be a valid WGS84 coordinate.
    static func decode(_ s: String, maxPoints: Int) throws(Failure) -> [LatLng] {
        let bytes = Array(s.utf8)
        var points: [LatLng] = []
        var i = 0
        var lat: Int64 = 0
        var lng: Int64 = 0
        func read() throws(Failure) -> Int64 {
            var result: UInt64 = 0
            var shift: UInt64 = 0
            while true {
                guard i < bytes.count else { throw .invalid }
                let b = bytes[i]
                guard (63...126).contains(b) else { throw .invalid }
                i += 1
                if shift > 35 { throw .invalid }
                let chunk = UInt64(b - 63)
                result |= (chunk & 0x1F) << shift
                shift += 5
                if chunk < 0x20 { break }
            }
            let v = Int64(result >> 1)
            return result & 1 == 1 ? ~v : v
        }
        while i < bytes.count {
            if points.count >= maxPoints { throw .tooManyPoints }
            lat += try read()
            if i >= bytes.count { throw .invalid }
            lng += try read()
            let p = LatLng(lat: Double(lat) / 1e6, lng: Double(lng) / 1e6)
            guard EventValidation.validCoordinate(p.lat, p.lng) else { throw .invalid }
            points.append(p)
        }
        return points
    }
}

// MARK: - Validation (mirrors event-core validate.rs, same order)

enum EventValidation {
    private static func fail(_ path: String, _ reason: String) -> EventPackError { .invalidEvent(path: path, reason: reason) }

    static func scalars(_ s: String) -> Int { s.unicodeScalars.count }

    static func text(_ path: String, _ s: String, min: Int, max: Int) throws(EventPackError) {
        let n = scalars(s)
        if n < min || (min > 0 && s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) { throw fail(path, "is required") }
        if n > max { throw fail(path, "is too long") }
    }

    static func optText(_ path: String, _ s: String?, max: Int) throws(EventPackError) {
        if let s { try text(path, s, min: 0, max: max) }
    }

    static func id(_ path: String, _ s: String, bytes: Int) throws(EventPackError) {
        guard let b = B64u.decode(s), b.count == bytes else { throw fail(path, "is not a valid id") }
    }

    static func validCoordinate(_ lat: Double, _ lng: Double) -> Bool {
        lat.isFinite && lng.isFinite && (-90...90).contains(lat) && (-180...180).contains(lng)
    }

    static func sixDecimals(_ v: Double) -> Bool { abs((v * 1e6).rounded() / 1e6 - v) <= 1e-9 }

    static func coord(_ path: String, _ lat: Double, _ lng: Double) throws(EventPackError) {
        guard validCoordinate(lat, lng) else { throw fail(path, "coordinate out of range") }
        guard sixDecimals(lat), sixDecimals(lng) else { throw fail(path, "coordinate has more than 6 decimals") }
    }

    static func window(_ path: String, _ from: EventTime, _ to: EventTime) throws(EventPackError) {
        if to.date < from.date { throw fail(path, "ends before it starts") }
    }

    static func validTimeZoneName(_ s: String) -> Bool {
        !s.isEmpty && s.utf8.count <= EventLimits.tzChars && !s.hasPrefix("/") && !s.hasSuffix("/") && !s.contains("//")
            && s.utf8.allSatisfy { ($0 >= 48 && $0 <= 57) || ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122) || [47, 95, 45, 43].contains($0) }
    }

    static func validDay(_ s: String) -> Bool {
        guard s.utf8.count == 10, let t = EventTime.parse(s + "T00:00:00Z") else { return false }
        return t.raw.hasPrefix(s)
    }

    /// The variant rules (§5.5) with their own error codes, then every §4 rule (`validate.rs` `check`).
    static func check(_ e: EventDoc) throws(EventPackError) {
        let stopsOnly: Bool
        switch e.variant {
        case nil: stopsOnly = false
        case .some(let v) where v.isStopsOnly: stopsOnly = true
        case .some(let v): throw .unsupportedVariant(v.kind)
        }
        for (i, r) in e.routes.enumerated() {
            if stopsOnly && r.polyline != nil { throw .variantHasPolyline(route: i) }
            if !stopsOnly && r.polyline == nil { throw .missingPolyline(route: i) }
        }
        try validate(e)
    }

    static func validate(_ e: EventDoc) throws(EventPackError) {
        let stopsOnly: Bool
        switch e.variant {
        case nil: stopsOnly = false
        case .some(let v) where v.isStopsOnly:
            if v.fullHashBytes == nil { throw fail("variant.fullHash", "is not a SHA-256 hash") }
            stopsOnly = true
        case .some: throw fail("variant.kind", "is not supported")
        }
        try id("id", e.id, bytes: EventLimits.eventIdBytes)
        if e.rev == 0 { throw fail("rev", "must be 1 or more") }
        switch (e.parent, e.rev) {
        case (nil, 1): break
        case (.some, 1): throw fail("parent", "must be null for rev 1")
        case (nil, _): throw fail("parent", "is required after rev 1")
        case (.some(let p), _):
            guard let b = B64u.decode(p), b.count == 32 else { throw fail("parent", "is not a SHA-256 hash") }
        }
        try text("title", e.title, min: 1, max: EventLimits.titleChars)
        try optText("description", e.description, max: EventLimits.descriptionChars)
        guard validTimeZoneName(e.tz) else { throw fail("tz", "is not an IANA time zone name") }
        try window("end", e.start, e.end)
        if e.end.date.timeIntervalSince(e.start.date) > Double(EventLimits.maxEventDays) * 86_400 {
            throw fail("end", "is more than 14 days after start")
        }
        if let o = e.organizer { try optText("organizer.alias", o.alias, max: EventLimits.aliasChars) }

        if e.stops.count > EventLimits.maxStops { throw fail("stops", "has more than 200 stops") }
        var stopIds = Set<String>()
        for (i, s) in e.stops.enumerated() {
            let p = { (f: String) in "stops[\(i)].\(f)" }
            try id(p("id"), s.id, bytes: EventLimits.itemIdBytes)
            guard stopIds.insert(s.id).inserted else { throw fail(p("id"), "is duplicated") }
            try text(p("label"), s.label, min: 1, max: EventLimits.stopLabelChars)
            try coord(p("lat"), s.lat, s.lng)
            if let en = s.entrance { try coord(p("entrance"), en.lat, en.lng) }
            if let a = s.arrive { try window(p("arrive"), a.from, a.to) }
            if let d = s.dwellMin, d > EventLimits.maxDwellMin { throw fail(p("dwellMin"), "is too long") }
            try optText(p("notes"), s.notes, max: EventLimits.stopNotesChars)
            try optText(p("audience"), s.audience, max: EventLimits.audienceChars)
        }
        func knownStop(_ path: String, _ sid: String) throws(EventPackError) {
            if !stopIds.contains(sid) { throw fail(path, "refers to an unknown stop") }
        }

        if e.sessions.count > EventLimits.maxSessions { throw fail("sessions", "has more than 50 sessions") }
        var sessionIds = Set<String>()
        for (i, s) in e.sessions.enumerated() {
            let p = { (f: String) in "sessions[\(i)].\(f)" }
            try id(p("id"), s.id, bytes: EventLimits.itemIdBytes)
            guard sessionIds.insert(s.id).inserted else { throw fail(p("id"), "is duplicated") }
            try text(p("title"), s.title, min: 1, max: EventLimits.sessionTitleChars)
            try window(p("end"), s.start, s.end)
            if let sid = s.stopId { try knownStop(p("stopId"), sid) }
        }

        if e.info.count > EventLimits.maxInfoBlocks { throw fail("info", "has more than 12 info blocks") }
        for (i, b) in e.info.enumerated() {
            try text("info[\(i)].text", b.text, min: 1, max: EventLimits.infoTextChars)
        }

        if e.routes.count > EventLimits.maxRoutes { throw fail("routes", "has more than 20 routes") }
        var routeIds = Set<String>()
        var points = 0
        for (i, r) in e.routes.enumerated() {
            let p = { (f: String) in "routes[\(i)].\(f)" }
            try id(p("id"), r.id, bytes: EventLimits.itemIdBytes)
            guard routeIds.insert(r.id).inserted else { throw fail(p("id"), "is duplicated") }
            try optText(p("title"), r.title, max: EventLimits.routeTitleChars)
            if let day = r.day, !validDay(day) { throw fail(p("day"), "is not a YYYY-MM-DD date") }
            for (j, sid) in r.stopIds.enumerated() { try knownStop(p("stopIds[\(j)]"), sid) }
            let polyline: String
            switch (r.polyline, stopsOnly) {
            case (.some, true): throw fail(p("polyline"), "must be absent on a stops-only variant")
            case (nil, false): throw fail(p("polyline"), "is required")
            case (nil, true): polyline = ""
            case (.some(let pl), false): polyline = pl
            }
            let decoded: [LatLng]
            do {
                decoded = try EventPolyline.decode(polyline, maxPoints: EventLimits.maxPolylinePoints - points)
            } catch EventPolyline.Failure.tooManyPoints {
                throw fail(p("polyline"), "routes have more than 50 000 polyline points in total")
            } catch {
                throw fail(p("polyline"), "is not a valid precision-6 polyline")
            }
            if !stopsOnly && decoded.count < 2 { throw fail(p("polyline"), "needs at least 2 points") }
            points += decoded.count
            for (j, l) in r.legs.enumerated() {
                try knownStop(p("legs[\(j)].fromStopId"), l.fromStopId)
                try knownStop(p("legs[\(j)].toStopId"), l.toStopId)
            }
        }
    }
}
