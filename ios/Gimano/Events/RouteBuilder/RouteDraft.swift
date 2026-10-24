import Foundation

// "Bina laluan" (route builder): the editable route, mirroring the desktop reference
// (`desktop/src-tauri/src/editing.rs` edit rules, `assemble.rs` publishing, `route-core` polyline/geo/gpx).
//
// Privacy: a route draft lives only on this phone (an event's draft in the events database, or "Laluan saya" in the
// personal store, which is also in the user's own backup).
// Routing sends the two leg ends' coordinates and the travel mode only (`"store": false`), never a name.

/// Why a leg couldn't be calculated (desktop `problem` codes).
enum LegProblem: String, Codable, Sendable, Hashable {
    /// The phone is offline or the server can't be reached.
    case offline
    /// Demo data mode: there is no server, so roads are never calculated (not a connection problem).
    case demo
    /// 422: no road between the points / nothing matched.
    case noRoute
    /// 503: routing engine down.
    case unavailable
    /// `/routes/match` isn't on this server yet (404/405).
    case matchUnavailable
    /// Any other status or an unreadable answer.
    case failed
}

/// Desktop `LegState` without GPX (iOS doesn't import GPX yet).
enum LegState: String, Codable, Sendable, Hashable {
    /// Waiting for the routing server ("perlu dikira").
    case pending
    /// `POST /routes` geometry.
    case routed
    /// `POST /routes/match` geometry of a drawn line.
    case matched
    /// Straight line or the drawn line as is ("lakaran"): not a road.
    case sketch
    /// The server couldn't calculate it (see `problem`).
    case unresolved

    var isResolved: Bool { self == .routed || self == .matched || self == .sketch }
}

/// Inclusive indices into a leg's drawn line that matched no road.
struct UnmatchedRange: Codable, Hashable, Sendable {
    var from: Int
    var to: Int
}

struct BuilderPoint: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var lat: Double
    var lng: Double
    /// The event stop this point is (guests see it in the plan). nil = a shaping point (route only).
    var stopId: String?

    init(id: String = EventRandom.itemId(), _ p: LatLng, stopId: String? = nil) {
        self.id = id
        self.lat = RouteGeo.round6(p.lat)
        self.lng = RouteGeo.round6(p.lng)
        self.stopId = stopId
    }

    var location: LatLng {
        get { LatLng(lat: lat, lng: lng) }
        set { lat = RouteGeo.round6(newValue.lat); lng = RouteGeo.round6(newValue.lng) }
    }
}

struct BuilderLeg: Hashable, Sendable {
    var state: LegState = .pending
    /// New for every leg version (random): a late server answer for an older version is dropped.
    var generation = BuilderLeg.nextGeneration()
    var geometry: [LatLng] = []
    var distanceM: Double = 0
    var durationS: Double?
    /// The finger-drawn line ("Lukis"), matched to roads when "Ikut jalan" is on.
    var drawn: [LatLng]?
    var unmatched: [UnmatchedRange] = []
    var problem: LegProblem?

    /// Desktop `leg_source`: matched with gaps and sketches are sketches; pending/unresolved have none.
    var source: EventGeometrySource? {
        switch state {
        case .routed: .routed
        case .matched: unmatched.isEmpty ? .routed : .sketch
        case .sketch: .sketch
        case .pending, .unresolved: nil
        }
    }

    var isStraightSketch: Bool { state == .sketch && drawn == nil }

    static func nextGeneration() -> Int { Int.random(in: 1...Int.max) }
}

extension BuilderLeg: Codable {
    private enum K: String, CodingKey { case state, generation, geometry, distanceM, durationS, drawn, unmatched, problem }

