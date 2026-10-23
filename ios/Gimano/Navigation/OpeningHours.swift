import Foundation

/// Week schedule edited in the app and converted to/from OSM `opening_hours`
/// (e.g. "Mo-Fr 08:00-18:00; Sa 08:00-13:00; Su off", "24/7", "Mo-Su 07:00-12:00,14:00-22:00").
struct OpeningHours: Equatable, Sendable {
    enum Day: Int, CaseIterable, Sendable, Identifiable, Comparable {
        case mo, tu, we, th, fr, sa, su
        var id: Int { rawValue }
        var osm: String { ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"][rawValue] }
        static func < (a: Day, b: Day) -> Bool { a.rawValue < b.rawValue }
        /// "Mon" / "Isn" in the app language (Monday-first index → Sunday-first symbols).
        var shortName: String {
            var cal = Calendar(identifier: .gregorian)
            cal.locale = Locale.current
            let symbols = cal.shortStandaloneWeekdaySymbols
            return symbols.indices.contains((rawValue + 1) % 7) ? symbols[(rawValue + 1) % 7] : osm
        }
        init?(osm: String) {
            guard let i = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"].firstIndex(of: osm) else { return nil }
            self = Day(rawValue: i)!
        }
    }

    /// Minutes since midnight; `close` may be ≤ `open` for overnight ranges, and 1440 means 24:00.
    struct Range: Equatable, Hashable, Sendable {
        var open: Int
        var close: Int

        init(open: Int, close: Int) {
            self.open = open
            self.close = close
        }

        init?(_ s: String) {
            let parts = s.split(separator: "-")
            guard parts.count == 2, let o = Self.minutes(String(parts[0])), let c = Self.minutes(String(parts[1])) else { return nil }
            open = o
            close = c
        }

        static func minutes(_ hhmm: String) -> Int? {
            let p = hhmm.trimmingCharacters(in: .whitespaces).split(separator: ":")
            guard p.count == 2, let h = Int(p[0]), let m = Int(p[1]), (0...24).contains(h), (0..<60).contains(m), h * 60 + m <= 1440 else { return nil }
            return h * 60 + m
        }

        static func hhmm(_ minutes: Int) -> String { String(format: "%02d:%02d", minutes / 60, minutes % 60) }

        var osm: String { "\(Self.hhmm(open))-\(Self.hhmm(close))" }
        var isAllDay: Bool { open == 0 && close == 1440 }
    }

    /// Ranges per day; an empty array means closed ("off").
    var days: [Day: [Range]]

    init(days: [Day: [Range]] = [:]) {
        self.days = days
    }

    static func uniform(_ ranges: [Range], on days: [Day] = Day.allCases) -> OpeningHours {
        var d: [Day: [Range]] = [:]
        for day in Day.allCases { d[day] = days.contains(day) ? ranges : [] }
        return OpeningHours(days: d)
    }

    static let allDay = OpeningHours.uniform([Range(open: 0, close: 1440)])

    func ranges(_ day: Day) -> [Range] { days[day] ?? [] }

    var isAlwaysOpen: Bool { Day.allCases.allSatisfy { ranges($0) == [Range(open: 0, close: 1440)] } }

    /// OSM `opening_hours` string. Groups days with identical hours; consecutive runs of 3+ use "Mo-Fr",
    /// shorter or scattered groups use commas ("Sa,Su", "Mo,We,Fr").
    var osm: String {
        if isAlwaysOpen { return "24/7" }
        var groups: [(days: [Day], ranges: [Range])] = []
        for day in Day.allCases {
            let r = ranges(day)
            if let i = groups.firstIndex(where: { $0.ranges == r }) { groups[i].days.append(day) } else { groups.append(([day], r)) }
        }
        // Omit an all-closed week entirely.
        if groups.count == 1 && groups[0].ranges.isEmpty { return "" }
        return groups.map { g in
            let spec = g.ranges.isEmpty ? "off" : g.ranges.map(\.osm).joined(separator: ",")
            return "\(Self.daySpec(g.days)) \(spec)"
        }.joined(separator: "; ")
    }

    static func daySpec(_ days: [Day]) -> String {
        let sorted = days.sorted()
        var runs: [[Day]] = []
        for d in sorted {
            if let last = runs.last?.last, last.rawValue + 1 == d.rawValue { runs[runs.count - 1].append(d) } else { runs.append([d]) }
        }
        return runs.map { run in
            run.count >= 3 ? "\(run.first!.osm)-\(run.last!.osm)" : run.map(\.osm).joined(separator: ",")
        }.joined(separator: ",")
    }

    /// Parses the subset of OSM syntax the app writes (day ranges/lists, time ranges, "off", "24/7").
    /// Returns nil for anything richer (public holidays, months, comments); the app then keeps the raw string.
    static func parse(_ raw: String) -> OpeningHours? {
        let s = raw.trimmingCharacters(in: .whitespaces)
        if s.isEmpty { return OpeningHours.uniform([]) }
        if s == "24/7" { return .allDay }
        var result = OpeningHours.uniform([])
        for rule in s.split(separator: ";").map({ $0.trimmingCharacters(in: .whitespaces) }) where !rule.isEmpty {
            let parts = rule.split(separator: " ", maxSplits: 1).map(String.init)
            var daysPart = "Mo-Su"
            var timesPart = rule
            if parts.count == 2, parts[0].first?.isLetter == true {
                daysPart = parts[0]
                timesPart = parts[1]
            } else if parts.count == 1, parts[0].first?.isLetter == true, parts[0] != "off" {
                return nil
            }
            guard let days = parseDays(daysPart) else { return nil }
            let ranges: [Range]
            if timesPart == "off" || timesPart == "closed" {
                ranges = []
            } else {
                let rs = timesPart.split(separator: ",").map { Range(String($0)) }
                guard !rs.isEmpty, rs.allSatisfy({ $0 != nil }) else { return nil }
                ranges = rs.compactMap { $0 }
            }
            for d in days { result.days[d] = ranges }
        }
        return result
    }

    static func parseDays(_ spec: String) -> [Day]? {
        var out: [Day] = []
        for item in spec.split(separator: ",") {
            let ends = item.split(separator: "-").map(String.init)
            if ends.count == 2, let a = Day(osm: ends[0]), let b = Day(osm: ends[1]) {
                var i = a.rawValue
                while true {
                    out.append(Day(rawValue: i)!)
                    if i == b.rawValue { break }
                    i = (i + 1) % 7 // "Fr-Mo" wraps
                }
            } else if ends.count == 1, let d = Day(osm: ends[0]) {
                out.append(d)
            } else {
                return nil
            }
        }
        return out
    }
}
