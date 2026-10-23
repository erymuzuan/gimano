package my.gimano.app.data.repo

import my.gimano.app.data.model.*

/**
 * Everything the app needs from the backend. Implemented by [RemoteRepository] and [DemoRepository].
 * Personal data (favourites, recents, private places) is deliberately absent: see LocalStore.
 */
interface GimanoRepository : my.gimano.app.data.share.ShareApi {
    suspend fun ensureAuth(): User
    suspend fun updateMe(body: UpdateMeRequest): User

    /** Legacy server copy for the one-time migration; null if the server has none / no endpoint. */
    suspend fun exportMyData(): MyDataExport?
    suspend fun deleteMyData()

    suspend fun search(q: String, lat: Double?, lng: Double?, limit: Int = 10, category: String? = null): List<PlaceSummary>
    suspend fun nearby(lat: Double, lng: Double, category: String, radius: Int = 5000): List<PlaceSummary>
    suspend fun place(id: String, lat: Double? = null, lng: Double? = null): Place
    suspend fun reverse(lat: Double, lng: Double): ReverseGeocode
    suspend fun routes(req: RouteRequest): List<Route>
    suspend fun reroute(routeId: String, req: RerouteRequest): List<Route>
    suspend fun departureTimes(oLat: Double, oLng: Double, dLat: Double, dLng: Double, vehicle: String? = null): List<DepartureSlot>
    suspend fun live(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double): LiveResponse
    suspend fun createReport(body: CreateReportRequest): Report
    suspend fun vote(reportId: String, stillThere: Boolean): Report
    suspend fun thank(reportId: String)
    suspend fun postLocations(batch: LocationBatch)

    /** Inbox (public-contribution news); empty when the server has no inbox yet. */
    suspend fun inbox(): List<InboxItem>

    suspend fun sendFeedback(body: FeedbackRequest): FeedbackCreated
    suspend fun myFeedback(): List<FeedbackSummary>
    suspend fun deleteFeedbackContact(id: String)
    suspend fun sendCrashReport(body: CrashReportRequest): CrashCreated

    suspend fun createPlace(body: CreatePlaceRequest): Place
    suspend fun suggestEdit(placeId: String, body: SuggestEditRequest): SuggestionCreated
    suspend fun verifyPlace(placeId: String, body: VerifyRequest, ts: Long? = null): VerifyResult
    /** Pending community places in the geohash-6 [cell] and its 8 neighbours (excludes my own). */
    suspend fun pendingNearby(cell: String): List<PlaceSummary>
    suspend fun contributedPlaces(): List<PlaceSummary>

    suspend fun reviews(placeId: String, sort: String = "recent"): ReviewsPage
    suspend fun createReview(placeId: String, body: ReviewRequest): Review
    suspend fun updateReview(reviewId: String, body: ReviewRequest): Review
    suspend fun deleteReview(reviewId: String)
    suspend fun helpful(reviewId: String)
    suspend fun flagReview(reviewId: String, reason: String)
    suspend fun uploadPhoto(placeId: String, jpeg: ByteArray, caption: String?): Photo
    suspend fun photos(placeId: String): PhotosPage
}
