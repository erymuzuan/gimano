import Foundation

/// Everything the app needs from the backend. Implemented by `LiveRepository` (REST, docs/api.md),
/// `DemoRepository` (offline canned Kuala Lumpur data) and `FallbackRepository` (live with demo fallback).
protocol GimanoRepository: Sendable {
    func signIn() async throws -> DeviceAuthResponse
    func me() async throws -> User
    func updateMe(_ patch: UserPatch) async throws -> User

    /// One-time migration of personal data the server held for earlier app versions (`GET /me/data/export`).
    func legacyExport() async throws -> LegacyDataMigration.Export
    /// Permanently deletes that legacy server copy (`DELETE /me/data`).
    func deleteLegacyData() async throws

    func search(query: String, near: LatLng, category: PlaceCategory?, limit: Int) async throws -> [PlaceSummary]
    func nearby(near: LatLng, category: PlaceCategory, radiusM: Int) async throws -> [PlaceSummary]
    func place(id: String, near: LatLng?) async throws -> Place
    func reverseGeocode(_ p: LatLng) async throws -> ReverseGeocode

    func routes(_ request: RouteRequest) async throws -> [Route]
    func reroute(routeId: String, _ request: RerouteRequest) async throws -> [Route]
    func departureTimes(origin: LatLng, destination: LatLng, date: Date, vehicle: Vehicle) async throws -> [DepartureSlot]

    func live(bbox: BBox) async throws -> LiveResponse
    func createReport(_ report: NewReport) async throws -> Report
    func vote(reportId: String, stillThere: Bool) async throws -> Report
    func thank(reportId: String) async throws
    func sendLocations(_ batch: LocationBatch) async throws

    func sendFeedback(_ feedback: FeedbackRequest) async throws -> FeedbackCreated
    func myFeedback() async throws -> [FeedbackSummary]

    // Community places
    func createPlace(_ place: NewPlace) async throws -> Place
    func updateCommunityPlace(id: String, _ place: NewPlace) async throws -> Place
    func deleteCommunityPlace(id: String) async throws
    func suggestEdit(placeId: String, _ s: PlaceEditSuggestion) async throws -> SuggestionAccepted
    func verifyPlace(id: String, _ v: VerifyPlaceRequest) async throws -> Place
    func myContributions() async throws -> [PlaceSummary]

    // Reviews & photos
    func reviews(placeId: String, sort: ReviewSort, cursor: String?) async throws -> ReviewsPage
    func createReview(placeId: String, _ r: NewReview) async throws -> Review
    func updateReview(id: String, _ r: NewReview) async throws -> Review
    func deleteReview(id: String) async throws
    func toggleHelpful(reviewId: String) async throws
    func flagReview(id: String, reason: FlagReason) async throws
    /// Uploads a JPEG (already ≤ 2048 px, metadata-free); the server re-encodes to WebP.
    func uploadPhoto(_ jpeg: Data, placeId: String, caption: String?, progress: @escaping @Sendable (Double) -> Void) async throws -> Photo
    func photos(placeId: String, cursor: String?) async throws -> PhotosPage
    func deletePhoto(id: String) async throws
    func flagPhoto(id: String, reason: FlagReason) async throws

    // Inbox
    func inbox(cursor: String?) async throws -> InboxPage
    func markInboxRead(upTo: Date) async throws

    // Share my drive (server relays ciphertext only)
    func createShare(_ r: ShareCreateRequest) async throws -> ShareCreated
    func putShare(id: String, writeToken: String, _ blob: ShareBlob) async throws
    func deleteShare(id: String, writeToken: String) async throws

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

    // MARK: legacy personal data (migration only — personal data now lives on the device)

    func legacyExport() async throws -> LegacyDataMigration.Export {
        try await authed { try await self.client.get("me/data/export") }
    }

