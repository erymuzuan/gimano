import Foundation

/// Decoded route geometry with cumulative distances.
struct RouteGeometry: Sendable {
    let points: [LatLng]
    let cumulative: [Double]
    var totalM: Double { cumulative.last ?? 0 }

    init(points: [LatLng]) {
        self.points = points
        self.cumulative = Geo.cumulativeDistances(points)
    }

    init(route: Route) {
        self.init(points: Polyline.decode(route.polyline))
    }

    func distance(atIndex i: Int) -> Double {
        guard !cumulative.isEmpty else { return 0 }
        return cumulative[min(max(0, i), cumulative.count - 1)]
    }
}

struct Snap: Sendable, Equatable {
    var segment: Int
    var distanceAlongM: Double
    var distanceFromRouteM: Double
    var point: LatLng
}

/// Progress along the active route, derived from one location fix.
struct RouteProgress: Sendable, Equatable {
    var snap: Snap
    var remainingM: Double
    var remainingS: Double
    /// Index into the flattened maneuver list of the next maneuver ahead.
    var upcomingIndex: Int?
    var distanceToUpcomingM: Double
    /// Maneuver after the upcoming one, only when it follows closely ("then" strip).
    var thenIndex: Int?
    var arrived: Bool
}

struct RouteTracker: Sendable {
    let route: Route
    let geometry: RouteGeometry
    let maneuvers: [Maneuver]
    private(set) var lastSegment = 0

    /// Distance between upcoming and following maneuver under which the "then" strip is shown.
    var thenThresholdM: Double = 500
    var arrivalThresholdM: Double = 30

    init(route: Route) {
        self.route = route
        self.geometry = RouteGeometry(route: route)
        self.maneuvers = route.maneuvers.sorted { $0.beginShapeIndex < $1.beginShapeIndex }
    }

    /// Snap a location to the polyline, searching a forward-biased window first.
    func snap(_ p: LatLng, hint: Int) -> Snap? {
        let pts = geometry.points
        guard pts.count >= 2 else { return nil }
        let lastSeg = pts.count - 2

        func search(_ range: ClosedRange<Int>) -> Snap? {
            var best: Snap?
            for i in range {
                let r = Geo.project(p, onto: pts[i], pts[i + 1])
                if best == nil || r.distanceM < best!.distanceFromRouteM {
                    let along = geometry.cumulative[i] + (geometry.cumulative[i + 1] - geometry.cumulative[i]) * r.t
                    best = Snap(segment: i, distanceAlongM: along, distanceFromRouteM: r.distanceM, point: r.point)
                }
            }
            return best
        }

        let lo = max(0, min(hint, lastSeg) - 3)
        let hi = min(lastSeg, max(hint, 0) + 80)
        let local = search(lo...hi)
        if let local, local.distanceFromRouteM <= 60 { return local }
        let global = search(0...lastSeg)
        if let local, let global { return global.distanceFromRouteM < local.distanceFromRouteM - 1 ? global : local }
        return global ?? local
    }

    mutating func update(location p: LatLng) -> RouteProgress? {
        guard let s = snap(p, hint: lastSegment) else { return nil }
        lastSegment = s.segment
        return progress(for: s)
    }

    func progress(for s: Snap) -> RouteProgress {
        let total = geometry.totalM
        let remaining = max(0, total - s.distanceAlongM)
        let fraction = total > 0 ? remaining / total : 0
        let remainingS = route.effectiveDurationS * fraction

        let upcoming = maneuvers.firstIndex { $0.beginShapeIndex > s.segment }
        var toUpcoming = remaining
        var then: Int?
        if let u = upcoming {
            toUpcoming = max(0, geometry.distance(atIndex: maneuvers[u].beginShapeIndex) - s.distanceAlongM)
            let n = u + 1
            if n < maneuvers.count {
                let gap = geometry.distance(atIndex: maneuvers[n].beginShapeIndex) - geometry.distance(atIndex: maneuvers[u].beginShapeIndex)
                if gap <= thenThresholdM { then = n }
            }
        }
        return RouteProgress(
            snap: s,
            remainingM: remaining,
            remainingS: remainingS,
            upcomingIndex: upcoming,
            distanceToUpcomingM: toUpcoming,
            thenIndex: then,
            arrived: remaining <= arrivalThresholdM
        )
    }
}

/// Decides when the driver has left the route. Requires several consecutive bad fixes so a single
/// noisy GPS sample (urban canyons in KL!) does not trigger a reroute.
struct OffRouteDetector: Sendable {
    var thresholdM: Double = 40
    var requiredConsecutive = 3
    /// Fixes with worse horizontal accuracy than this are ignored.
    var maxAccuracyM: Double = 80
    /// Minimum time between reroute requests.
    var cooldownS: TimeInterval = 10

    private(set) var consecutive = 0
    private(set) var lastRerouteAt: Date?

    init(thresholdM: Double = 40, requiredConsecutive: Int = 3, maxAccuracyM: Double = 80, cooldownS: TimeInterval = 10) {
        self.thresholdM = thresholdM
        self.requiredConsecutive = requiredConsecutive
        self.maxAccuracyM = maxAccuracyM
        self.cooldownS = cooldownS
    }

    /// Effective tolerance grows with poor accuracy.
    func tolerance(accuracyM: Double) -> Double {
        max(thresholdM, accuracyM.isFinite && accuracyM > 0 ? accuracyM * 1.2 : thresholdM)
    }

    /// Returns true when a reroute should be requested now.
    mutating func evaluate(distanceFromRouteM d: Double, accuracyM: Double, at now: Date) -> Bool {
        if accuracyM < 0 || accuracyM > maxAccuracyM { return false }
        if d > tolerance(accuracyM: accuracyM) {
            consecutive += 1
        } else {
            consecutive = 0
            return false
        }
        guard consecutive >= requiredConsecutive else { return false }
        if let last = lastRerouteAt, now.timeIntervalSince(last) < cooldownS { return false }
        return true
    }

    mutating func didReroute(at now: Date) {
        lastRerouteAt = now
        consecutive = 0
    }

    mutating func reset() {
        consecutive = 0
    }
}

/// Batches location pings: one point every `intervalS`, at most `maxBatch` per request.
struct LocationPingBatcher: Sendable {
    var intervalS: TimeInterval = 5
    var maxBatch = 12
    private(set) var buffer: [LocationPoint] = []
    private var lastTs: Date?

    init(intervalS: TimeInterval = 5, maxBatch: Int = 12) {
        self.intervalS = intervalS
        self.maxBatch = maxBatch
    }

    /// Adds a fix; returns a full batch to send when the buffer reaches `maxBatch`.
    mutating func add(_ p: LocationPoint) -> [LocationPoint]? {
        if let lastTs, p.ts.timeIntervalSince(lastTs) < intervalS { return nil }
        lastTs = p.ts
        buffer.append(p)
        if buffer.count >= maxBatch { return drain() }
        return nil
    }

    mutating func drain() -> [LocationPoint] {
        let out = Array(buffer.prefix(maxBatch))
        buffer.removeFirst(out.count)
        return out
    }
}
