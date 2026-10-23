import Foundation
import Observation
import SwiftUI

enum BackendStatus: Equatable {
    case unknown, online, demoFallback, demoForced
}

enum Screen: Equatable {
    case home
    case search(SearchPurpose)
    case place(PlaceSummary)
    case routePreview
    case navigating
}

enum SearchPurpose: Equatable {
    case destination
    case addStop
    case setHome
    case setWork
}

/// Route options + alternatives for the preview screen.
@MainActor
@Observable
final class RoutePlanner {
    var destination: Destination
    var routes: [Route] = []
    var selectedId: String?
    var vehicle: Vehicle
    var avoid = AvoidOptions()
    var departAt: Date?
    var isLoading = false
    var error: String?

    init(destination: Destination, vehicle: Vehicle) {
        self.destination = destination
        self.vehicle = vehicle
    }

    var selected: Route? { routes.first { $0.id == selectedId } ?? routes.first }
}

@MainActor
@Observable
final class AppModel {
    let config: AppConfig
    let location = LocationService()
    let live = LiveMapStore()
    let map: MapState
    @ObservationIgnored let voice = VoiceGuide()
    @ObservationIgnored private let simulator = DriveSimulator()

    private(set) var repo: any GimanoRepository
    private(set) var backendStatus: BackendStatus = .unknown
    private(set) var user: User?
    private(set) var savedPlaces: [SavedPlace] = []
    private(set) var recents: [Recent] = []

    var screen: Screen = .home
    var planner: RoutePlanner?
    private(set) var navigation: NavigationSession?
    var showMenu = false
    var showReport = false
    var showDebug = false
    var showOnboarding = false
    var selectedAlert: Report?
    var toast: String?
    var soundMode: SoundMode = .on { didSet { voice.mode = soundMode } }
    var vehicle: Vehicle = .car
    /// Category pre-selected when opening search from a home shortcut tile.
    var searchCategory: PlaceCategory?

    /// Latest position (simulated during demo drives) and motion.
    private(set) var position: LatLng = DemoRepository.defaultLocation
    private(set) var hasRealFix = false
    private(set) var speedKmh: Double = 0
    private(set) var heading: Double = 0

    @ObservationIgnored private var toastTask: Task<Void, Never>?

    init(config: AppConfig = AppConfig()) {
        self.config = config
        self.map = MapState(styleURL: config.styleURL)
        self.repo = DemoRepository()
        wireMap()
        location.onFix = { [weak self] fix in self?.handleRealFix(fix) }
        rebuildRepository()
    }

    // MARK: bootstrap

    func bootstrap() async {
        if location.needsPermission && !UserDefaults.standard.bool(forKey: "onboarding.done") {
            showOnboarding = true
        } else {
            location.start()
        }
        map.puck = position
        await refreshUserData()
        #if DEBUG
        await runDemoScene()
        #endif
    }

    #if DEBUG
    /// Screenshot / demo hook: `-GimanoDemoScene search|place|route|nav|report|menu`.
    private func runDemoScene() async {
        guard let scene = UserDefaults.standard.string(forKey: "GimanoDemoScene") else { return }
        let target = DemoData.places.first { $0.id == "demo:midvalley" }!
        let dest = Destination(name: target.name, address: target.address, location: target.location, placeId: target.id, category: target.category)
        try? await Task.sleep(for: .seconds(1.5))
        switch scene {
        case "search":
            screen = .search(.destination)
        case "place":
            openPlace(target)
        case "route":
            planRoute(to: dest)
        case "nav":
            planRoute(to: dest)
            try? await Task.sleep(for: .seconds(1.5))
            startNavigation()
        case "report":
            showReport = true
        case "menu":
            showMenu = true
        default:
            break
        }
    }
    #endif

    func finishOnboarding(allowLocation: Bool) {
        UserDefaults.standard.set(true, forKey: "onboarding.done")
        showOnboarding = false
        if allowLocation { location.requestPermission() }
    }

