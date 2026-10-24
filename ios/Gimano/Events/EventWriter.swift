import Compression
import CryptoKit
import Foundation
import Sodium

// Writer side of Manotu event packs (docs/events.md §3–§5), mirroring the desktop reference
// `desktop/crates/event-core` (model.rs `to_bytes`, pack.rs `seal_parts`/`seal`, link.rs, file.rs, keys.rs).
//
// Privacy: the organizer seed and the pack key never leave this phone (Keychain, this device only), and nothing
// here touches the network or logs event content.

// MARK: - Model constructors (the reader types only have decoding initialisers)

extension EventTime {
    private static func formatter(_ tz: TimeZone, utc: Bool) -> DateFormatter {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = tz
        f.dateFormat = utc ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm:ssxxx"
        return f
    }

    /// Whole seconds, carrying the offset `tz` has at that instant (`2026-11-15T11:00:00+08:00`).
    static func local(_ date: Date, _ tz: TimeZone) -> EventTime {
        let d = Date(timeIntervalSince1970: date.timeIntervalSince1970.rounded(.down))
        return EventTime(raw: formatter(tz, utc: false).string(from: d), date: d, offsetSeconds: tz.secondsFromGMT(for: d))
    }

    /// Whole seconds in UTC with `Z` (`publishedAt`, `expiresAt`).
    static func utc(_ date: Date) -> EventTime {
        let d = Date(timeIntervalSince1970: date.timeIntervalSince1970.rounded(.down))
        return EventTime(raw: formatter(TimeZone(secondsFromGMT: 0)!, utc: true).string(from: d), date: d, offsetSeconds: 0)
    }
}

extension EventDoc {
    init(id: String, rev: UInt32, parent: String?, publishedAt: EventTime, template: EventTemplate, title: String,
         description: String? = nil, lang: EventLang, tz: String, start: EventTime, end: EventTime, expiresAt: EventTime,
         organizer: EventOrganizer? = nil, stops: [EventStop], sessions: [EventSession] = [], routes: [EventRoute] = [],
         variant: EventVariant? = nil) {
        self.id = id; self.rev = rev; self.parent = parent; self.publishedAt = publishedAt; self.template = template
        self.title = title; self.description = description; self.lang = lang; self.tz = tz; self.start = start
        self.end = end; self.expiresAt = expiresAt; self.organizer = organizer; self.stops = stops
        self.sessions = sessions; self.routes = routes; self.variant = variant
    }

    /// The writer's bytes for this event (validated first, like `Event::to_bytes`). Writers publish
    /// `simplified()` (§5.4); `HostedEventDraft.event` already builds simplified routes.
    func bytes() throws(EventPackError) -> [UInt8] {
        try EventValidation.check(self)
        return EventJSON.encode(self)
    }

    /// `next_revision`: rev + 1, `parent` = the revision hash of the previous signed bytes (their `fullHash` if they
    /// were a stops-only variant), new `publishedAt`; same id; always a full event.
    func nextRevision(previousBytes: [UInt8], publishedAt: Date) -> EventDoc {
        var e = self
        e.rev = rev &+ 1 == 0 ? rev : rev + 1
        e.parent = B64u.encode(EventCodes.revisionHash(previousBytes))
        e.publishedAt = .utc(publishedAt)
        e.variant = nil
        return e
    }

    /// Copy with every route polyline simplified with Douglas–Peucker at 5 m (§5.4). Distances and durations keep the
    /// routing values. Polylines that don't decode are left to validation.
    func simplified() -> EventDoc {
        var e = self
        for i in e.routes.indices {
            if let p = e.routes[i].polyline, let s = RouteSimplify.polyline(p) { e.routes[i].polyline = s }
        }
        return e
    }

