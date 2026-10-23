package my.gimano.app.data.remote

import my.gimano.app.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit binding of docs/api.md. Paths are relative to the `/api/v1/` base URL. */
interface GimanoApi {
    @POST("auth/device")
    suspend fun authDevice(@Body body: DeviceAuthRequest): DeviceAuthResponse

    @GET("me")
    suspend fun me(): User

    @PATCH("me")
    suspend fun updateMe(@Body body: UpdateMeRequest): User

    @GET("me/places")
    suspend fun places(): List<SavedPlace>

    @PUT("me/places/{kind}")
    suspend fun putPlace(@Path("kind") kind: String, @Body body: SavePlaceRequest): SavedPlace

    @POST("me/places")
    suspend fun addFavorite(@Body body: SavePlaceRequest): SavedPlace

    @DELETE("me/places/{id}")
    suspend fun deletePlace(@Path("id") id: String): Response<ResponseBody>

    @GET("me/recents")
    suspend fun recents(): List<Recent>

    @GET("search")
    suspend fun search(
        @Query("q") q: String,
        @Query("lat") lat: Double?,
        @Query("lng") lng: Double?,
        @Query("limit") limit: Int = 10,
        @Query("category") category: String? = null,
    ): SearchResponse

    @GET("search/nearby")
    suspend fun nearby(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("category") category: String,
        @Query("radius") radius: Int = 5000,
    ): SearchResponse

    @GET("places/{id}")
    suspend fun place(@Path("id") id: String): Place

    @GET("geocode/reverse")
    suspend fun reverse(@Query("lat") lat: Double, @Query("lng") lng: Double): ReverseGeocode

    @POST("routes")
    suspend fun routes(@Body body: RouteRequest): RoutesResponse

    @POST("routes/{id}/reroute")
    suspend fun reroute(@Path("id") id: String, @Body body: RerouteRequest): RoutesResponse

    @GET("routes/departure-times")
    suspend fun departureTimes(
        @Query("originLat") originLat: Double,
        @Query("originLng") originLng: Double,
        @Query("destLat") destLat: Double,
        @Query("destLng") destLng: Double,
        @Query("date") date: String? = null,
    ): DepartureTimesResponse

    @GET("live")
    suspend fun live(@Query("bbox") bbox: String, @Query("types") types: String = "alerts,jams,drivers"): LiveResponse

    @POST("reports")
    suspend fun createReport(@Body body: CreateReportRequest): Report

    @POST("reports/{id}/votes")
    suspend fun vote(@Path("id") id: String, @Body body: VoteRequest): Report

    @POST("reports/{id}/thanks")
    suspend fun thank(@Path("id") id: String): Response<ResponseBody>

    @POST("locations")
    suspend fun postLocations(@Body body: LocationBatch): Response<ResponseBody>
}
