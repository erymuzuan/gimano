import Foundation

/// Everything the app needs from the backend. Implemented by `LiveRepository` (REST, docs/api.md),
/// `DemoRepository` (offline canned Kuala Lumpur data) and `FallbackRepository` (live with demo fallback).
protocol GimanoRepository: Sendable {
    func signIn() async throws -> DeviceAuthResponse
    func me() async throws -> User
    func updateMe(_ patch: UserPatch) async throws -> User

    func savedPlaces() async throws -> [SavedPlace]
    func setPlace(kind: SavedPlaceKind, _ input: SavedPlaceInput) async throws -> SavedPlace
    func addFavorite(_ input: SavedPlaceInput) async throws -> SavedPlace
    func deletePlace(id: String) async throws
    func recents() async throws -> [Recent]

    func search(query: String, near: LatLng, category: PlaceCategory?, limit: Int) async throws -> [PlaceSummary]
    func nearby(near: LatLng, category: PlaceCategory, radiusM: Int) async throws -> [PlaceSummary]
    func place(id: String) async throws -> Place
    func reverseGeocode(_ p: LatLng) async throws -> ReverseGeocode

    func routes(_ request: RouteRequest) async throws -> [Route]
    func reroute(routeId: String, _ request: RerouteRequest) async throws -> [Route]
    func departureTimes(origin: LatLng, destination: LatLng, date: Date) async throws -> [DepartureSlot]

    func live(bbox: BBox) async throws -> LiveResponse
    func createReport(_ report: NewReport) async throws -> Report
    func vote(reportId: String, stillThere: Bool) async throws -> Report
    func thank(reportId: String) async throws
    func sendLocations(_ batch: LocationBatch) async throws

    func health() async -> Bool
}

// MARK: - Live (REST)

/// Persists the anonymous device token per backend.
enum TokenStore {
    static func key(for base: URL) -> String { "auth.token.\(base.host() ?? "")\(base.port.map { ":\($0)" } ?? "")" }

    static func load(for base: URL) -> String? { Keychain.get(key(for: base)) }
    static func save(_ token: String?, for base: URL) { Keychain.set(token, for: key(for: base)) }

    static var deviceId: String {
        if let id = Keychain.get("device.id") { return id }
        let id = UUID().uuidString
        Keychain.set(id, for: "device.id")
        return id
    }
}

final class LiveRepository: GimanoRepository {
    let client: APIClient
    private let baseURL: URL

    init(baseURL: URL) {
        self.baseURL = baseURL
        self.client = APIClient(baseURL: baseURL, token: TokenStore.load(for: baseURL))
    }

    // MARK: auth

    func signIn() async throws -> DeviceAuthResponse {
        let locale = Locale.current.language.languageCode?.identifier ?? "en"
        let body = DeviceAuthRequest(deviceId: TokenStore.deviceId, locale: locale)
        let res: DeviceAuthResponse = try await client.send("POST", "auth/device", body: body)
        await client.setToken(res.token)
        TokenStore.save(res.token, for: baseURL)
        return res
    }

    /// Ensures a token exists and retries once after re-registering on 401.
    private func authed<T: Sendable>(_ op: @Sendable () async throws -> T) async throws -> T {
        if await client.token == nil { _ = try await signIn() }
        do {
            return try await op()
        } catch APIError.http(status: 401, _) {
            _ = try await signIn()
            return try await op()
        }
    }

    func me() async throws -> User { try await authed { try await self.client.get("me") } }

    func updateMe(_ patch: UserPatch) async throws -> User {
        try await authed { try await self.client.send("PATCH", "me", body: patch) }
    }

    // MARK: places

    func savedPlaces() async throws -> [SavedPlace] { try await authed { try await self.client.get("me/places") } }

    func setPlace(kind: SavedPlaceKind, _ input: SavedPlaceInput) async throws -> SavedPlace {
        try await authed { try await self.client.send("PUT", "me/places/\(kind.rawValue)", body: input) }
    }

    func addFavorite(_ input: SavedPlaceInput) async throws -> SavedPlace {
        try await authed { try await self.client.send("POST", "me/places", body: input) }
    }

    func deletePlace(id: String) async throws {
        try await authed { try await self.client.sendNoContent("DELETE", "me/places/\(id)", body: Optional<Empty>.none) }
    }

    func recents() async throws -> [Recent] { try await authed { try await self.client.get("me/recents") } }

