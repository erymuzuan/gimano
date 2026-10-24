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
         organizer: EventOrganizer? = nil, stops: [EventStop], sessions: [EventSession] = [], routes: [EventRoute] = []) {
        self.id = id; self.rev = rev; self.parent = parent; self.publishedAt = publishedAt; self.template = template
        self.title = title; self.description = description; self.lang = lang; self.tz = tz; self.start = start
        self.end = end; self.expiresAt = expiresAt; self.organizer = organizer; self.stops = stops
        self.sessions = sessions; self.routes = routes
    }

    /// The writer's bytes for this event (validated first, like `Event::to_bytes`).
    func bytes() throws(EventPackError) -> [UInt8] {
        try EventValidation.validate(self)
        return EventJSON.encode(self)
    }

    /// `next_revision`: rev + 1, `parent` = SHA-256 of the previous signed bytes, new `publishedAt`; same id.
    func nextRevision(previousBytes: [UInt8], publishedAt: Date) -> EventDoc {
        var e = self
        e.rev = rev &+ 1 == 0 ? rev : rev + 1
        e.parent = B64u.encode(EventCodes.revisionHash(previousBytes))
        e.publishedAt = .utc(publishedAt)
        return e
    }

    /// Default access expiry: `end` + 24 h, in UTC (`default_expiry`).
    static func defaultExpiry(end: Date) -> Date { end.addingTimeInterval(24 * 3600) }
}

extension EventRoute {
    init(id: String, title: String?, mode: EventTravelMode, day: String?, stopIds: [String], polyline: String,
         source: EventGeometrySource, distanceM: UInt32, durationS: UInt32?, ascentM: UInt32?, descentM: UInt32?, legs: [EventRouteLeg]) {
        self.id = id; self.title = title; self.mode = mode; self.day = day; self.stopIds = stopIds; self.polyline = polyline
        self.source = source; self.distanceM = distanceM; self.durationS = durationS; self.ascentM = ascentM
        self.descentM = descentM; self.legs = legs
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
            w.field("polyline") { w.string(r.polyline) }
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