    // Geometry is stored as precision-6 polylines (points are already rounded to 6 decimals, so this is lossless).
    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        state = try c.decode(LegState.self, forKey: .state)
        generation = try c.decodeIfPresent(Int.self, forKey: .generation) ?? BuilderLeg.nextGeneration()
        geometry = Polyline.decode(try c.decodeIfPresent(String.self, forKey: .geometry) ?? "")
        distanceM = try c.decodeIfPresent(Double.self, forKey: .distanceM) ?? 0
        durationS = try c.decodeIfPresent(Double.self, forKey: .durationS)
        drawn = try c.decodeIfPresent(String.self, forKey: .drawn).map { Polyline.decode($0) }
        unmatched = try c.decodeIfPresent([UnmatchedRange].self, forKey: .unmatched) ?? []
        problem = try c.decodeIfPresent(LegProblem.self, forKey: .problem)
        // A leg that was waiting when the app closed is still waiting.
    }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(state, forKey: .state)
        try c.encode(generation, forKey: .generation)
        try c.encode(Polyline.encode(geometry), forKey: .geometry)
        try c.encode(distanceM, forKey: .distanceM)
        try c.encodeIfPresent(durationS, forKey: .durationS)
        try c.encodeIfPresent(drawn.map { Polyline.encode($0) }, forKey: .drawn)
        if !unmatched.isEmpty { try c.encode(unmatched, forKey: .unmatched) }
        try c.encodeIfPresent(problem, forKey: .problem)
    }
}

/// What a finished server call says about one leg.
enum LegResolution: Equatable, Sendable {
    case routed(geometry: [LatLng], distanceM: Double, durationS: Double?)
    case matched(geometry: [LatLng], distanceM: Double, durationS: Double?, unmatched: [UnmatchedRange])
    case failed(LegProblem)
}

enum RouteEditError: Error, Equatable {
    case needsTwoPoints
    case alreadyClosed
}

/// One route being built: ordered points and one leg between each pair (`legs.count == points.count - 1`).
struct RouteDraft: Codable, Hashable, Sendable, Identifiable {
    var id: String = EventRandom.itemId()
    var title: String = ""
    var modeRaw: String = EventTravelMode.car.rawValue
    /// Optional local date (`YYYY-MM-DD`) for multi-day events.
    var day: String?
    var followRoads = true
    var points: [BuilderPoint] = []
    var legs: [BuilderLeg] = []

    var mode: EventTravelMode {
        get { EventTravelMode(rawValue: modeRaw) ?? .car }
        set { modeRaw = newValue.rawValue }
    }

    var isEmpty: Bool { points.isEmpty }

    // MARK: edits (desktop editing.rs)

    /// Appends a point and a leg to it (routed later, or a straight sketch with "Ikut jalan" off).
    mutating func addPoint(_ p: LatLng, stopId: String? = nil) {
        points.append(BuilderPoint(p, stopId: stopId))
        if points.count > 1 { legs.append(BuilderLeg()) }
        settleOffline()
    }

    /// Splits leg `i` with a new point (a tap on the line).
    mutating func insertPoint(_ p: LatLng, inLeg i: Int) {
        guard legs.indices.contains(i) else { return }
        points.insert(BuilderPoint(p), at: i + 1)
        legs[i] = BuilderLeg()
        legs.insert(BuilderLeg(), at: i + 1)
        settleOffline()
    }

    /// Moves a point (drag, or a stop it is linked to moved); its two legs are calculated again.
    mutating func movePoint(_ i: Int, to p: LatLng) {
        guard points.indices.contains(i) else { return }
        points[i].location = p
        invalidate(i - 1, dropDrawn: true)
        invalidate(i, dropDrawn: true)
        settleOffline()
    }

    /// Deleting an inner point merges its two legs into one new leg.
    mutating func deletePoint(_ i: Int) {
        guard points.indices.contains(i) else { return }
        points.remove(at: i)
        guard !legs.isEmpty else { return }
        if i == 0 {
            legs.removeFirst()
        } else if i >= legs.count {
            legs.removeLast()
        } else {
            legs.remove(at: i)
            invalidate(i - 1, dropDrawn: true)
        }
        settleOffline()
    }

    /// Reorders the point list (the non-map path for accessibility): the legs around both positions are redone.
    mutating func movePointInList(_ i: Int, by offset: Int) {
        let j = i + offset
        guard points.indices.contains(i), points.indices.contains(j), i != j else { return }
        points.swapAt(i, j)
        for k in Set([i - 1, i, j - 1, j]) { invalidate(k, dropDrawn: true) }
        settleOffline()
    }

