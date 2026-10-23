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
    private(set) var lastFix: LocationFix?
    private(set) var isNavigating = false
    @ObservationIgnored var onFix: ((LocationFix) -> Void)?

    @ObservationIgnored private let manager = CLLocationManager()

    override init() {
        authorization = manager.authorizationStatus
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
