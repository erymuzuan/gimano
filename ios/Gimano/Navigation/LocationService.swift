import CoreLocation
import Foundation
import Observation

struct LocationFix: Sendable, Equatable {
    var coordinate: LatLng
    var speedKmh: Double
    /// Course over ground in degrees, or nil when unknown.
    var course: Double?
    var accuracyM: Double
    var timestamp: Date

    init(coordinate: LatLng, speedKmh: Double, course: Double?, accuracyM: Double, timestamp: Date) {
        self.coordinate = coordinate
        self.speedKmh = speedKmh
        self.course = course
        self.accuracyM = accuracyM
        self.timestamp = timestamp
    }

    init(_ l: CLLocation) {
        coordinate = LatLng(l.coordinate)
        speedKmh = max(0, l.speed) * 3.6
        course = l.course >= 0 ? l.course : nil
        accuracyM = l.horizontalAccuracy
        timestamp = l.timestamp
    }
}

/// CoreLocation wrapper. Enables background updates only while navigating.
@MainActor
@Observable
final class LocationService: NSObject {
    private(set) var authorization: CLAuthorizationStatus
    /// False when the user turned Precise Location off for Gimano (approximate, ~1–3 km).
    private(set) var isPrecise: Bool
    private(set) var lastFix: LocationFix?
    private(set) var isNavigating = false
    @ObservationIgnored var onFix: ((LocationFix) -> Void)?

    @ObservationIgnored private let manager = CLLocationManager()

    override init() {
        authorization = manager.authorizationStatus
        isPrecise = manager.accuracyAuthorization == .fullAccuracy
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    var isAuthorized: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }
    var needsPermission: Bool { authorization == .notDetermined }

    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    /// Before navigating with approximate location: ask for precise location for this session
    /// (purpose key "Navigation" in NSLocationTemporaryUsageDescriptionDictionary).
    func requestTemporaryPrecision() async {
        guard isAuthorized, manager.accuracyAuthorization == .reducedAccuracy else { return }
        try? await manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: "Navigation")
        isPrecise = manager.accuracyAuthorization == .fullAccuracy
    }

    func start() {
        guard isAuthorized else { return }
        manager.startUpdatingLocation()
    }

    /// - Parameter requestAlways: ask (once) for Always authorization so guidance survives screen-off.
    func setNavigating(_ on: Bool, requestAlways: Bool = true) {
        isNavigating = on
        guard isAuthorized else { return }
        if on {
            manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
            manager.distanceFilter = kCLDistanceFilterNone
            // Requires UIBackgroundModes=location; keeps guidance alive with the screen off.
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
            if requestAlways, authorization == .authorizedWhenInUse, !UserDefaults.standard.bool(forKey: "location.askedAlways") {
                UserDefaults.standard.set(true, forKey: "location.askedAlways")
                manager.requestAlwaysAuthorization()
            }
        } else {
            manager.desiredAccuracy = kCLLocationAccuracyBest
            manager.distanceFilter = 5
            manager.allowsBackgroundLocationUpdates = false
        }
        manager.startUpdatingLocation()
    }

    fileprivate func handle(_ locations: [CLLocation]) {
        guard let l = locations.last, l.horizontalAccuracy >= 0 else { return }
        let fix = LocationFix(l)
        lastFix = fix
        onFix?(fix)
    }

    fileprivate func handleAuth(_ status: CLAuthorizationStatus) {
        authorization = status
        isPrecise = manager.accuracyAuthorization == .fullAccuracy
        if isAuthorized { manager.startUpdatingLocation() }
    }
}

extension LocationService: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated { handle(locations) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated { handleAuth(status) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {}
}

/// GPS quality while navigating: "weak" once accuracy has stayed above 50 m for 10 s;
/// fixes worse than 100 m never advance instructions.
struct GpsQuality: Sendable, Equatable {
    static let weakAboveM = 50.0
    static let weakAfter: TimeInterval = 10
    static let ignoreForGuidanceAboveM = 100.0

    private(set) var badSince: Date?
    private(set) var lastAccuracyM: Double?
    private(set) var isWeak = false

    mutating func update(accuracyM: Double, at now: Date) {
        lastAccuracyM = accuracyM
        if accuracyM > Self.weakAboveM {
            if badSince == nil { badSince = now }
            isWeak = now.timeIntervalSince(badSince!) >= Self.weakAfter
        } else {
            badSince = nil
            isWeak = false
        }
    }

    /// Evaluated against the clock too: approximate location delivers fixes only every few minutes.
    func isWeak(at now: Date) -> Bool {
        guard let since = badSince else { return false }
        return now.timeIntervalSince(since) >= Self.weakAfter
    }

    static func usableForGuidance(_ fix: LocationFix) -> Bool { fix.accuracyM <= ignoreForGuidanceAboveM }
}