    func rebuildRepository() {
        let base = config.apiBaseURL
        let demo = DemoRepository()
        let liveRepo = LiveRepository(baseURL: base)
        switch config.dataMode {
        case .demo:
            repo = demo
            backendStatus = .demoForced
        case .live:
            repo = liveRepo
            backendStatus = .unknown
        case .auto:
            repo = FallbackRepository(live: liveRepo, demo: demo) { [weak self] offline in
                self?.backendStatus = offline ? .demoFallback : .online
            }
            backendStatus = .unknown
        }
        map.styleURL = config.styleURL
        let hubURL: URL? = config.dataMode == .demo ? nil : config.liveHubURL
        live.configure(repo: repo, hubURL: hubURL, tokenProvider: { TokenStore.load(for: base) })
        Task { await refreshUserData() }
    }

    func refreshUserData() async {
        let repo = self.repo
        if config.dataMode != .demo, TokenStore.load(for: config.apiBaseURL) == nil {
            _ = try? await repo.signIn()
        }
        if let u = try? await repo.me() {
            user = u
            if let v = u.vehicle { vehicle = v }
            if backendStatus == .unknown, config.dataMode == .live { backendStatus = .online }
        }
        if let p = try? await repo.savedPlaces() { savedPlaces = p }
        if let r = try? await repo.recents() { recents = r }
    }

    var home: SavedPlace? { savedPlaces.first { $0.kind == .home } }
    var work: SavedPlace? { savedPlaces.first { $0.kind == .work } }
    var favorites: [SavedPlace] { savedPlaces.filter { $0.kind == .favorite } }

    var isDemo: Bool { backendStatus == .demoFallback || backendStatus == .demoForced }

    // MARK: location

    private func handleRealFix(_ fix: LocationFix) {
        hasRealFix = true
        guard !simulator.isRunning else { return }
        applyFix(fix)
    }

    private func applyFix(_ fix: LocationFix) {
        position = fix.coordinate
        speedKmh = fix.speedKmh
        if let c = fix.course { heading = c }
        map.puck = fix.coordinate
        map.puckHeading = heading
        navigation?.handle(fix)
        if let nav = navigation, let snapped = nav.progress?.snap, snapped.distanceFromRouteM <= 30 {
            // Keep the puck glued to the road while on route (≤ 30 m); otherwise show the raw fix (off-route logic runs on it).
            map.puck = snapped.point
        }
    }

    func recenter() {
        map.setCamera(screen == .navigating ? .navigation : .trackNorth(zoom: 15))
    }

    // MARK: map wiring

    private func wireMap() {
        map.onUserGesture = { [weak self] in
            guard let self else { return }
            if self.map.isFollowing { self.map.camera = .free }
        }
        map.onRouteTapped = { [weak self] id in
            guard let self, self.screen == .routePreview else { return }
            self.selectRoute(id)
        }
        map.onAlertTapped = { [weak self] r in self?.selectedAlert = r }
        map.onLongPress = { [weak self] p in
            guard let self, self.screen == .home else { return }
            Task { await self.pickDroppedPin(p) }
        }
    }

    /// Pushes live data into the map (called when the live store changes).
    func syncLiveLayers() {
        map.alerts = Array(live.alerts.values)
        map.jams = live.jams.values.map { JamLine(id: $0.id, level: $0.level, points: Polyline.decode($0.polyline)) }
        map.drivers = screen == .navigating ? [] : live.drivers
    }

    private func syncRouteLayers() {
        switch screen {
        case .routePreview:
            guard let planner else { break }
            let sel = planner.selected?.id
            map.routes = planner.routes.map {
                RouteLine(id: $0.id, points: Polyline.decode($0.polyline), selected: $0.id == sel, traffic: $0.trafficSegments ?? [])
            }
            map.destination = planner.destination.location
            map.bubbleAnchors = Self.bubbleAnchors(for: planner.routes.map { ($0.id, Polyline.decode($0.polyline)) })
        case .navigating:
            guard let nav = navigation else { break }
            map.routes = [RouteLine(id: nav.route.id, points: nav.geometry.points, selected: true, traffic: nav.route.trafficSegments ?? [])]
            map.destination = nav.destination.location
            map.bubbleAnchors = [:]
        case .place(let p):
            map.routes = []
            map.destination = p.location
            map.bubbleAnchors = [:]
        default:
            map.routes = []
            map.destination = nil
            map.bubbleAnchors = [:]
        }
    }