    func search(query: String, near: LatLng, category: PlaceCategory?, limit: Int) async throws -> [PlaceSummary] {
        var q = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "lat", value: String(near.lat)),
            URLQueryItem(name: "lng", value: String(near.lng)),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        if let category { q.append(URLQueryItem(name: "category", value: category.rawValue)) }
        let query = q
        let res: SearchResponse = try await authed { try await self.client.get("search", query: query) }
        return res.results
    }

    func nearby(near: LatLng, category: PlaceCategory, radiusM: Int) async throws -> [PlaceSummary] {
        let q = [
            URLQueryItem(name: "lat", value: String(near.lat)),
            URLQueryItem(name: "lng", value: String(near.lng)),
            URLQueryItem(name: "category", value: category.rawValue),
            URLQueryItem(name: "radius", value: String(radiusM)),
        ]
        let res: SearchResponse = try await authed { try await self.client.get("search/nearby", query: q) }
        return res.results
    }

    func place(id: String) async throws -> Place {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        return try await authed { try await self.client.get("places/\(escaped)") }
    }

    func reverseGeocode(_ p: LatLng) async throws -> ReverseGeocode {
        try await authed {
            try await self.client.get("geocode/reverse", query: [
                URLQueryItem(name: "lat", value: String(p.lat)), URLQueryItem(name: "lng", value: String(p.lng)),
            ])
        }
    }

    // MARK: routing

    func routes(_ request: RouteRequest) async throws -> [Route] {
        let res: RoutesResponse = try await authed { try await self.client.send("POST", "routes", body: request) }
        return res.routes
    }

    func reroute(routeId: String, _ request: RerouteRequest) async throws -> [Route] {
        let res: RoutesResponse = try await authed { try await self.client.send("POST", "routes/\(routeId)/reroute", body: request) }
        return res.routes
    }

    func departureTimes(origin: LatLng, destination: LatLng, date: Date) async throws -> [DepartureSlot] {
        let day = date.formatted(Date.ISO8601FormatStyle().year().month().day())
        let q = [
            URLQueryItem(name: "originLat", value: String(origin.lat)), URLQueryItem(name: "originLng", value: String(origin.lng)),
            URLQueryItem(name: "destLat", value: String(destination.lat)), URLQueryItem(name: "destLng", value: String(destination.lng)),
            URLQueryItem(name: "date", value: day),
        ]
        let res: DepartureTimesResponse = try await authed { try await self.client.get("routes/departure-times", query: q) }
        return res.slots
    }

    // MARK: live

    func live(bbox: BBox) async throws -> LiveResponse {
        try await authed {
            try await self.client.get("live", query: [
                URLQueryItem(name: "bbox", value: bbox.queryValue),
                URLQueryItem(name: "types", value: "alerts,jams,drivers"),
            ])
        }
    }

    func createReport(_ report: NewReport) async throws -> Report {
        try await authed { try await self.client.send("POST", "reports", body: report) }
    }

    func vote(reportId: String, stillThere: Bool) async throws -> Report {
        try await authed { try await self.client.send("POST", "reports/\(reportId)/votes", body: VoteRequest(stillThere: stillThere)) }
    }

    func thank(reportId: String) async throws {
        try await authed { try await self.client.sendNoContent("POST", "reports/\(reportId)/thanks", body: Optional<Empty>.none) }
    }

    func sendLocations(_ batch: LocationBatch) async throws {
        try await authed { try await self.client.sendNoContent("POST", "locations", body: batch) }
    }

    func health() async -> Bool {
        // healthz lives at the host root, not under /api/v1.
        var c = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        c.path = "/healthz"
        guard let url = c.url else { return false }
        var req = URLRequest(url: url)
        req.timeoutInterval = 3
        guard let (_, res) = try? await URLSession.shared.data(for: req) else { return false }
        return (res as? HTTPURLResponse)?.statusCode == 200
    }
}

// MARK: - Fallback (auto mode)

/// Tries the live backend; when it is unreachable (or an endpoint is missing) serves demo data instead,
/// so the app stays fully demoable while the backend is being built.
final class FallbackRepository: GimanoRepository {
    let live: LiveRepository
    let demo: DemoRepository
    private let state = FallbackState()
    private let onStatus: @Sendable @MainActor (Bool) -> Void

    init(live: LiveRepository, demo: DemoRepository, onStatus: @escaping @Sendable @MainActor (Bool) -> Void) {
        self.live = live
        self.demo = demo
        self.onStatus = onStatus
    }

    private func run<T: Sendable>(_ liveOp: @Sendable (LiveRepository) async throws -> T,
                                  demoOp: @Sendable (DemoRepository) async throws -> T) async throws -> T {
        if await state.shouldSkipLive() { return try await demoOp(demo) }
        do {
            let value = try await liveOp(live)
            if await state.markOnline() { await onStatus(false) }
            return value
        } catch let e as APIError where e.isUnreachable {
            if await state.markOffline() { await onStatus(true) }
            return try await demoOp(demo)
        } catch let e as APIError where e.isServerSideGap {
            return try await demoOp(demo)
        }
    }

