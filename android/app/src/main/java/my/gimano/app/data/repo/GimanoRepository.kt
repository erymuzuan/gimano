package my.gimano.app.data.repo

import my.gimano.app.data.model.*

/** Everything the UI needs from the backend. Implemented by [RemoteRepository] and [DemoRepository]. */
interface GimanoRepository {
    suspend fun ensureAuth(): User
    suspend fun updateMe(body: UpdateMeRequest): User
    suspend fun places(): List<SavedPlace>
    suspend fun setPlace(kind: String, body: SavePlaceRequest): SavedPlace
    suspend fun addFavorite(body: SavePlaceRequest): SavedPlace
    suspend fun deletePlace(id: String)
    suspend fun recents(): List<Recent>
    suspend fun search(q: String, lat: Double?, lng: Double?, limit: Int = 10, category: String? = null): List<PlaceSummary>
    suspend fun nearby(lat: Double, lng: Double, category: String, radius: Int = 5000): List<PlaceSummary>
    suspend fun place(id: String): Place
    suspend fun reverse(lat: Double, lng: Double): ReverseGeocode
    suspend fun routes(req: RouteRequest): List<Route>
    suspend fun reroute(routeId: String, req: RerouteRequest): List<Route>
    suspend fun departureTimes(oLat: Double, oLng: Double, dLat: Double, dLng: Double): List<DepartureSlot>
    suspend fun live(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double): LiveResponse
    suspend fun createReport(body: CreateReportRequest): Report
    suspend fun vote(reportId: String, stillThere: Boolean): Report
    suspend fun thank(reportId: String)
    suspend fun postLocations(batch: LocationBatch)
}