    /// The stops-only variant of signed full event bytes (§5.5, `event_core::stops_only_variant`): the same revision
    /// with every route's `polyline` removed and `variant: {kind: "stops-only", fullHash: SHA-256(full bytes)}` as the
    /// last field. Deterministic for given full bytes. Sign it with the same organizer key.
    static func stopsOnlyVariant(fullEventBytes: [UInt8]) throws(EventPackError) -> [UInt8] {
        var v = try EventDoc.parse(fullEventBytes)
        if v.variant != nil { throw .notFullEvent }
        for i in v.routes.indices { v.routes[i].polyline = nil }
        v.variant = .stopsOnly(fullHash: EventCodes.bytesHash(fullEventBytes))
        return try v.bytes()
    }

    /// Default access expiry: `end` + 24 h, in UTC (`default_expiry`).
    static func defaultExpiry(end: Date) -> Date { end.addingTimeInterval(24 * 3600) }
}

extension EventRoute {
    init(id: String, title: String?, mode: EventTravelMode, day: String?, stopIds: [String], polyline: String?,
         source: EventGeometrySource, distanceM: UInt32, durationS: UInt32?, ascentM: UInt32?, descentM: UInt32?, legs: [EventRouteLeg]) {
        self.id = id; self.title = title; self.mode = mode; self.day = day; self.stopIds = stopIds; self.polyline = polyline
        self.source = source; self.distanceM = distanceM; self.durationS = durationS; self.ascentM = ascentM
        self.descentM = descentM; self.legs = legs
    }
}


// MARK: - Route simplification (docs/events.md §5.4, route-core simplify.rs)

/// Douglas–Peucker. Distances are measured on a local equirectangular projection around each chord, in metres,
/// against the chord **segment** (not the infinite line), so every dropped point lies within the tolerance of the
/// simplified line, closed loops included. First and last points are always kept. Iterative (50 000 points can't
/// overflow the stack).
enum RouteSimplify {
    /// Tolerance writers apply before signing an event.
    static let eventToleranceM = 5.0
    static let earthRadiusM = 6_371_008.8

    /// Simplified copy; fewer than 3 points, or a tolerance that isn't positive, come back unchanged.
    static func simplify(_ points: [LatLng], toleranceM: Double = eventToleranceM) -> [LatLng] {
        let n = points.count
        guard n >= 3, toleranceM.isFinite, toleranceM > 0 else { return points }
        var keep = [Bool](repeating: false, count: n)
        keep[0] = true
        keep[n - 1] = true
        var stack = [(0, n - 1)]
        while let (first, last) = stack.popLast() {
            guard last > first + 1 else { continue }
            let chord = Chord(points[first], points[last])
            var index = first
            var maxD = -1.0
            for i in (first + 1)..<last {
                let d = chord.distanceM(points[i])
                if d > maxD { maxD = d; index = i }
            }
            if maxD > toleranceM {
                keep[index] = true
                stack.append((first, index))
                stack.append((index, last))
            }
        }
        return zip(points, keep).compactMap { $1 ? $0 : nil }
    }

    /// Decodes a precision-6 polyline, simplifies it and encodes it again (nil when it doesn't decode).
    static func polyline(_ encoded: String, toleranceM: Double = eventToleranceM) -> String? {
        guard let pts = try? EventPolyline.decode(encoded, maxPoints: EventLimits.maxPolylinePoints) else { return nil }
        return Polyline.encode(simplify(pts, toleranceM: toleranceM), precision: 6)
    }

    /// A chord projected around its start point: x east, y north, metres.
    struct Chord {
        let origin: LatLng
        let kx: Double, ky: Double
        var bx = 0.0, by = 0.0, len2 = 0.0

        init(_ a: LatLng, _ b: LatLng) {
            origin = a
            ky = RouteSimplify.earthRadiusM * .pi / 180
            kx = ky * cos((a.lat + b.lat) / 2 * .pi / 180)
            (bx, by) = project(b)
            len2 = bx * bx + by * by
        }

        func project(_ p: LatLng) -> (Double, Double) {
            var dlng = p.lng - origin.lng
            if dlng > 180 { dlng -= 360 } else if dlng < -180 { dlng += 360 }
            return (dlng * kx, (p.lat - origin.lat) * ky)
        }

