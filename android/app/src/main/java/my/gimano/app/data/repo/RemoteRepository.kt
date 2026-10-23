package my.gimano.app.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.gimano.app.data.model.*
import my.gimano.app.data.remote.ApiFactory
import my.gimano.app.data.remote.GimanoApi
import my.gimano.app.data.settings.SettingsStore
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

    val baseUrl get() = settings.current.apiBaseUrl

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

    override suspend fun ensureAuth(): User = authed { me() }
    override suspend fun updateMe(body: UpdateMeRequest) = authed { updateMe(body) }
    override suspend fun places() = authed { places() }
    override suspend fun setPlace(kind: String, body: SavePlaceRequest) = authed { putPlace(kind, body) }
    override suspend fun addFavorite(body: SavePlaceRequest) = authed { addFavorite(body) }
    override suspend fun deletePlace(id: String) { authed { deletePlace(id) } }
    override suspend fun recents() = authed { recents() }
    override suspend fun search(q: String, lat: Double?, lng: Double?, limit: Int, category: String?) =
        authed { search(q, lat, lng, limit, category).results }
    override suspend fun nearby(lat: Double, lng: Double, category: String, radius: Int) = authed { nearby(lat, lng, category, radius).results }
    override suspend fun place(id: String) = authed { place(id) }
    override suspend fun reverse(lat: Double, lng: Double) = authed { reverse(lat, lng) }
    override suspend fun routes(req: RouteRequest) = authed { routes(req).routes }
    override suspend fun reroute(routeId: String, req: RerouteRequest) = authed { reroute(routeId, req).routes }
    override suspend fun departureTimes(oLat: Double, oLng: Double, dLat: Double, dLng: Double) =
        authed { departureTimes(oLat, oLng, dLat, dLng).slots }
    override suspend fun live(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double) =
        authed { live(String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", minLng, minLat, maxLng, maxLat)) }
    override suspend fun createReport(body: CreateReportRequest) = authed { createReport(body) }
    override suspend fun vote(reportId: String, stillThere: Boolean) = authed { vote(reportId, VoteRequest(stillThere)) }
    override suspend fun thank(reportId: String) { authed { thank(reportId) } }
    override suspend fun postLocations(batch: LocationBatch) { authed { postLocations(batch) } }
}
