import Foundation

// MARK: - Tolls

/// What the route card shows for tolls (docs/api.md "Tolls & fares").
enum TollBadge: Equatable, Sendable {
    /// No tolls (or a mode that never uses tolls: bicycle/walk).
    case none
    /// Every plaza priced: "RM 2.50".
    case complete(Double)
    /// Some plazas priced: "from RM 1.60".
    case from(Double)
    /// Tolls on the route, no known fare: "Tolls".
    case unknown

    static func make(for route: Route, mode: Vehicle) -> TollBadge {
        guard mode.isMotorized, route.vehicle?.isMotorized ?? true else { return .none }
        let plazas = route.tolls ?? []
        guard route.hasTolls || !plazas.isEmpty else { return .none }
        let known = plazas.compactMap(\.fareMyr)
        let estimate = route.tollEstimateMyr ?? (known.isEmpty ? nil : known.reduce(0, +))
        let complete = route.tollFareComplete ?? (!plazas.isEmpty && known.count == plazas.count)
        guard let estimate else { return .unknown }
        if complete { return .complete(estimate) }
        return estimate > 0 ? .from(estimate) : .unknown
    }

    var text: String? {
        switch self {
        case .none: nil
        case .complete(let v): Self.myr(v)
        case .from(let v): String(localized: "from \(Self.myr(v))")
        case .unknown: String(localized: "Tolls")
        }
    }

    /// "RM 2.50" (Malaysian ringgit, always two decimals).
    static func myr(_ v: Double) -> String {
        "RM " + String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), v)
    }
}

// MARK: - Stops

/// An intermediate stop on a multi-stop trip (≤ 5, in order).
struct TripStop: Identifiable, Hashable, Sendable {
    var id = UUID()
    var name: String
    var location: LatLng
    var placeId: String?

    var endpoint: RouteEndpoint { RouteEndpoint(lat: location.lat, lng: location.lng, placeId: placeId, name: name) }

    static let maxStops = 5
}

enum StopPlanner {
    /// Adds a stop if there is room; returns false when the trip already has the maximum.
    static func add(_ stop: TripStop, to stops: inout [TripStop]) -> Bool {
        guard stops.count < TripStop.maxStops else { return false }
        stops.append(stop)
        return true
    }

    static func move(_ stops: inout [TripStop], from source: IndexSet, to destination: Int) {
        stops.move(fromOffsets: source, toOffset: destination)
    }
}

/// Where each stop (and the destination) sits along the route, and the ETA offsets to them.
struct StopSchedule: Equatable, Sendable {
    /// Distance along the route (m) of each stop, then the destination last.
    var alongM: [Double]
    /// Seconds from departure to each stop, then the destination last.
    var offsetsS: [Double]

    /// Uses per-leg durations when the server returned one leg per stop (contract), otherwise projects the
    /// stops onto the geometry and splits the duration proportionally to distance (older servers / demo).
    static func make(route: Route, stops: [LatLng]) -> StopSchedule {
        let geometry = RouteGeometry(route: route)
        let total = geometry.totalM
        let duration = route.effectiveDurationS
        let scale = route.durationS > 0 ? duration / route.durationS : 1

        if route.legs.count == stops.count + 1, route.legs.count > 1 {
            var along: [Double] = []
            var offsets: [Double] = []
            var t = 0.0
            for leg in route.legs {
                let legDuration = leg.durationS ?? leg.maneuvers.reduce(0) { $0 + $1.durationS }
                t += legDuration * scale
                offsets.append(t)
                let endIndex = leg.maneuvers.last?.endShapeIndex ?? 0
                along.append(geometry.distance(atIndex: endIndex))
            }
            along[along.count - 1] = total
            offsets[offsets.count - 1] = duration
            return StopSchedule(alongM: along, offsetsS: offsets)
        }

        // Project stops in order, never going backwards.
        var along: [Double] = []
        var from = 0
        let pts = geometry.points
        for s in stops {
            var best = (d: Double.infinity, along: total, seg: from)
            if pts.count > 1 {
                for i in from..<(pts.count - 1) {
                    let p = Geo.project(s, onto: pts[i], pts[i + 1])
                    if p.distanceM < best.d {
                        best = (p.distanceM, geometry.cumulative[i] + (geometry.cumulative[i + 1] - geometry.cumulative[i]) * p.t, i)
                    }
                }
            }
            along.append(best.along)
            from = best.seg
        }
        along.append(total)
        var offsets = along.map { total > 0 ? duration * $0 / total : 0 }
        offsets[offsets.count - 1] = duration
        return StopSchedule(alongM: along, offsetsS: offsets)
    }
}

/// Tracks which stop is next during navigation and reports arrivals.
struct StopTracker: Equatable, Sendable {
    var schedule: StopSchedule
    /// Number of intermediate stops (the destination is not counted).
    var stopCount: Int
    private(set) var nextStopIndex = 0
    var arrivalRadiusM: Double = 35

    init(schedule: StopSchedule, stopCount: Int) {
        self.schedule = schedule
        self.stopCount = stopCount
    }

    var hasPendingStop: Bool { nextStopIndex < stopCount }

    /// Returns the index of a stop that was just reached at this distance along the route.
    mutating func update(alongM: Double) -> Int? {
        guard hasPendingStop else { return nil }
        if alongM >= schedule.alongM[nextStopIndex] - arrivalRadiusM {
            nextStopIndex += 1
            return nextStopIndex - 1
        }
        return nil
    }

    /// Remaining time to the next stop (or destination) given progress along the route.
    func remainingToNext(alongM: Double, totalRemainingS: Double, totalRemainingM: Double) -> (seconds: Double, meters: Double) {
        let target = schedule.alongM[min(nextStopIndex, schedule.alongM.count - 1)]
        let meters = max(0, target - alongM)
        let seconds = totalRemainingM > 0 ? totalRemainingS * meters / totalRemainingM : 0
        return (seconds, meters)
    }
}

// MARK: - Feedback helpers

enum FeedbackRules {
    static let maxMessage = 2000
    static let maxScreenshotBytes = 1_500_000

    static func validate(message: String) -> Bool {
        let t = message.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && t.count <= maxMessage
    }

    /// Feedback during navigation is only allowed when (almost) stopped, or when the user is a passenger.
    static func allowedWhileDriving(speedKmh: Double, isPassenger: Bool) -> Bool {
        isPassenger || speedKmh < 5
    }
}