    /// Reverses the direction. Road geometry is calculated again (one-way streets); sketches are just reversed.
    mutating func reverse() throws(RouteEditError) {
        guard points.count >= 2 else { throw .needsTwoPoints }
        points.reverse()
        legs.reverse()
        for i in legs.indices {
            switch legs[i].state {
            case .sketch:
                legs[i].geometry.reverse()
                legs[i].drawn?.reverse()
                legs[i].unmatched = []
            default:
                legs[i].drawn?.reverse()
                invalidate(i, dropDrawn: false)
            }
        }
        settleOffline()
    }

    /// "Kembali ke mula": a copy of the first point (same stop) at the end.
    mutating func closeLoop() throws(RouteEditError) {
        guard points.count >= 2, let first = points.first, let last = points.last else { throw .needsTwoPoints }
        if first.lat == last.lat && first.lng == last.lng { throw .alreadyClosed }
        points.append(BuilderPoint(first.location, stopId: first.stopId))
        legs.append(BuilderLeg())
        settleOffline()
    }

    /// "Pergi-balik": copies of points n-2 … 0 appended, each with a new leg.
    mutating func outAndBack() throws(RouteEditError) {
        guard points.count >= 2 else { throw .needsTwoPoints }
        for p in points.dropLast().reversed() {
            points.append(BuilderPoint(p.location, stopId: p.stopId))
            legs.append(BuilderLeg())
        }
        settleOffline()
    }

    /// "Kosongkan".
    mutating func clear() {
        points = []
        legs = []
    }

    /// A finished finger drawing (at least 2 points). It continues from the last point, or starts the route.
    mutating func draw(_ line: [LatLng]) throws(RouteEditError) {
        let line = line.map { LatLng(lat: RouteGeo.round6($0.lat), lng: RouteGeo.round6($0.lng)) }
        guard line.count >= 2, let end = line.last else { throw .needsTwoPoints }
        var drawn = line
        if let last = points.last {
            drawn.insert(last.location, at: 0)
        } else {
            points.append(BuilderPoint(line[0]))
        }
        points.append(BuilderPoint(end))
        legs.append(BuilderLeg(drawn: RouteGeo.thin(drawn, max: RouteBuilderService.maxMatchPoints)))
        settleOffline()
    }

    /// "Simpan sebagai lakaran": the drawn line as it is (or a straight line). Not a road, no duration.
    mutating func keepAsSketch(_ i: Int) {
        guard legs.indices.contains(i) else { return }
        let line = legs[i].drawn ?? [points[i].location, points[i + 1].location]
        legs[i] = BuilderLeg(state: .sketch, geometry: line,
                             distanceM: RouteGeo.length(line), durationS: nil, drawn: legs[i].drawn)
    }

    /// "Simpan sebagai lakaran" for every leg still waiting or failed, in one edit (one undo step in the builder).
    /// Road, matched and sketch legs stay as they are.
    mutating func keepOpenLegsAsSketch() {
        for i in legs.indices where legs[i].state == .pending || legs[i].state == .unresolved { keepAsSketch(i) }
    }

    /// "Tambah titik" for a drawing with gaps: points at the ends of every unmatched stretch, routed legs between.
    mutating func splitAtUnmatched(_ i: Int) {
        guard legs.indices.contains(i), let drawn = legs[i].drawn, !legs[i].unmatched.isEmpty else { return }
        var cut = Set<Int>()
        for r in legs[i].unmatched {
            cut.insert(max(0, r.from - 1))
            cut.insert(min(drawn.count - 1, r.to + 1))
        }
        let inner = cut.filter { $0 > 0 && $0 < drawn.count - 1 }.sorted()
        let newPoints = inner.map { BuilderPoint(drawn[$0]) }
        points.insert(contentsOf: newPoints, at: i + 1)
        legs.replaceSubrange(i...i, with: (0...newPoints.count).map { _ in BuilderLeg() })
        settleOffline()
    }

    /// "Cuba lagi".
    mutating func retry(_ i: Int) {
        invalidate(i, dropDrawn: false)
        settleOffline()
    }

    mutating func retryAll() {
        for i in legs.indices where legs[i].state == .unresolved { invalidate(i, dropDrawn: false) }
        settleOffline()
    }

    /// A new travel mode: road and matched legs are calculated again for it.
    mutating func setMode(_ m: EventTravelMode) {
        guard m != mode else { return }
        mode = m
        for i in legs.indices where [.routed, .matched, .unresolved].contains(legs[i].state) {
            invalidate(i, dropDrawn: false)
        }
        settleOffline()
    }