    func deleteLegacyData() async throws {
        try await authed { try await self.client.sendNoContent("DELETE", "me/data", body: Optional<Empty>.none) }
    }

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

    func place(id: String, near: LatLng?) async throws -> Place {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        let q = near.map { [URLQueryItem(name: "lat", value: String($0.lat)), URLQueryItem(name: "lng", value: String($0.lng))] } ?? []
        return try await authed { try await self.client.get("places/\(escaped)", query: q) }
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

    func departureTimes(origin: LatLng, destination: LatLng, date: Date, vehicle: Vehicle) async throws -> [DepartureSlot] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Kuala_Lumpur")!
        let c = cal.dateComponents([.year, .month, .day], from: date)
        let day = String(format: "%04d-%02d-%02d", c.year ?? 2026, c.month ?? 1, c.day ?? 1)
        let q = [
            URLQueryItem(name: "vehicle", value: vehicle.isMotorized ? vehicle.rawValue : Vehicle.car.rawValue),
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

    func sendFeedback(_ feedback: FeedbackRequest) async throws -> FeedbackCreated {
        try await authed { try await self.client.send("POST", "feedback", body: feedback) }
    }

    func myFeedback() async throws -> [FeedbackSummary] {
        try await authed { try await self.client.get("me/feedback") }
    }

    // MARK: community places, reviews, photos

    func createPlace(_ place: NewPlace) async throws -> Place {
        try await authed { try await self.client.send("POST", "places", body: place) }
    }

    func updateCommunityPlace(id: String, _ place: NewPlace) async throws -> Place {
        try await authed { try await self.client.send("PATCH", "places/\(id)", body: place) }
    }

    func deleteCommunityPlace(id: String) async throws {
        try await authed { try await self.client.sendNoContent("DELETE", "places/\(id)", body: Optional<Empty>.none) }
    }

    func suggestEdit(placeId: String, _ s: PlaceEditSuggestion) async throws -> SuggestionAccepted {
        try await authed { try await self.client.send("POST", "places/\(placeId)/suggest-edit", body: s) }
    }

    func verifyPlace(id: String, _ v: VerifyPlaceRequest) async throws -> Place {
        try await authed { try await self.client.send("POST", "places/\(id)/verify", body: v) }
    }

    func myContributions() async throws -> [PlaceSummary] {
        try await authed { try await self.client.get("me/places/contributed") }
    }

    func reviews(placeId: String, sort: ReviewSort, cursor: String?) async throws -> ReviewsPage {
        var q = [URLQueryItem(name: "sort", value: sort.rawValue)]
        if let cursor { q.append(URLQueryItem(name: "cursor", value: cursor)) }
        let query = q
        return try await authed { try await self.client.get("places/\(placeId)/reviews", query: query) }
    }

    func createReview(placeId: String, _ r: NewReview) async throws -> Review {
        try await authed { try await self.client.send("POST", "places/\(placeId)/reviews", body: r) }
    }

    func updateReview(id: String, _ r: NewReview) async throws -> Review {
        try await authed { try await self.client.send("PATCH", "reviews/\(id)", body: r) }
    }

    func deleteReview(id: String) async throws {
        try await authed { try await self.client.sendNoContent("DELETE", "reviews/\(id)", body: Optional<Empty>.none) }
    }

    func toggleHelpful(reviewId: String) async throws {
        try await authed { try await self.client.sendNoContent("POST", "reviews/\(reviewId)/helpful", body: Optional<Empty>.none) }
    }

    func flagReview(id: String, reason: FlagReason) async throws {
        try await authed { try await self.client.sendNoContent("POST", "reviews/\(id)/flag", body: FlagRequest(reason: reason)) }
    }

    func uploadPhoto(_ jpeg: Data, placeId: String, caption: String?, progress: @escaping @Sendable (Double) -> Void) async throws -> Photo {
        var fields = ["placeId": placeId]
        if let caption, !caption.isEmpty { fields["caption"] = caption }
        let f = fields
        return try await authed {
            try await self.client.upload("photos", fields: f, file: jpeg, fileName: "photo.jpg", mimeType: "image/jpeg", progress: progress)
        }
    }

    func photos(placeId: String, cursor: String?) async throws -> PhotosPage {
        let q = cursor.map { [URLQueryItem(name: "cursor", value: $0)] } ?? []
        return try await authed { try await self.client.get("places/\(placeId)/photos", query: q) }
    }

    func deletePhoto(id: String) async throws {
        try await authed { try await self.client.sendNoContent("DELETE", "photos/\(id)", body: Optional<Empty>.none) }
    }

    func flagPhoto(id: String, reason: FlagReason) async throws {
        try await authed { try await self.client.sendNoContent("POST", "photos/\(id)/flag", body: FlagRequest(reason: reason)) }
    }

    func createShare(_ r: ShareCreateRequest) async throws -> ShareCreated {
        try await authed { try await self.client.send("POST", "shares", body: r) }
    }

    func inbox(cursor: String?) async throws -> InboxPage {
        let q = cursor.map { [URLQueryItem(name: "cursor", value: $0)] } ?? []
        return try await authed { try await self.client.get("me/inbox", query: q) }
    }

    func markInboxRead(upTo: Date) async throws {
        try await authed { try await self.client.sendNoContent("POST", "me/inbox/read", body: InboxReadRequest(upTo: upTo)) }
    }

    func putShare(id: String, writeToken: String, _ blob: ShareBlob) async throws {
        try await client.sendNoContent("PUT", "shares/\(id)", body: blob, authorization: "Share \(writeToken)")
    }

    func deleteShare(id: String, writeToken: String) async throws {
        try await client.sendNoContent("DELETE", "shares/\(id)", body: Optional<Empty>.none, authorization: "Share \(writeToken)")
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

    /// - Parameter gapFallback: serve demo data when the endpoint is missing (404/5xx). Off for writes, so
    ///   user data is never silently written to the local demo store while the backend is online.
    private func run<T: Sendable>(gapFallback: Bool = true,
                                  _ liveOp: @Sendable (LiveRepository) async throws -> T,
                                  demoOp: @Sendable (DemoRepository) async throws -> T) async throws -> T {
        if await state.shouldSkipLive() { return try await demoOp(demo) }
        do {
            let value = try await liveOp(live)
            if await state.markOnline() { await onStatus(false) }
            return value
        } catch let e as APIError where e.isUnreachable {
            if await state.markOffline() { await onStatus(true) }
            return try await demoOp(demo)
        } catch let e as APIError where e.isServerSideGap && gapFallback {
            return try await demoOp(demo)
        }
    }

    func signIn() async throws -> DeviceAuthResponse { try await run({ try await $0.signIn() }, demoOp: { try await $0.signIn() }) }
    func me() async throws -> User { try await run({ try await $0.me() }, demoOp: { try await $0.me() }) }
    func updateMe(_ patch: UserPatch) async throws -> User { try await run(gapFallback: false, { try await $0.updateMe(patch) }, demoOp: { try await $0.updateMe(patch) }) }
    /// Migration calls go only to the real backend (never demo).
    func legacyExport() async throws -> LegacyDataMigration.Export { try await live.legacyExport() }
    func deleteLegacyData() async throws { try await live.deleteLegacyData() }
    func search(query: String, near: LatLng, category: PlaceCategory?, limit: Int) async throws -> [PlaceSummary] {
        try await run({ try await $0.search(query: query, near: near, category: category, limit: limit) },
                      demoOp: { try await $0.search(query: query, near: near, category: category, limit: limit) })
    }
    func nearby(near: LatLng, category: PlaceCategory, radiusM: Int) async throws -> [PlaceSummary] {
        try await run({ try await $0.nearby(near: near, category: category, radiusM: radiusM) },
                      demoOp: { try await $0.nearby(near: near, category: category, radiusM: radiusM) })
    }
    func place(id: String, near: LatLng?) async throws -> Place { try await run({ try await $0.place(id: id, near: near) }, demoOp: { try await $0.place(id: id, near: near) }) }
    func reverseGeocode(_ p: LatLng) async throws -> ReverseGeocode { try await run({ try await $0.reverseGeocode(p) }, demoOp: { try await $0.reverseGeocode(p) }) }
    func routes(_ request: RouteRequest) async throws -> [Route] { try await run({ try await $0.routes(request) }, demoOp: { try await $0.routes(request) }) }
    func reroute(routeId: String, _ request: RerouteRequest) async throws -> [Route] {
        try await run({ try await $0.reroute(routeId: routeId, request) }, demoOp: { try await $0.reroute(routeId: routeId, request) })
    }
    func departureTimes(origin: LatLng, destination: LatLng, date: Date, vehicle: Vehicle) async throws -> [DepartureSlot] {
        try await run({ try await $0.departureTimes(origin: origin, destination: destination, date: date, vehicle: vehicle) },
                      demoOp: { try await $0.departureTimes(origin: origin, destination: destination, date: date, vehicle: vehicle) })
    }
    func live(bbox: BBox) async throws -> LiveResponse { try await run({ try await $0.live(bbox: bbox) }, demoOp: { try await $0.live(bbox: bbox) }) }
    func createReport(_ report: NewReport) async throws -> Report { try await run(gapFallback: false, { try await $0.createReport(report) }, demoOp: { try await $0.createReport(report) }) }
    func vote(reportId: String, stillThere: Bool) async throws -> Report {
        try await run({ try await $0.vote(reportId: reportId, stillThere: stillThere) }, demoOp: { try await $0.vote(reportId: reportId, stillThere: stillThere) })
    }
    func thank(reportId: String) async throws { try await run({ try await $0.thank(reportId: reportId) }, demoOp: { try await $0.thank(reportId: reportId) }) }
    func sendLocations(_ batch: LocationBatch) async throws {
        try await run({ try await $0.sendLocations(batch) }, demoOp: { try await $0.sendLocations(batch) })
    }
    /// Feedback never goes to the demo store while in auto mode: failures leave it in the outbox for retry.
    func sendFeedback(_ feedback: FeedbackRequest) async throws -> FeedbackCreated { try await live.sendFeedback(feedback) }
    func myFeedback() async throws -> [FeedbackSummary] { try await live.myFeedback() }

    // Community contributions are public writes: no silent demo fallback while the backend is online.
    func createPlace(_ place: NewPlace) async throws -> Place {
        try await run(gapFallback: false, { try await $0.createPlace(place) }, demoOp: { try await $0.createPlace(place) })
    }
    func updateCommunityPlace(id: String, _ place: NewPlace) async throws -> Place {
        try await run(gapFallback: false, { try await $0.updateCommunityPlace(id: id, place) }, demoOp: { try await $0.updateCommunityPlace(id: id, place) })
    }
    func deleteCommunityPlace(id: String) async throws {
        try await run(gapFallback: false, { try await $0.deleteCommunityPlace(id: id) }, demoOp: { try await $0.deleteCommunityPlace(id: id) })
    }
    func suggestEdit(placeId: String, _ s: PlaceEditSuggestion) async throws -> SuggestionAccepted {
        try await run(gapFallback: false, { try await $0.suggestEdit(placeId: placeId, s) }, demoOp: { try await $0.suggestEdit(placeId: placeId, s) })
    }
    func verifyPlace(id: String, _ v: VerifyPlaceRequest) async throws -> Place {
        try await run(gapFallback: false, { try await $0.verifyPlace(id: id, v) }, demoOp: { try await $0.verifyPlace(id: id, v) })
    }
    func myContributions() async throws -> [PlaceSummary] {
        try await run({ try await $0.myContributions() }, demoOp: { try await $0.myContributions() })
    }
    func reviews(placeId: String, sort: ReviewSort, cursor: String?) async throws -> ReviewsPage {
        try await run({ try await $0.reviews(placeId: placeId, sort: sort, cursor: cursor) }, demoOp: { try await $0.reviews(placeId: placeId, sort: sort, cursor: cursor) })
    }
    func createReview(placeId: String, _ r: NewReview) async throws -> Review {
        try await run(gapFallback: false, { try await $0.createReview(placeId: placeId, r) }, demoOp: { try await $0.createReview(placeId: placeId, r) })
    }
    func updateReview(id: String, _ r: NewReview) async throws -> Review {
        try await run(gapFallback: false, { try await $0.updateReview(id: id, r) }, demoOp: { try await $0.updateReview(id: id, r) })
    }
    func deleteReview(id: String) async throws {
        try await run(gapFallback: false, { try await $0.deleteReview(id: id) }, demoOp: { try await $0.deleteReview(id: id) })
    }
    func toggleHelpful(reviewId: String) async throws {
        try await run(gapFallback: false, { try await $0.toggleHelpful(reviewId: reviewId) }, demoOp: { try await $0.toggleHelpful(reviewId: reviewId) })
    }
    func flagReview(id: String, reason: FlagReason) async throws {
        try await run(gapFallback: false, { try await $0.flagReview(id: id, reason: reason) }, demoOp: { try await $0.flagReview(id: id, reason: reason) })
    }
    func uploadPhoto(_ jpeg: Data, placeId: String, caption: String?, progress: @escaping @Sendable (Double) -> Void) async throws -> Photo {
        try await run(gapFallback: false, { try await $0.uploadPhoto(jpeg, placeId: placeId, caption: caption, progress: progress) },
                      demoOp: { try await $0.uploadPhoto(jpeg, placeId: placeId, caption: caption, progress: progress) })
    }
    func photos(placeId: String, cursor: String?) async throws -> PhotosPage {
        try await run({ try await $0.photos(placeId: placeId, cursor: cursor) }, demoOp: { try await $0.photos(placeId: placeId, cursor: cursor) })
    }
    func deletePhoto(id: String) async throws {
        try await run(gapFallback: false, { try await $0.deletePhoto(id: id) }, demoOp: { try await $0.deletePhoto(id: id) })
    }
    func flagPhoto(id: String, reason: FlagReason) async throws {
        try await run(gapFallback: false, { try await $0.flagPhoto(id: id, reason: reason) }, demoOp: { try await $0.flagPhoto(id: id, reason: reason) })
    }

    /// No demo items in auto mode: an inbox must only show real news about the user's contributions.
    func inbox(cursor: String?) async throws -> InboxPage {
        try await run(gapFallback: false, { try await $0.inbox(cursor: cursor) }, demoOp: { _ in InboxPage(items: [], next: nil, unread: 0) })
    }
    func markInboxRead(upTo: Date) async throws {
        try await run(gapFallback: false, { try await $0.markInboxRead(upTo: upTo) }, demoOp: { _ in })
    }

    func createShare(_ r: ShareCreateRequest) async throws -> ShareCreated {
        try await run(gapFallback: false, { try await $0.createShare(r) }, demoOp: { try await $0.createShare(r) })
    }
    func putShare(id: String, writeToken: String, _ blob: ShareBlob) async throws {
        try await run(gapFallback: false, { try await $0.putShare(id: id, writeToken: writeToken, blob) },
                      demoOp: { try await $0.putShare(id: id, writeToken: writeToken, blob) })
    }
    func deleteShare(id: String, writeToken: String) async throws {
        try await run(gapFallback: false, { try await $0.deleteShare(id: id, writeToken: writeToken) },
                      demoOp: { try await $0.deleteShare(id: id, writeToken: writeToken) })
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
