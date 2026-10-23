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
        } catch (e: IOException) {
            // Server unreachable (wrong base URL, no backend running, offline): keep the app usable.
            _fallbackActive.value = true
            demo.block()
        }
    }

    override suspend fun ensureAuth() = pick { ensureAuth() }
    override suspend fun updateMe(body: UpdateMeRequest) = pick { updateMe(body) }
    override suspend fun places() = pick { places() }
    override suspend fun setPlace(kind: String, body: SavePlaceRequest) = pick { setPlace(kind, body) }
    override suspend fun addFavorite(body: SavePlaceRequest) = pick { addFavorite(body) }
    override suspend fun deletePlace(id: String) = pick { deletePlace(id) }
    override suspend fun recents() = pick { recents() }
    override suspend fun search(q: String, lat: Double?, lng: Double?, limit: Int, category: String?) = pick { search(q, lat, lng, limit, category) }
    override suspend fun nearby(lat: Double, lng: Double, category: String, radius: Int) = pick { nearby(lat, lng, category, radius) }
    override suspend fun place(id: String) = pick { place(id) }
    override suspend fun reverse(lat: Double, lng: Double) = pick { reverse(lat, lng) }
    override suspend fun routes(req: RouteRequest) = pick { routes(req) }
    override suspend fun reroute(routeId: String, req: RerouteRequest) = pick { reroute(routeId, req) }
    override suspend fun departureTimes(oLat: Double, oLng: Double, dLat: Double, dLng: Double) = pick { departureTimes(oLat, oLng, dLat, dLng) }
    override suspend fun live(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double) = pick { live(minLng, minLat, maxLng, maxLat) }
    override suspend fun createReport(body: CreateReportRequest) = pick { createReport(body) }
    override suspend fun vote(reportId: String, stillThere: Boolean) = pick { vote(reportId, stillThere) }
    override suspend fun thank(reportId: String) = pick { thank(reportId) }
    override suspend fun postLocations(batch: LocationBatch) = pick { postLocations(batch) }
}