        /// Distance from `p` to the chord segment, metres.
        func distanceM(_ p: LatLng) -> Double {
            let (px, py) = project(p)
            let t = len2 > 0 ? min(1, max(0, (px * bx + py * by) / len2)) : 0
            let dx = px - t * bx, dy = py - t * by
            return (dx * dx + dy * dy).squareRoot()
        }
    }
}

// MARK: - Event JSON (byte-compatible with serde_json as used by event-core)

/// Compact JSON in event-core's field order. Optional fields are omitted when absent, except `parent`
/// (always written, `null` on rev 1); `stops` is always written; `sessions` / `routes` only when non-empty.
/// Numbers and strings are formatted the way serde_json does (shortest round-trip floats with `.0` for whole
/// numbers, minimal string escaping, non-ASCII as UTF-8), so a model read from the vectors re-serialises to the
/// same bytes. Readers never depend on this: they keep and hash the signed bytes as received.
enum EventJSON {
    static func encode(_ e: EventDoc) -> [UInt8] {
        let w = Writer()
        w.object {
            w.field("id") { w.string(e.id) }
            w.field("rev") { w.int(Int(e.rev)) }
            w.field("parent") { if let p = e.parent { w.string(p) } else { w.raw("null") } }
            w.field("publishedAt") { w.string(e.publishedAt.raw) }
            w.field("template") { w.string(e.template.rawValue) }
            w.field("title") { w.string(e.title) }
            if let d = e.description { w.field("description") { w.string(d) } }
            w.field("lang") { w.string(e.lang.rawValue) }
            w.field("tz") { w.string(e.tz) }
            w.field("start") { w.string(e.start.raw) }
            w.field("end") { w.string(e.end.raw) }
            w.field("expiresAt") { w.string(e.expiresAt.raw) }
            if let o = e.organizer {
                w.field("organizer") { w.object { if let a = o.alias { w.field("alias") { w.string(a) } } } }
            }
            w.field("stops") { w.array(e.stops) { s in stop(w, s) } }
            if !e.sessions.isEmpty {
                w.field("sessions") {
                    w.array(e.sessions) { s in
                        w.object {
                            w.field("id") { w.string(s.id) }
                            w.field("title") { w.string(s.title) }
                            w.field("start") { w.string(s.start.raw) }
                            w.field("end") { w.string(s.end.raw) }
                            if let sid = s.stopId { w.field("stopId") { w.string(sid) } }
                        }
                    }
                }
            }
            if !e.routes.isEmpty { w.field("routes") { w.array(e.routes) { r in route(w, r) } } }
            // Last, like the reference writer (§5.5).
            if let v = e.variant {
                w.field("variant") { w.object { w.field("kind") { w.string(v.kind) }; w.field("fullHash") { w.string(v.fullHash) } } }
            }
        }
        return w.out
    }

    private static func stop(_ w: Writer, _ s: EventStop) {
        w.object {
            w.field("id") { w.string(s.id) }
            w.field("kind") { w.string(s.kind.rawValue) }
            w.field("label") { w.string(s.label) }
            w.field("lat") { w.double(s.lat) }
            w.field("lng") { w.double(s.lng) }
            if let en = s.entrance {
                w.field("entrance") { w.object { w.field("lat") { w.double(en.lat) }; w.field("lng") { w.double(en.lng) } } }
            }
            if let a = s.arrive {
                w.field("arrive") { w.object { w.field("from") { w.string(a.from.raw) }; w.field("to") { w.string(a.to.raw) } } }
            }
            if let d = s.dwellMin { w.field("dwellMin") { w.int(Int(d)) } }
            if let n = s.notes { w.field("notes") { w.string(n) } }
            if let a = s.audience { w.field("audience") { w.string(a) } }
        }
    }