    /// "Ikut jalan": turning it back on routes the straight sketches again.
    mutating func setFollowRoads(_ on: Bool) {
        guard on != followRoads else { return }
        followRoads = on
        if on {
            for i in legs.indices where legs[i].isStraightSketch { invalidate(i, dropDrawn: false) }
        }
        settleOffline()
    }

    /// Links point `i` to an event stop: the point moves onto the stop.
    mutating func link(_ i: Int, to stop: HostedStopDraft) {
        guard points.indices.contains(i) else { return }
        let moved = points[i].location != stop.location
        points[i].stopId = stop.id
        if moved { movePoint(i, to: stop.location) }
    }

    mutating func unlink(_ i: Int) {
        guard points.indices.contains(i) else { return }
        points[i].stopId = nil
    }

    /// A stop was moved in the event editor: every point linked to it follows.
    mutating func syncStop(_ stop: HostedStopDraft) {
        for i in points.indices where points[i].stopId == stop.id && points[i].location != stop.location {
            movePoint(i, to: stop.location)
        }
    }

    /// A stop was removed from the event: its points stay as shaping points.
    mutating func unlinkStops(notIn ids: Set<String>) {
        for i in points.indices where points[i].stopId.map({ !ids.contains($0) }) ?? false { points[i].stopId = nil }
    }

    /// The server answered for the leg version `generation`. Dropped when that leg changed or is gone meanwhile.
    @discardableResult
    mutating func apply(_ r: LegResolution, generation: Int) -> Bool {
        guard let i = legs.firstIndex(where: { $0.generation == generation }), legs[i].state == .pending else { return false }
        switch r {
        case .routed(let g, let d, let t):
            legs[i].state = .routed
            legs[i].geometry = g.map(RouteGeo.round6)
            legs[i].distanceM = d
            legs[i].durationS = t
            legs[i].unmatched = []
            legs[i].problem = nil
        case .matched(let g, let d, let t, let u):
            legs[i].state = .matched
            legs[i].geometry = g.map(RouteGeo.round6)
            legs[i].distanceM = d
            legs[i].durationS = t
            legs[i].unmatched = u
            legs[i].problem = nil
        case .failed(.matchUnavailable):
            // No map matching on this server yet: keep the drawing as a sketch (labelled), never as a road.
            keepAsSketch(i)
            legs[i].problem = .matchUnavailable
        case .failed(let p):
            legs[i].state = .unresolved
            legs[i].problem = p
        }
        return true
    }

    /// Legs waiting for the server: (index, generation, from, to, drawn line).
    var pendingLegs: [(index: Int, generation: Int, from: LatLng, to: LatLng, drawn: [LatLng]?)] {
        legs.indices.compactMap { i in
            legs[i].state == .pending ? (i, legs[i].generation, points[i].location, points[i + 1].location, legs[i].drawn) : nil
        }
    }

    private mutating func invalidate(_ i: Int, dropDrawn: Bool) {
        guard legs.indices.contains(i) else { return }
        legs[i] = BuilderLeg(drawn: dropDrawn ? nil : legs[i].drawn)
    }

    /// With "Ikut jalan" off, waiting legs become straight sketches (or the drawn line) at once.
    private mutating func settleOffline() {
        guard !followRoads else { return }
        for i in legs.indices where legs[i].state == .pending { keepAsSketch(i) }
    }

    // MARK: totals (desktop assemble.rs)

    var isComplete: Bool { !legs.isEmpty && legs.allSatisfy(\.state.isResolved) }
    var hasUnmatched: Bool { legs.contains { $0.state == .matched && !$0.unmatched.isEmpty } }
    var needsCalculation: Int { legs.filter { $0.state == .pending || $0.state == .unresolved }.count }
    var hasSketch: Bool { legs.contains { $0.source == .sketch } }

    /// Sum of the calculated legs.
    var distanceM: Double { legs.filter(\.state.isResolved).reduce(0) { $0 + $1.distanceM } }

    /// Only when every leg is calculated and has a time (sketches have none): else "Tidak tersedia".
    var durationS: Double? {
        guard isComplete else { return nil }
        var total = 0.0
        for l in legs {
            guard let d = l.durationS else { return nil }
            total += d
        }
        return total
    }

