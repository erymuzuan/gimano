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
    /// Intermediate stops, in order (≤ 5).
    var stops: [TripStop] = []

    init(destination: Destination, vehicle: Vehicle) {
        self.destination = destination
        self.vehicle = vehicle
    }

    var selected: Route? { routes.first { $0.id == selectedId } ?? routes.first }

    /// Seconds from departure to each stop, then the destination.
    var schedule: StopSchedule? { selected.map { StopSchedule.make(route: $0, stops: stops.map(\.location)) } }
}

/// What the feedback sheet was opened for.
struct FeedbackDraft: Identifiable {
    let id = UUID()
    var screen: FeedbackScreen
    var screenshot: Data?
    var context: FeedbackContext
}

enum MenuSheet: String, Identifiable {
    case favorites, myFeedback, backup, contributions
    var id: String { rawValue }
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
    /// "Just for me" places (on device only).
    private(set) var privatePlaces: [PrivatePlace] = []
    /// Add-a-place flow (nil when closed); the coordinate is where the pin starts.
    var addPlaceStart: LatLng?
    /// Long-press menu: "Navigate here" / "Add a place here".
    var longPressPoint: LatLng?

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
    /// Place being saved from the place card ("Save" sheet).
    var savingPlace: PlaceSummary?
    var feedbackDraft: FeedbackDraft?
    /// Navigation feedback while moving: ask "Are you a passenger?" first.
    var askPassengerForFeedback = false
    var menuSheet: MenuSheet?
    /// A `.gimano` file opened from another app.
    var incomingBackup: URL?
    /// On-device personal data (favourites, recents, private places, settings).
    let store: LocalStore
    let backup: BackupManager
    let feedbackOutbox: FeedbackOutbox
    /// Server-side "My feedback" (merged with the local outbox in the UI).
    private(set) var serverFeedback: [FeedbackSummary] = []
    /// DEBUG/UI tests: fixed position instead of GPS (`-GimanoFakeLocation lat,lng`).
    @ObservationIgnored private var fixedLocation: LatLng?
    @ObservationIgnored private var outboxTimer: Task<Void, Never>?

    /// Latest position (simulated during demo drives) and motion.
    private(set) var position: LatLng = DemoRepository.defaultLocation
    private(set) var hasRealFix = false
    private(set) var speedKmh: Double = 0
    private(set) var heading: Double = 0

    @ObservationIgnored private var toastTask: Task<Void, Never>?

    init(config: AppConfig = AppConfig(), store: LocalStore = .shared) {
        self.config = config
        self.map = MapState(styleURL: config.styleURL)
        self.repo = DemoRepository()
        self.store = store
        self.backup = BackupManager(store: store)
        #if DEBUG
        Self.applyTestLaunchArguments(store: store)
        #endif
        self.feedbackOutbox = FeedbackOutbox(storage: LocalStoreOutboxStorage(store: store))
        #if DEBUG
        if let raw = UserDefaults.standard.string(forKey: "GimanoFakeLocation") {
            let parts = raw.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            if parts.count == 2 { fixedLocation = LatLng(lat: parts[0], lng: parts[1]); position = fixedLocation! }
        }
        #endif
        if let raw = UserDefaults.standard.string(forKey: "travelMode"), let v = Vehicle(rawValue: raw) { vehicle = v }
        wireMap()
        location.onFix = { [weak self] fix in self?.handleRealFix(fix) }
        rebuildRepository()
        startOutboxTimer()
    }