    private static func route(_ w: Writer, _ r: EventRoute) {
        w.object {
            w.field("id") { w.string(r.id) }
            if let t = r.title { w.field("title") { w.string(t) } }
            w.field("mode") { w.string(r.mode.rawValue) }
            if let d = r.day { w.field("day") { w.string(d) } }
            w.field("stopIds") { w.array(r.stopIds) { w.string($0) } }
            if let p = r.polyline { w.field("polyline") { w.string(p) } }
            w.field("source") { w.string(r.source.rawValue) }
            w.field("distanceM") { w.int(Int(r.distanceM)) }
            if let v = r.durationS { w.field("durationS") { w.int(Int(v)) } }
            if let v = r.ascentM { w.field("ascentM") { w.int(Int(v)) } }
            if let v = r.descentM { w.field("descentM") { w.int(Int(v)) } }
            w.field("legs") {
                w.array(r.legs) { l in
                    w.object {
                        w.field("fromStopId") { w.string(l.fromStopId) }
                        w.field("toStopId") { w.string(l.toStopId) }
                        w.field("distanceM") { w.int(Int(l.distanceM)) }
                        if let v = l.durationS { w.field("durationS") { w.int(Int(v)) } }
                        w.field("source") { w.string(l.source.rawValue) }
                    }
                }
            }
        }
    }

    /// serde_json float text: shortest round-trip digits; whole numbers keep `.0`; exponents as `1e-7` / `1e16`.
    static func formatDouble(_ v: Double) -> String {
        guard v.isFinite else { return "null" }
        var s = "\(v)"   // Swift prints the shortest round-trip representation
        if let e = s.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            var mantissa = String(s[..<e])
            var exp = String(s[s.index(after: e)...])
            if mantissa.hasSuffix(".0") { mantissa.removeLast(2) }
            let neg = exp.hasPrefix("-")
            exp = String(exp.drop { $0 == "-" || $0 == "+" }.drop { $0 == "0" })
            s = "\(mantissa)e\(neg ? "-" : "")\(exp.isEmpty ? "0" : exp)"
        } else if !s.contains(".") {
            s += ".0"
        }
        return s
    }

    private final class Writer {
        var out: [UInt8] = []
        private var needsComma: [Bool] = []

        func raw(_ s: String) { out += Array(s.utf8) }

        /// A value inside an array needs a comma before it unless it is the first.
        private func separator() {
            if let last = needsComma.last {
                if last { out.append(UInt8(ascii: ",")) }
                needsComma[needsComma.count - 1] = true
            }
        }

        func object(_ body: () -> Void) {
            out.append(UInt8(ascii: "{"))
            needsComma.append(false)
            body()
            needsComma.removeLast()
            out.append(UInt8(ascii: "}"))
        }

        func array<T>(_ items: [T], _ each: (T) -> Void) {
            out.append(UInt8(ascii: "["))
            for (i, item) in items.enumerated() {
                if i > 0 { out.append(UInt8(ascii: ",")) }
                needsComma.append(false)
                each(item)
                needsComma.removeLast()
            }
            out.append(UInt8(ascii: "]"))
        }

        func field(_ name: String, _ value: () -> Void) {
            separator()
            string(name)
            out.append(UInt8(ascii: ":"))
            needsComma.append(false)
            value()
            needsComma.removeLast()
        }

        func string(_ s: String) {
            out.append(UInt8(ascii: "\""))
            for b in s.utf8 {
                switch b {
                case 0x22: out += Array("\\\"".utf8)
                case 0x5C: out += Array("\\\\".utf8)
                case 0x0A: out += Array("\\n".utf8)
                case 0x0D: out += Array("\\r".utf8)
                case 0x09: out += Array("\\t".utf8)
                case 0x08: out += Array("\\b".utf8)
                case 0x0C: out += Array("\\f".utf8)
                case 0..<0x20: out += Array(String(format: "\\u%04x", b).utf8)
                default: out.append(b)
                }
            }
            out.append(UInt8(ascii: "\""))
        }

        func int(_ v: Int) { raw(String(v)) }
        func double(_ v: Double) { raw(EventJSON.formatDouble(v)) }
    }
}
