package my.gimano.app.data.remote

import my.gimano.app.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit binding of docs/api.md. Paths are relative to the `/api/v1/` base URL.
 * Personal data (favourites, recents, private places) is NOT here: it lives on the device.
 */
interface GimanoApi {
    @POST("auth/device")
    suspend fun authDevice(@Body body: DeviceAuthRequest): DeviceAuthResponse

    @POST("auth/register")
    suspend fun authRegister(@Body body: RegisterRequest): DeviceAuthResponse

    @POST("auth/token")
    suspend fun authToken(@Body body: TokenRequest): TokenResponse

    @POST("me/keys")
    suspend fun attachKey(@Body body: AttachKeyRequest): Response<ResponseBody>

    @GET("me")
    suspend fun me(): User

    @PATCH("me")
    suspend fun updateMe(@Body body: UpdateMeRequest): User

    // One-time migration of the legacy server copy into the local DB.
    @GET("me/data/export")
    suspend fun exportMyData(): MyDataExport

    @DELETE("me/data")
    suspend fun deleteMyData(): Response<ResponseBody>

    @GET("search")
    suspend fun search(
        @Query("q") q: String,
        @Query("lat") lat: Double?,
        @Query("lng") lng: Double?,
        @Query("limit") limit: Int = 10,
        @Query("category") category: String? = null,
        @Query("exact") exact: Boolean? = null,
    ): SearchResponse

    @GET("search/nearby")
    suspend fun nearby(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("category") category: String,
        @Query("radius") radius: Int = 5000,
    ): SearchResponse

    @GET("places/{id}")
    suspend fun place(@Path("id") id: String, @Query("lat") lat: Double? = null, @Query("lng") lng: Double? = null): Place

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
        @Query("vehicle") vehicle: String? = null,
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

    // --- Share my drive (server relays ciphertext only) ---
    @POST("shares")
    suspend fun createShare(@Body body: my.gimano.app.data.share.CreateShareRequest): my.gimano.app.data.share.ShareCreated

    @retrofit2.http.PUT("shares/{id}")
    suspend fun updateShare(@Path("id") id: String, @retrofit2.http.Header("Authorization") auth: String, @Body body: my.gimano.app.data.share.SealedShare): Response<ResponseBody>

    @DELETE("shares/{id}")
    suspend fun deleteShare(@Path("id") id: String, @retrofit2.http.Header("Authorization") auth: String): Response<ResponseBody>

    @GET("me/inbox")
    suspend fun inbox(): List<InboxItem>

    // --- Feedback ---
    @POST("feedback")
    suspend fun feedback(@Body body: FeedbackRequest): FeedbackCreated

    @DELETE("feedback/{id}/contact")
    suspend fun deleteFeedbackContact(@Path("id") id: String): Response<ResponseBody>

    @POST("crash-reports")
    suspend fun crashReport(@Body body: CrashReportRequest): CrashCreated

    @GET("me/feedback")
    suspend fun myFeedback(): List<FeedbackSummary>

    // --- Community places ---
    @POST("places")
    suspend fun createPlace(@Body body: CreatePlaceRequest): Place

    @PATCH("places/{id}")
    suspend fun updatePlace(@Path("id") id: String, @Body body: CreatePlaceRequest): Place

    @DELETE("places/{id}")
    suspend fun deletePlace(@Path("id") id: String): Response<ResponseBody>

    @POST("places/{id}/suggest-edit")
    suspend fun suggestEdit(@Path("id") id: String, @Body body: SuggestEditRequest): SuggestionCreated

    @POST("places/{id}/verify")
    suspend fun verifyPlace(@Path("id") id: String, @Body body: VerifyRequest, @retrofit2.http.Header("X-Gimano-Ts") ts: String?): VerifyResult

    @GET("places/pending-nearby")
    suspend fun pendingNearby(@Query("cell") cell: String): List<PlaceSummary>

    @GET("me/places/contributed")
    suspend fun contributedPlaces(): List<PlaceSummary>

    // --- Reviews & photos ---
    @GET("places/{id}/reviews")
    suspend fun reviews(@Path("id") id: String, @Query("sort") sort: String = "recent", @Query("cursor") cursor: String? = null): ReviewsPage

    @POST("places/{id}/reviews")
    suspend fun createReview(@Path("id") id: String, @Body body: ReviewRequest): Review

    @PATCH("reviews/{id}")
    suspend fun updateReview(@Path("id") id: String, @Body body: ReviewRequest): Review

    @DELETE("reviews/{id}")
    suspend fun deleteReview(@Path("id") id: String): Response<ResponseBody>

    @POST("reviews/{id}/helpful")
    suspend fun helpful(@Path("id") id: String): Response<ResponseBody>

    @POST("reviews/{id}/flag")
    suspend fun flagReview(@Path("id") id: String, @Body body: FlagRequest): Response<ResponseBody>

    @Multipart
    @POST("photos")
    suspend fun uploadPhoto(@Part file: MultipartBody.Part, @Part("placeId") placeId: RequestBody, @Part("caption") caption: RequestBody?): Photo

    @GET("places/{id}/photos")
    suspend fun photos(@Path("id") id: String, @Query("cursor") cursor: String? = null): PhotosPage

    @DELETE("photos/{id}")
    suspend fun deletePhoto(@Path("id") id: String): Response<ResponseBody>

    @POST("photos/{id}/flag")
    suspend fun flagPhoto(@Path("id") id: String, @Body body: FlagRequest): Response<ResponseBody>
}
