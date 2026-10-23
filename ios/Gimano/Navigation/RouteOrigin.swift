import Foundation

/// Where a route starts (Android `RouteOrigin.kt`, commit a5b85f0). Only a recent GPS fix may be called
/// "Your location"; never the map centre or a default point.
enum RouteOrigin {
    /// Older fixes are refreshed (or the user is asked) before routing from them.
    static let maxFixAge: TimeInterval = 120
    /// Closer than this to the destination → "You're already here", no route.
    static let sameSpotM = 50.0
    /// How long to wait for a fresh fix before saying the location is unknown.
    static let waitForFix: TimeInterval = 8

    enum GPS: Equatable {
        case fresh(LatLng)
        case stale(LatLng, age: TimeInterval)
        case none
    }

    static func classify(fix: LatLng?, at fixTime: Date?, now: Date, maxAge: TimeInterval = maxFixAge) -> GPS {
        guard let fix, let fixTime, fixTime.timeIntervalSince1970 > 0 else { return .none }
        let age = now.timeIntervalSince(fixTime)
        // Up to 60 s in the future tolerates clock skew; anything else isn't trusted.
        return (-60...maxAge).contains(age) ? .fresh(fix) : .stale(fix, age: age)
    }

    static func sameSpot(_ a: LatLng, _ b: LatLng) -> Bool { Geo.distance(a, b) < sameSpotM }
}

/// The start row of a route preview.
enum RouteStart: Equatable {
    /// Waiting (≤ 8 s) for a fresh GPS fix: "Finding your location…".
    case locating
    /// No fresh fix: no route request; offer Allow location / Choose start on map / Search for a start.
    case unknown
    /// A fresh GPS fix: "Your location"; the only start navigation may begin from.
    case gps(LatLng)
    /// A start the user picked (favourite, recent, search, map): routes are previewed, never navigated.
    case chosen(name: String, location: LatLng, placeId: String?)

    var point: LatLng? {
        switch self {
        case .gps(let p): p
        case .chosen(_, let p, _): p
        default: nil
        }
    }

    var isGPS: Bool { if case .gps = self { true } else { false } }

    var chosenName: String? { if case .chosen(let n, _, _) = self { n } else { nil } }
}

/// The route preview's 10 s "Go" countdown (Android `AutoStart`). Off by default (Settings → "Start navigation
/// automatically"); even then only for one-tap destinations (Home, Work, a favourite, a recent, a planned drive), never
/// after a search, never with a chosen start, stops or a departure time, and never while the app is in the background.
enum AutoStart {
    static func shouldCountdown(enabled: Bool, quickPick: Bool, originIsGPS: Bool, hasStops: Bool, departLater: Bool, foreground: Bool) -> Bool {
        enabled && quickPick && originIsGPS && !hasStops && !departLater && foreground
    }
}