    #if DEBUG
    /// `-GimanoFreshDevice YES`: new anonymous device + empty local state (UI tests start clean).
    private static func applyTestLaunchArguments(store: LocalStore) {
        let d = UserDefaults.standard
        if d.bool(forKey: "GimanoFreshDevice") {
            Keychain.set(nil, for: "device.id")
            for key in ["travelMode", "location.askedAlways"] { d.removeObject(forKey: key) }
            for base in [AppConfig().apiBaseURL] { TokenStore.save(nil, for: base) }
            try? store.wipePersonalData()
            try? store.replaceOutbox(kind: "feedback", items: [])
            try? store.setSetting(LegacyDataMigration.doneKey, nil)
        }
        if d.bool(forKey: "GimanoSkipOnboarding") { d.set(true, forKey: "onboarding.done") }
    }
    #endif

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
        case "overview":
            map.setCamera(.center(LatLng(lat: 3.13, lng: 101.68), zoom: 11.6))
        case "route-far":
            // Not a captured destination → nearest captured route, labelled "Demo route".
            if let klia = DemoData.places.first(where: { $0.id == "demo:klia" }) {
                planRoute(to: Destination(name: klia.name, address: klia.address, location: klia.location, placeId: klia.id, category: klia.category))
            }
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
            if let v = u.vehicle, UserDefaults.standard.string(forKey: "travelMode") == nil { vehicle = v }
            if backendStatus == .unknown, config.dataMode == .live { backendStatus = .online }
        }
        await migrateLegacyDataIfNeeded()
        reloadLocal()
        await flushFeedback()
    }

    /// Reloads favourites and recents from the on-device store.
    func reloadLocal() {
        savedPlaces = (try? store.savedPlaces()) ?? []
        recents = (try? store.recents()) ?? []
        privatePlaces = (try? store.privatePlaces()) ?? []
        map.privatePins = privatePlaces.map { $0.location }
    }

    /// Local "Just for me" places matching a query.
    func privateMatches(_ query: String) -> [PrivatePlace] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return [] }
        return privatePlaces.filter { $0.name.lowercased().contains(q) || ($0.address ?? "").lowercased().contains(q) }
    }

    /// First launch after the on-device switch: download any server-side copy once, import it, then delete it
    /// on the server. A 404/410 (not deployed yet / already gone) just marks the migration done.
    func migrateLegacyDataIfNeeded() async {
        guard config.dataMode != .demo, (try? store.setting(LegacyDataMigration.doneKey)) == nil else { return }
        do {
            let export = try await repo.legacyExport()
            let n = try LegacyDataMigration.importExport(export, into: store)
            try? await repo.deleteLegacyData()
            try? store.setSetting(LegacyDataMigration.doneKey, LocalStore.timestamp())
            if n > 0 { showToast(String(localized: "Moved \(n) saved places to this phone")) }
        } catch let e as APIError {
            if case .http(let status, _) = e, status == 404 || status == 410 || status == 405 {
                try? store.setSetting(LegacyDataMigration.doneKey, LocalStore.timestamp())
            }
            // Offline: try again next launch.
        } catch {}
    }

    var home: SavedPlace? { savedPlaces.first { $0.kind == .home } }
    var work: SavedPlace? { savedPlaces.first { $0.kind == .work } }
    var favorites: [SavedPlace] { savedPlaces.filter { $0.kind == .favorite } }

    var isDemo: Bool { backendStatus == .demoFallback || backendStatus == .demoForced }

    // MARK: location

    private func handleRealFix(_ fix: LocationFix) {
        guard fixedLocation == nil else { return }
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
            self.longPressPoint = p
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
            // A substituted demo route ends at its own (captured) destination; pin that, not the requested place.
            if let r = planner.selected, r.demoNote != nil, let end = Polyline.decode(r.polyline).last {
                map.destination = end
            } else {
                map.destination = planner.destination.location
            }
            map.bubbleAnchors = Self.bubbleAnchors(for: planner.routes.map { ($0.id, Polyline.decode($0.polyline)) })
        case .navigating:
            guard let nav = navigation else { break }
            map.routes = [RouteLine(id: nav.route.id, points: nav.geometry.points, selected: true, traffic: nav.route.trafficSegments ?? [])]
            map.destination = nav.route.demoNote != nil ? (nav.geometry.points.last ?? nav.destination.location) : nav.destination.location
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

    func planRoute(to d: Destination, stops: [TripStop] = []) {
        // Recents stay on the device.
        try? store.recordRecent(name: d.name, address: d.address, lat: d.location.lat, lng: d.location.lng, placeId: d.placeId)
        recents = (try? store.recents()) ?? recents
        let planner = RoutePlanner(destination: d, vehicle: vehicle)
        planner.stops = stops
        self.planner = planner
        go(.routePreview)
        Task { await fetchRoutes() }
    }

    /// Travel mode tabs: remembered locally and on the profile (`PATCH /me vehicle`).
    func setTravelMode(_ v: Vehicle) {
        vehicle = v
        UserDefaults.standard.set(v.rawValue, forKey: "travelMode")
        updateProfile(UserPatch(vehicle: v))
        if let planner, planner.vehicle != v {
            planner.vehicle = v
            Task { await fetchRoutes() }
        }
    }

    // MARK: stops

    func removeStop(_ id: UUID) {
        guard let planner else { return }
        planner.stops.removeAll { $0.id == id }
        Task { await fetchRoutes() }
    }

    func moveStops(from: IndexSet, to: Int) {
        guard let planner else { return }
        StopPlanner.move(&planner.stops, from: from, to: to)
        Task { await fetchRoutes() }
    }

    func fetchRoutes() async {
        guard let planner else { return }
        planner.isLoading = true
        planner.error = nil
        let req = RouteRequest(
            origin: RouteEndpoint(lat: position.lat, lng: position.lng),
            destination: RouteEndpoint(lat: planner.destination.location.lat, lng: planner.destination.location.lng, placeId: planner.destination.placeId),
            waypoints: planner.stops.map(\.endpoint),
            vehicle: planner.vehicle, avoid: planner.vehicle.isMotorized ? planner.avoid : AvoidOptions(),
            departAt: planner.departAt, alternatives: 2)
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
        let session = NavigationSession(destination: planner.destination, route: route, stops: planner.stops, vehicle: planner.vehicle,
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
        // Demo routes are captured fixtures that may not start where the device is, so always simulate them.
        config.simulateDrive || isDemo || !hasRealFix || fixedLocation != nil || planner?.selected?.demoNote != nil
    }

    private func startSimulator(on route: Route) {
        let pts = Polyline.decode(route.polyline)
        let mode = navigation?.vehicle ?? planner?.vehicle ?? .car
        let speed: Double = switch mode {
        case .car: 50
        case .motorcycle: 55
        case .bicycle: 16
        case .walk: 5
        }
        // Slow modes get a bigger speed-up so demos don't take an hour.
        let speedup = config.simulationSpeedup * (mode.isMotorized ? 1 : 4)
        simulator.start(points: pts, speedKmh: speed, speedup: speedup) { [weak self] fix in
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
        reloadLocal()
    }

    /// "Add a stop" from search: appends to the trip being previewed, or to the active drive.
    func addStop(_ p: PlaceSummary) {
        let stop = TripStop(name: p.name, location: p.location, placeId: p.id.hasPrefix("pin:") ? nil : p.id)
        if let nav = navigation {
            var remaining = Array(nav.stops.dropFirst(nav.stopTracker.nextStopIndex))
            guard StopPlanner.add(stop, to: &remaining) else {
                showToast(String(localized: "A trip can have up to 5 stops"))
                go(.navigating)
                return
            }
            let req = RouteRequest(
                origin: RouteEndpoint(lat: position.lat, lng: position.lng),
                destination: RouteEndpoint(lat: nav.destination.location.lat, lng: nav.destination.location.lng, placeId: nav.destination.placeId),
                waypoints: remaining.map(\.endpoint), vehicle: nav.vehicle, avoid: nav.avoid, alternatives: 0)
            go(.navigating)
            Task {
                do {
                    if let r = try await repo.routes(req).first {
                        nav.setRoute(r, stops: remaining)
                        showToast(String(localized: "Stop added: \(p.name)"))
                    }
                } catch {
                    showToast(error.localizedDescription)
                }
            }
        } else if let planner {
            guard StopPlanner.add(stop, to: &planner.stops) else {
                showToast(String(localized: "A trip can have up to 5 stops"))
                go(.routePreview)
                return
            }
            go(.routePreview)
            Task { await fetchRoutes() }
        } else {
            openPlace(p)
        }
    }

    // MARK: saved places (on device)

    func setSaved(kind: SavedPlaceKind, place p: PlaceSummary, label: String? = nil, icon: FavoriteIcon? = nil) {
        let placeId = p.id.hasPrefix("pin:") || p.id.hasPrefix("local:") ? nil : p.id
        do {
            switch kind {
            case .home, .work:
                try store.setHomeWork(kind, name: kind == .home ? String(localized: "Home") : String(localized: "Work"),
                                      lat: p.lat, lng: p.lng, address: p.address, placeId: placeId)
            case .favorite:
                let name = (label?.trimmingCharacters(in: .whitespaces)).flatMap { $0.isEmpty ? nil : $0 } ?? p.name
                try store.addFavorite(name: name, icon: icon ?? QuickLabel.suggestedIcon(for: name) ?? .star,
                                      lat: p.lat, lng: p.lng, address: p.address, placeId: placeId)
            }
            reloadLocal()
            showToast(String(localized: "Saved"))
        } catch {
            showToast(error.localizedDescription)
        }
    }

    func icon(for place: SavedPlace) -> FavoriteIcon { place.favoriteIcon }

    func updateFavorite(_ place: SavedPlace, name: String, icon: FavoriteIcon) {
        try? store.updateFavorite(id: place.id, name: name.trimmingCharacters(in: .whitespaces), icon: icon)
        reloadLocal()
    }

    func deleteFavorite(_ place: SavedPlace) {
        try? store.deleteSavedPlace(id: place.id)
        reloadLocal()
    }

    func moveFavorites(from: IndexSet, to: Int) {
        var favs = favorites
        favs.move(fromOffsets: from, toOffset: to)
        try? store.reorderFavorites(favs.map(\.id))
        reloadLocal()
    }

    /// Promotes a favourite to Home or Work.
    func promote(_ place: SavedPlace, to kind: SavedPlaceKind) {
        let p = PlaceSummary(id: place.placeId ?? "local:\(place.id)", name: place.name, category: .other, address: place.address,
                             lat: place.lat, lng: place.lng, distanceM: nil, brand: nil)
        setSaved(kind: kind, place: p)
    }

    func navigate(to saved: SavedPlace) {
        planRoute(to: Destination(name: saved.name, address: saved.address, location: saved.location, placeId: saved.placeId))
    }

    /// Local favourites matching a search query (shown before server results).
    func localMatches(_ query: String) -> [SavedPlace] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return [] }
        return savedPlaces.filter {
            $0.name.lowercased().contains(q) || $0.address.lowercased().contains(q)
                || ($0.kind == .home && String(localized: "Home").lowercased().hasPrefix(q))
                || ($0.kind == .work && String(localized: "Work").lowercased().hasPrefix(q))
        }
    }

    // MARK: feedback

    /// Opens "Add feedback": captures the current screen first, then shows the form.
    /// During navigation it's only allowed when stopped (< 5 km/h) or after the user confirms they're a passenger.
    func openFeedback(from screen: FeedbackScreen, placeId: String? = nil, passengerConfirmed: Bool = false) {
        if screen == .navigation, !FeedbackRules.allowedWhileDriving(speedKmh: speedKmh, isPassenger: passengerConfirmed) {
            askPassengerForFeedback = true
            return
        }
        let shot = FeedbackAttachments.captureWindowJPEG()
        let routeId = navigation?.route.id ?? planner?.selected?.id
        // Privacy: location in diagnostics is rounded to 2 dp (~1 km); diagnostics are opt-in in the form.
        let rounded = LatLng(lat: (position.lat * 100).rounded() / 100, lng: (position.lng * 100).rounded() / 100)
        feedbackDraft = FeedbackDraft(screen: screen, screenshot: shot,
                                      context: FeedbackAttachments.diagnostics(position: rounded, routeId: routeId, placeId: placeId))
    }

    func submitFeedback(_ request: FeedbackRequest) {
        _ = feedbackOutbox.enqueue(request)
        feedbackDraft = nil
        Task {
            let sent = await feedbackOutbox.flush { [repo] in try await repo.sendFeedback($0) }
            showToast(sent > 0 ? String(localized: "Thanks! Feedback sent") : String(localized: "Saved. We'll send it when we can."))
        }
    }

    func flushFeedback() async {
        let repo = self.repo
        await feedbackOutbox.flush { try await repo.sendFeedback($0) }
    }

    func refreshMyFeedback() async {
        await flushFeedback()
        if let list = try? await repo.myFeedback() {
            serverFeedback = list
            feedbackOutbox.applyServerStatuses(list)
        }
    }

    private func startOutboxTimer() {
        outboxTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                await self?.flushFeedback()
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
