package my.gimano.app.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.gimano.app.data.model.*
import my.gimano.app.data.settings.SettingsStore
import java.io.IOException

/**
 * Routes calls to the demo backend when demo mode is on, or automatically after the real
 * server proved unreachable (connection-level failure). [fallbackActive] lets the UI say so
 * and offer a retry.
 */
class SwitchingRepository(
    private val settings: SettingsStore,
    private val remote: RemoteRepository,
    val demo: DemoRepository,
) : GimanoRepository {

    private val _fallbackActive = MutableStateFlow(false)
    val fallbackActive: StateFlow<Boolean> = _fallbackActive.asStateFlow()

    val isDemo: Boolean get() = settings.current.demoMode || _fallbackActive.value

    fun retryRemote() {
        _fallbackActive.value = false
    }

    private suspend fun <T> pick(block: suspend GimanoRepository.() -> T): T {
        if (isDemo) return demo.block()
        return try {
            remote.block()
        } catch (e: retrofit2.HttpException) {
            // The proxy answers but the API behind it is down (500/502/503/504 HTML page): same as unreachable.
            if (!my.gimano.app.core.net.Reachability.isGatewayDown(e.code())) throw e
            lastFallbackReason = e.response()?.raw()?.request?.let { "${it.method} ${it.url.encodedPath} → HTTP ${e.code()}" } ?: "HTTP ${e.code()}"
            _fallbackActive.value = true
            demo.block()
        } catch (e: IOException) {
            // A dropped keep-alive connection ("unexpected end of stream") is not an outage: retry once.
            try {
                kotlinx.coroutines.delay(300)
                remote.block()
            } catch (e2: IOException) {
                if (!isUnreachable(e2)) throw e2
                // Server unreachable (wrong base URL, no backend, no Tailscale, offline): keep the app usable.
                lastFallbackReason = e2.javaClass.simpleName
                _fallbackActive.value = true
                demo.block()
            }
        }
    }

    /** Why the app last switched to demo data (endpoint + status or exception type; never coordinates). */
    @Volatile var lastFallbackReason: String? = null

    /** True only for "can't reach the server at all" failures. */
    fun isUnreachable(e: IOException): Boolean = my.gimano.app.core.net.Reachability.isUnreachable(e)

    /** While falling back, the app probes the server and switches back as soon as it answers. */
    suspend fun probeRemote(): Boolean = try { remote.ensureAuth(); _fallbackActive.value = false; true } catch (e: Exception) { false }

    /**
     * Writes the user expects to reach the real server (feedback, places, reviews, photos) never
     * fall back to demo silently: failures propagate so the caller can queue or show an error.
     */
    private suspend fun <T> write(block: suspend GimanoRepository.() -> T): T =
        if (settings.current.demoMode) demo.block() else remote.block()

    override suspend fun ensureAuth() = pick { ensureAuth() }
    override suspend fun updateMe(body: UpdateMeRequest) = pick { updateMe(body) }
    override suspend fun exportMyData() = if (settings.current.demoMode) null else remote.exportMyData()
    override suspend fun deleteMyData() = if (settings.current.demoMode) Unit else remote.deleteMyData()
    override suspend fun search(q: String, lat: Double?, lng: Double?, limit: Int, category: String?) = pick { search(q, lat, lng, limit, category) }
    override suspend fun nearby(lat: Double, lng: Double, category: String, radius: Int) = pick { nearby(lat, lng, category, radius) }
    override suspend fun place(id: String, lat: Double?, lng: Double?) = pick { place(id, lat, lng) }
    override suspend fun reverse(lat: Double, lng: Double) = pick { reverse(lat, lng) }
    override suspend fun routes(req: RouteRequest) = pick { routes(req) }
    override suspend fun reroute(routeId: String, req: RerouteRequest) = pick { reroute(routeId, req) }
    override suspend fun departureTimes(oLat: Double, oLng: Double, dLat: Double, dLng: Double, vehicle: String?) = pick { departureTimes(oLat, oLng, dLat, dLng, vehicle) }
    override suspend fun live(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double) = pick { live(minLng, minLat, maxLng, maxLat) }
    override suspend fun createReport(body: CreateReportRequest) = pick { createReport(body) }
    override suspend fun vote(reportId: String, stillThere: Boolean) = pick { vote(reportId, stillThere) }
    override suspend fun thank(reportId: String) = pick { thank(reportId) }
    override suspend fun postLocations(batch: LocationBatch) = pick { postLocations(batch) }

    override suspend fun inbox() = pick { inbox() }
    override suspend fun sendFeedback(body: FeedbackRequest) = write { sendFeedback(body) }
    override suspend fun myFeedback() = write { myFeedback() }
    override suspend fun deleteFeedbackContact(id: String) = write { deleteFeedbackContact(id) }
    override suspend fun sendCrashReport(body: CrashReportRequest) = write { sendCrashReport(body) }
    override suspend fun createPlace(body: CreatePlaceRequest) = write { createPlace(body) }
    override suspend fun suggestEdit(placeId: String, body: SuggestEditRequest) = write { suggestEdit(placeId, body) }
    override suspend fun verifyPlace(placeId: String, body: VerifyRequest, ts: Long?) = write { verifyPlace(placeId, body, ts) }
    override suspend fun pendingNearby(cell: String) = pick { pendingNearby(cell) }
    override suspend fun contributedPlaces() = write { contributedPlaces() }
    override suspend fun reviews(placeId: String, sort: String) = pick { reviews(placeId, sort) }
    override suspend fun createReview(placeId: String, body: ReviewRequest) = write { createReview(placeId, body) }
    override suspend fun updateReview(reviewId: String, body: ReviewRequest) = write { updateReview(reviewId, body) }
    override suspend fun deleteReview(reviewId: String) = write { deleteReview(reviewId) }
    override suspend fun helpful(reviewId: String) = write { helpful(reviewId) }
    override suspend fun flagReview(reviewId: String, reason: String) = write { flagReview(reviewId, reason) }
    override suspend fun uploadPhoto(placeId: String, jpeg: ByteArray, caption: String?) = write { uploadPhoto(placeId, jpeg, caption) }
    override suspend fun photos(placeId: String) = pick { photos(placeId) }
    override suspend fun createShare(expiresInMinutes: Int) = write { createShare(expiresInMinutes) }
    override suspend fun updateShare(shareId: String, writeToken: String, sealed: my.gimano.app.data.share.SealedShare) = write { updateShare(shareId, writeToken, sealed) }
    override suspend fun deleteShare(shareId: String, writeToken: String) = write { deleteShare(shareId, writeToken) }
}