    /// Places each route's duration bubble on the stretch of that route farthest from the other routes
    /// (and from bubbles already placed), so bubbles don't stack where alternatives share road.
    static func bubbleAnchors(for routes: [(id: String, points: [LatLng])]) -> [String: LatLng] {
        func sample(_ pts: [LatLng], _ n: Int) -> [LatLng] {
            guard pts.count > n else { return pts }
            return (0..<n).map { pts[$0 * (pts.count - 1) / (n - 1)] }
        }
        let samples = routes.map { sample($0.points, 60) }
        var anchors: [String: LatLng] = [:]
        var placed: [LatLng] = []
        for (i, r) in routes.enumerated() where r.points.count > 1 {
            let others = samples.enumerated().filter { $0.offset != i }.flatMap(\.element)
            // Skip the first/last 15 % so bubbles don't sit on the origin or destination pin.
            let cand = samples[i].dropFirst(samples[i].count * 15 / 100).dropLast(samples[i].count * 15 / 100)
            let best = cand.max { a, b in
                score(a, others, placed) < score(b, others, placed)
            } ?? r.points[r.points.count / 2]
            anchors[r.id] = best
            placed.append(best)
        }
        return anchors
    }

    private static func score(_ p: LatLng, _ others: [LatLng], _ placed: [LatLng]) -> Double {
        let dOther = others.map { Geo.distance(p, $0) }.min() ?? 10_000
        let dPlaced = placed.map { Geo.distance(p, $0) }.min() ?? 10_000
        return min(dOther, 3_000) + min(dPlaced, 1_500)
    }

    // MARK: flows

    func go(_ s: Screen) {
        screen = s
        syncRouteLayers()
        syncLiveLayers()
        map.navigationPuck = s == .navigating
        switch s {
        case .home:
            map.setCamera(.trackNorth(zoom: 15))
        case .place(let p):
            map.setCamera(.center(LatLng(lat: p.lat - 0.004, lng: p.lng), zoom: 14.5))
        case .routePreview, .search:
            break
        case .navigating:
            map.setCamera(.navigation)
        }
    }

    func openPlace(_ p: PlaceSummary) {
        go(.place(p))
    }

    func pickDroppedPin(_ p: LatLng) async {
        let rg = try? await repo.reverseGeocode(p)
        let summary = PlaceSummary(id: "pin:\(p.lat),\(p.lng)", name: rg?.name ?? String(localized: "Dropped pin"), category: .other,
                                   address: rg?.address ?? String(format: "%.5f, %.5f", p.lat, p.lng), lat: p.lat, lng: p.lng,
                                   distanceM: Geo.distance(position, p), brand: nil)
        openPlace(summary)
    }

    func planRoute(to d: Destination) {
        let planner = RoutePlanner(destination: d, vehicle: vehicle)
        self.planner = planner
        go(.routePreview)
        Task { await fetchRoutes() }
    }

    func fetchRoutes() async {
        guard let planner else { return }
        planner.isLoading = true
        planner.error = nil
        let req = RouteRequest(
            origin: RouteEndpoint(lat: position.lat, lng: position.lng),
            destination: RouteEndpoint(lat: planner.destination.location.lat, lng: planner.destination.location.lng, placeId: planner.destination.placeId),
            vehicle: planner.vehicle, avoid: planner.avoid, departAt: planner.departAt, alternatives: 2)
        do {
            let routes = try await repo.routes(req)
            guard self.planner === planner else { return }
            planner.routes = routes
            planner.selectedId = (routes.first(where: \.isBest) ?? routes.first)?.id
            if routes.isEmpty { planner.error = String(localized: "No route found") }
        } catch {
            planner.error = error.localizedDescription
        }
        planner.isLoading = false
        syncRouteLayers()
        if let r = planner.selected, let bbox = BBox(array: r.bbox) {
            let all = planner.routes.compactMap { BBox(array: $0.bbox) }
            let union = BBox.around(all.flatMap { [LatLng(lat: $0.minLat, lng: $0.minLng), LatLng(lat: $0.maxLat, lng: $0.maxLng)] } + [position]) ?? bbox
            map.overviewInsets = (top: 170, bottom: 400)
            map.setCamera(.overview(union))
        }
    }

    func selectRoute(_ id: String) {
        planner?.selectedId = id
        syncRouteLayers()
    }