    /// gpx > sketch > routed; nil while any leg isn't calculated.
    var source: EventGeometrySource? { RouteDraft.combine(legs.map(\.source)) }

    static func combine(_ sources: [EventGeometrySource?]) -> EventGeometrySource? {
        var out: EventGeometrySource?
        for s in sources {
            guard let s else { return nil }
            switch (out, s) {
            case (.gpx, _), (_, .gpx): out = .gpx
            case (.sketch, _), (_, .sketch): out = .sketch
            default: out = .routed
            }
        }
        return out
    }

    /// Geometry of the calculated legs joined; a leg's first point is skipped when it repeats the previous last.
    var joinedGeometry: [LatLng] {
        var out: [LatLng] = []
        for l in legs where l.state.isResolved {
            for (k, p) in l.geometry.map(RouteGeo.round6).enumerated() {
                if k == 0, let last = out.last, last == p { continue }
                out.append(p)
            }
        }
        return out
    }

    var polylinePointCount: Int { joinedGeometry.count }
}

// MARK: - Publishing into an event (desktop assemble.rs)

enum RouteAssemblyIssue: Error, Equatable, Sendable {
    /// Fewer than two points: the route is left out.
    case tooFewPoints
    /// Some legs aren't calculated yet ("perlu dikira").
    case unresolved
    /// Part of a drawing matched no road: redraw, add a point or keep it as a sketch.
    case unmatched
}

extension RouteDraft {
    /// The event route guests receive: stops in order, legs only between consecutive stops (shaping points merged
    /// into them), precision-6 polyline. Coordinates and modes only; titles trimmed.
    func eventRoute(knownStops: Set<String>) -> Result<EventRoute, RouteAssemblyIssue> {
        guard points.count >= 2 else { return .failure(.tooFewPoints) }
        guard isComplete, let source else { return .failure(.unresolved) }
        if hasUnmatched { return .failure(.unmatched) }
        let geometry = joinedGeometry
        guard geometry.count >= 2 else { return .failure(.tooFewPoints) }

        let stopOf = points.map { p in p.stopId.flatMap { knownStops.contains($0) ? $0 : nil } }
        var stopIds: [String] = []
        for s in stopOf.compactMap({ $0 }) where stopIds.last != s { stopIds.append(s) }

        var eventLegs: [EventRouteLeg] = []
        var lastStop: Int?
        for (i, s) in stopOf.enumerated() {
            guard let s else { continue }
            if let from = lastStop, let fromId = stopOf[from] {
                let part = Array(legs[from..<i])
                let dist = part.reduce(0) { $0 + $1.distanceM }
                let dur: Double? = part.allSatisfy { $0.durationS != nil } ? part.reduce(0) { $0 + ($1.durationS ?? 0) } : nil
                eventLegs.append(EventRouteLeg(fromStopId: fromId, toStopId: s, distanceM: RouteGeo.meters(dist),
                                               durationS: dur.map(RouteGeo.meters),
                                               source: RouteDraft.combine(part.map(\.source)) ?? .sketch))
            }
            lastStop = i
        }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return .success(EventRoute(
            id: id, title: t.isEmpty ? nil : String(t.unicodeScalars.prefix(EventLimits.routeTitleChars).map(Character.init)),
            mode: mode, day: day, stopIds: stopIds, polyline: Polyline.encode(geometry), source: source,
            distanceM: RouteGeo.meters(distanceM), durationS: durationS.map(RouteGeo.meters),
            // The phone never writes climb/descent (no elevation): readers accept them from other writers.
            ascentM: nil, descentM: nil, legs: eventLegs))
    }
}

// MARK: - Geo (route-core geo.rs)

enum RouteGeo {
    /// `(v * 1e6).round() / 1e6`, half away from zero like Rust's `f64::round`.
    static func round6(_ v: Double) -> Double { (v * 1e6).rounded(.toNearestOrAwayFromZero) / 1e6 }
    static func round6(_ p: LatLng) -> LatLng { LatLng(lat: round6(p.lat), lng: round6(p.lng)) }