    func signIn() async throws -> DeviceAuthResponse { try await run({ try await $0.signIn() }, demoOp: { try await $0.signIn() }) }
    func me() async throws -> User { try await run({ try await $0.me() }, demoOp: { try await $0.me() }) }
    func updateMe(_ patch: UserPatch) async throws -> User { try await run({ try await $0.updateMe(patch) }, demoOp: { try await $0.updateMe(patch) }) }
    func savedPlaces() async throws -> [SavedPlace] { try await run({ try await $0.savedPlaces() }, demoOp: { try await $0.savedPlaces() }) }
    func setPlace(kind: SavedPlaceKind, _ input: SavedPlaceInput) async throws -> SavedPlace {
        try await run({ try await $0.setPlace(kind: kind, input) }, demoOp: { try await $0.setPlace(kind: kind, input) })
    }
    func addFavorite(_ input: SavedPlaceInput) async throws -> SavedPlace {
        try await run({ try await $0.addFavorite(input) }, demoOp: { try await $0.addFavorite(input) })
    }
    func deletePlace(id: String) async throws { try await run({ try await $0.deletePlace(id: id) }, demoOp: { try await $0.deletePlace(id: id) }) }
    func recents() async throws -> [Recent] { try await run({ try await $0.recents() }, demoOp: { try await $0.recents() }) }
    func search(query: String, near: LatLng, category: PlaceCategory?, limit: Int) async throws -> [PlaceSummary] {
        try await run({ try await $0.search(query: query, near: near, category: category, limit: limit) },
                      demoOp: { try await $0.search(query: query, near: near, category: category, limit: limit) })
    }
    func nearby(near: LatLng, category: PlaceCategory, radiusM: Int) async throws -> [PlaceSummary] {
        try await run({ try await $0.nearby(near: near, category: category, radiusM: radiusM) },
                      demoOp: { try await $0.nearby(near: near, category: category, radiusM: radiusM) })
    }
    func place(id: String) async throws -> Place { try await run({ try await $0.place(id: id) }, demoOp: { try await $0.place(id: id) }) }
    func reverseGeocode(_ p: LatLng) async throws -> ReverseGeocode { try await run({ try await $0.reverseGeocode(p) }, demoOp: { try await $0.reverseGeocode(p) }) }
    func routes(_ request: RouteRequest) async throws -> [Route] { try await run({ try await $0.routes(request) }, demoOp: { try await $0.routes(request) }) }
    func reroute(routeId: String, _ request: RerouteRequest) async throws -> [Route] {
        try await run({ try await $0.reroute(routeId: routeId, request) }, demoOp: { try await $0.reroute(routeId: routeId, request) })
    }
    func departureTimes(origin: LatLng, destination: LatLng, date: Date) async throws -> [DepartureSlot] {
        try await run({ try await $0.departureTimes(origin: origin, destination: destination, date: date) },
                      demoOp: { try await $0.departureTimes(origin: origin, destination: destination, date: date) })
    }
    func live(bbox: BBox) async throws -> LiveResponse { try await run({ try await $0.live(bbox: bbox) }, demoOp: { try await $0.live(bbox: bbox) }) }
    func createReport(_ report: NewReport) async throws -> Report { try await run({ try await $0.createReport(report) }, demoOp: { try await $0.createReport(report) }) }
    func vote(reportId: String, stillThere: Bool) async throws -> Report {
        try await run({ try await $0.vote(reportId: reportId, stillThere: stillThere) }, demoOp: { try await $0.vote(reportId: reportId, stillThere: stillThere) })
    }
    func thank(reportId: String) async throws { try await run({ try await $0.thank(reportId: reportId) }, demoOp: { try await $0.thank(reportId: reportId) }) }
    func sendLocations(_ batch: LocationBatch) async throws {
        try await run({ try await $0.sendLocations(batch) }, demoOp: { try await $0.sendLocations(batch) })
    }
    func health() async -> Bool { await live.health() }

    var isOffline: Bool { get async { await state.offline } }
}

/// After a transport failure, skip the live backend for a while so the UI isn't stuck on timeouts.
actor FallbackState {
    private(set) var offline = false
    private var retryAfter: Date = .distantPast
    var backoffS: TimeInterval = 30

    func shouldSkipLive() -> Bool { offline && Date() < retryAfter }

    /// Returns true when the status changed.
    func markOffline() -> Bool {
        retryAfter = Date().addingTimeInterval(backoffS)
        defer { offline = true }
        return !offline
    }

    func markOnline() -> Bool {
        defer { offline = false }
        return offline
    }
}
