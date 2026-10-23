package my.gimano.app.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.gimano.app.data.model.*
import my.gimano.app.data.remote.ApiFactory
import my.gimano.app.data.remote.GimanoApi
import my.gimano.app.data.settings.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.util.Locale

/** Talks to the real Gimano backend. Re-authenticates once on HTTP 401. */
class RemoteRepository(private val settings: SettingsStore) : GimanoRepository {
    private val client = ApiFactory.okHttp { settings.current.token }
    private var apiBase: String? = null
    private var apiInstance: GimanoApi? = null
    private val authLock = Mutex()

    private val api: GimanoApi
        get() {
            val base = settings.current.apiBaseUrl
            if (apiInstance == null || apiBase != base) {
                apiInstance = ApiFactory.create(base, client)
                apiBase = base
            }
            return apiInstance!!
        }

    private suspend fun authenticate(): DeviceAuthResponse {
        val res = api.authDevice(DeviceAuthRequest(settings.deviceId(), "android", Locale.getDefault().toLanguageTag()))
        settings.saveAuth(res.token, res.userId, res.displayName)
        return res
    }

    private suspend fun <T> authed(block: suspend GimanoApi.() -> T): T {
        if (settings.current.token == null) authLock.withLock { if (settings.current.token == null) authenticate() }
        return try {
            api.block()
        } catch (e: HttpException) {
            if (e.code() == 401) {
                authLock.withLock { authenticate() }
                api.block()
            } else throw e
        }
    }

    private fun retrofit2.Response<*>.orThrow() { if (!isSuccessful) throw HttpException(this) }

    override suspend fun ensureAuth(): User = authed { me() }
    override suspend fun updateMe(body: UpdateMeRequest) = authed { updateMe(body) }

    override suspend fun exportMyData(): MyDataExport? = try {
        authed { exportMyData() }
    } catch (e: HttpException) {
        if (e.code() in setOf(404, 405, 410)) null else throw e
    }

    override suspend fun deleteMyData() {
        val r = authed { deleteMyData() }
        if (!r.isSuccessful && r.code() !in setOf(404, 405, 410)) throw HttpException(r)
    }

    override suspend fun search(q: String, lat: Double?, lng: Double?, limit: Int, category: String?) =
        authed { search(q, lat, lng, limit, category).results }
    override suspend fun nearby(lat: Double, lng: Double, category: String, radius: Int) = authed { nearby(lat, lng, category, radius).results }
    override suspend fun place(id: String, lat: Double?, lng: Double?) = authed { place(id, lat, lng) }
    override suspend fun reverse(lat: Double, lng: Double) = authed { reverse(lat, lng) }
    override suspend fun routes(req: RouteRequest) = authed { routes(req).routes }
    override suspend fun reroute(routeId: String, req: RerouteRequest) = authed { reroute(routeId, req).routes }
    override suspend fun departureTimes(oLat: Double, oLng: Double, dLat: Double, dLng: Double, vehicle: String?) =
        authed { departureTimes(oLat, oLng, dLat, dLng, null, vehicle?.takeIf { it == "car" || it == "motorcycle" }).slots }
    override suspend fun live(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double) =
        authed { live(String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", minLng, minLat, maxLng, maxLat)) }
    override suspend fun createReport(body: CreateReportRequest) = authed { createReport(body) }
    override suspend fun vote(reportId: String, stillThere: Boolean) = authed { vote(reportId, VoteRequest(stillThere)) }
    override suspend fun thank(reportId: String) { authed { thank(reportId) }.orThrow() }
    override suspend fun postLocations(batch: LocationBatch) { authed { postLocations(batch) }.orThrow() }

    override suspend fun inbox(): List<InboxItem> = try { authed { inbox() } } catch (e: HttpException) { if (e.code() in setOf(404, 405, 501)) emptyList() else throw e }
    override suspend fun sendFeedback(body: FeedbackRequest) = authed { feedback(body) }
    override suspend fun myFeedback() = authed { myFeedback() }

    override suspend fun createPlace(body: CreatePlaceRequest) = authed { createPlace(body) }
    override suspend fun suggestEdit(placeId: String, body: SuggestEditRequest) = authed { suggestEdit(placeId, body) }
    override suspend fun verifyPlace(placeId: String, exists: Boolean) = authed { verifyPlace(placeId, VerifyRequest(exists)) }
    override suspend fun contributedPlaces() = authed { contributedPlaces() }

    override suspend fun reviews(placeId: String, sort: String) = authed { reviews(placeId, sort) }
    override suspend fun createReview(placeId: String, body: ReviewRequest) = authed { createReview(placeId, body) }
    override suspend fun updateReview(reviewId: String, body: ReviewRequest) = authed { updateReview(reviewId, body) }
    override suspend fun deleteReview(reviewId: String) { authed { deleteReview(reviewId) }.orThrow() }
    override suspend fun helpful(reviewId: String) { authed { helpful(reviewId) }.orThrow() }
    override suspend fun flagReview(reviewId: String, reason: String) { authed { flagReview(reviewId, FlagRequest(reason)) }.orThrow() }

    override suspend fun uploadPhoto(placeId: String, jpeg: ByteArray, caption: String?): Photo = authed {
        val part = MultipartBody.Part.createFormData("file", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
        uploadPhoto(part, placeId.toRequestBody("text/plain".toMediaType()), caption?.toRequestBody("text/plain".toMediaType()))
    }

    override suspend fun photos(placeId: String) = authed { photos(placeId) }

    override suspend fun createShare(expiresInMinutes: Int) = authed { createShare(my.gimano.app.data.share.CreateShareRequest(expiresInMinutes)) }
    override suspend fun updateShare(shareId: String, writeToken: String, sealed: my.gimano.app.data.share.SealedShare) { api.updateShare(shareId, "Share $writeToken", sealed).orThrow() }
    override suspend fun deleteShare(shareId: String, writeToken: String) { api.deleteShare(shareId, "Share $writeToken").orThrow() }
}