    /// Haversine, R = 6 371 008.8 m (same as `Geo.distance`).
    static func length(_ pts: [LatLng]) -> Double {
        zip(pts, pts.dropFirst()).reduce(0) { $0 + Geo.distance($1.0, $1.1) }
    }

    /// Non-negative whole metres / seconds for the event JSON.
    static func meters(_ v: Double) -> UInt32 {
        guard v.isFinite, v > 0 else { return 0 }
        return UInt32(min(v.rounded(), Double(UInt32.max)))
    }

    /// Evenly resamples to at most `max` points, keeping both ends (desktop `thin`).
    static func thin(_ pts: [LatLng], max: Int) -> [LatLng] {
        guard pts.count > max, max >= 2 else { return pts }
        let step = Double(pts.count - 1) / Double(max - 1)
        return (0..<max).map { pts[Int((Double($0) * step).rounded())] }
    }
}

// MARK: - Undo / redo (one snapshot per edit, 100 kept)

struct EditHistory<State: Equatable> {
    static var limit: Int { 100 }
    private(set) var undoStack: [State] = []
    private(set) var redoStack: [State] = []

    var canUndo: Bool { !undoStack.isEmpty }
    var canRedo: Bool { !redoStack.isEmpty }

    /// Call before applying an edit with the state as it was.
    mutating func record(_ before: State) {
        undoStack.append(before)
        if undoStack.count > Self.limit { undoStack.removeFirst(undoStack.count - Self.limit) }
        redoStack = []
    }

    mutating func undo(_ current: State) -> State? {
        guard let s = undoStack.popLast() else { return nil }
        redoStack.append(current)
        return s
    }

    mutating func redo(_ current: State) -> State? {
        guard let s = redoStack.popLast() else { return nil }
        undoStack.append(current)
        return s
    }
}

// MARK: - GPX 1.1 (route-core gpx.rs `export_gpx`, no metadata/elevation/times)

enum GPXWriter {
    struct Waypoint: Equatable {
        var location: LatLng
        var name: String?
    }

    static func write(trackName: String?, track: [LatLng], waypoints: [Waypoint] = []) -> String {
        var x = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        x += "<gpx version=\"1.1\" creator=\"Manotu\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
        for w in waypoints { point(&x, "wpt", w.location, name: w.name, indent: "  ") }
        x += "  <trk>\n"
        if let n = trackName, !n.isEmpty { x += "    <name>\(escape(n))</name>\n" }
        x += "    <trkseg>\n"
        for p in track { point(&x, "trkpt", p, name: nil, indent: "      ") }
        x += "    </trkseg>\n"
        x += "  </trk>\n"
        x += "</gpx>\n"
        return x
    }

    private static func point(_ x: inout String, _ tag: String, _ p: LatLng, name: String?, indent: String) {
        let head = "\(indent)<\(tag) lat=\"\(fmt6(p.lat))\" lon=\"\(fmt6(p.lng))\""
        guard let name, !name.isEmpty else { x += head + "/>\n"; return }
        x += head + ">\n"
        x += "\(indent)  <name>\(escape(name))</name>\n"
        x += "\(indent)</\(tag)>\n"
    }

    /// `{:.6}` with trailing zeros and "." trimmed; "-0" → "0".
    static func fmt6(_ v: Double) -> String {
        var s = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), v)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s == "-0" ? "0" : s
    }

    static func escape(_ s: String) -> String {
        var o = ""
        for c in s.unicodeScalars {
            switch c {
            case "&": o += "&amp;"
            case "<": o += "&lt;"
            case ">": o += "&gt;"
            case "\"": o += "&quot;"
            case "'": o += "&apos;"
            case "\t", "\n", "\r": o.unicodeScalars.append(c)
            default:
                if c.properties.generalCategory == .control { o += " " } else { o.unicodeScalars.append(c) }
            }
        }
        return o
    }

    /// The route's track (calculated legs only) and its event stops as named waypoints.
    static func gpx(_ route: RouteDraft, name: String?, stops: [HostedStopDraft] = []) -> String {
        let used = Set(route.points.compactMap(\.stopId))
        let wpts = stops.filter { used.contains($0.id) }.map { Waypoint(location: $0.location, name: $0.label) }
        return write(trackName: name, track: route.joinedGeometry, waypoints: wpts)
    }
}
