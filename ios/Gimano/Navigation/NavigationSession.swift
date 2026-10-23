import Foundation
import Observation

/// One active turn-by-turn drive: tracks progress, speaks guidance, detects off-route → reroute,
/// surfaces alerts ahead and batches location pings to `POST /locations`.
@MainActor
@Observable
final class NavigationSession {
    let destination: Destination
    private(set) var route: Route
    private(set) var progress: RouteProgress?
    private(set) var fix: LocationFix?
    private(set) var isRerouting = false
    private(set) var arrived = false
    private(set) var approachingAlert: AlertOnRoute?
    private(set) var rerouteCount = 0
    let startedAt = Date()
    var waypoints: [RouteEndpoint] = []
    var vehicle: Vehicle
    var avoid: AvoidOptions

    @ObservationIgnored private var tracker: RouteTracker
    @ObservationIgnored private var offRoute = OffRouteDetector()
    @ObservationIgnored private var announcer = VoiceAnnouncer()
    @ObservationIgnored private var batcher = LocationPingBatcher()
    @ObservationIgnored private var handledAlerts: Set<String> = []
    @ObservationIgnored private let repo: any GimanoRepository
    @ObservationIgnored let voice: VoiceGuide
    /// Called when the active route geometry changes (reroute, add stop, alternative picked).
    @ObservationIgnored var onRouteChanged: ((Route) -> Void)?

    init(destination: Destination, route: Route, vehicle: Vehicle, avoid: AvoidOptions, repo: any GimanoRepository, voice: VoiceGuide) {
        self.destination = destination
        self.route = route
        self.vehicle = vehicle
        self.avoid = avoid
        self.repo = repo
        self.voice = voice
        self.tracker = RouteTracker(route: route)
    }

    var geometry: RouteGeometry { tracker.geometry }
    var maneuvers: [Maneuver] { tracker.maneuvers }

    var upcoming: Maneuver? { progress?.upcomingIndex.map { tracker.maneuvers[$0] } ?? tracker.maneuvers.dropFirst().first }
    var then: Maneuver? { progress?.thenIndex.map { tracker.maneuvers[$0] } }
    var distanceToUpcoming: Double { progress?.distanceToUpcomingM ?? (tracker.maneuvers.dropFirst().first.map { tracker.geometry.distance(atIndex: $0.beginShapeIndex) } ?? 0) }
    var remainingM: Double { progress?.remainingM ?? route.distanceM }
    var remainingS: Double { progress?.remainingS ?? route.effectiveDurationS }
    var speedKmh: Double { fix?.speedKmh ?? 0 }

    func begin() {
        if let first = tracker.maneuvers.first {
            voice.speak(ManeuverFormatter.instruction(for: first))
        }
    }

    func handle(_ fix: LocationFix) {
        self.fix = fix
        guard !arrived else { return }
        guard let p = tracker.update(location: fix.coordinate) else { return }
        progress = p

        if let batch = batcher.add(LocationPoint(lat: fix.coordinate.lat, lng: fix.coordinate.lng, speedKmh: fix.speedKmh,
                                                  heading: fix.course ?? 0, accuracyM: fix.accuracyM, ts: fix.timestamp)) {
            send(batch)
        }

        if p.arrived {
            arrived = true
            voice.speak(String(localized: "You have arrived at \(destination.name)"))
            send(batcher.drain())
            return
        }

        if !isRerouting, offRoute.evaluate(distanceFromRouteM: p.snap.distanceFromRouteM, accuracyM: fix.accuracyM, at: fix.timestamp) {
            offRoute.didReroute(at: fix.timestamp)
            reroute(from: fix)
            return
        }

        if let i = p.upcomingIndex,
           let text = announcer.prompt(maneuverIndex: i, maneuver: tracker.maneuvers[i], distanceM: p.distanceToUpcomingM, speedKmh: fix.speedKmh) {
            voice.speak(text)
        }

        updateAlerts(along: p.snap.distanceAlongM)
    }

    private func updateAlerts(along: Double) {
        if let current = approachingAlert, current.distanceAlongRouteM < along - 30 {
            approachingAlert = nil
        }
        guard approachingAlert == nil else { return }
        let ahead = (route.alerts ?? [])
            .filter { !handledAlerts.contains($0.id) && $0.distanceAlongRouteM - along > 0 && $0.distanceAlongRouteM - along < 600 }
            .min { $0.distanceAlongRouteM < $1.distanceAlongRouteM }
        if let ahead {
            handledAlerts.insert(ahead.id)
            approachingAlert = ahead
            voice.speak(String(localized: "\(String(localized: ahead.report.type.title)) reported ahead"), kind: .alert)
        }
    }

    func distanceToAlert(_ a: AlertOnRoute) -> Double {
        max(0, a.distanceAlongRouteM - (progress?.snap.distanceAlongM ?? 0))
    }

    func dismissAlert() { approachingAlert = nil }

    func vote(stillThere: Bool) {
        guard let a = approachingAlert else { return }
        approachingAlert = nil
        Task { _ = try? await repo.vote(reportId: a.id, stillThere: stillThere) }
    }

    /// Replace the active route (alternative picked / stop added).
    func setRoute(_ r: Route) {
        route = r
        tracker = RouteTracker(route: r)
        announcer.reset()
        offRoute.reset()
        progress = nil
        if let fix { handle(fix) }
        onRouteChanged?(r)
    }

    func reroute(from fix: LocationFix) {
        isRerouting = true
        voice.speak(String(localized: "Recalculating route"))
        let origin = fix.coordinate
        let heading = fix.course ?? 0
        let routeId = route.id
        Task {
            defer { isRerouting = false }
            var routes = (try? await repo.reroute(routeId: routeId, RerouteRequest(lat: origin.lat, lng: origin.lng, heading: heading))) ?? []
            if routes.isEmpty {
                let req = RouteRequest(origin: RouteEndpoint(lat: origin.lat, lng: origin.lng),
                                       destination: RouteEndpoint(lat: destination.location.lat, lng: destination.location.lng, placeId: destination.placeId),
                                       waypoints: waypoints, vehicle: vehicle, avoid: avoid, alternatives: 0)
                routes = (try? await repo.routes(req)) ?? []
            }
            guard let best = routes.first(where: \.isBest) ?? routes.first else { return }
            rerouteCount += 1
            setRoute(best)
        }
    }

    func stop() {
        voice.stop()
        send(batcher.drain())
    }

    private func send(_ points: [LocationPoint]) {
        guard !points.isEmpty else { return }
        let batch = LocationBatch(routeId: route.id, points: points)
        Task { try? await repo.sendLocations(batch) }
    }
}