    func startNavigation() {
        guard let planner, let route = planner.selected else { return }
        let session = NavigationSession(destination: planner.destination, route: route, vehicle: planner.vehicle,
                                        avoid: planner.avoid, repo: repo, voice: voice)
        session.onRouteChanged = { [weak self] r in
            guard let self else { return }
            self.syncRouteLayers()
            if self.shouldSimulate { self.startSimulator(on: r) }
        }
        navigation = session
        location.setNavigating(true, requestAlways: !shouldSimulate)
        go(.navigating)
        session.begin()
        if shouldSimulate { startSimulator(on: route) } else if let fix = location.lastFix { applyFix(fix) }
        UIApplication.shared.isIdleTimerDisabled = true
    }

    /// Demo drives: no backend, no real GPS fix (e.g. Simulator), or forced from Debug settings.
    var shouldSimulate: Bool {
        config.simulateDrive || backendStatus == .demoForced || !hasRealFix
    }

    private func startSimulator(on route: Route) {
        let pts = Polyline.decode(route.polyline)
        simulator.start(points: pts, speedKmh: planner?.vehicle == .motorcycle ? 55 : 50, speedup: config.simulationSpeedup) { [weak self] fix in
            self?.applyFix(fix)
        }
    }

    func stopNavigation() {
        simulator.stop()
        navigation?.stop()
        navigation = nil
        planner = nil
        location.setNavigating(false)
        UIApplication.shared.isIdleTimerDisabled = false
        speedKmh = 0
        go(.home)
        Task { if let r = try? await repo.recents() { recents = r } }
    }

    func addStop(_ p: PlaceSummary) {
        guard let nav = navigation else { return }
        nav.waypoints = [RouteEndpoint(lat: p.lat, lng: p.lng, placeId: p.id)]
        screen = .navigating
        let req = RouteRequest(
            origin: RouteEndpoint(lat: position.lat, lng: position.lng),
            destination: RouteEndpoint(lat: nav.destination.location.lat, lng: nav.destination.location.lng, placeId: nav.destination.placeId),
            waypoints: nav.waypoints, vehicle: nav.vehicle, avoid: nav.avoid, alternatives: 0)
        Task {
            if let r = try? await repo.routes(req).first {
                nav.setRoute(r)
                showToast(String(localized: "Stop added: \(p.name)"))
            }
        }
        go(.navigating)
    }

    // MARK: saved places

    func setSaved(kind: SavedPlaceKind, place p: PlaceSummary) {
        let input = SavedPlaceInput(name: kind == .favorite ? p.name : (kind == .home ? String(localized: "Home") : String(localized: "Work")),
                                    lat: p.lat, lng: p.lng, address: p.address, placeId: p.id)
        Task {
            do {
                _ = kind == .favorite ? try await repo.addFavorite(input) : try await repo.setPlace(kind: kind, input)
                if let s = try? await repo.savedPlaces() { savedPlaces = s }
                showToast(String(localized: "Saved"))
            } catch {
                showToast(error.localizedDescription)
            }
        }
    }

    // MARK: reports

    func submitReport(type: ReportType, subtype: String?, comment: String?) {
        let new = NewReport(type: type, subtype: subtype, lat: position.lat, lng: position.lng,
                            heading: heading, comment: comment?.isEmpty == true ? nil : comment)
        showReport = false
        Task {
            do {
                let r = try await repo.createReport(new)
                live.upsert(r)
                syncLiveLayers()
                showToast(String(localized: "Thanks! Your report is live."))
            } catch {
                showToast(String(localized: "Couldn't send report"))
            }
        }
    }

    func vote(_ report: Report, stillThere: Bool) {
        selectedAlert = nil
        Task {
            if let r = try? await repo.vote(reportId: report.id, stillThere: stillThere) { live.upsert(r); syncLiveLayers() }
            showToast(String(localized: "Thanks for confirming"))
        }
    }

    func thank(_ report: Report) {
        Task { try? await repo.thank(reportId: report.id) }
        showToast(String(localized: "Thanks sent"))
    }

    func updateProfile(_ patch: UserPatch) {
        Task {
            if let u = try? await repo.updateMe(patch) {
                user = u
                if let v = u.vehicle { vehicle = v }
            }
        }
    }

    func showToast(_ text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task {
            try? await Task.sleep(for: .seconds(2.5))
            if !Task.isCancelled { toast = nil }
        }
    }
}
