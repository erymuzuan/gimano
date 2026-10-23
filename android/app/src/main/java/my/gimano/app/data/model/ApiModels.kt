package my.gimano.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import my.gimano.app.core.geo.GeoPoint

// DTOs mirror docs/api.md exactly (camelCase JSON). Unknown fields are ignored by the Json config.

@Serializable
data class DeviceAuthRequest(val deviceId: String, val platform: String = "android", val locale: String)

@Serializable
data class DeviceAuthResponse(val token: String, val userId: String, val displayName: String)

@Serializable
data class User(
    val id: String,
    val displayName: String,
    val mood: String? = null,
    val vehicle: String? = null,
    val points: Int = 0,
    val level: Int = 1,
    val joinedAt: String? = null,
)

@Serializable
data class UpdateMeRequest(
    val displayName: String? = null,
    val mood: String? = null,
    val vehicle: String? = null,
    val locale: String? = null,
)

@Serializable
data class SavedPlace(
    val id: String,
    val kind: String, // home | work | favorite
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String = "",
    val placeId: String? = null,
    val icon: String = "star",
    val sortOrder: Int = 0,
)

/** Legacy one-time migration payload: GET /me/data/export. */
@Serializable
data class MyDataExport(val savedPlaces: List<SavedPlace> = emptyList(), val recents: List<Recent> = emptyList())

@Serializable
data class SavePlaceRequest(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val placeId: String? = null,
)

@Serializable
data class Recent(
    val id: String,
    val name: String,
    val address: String = "",
    val lat: Double,
    val lng: Double,
    val placeId: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable
data class PlaceSummary(
    val id: String,
    val name: String,
    val category: String = "other",
    val address: String = "",
    val lat: Double,
    val lng: Double,
    val distanceM: Double? = null,
    val brand: String? = null,
    val source: String = "osm",          // osm | community
    val status: String? = null,          // pending | published (community only)
    val rating: Double? = null,
    val reviewCount: Int = 0,
    val thumbnailUrl: String? = null,
)

@Serializable
data class SearchResponse(val results: List<PlaceSummary> = emptyList())

@Serializable
data class Place(
    val id: String,
    val name: String,
    val category: String = "other",
    val address: String = "",
    val lat: Double,
    val lng: Double,
    val distanceM: Double? = null,
    val brand: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val openingHours: String? = null,
    val openNow: Boolean? = null,
    val amenities: List<String> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val osmType: String? = null,
    val osmId: Long? = null,
    val source: String = "osm",
    val status: String? = null,
    val rating: Double? = null,
    val reviewCount: Int = 0,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val addedBy: Author? = null,
    val verifiedCount: Int = 0,
    val photos: List<Photo> = emptyList(),
) {
    val isCommunity get() = source == "community"
    val isUnverified get() = isCommunity && status != "published"
    fun summary() = PlaceSummary(id, name, category, address, lat, lng, distanceM, brand, source, status, rating, reviewCount, thumbnailUrl)
}

@Serializable
data class Author(val displayName: String = "", val level: Int? = null, val reviewCount: Int? = null)

@Serializable
data class Photo(
    val id: String,
    val url: String = "",
    val thumbUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val caption: String? = null,
    val uploadedBy: Author? = null,
    val createdAt: String? = null,
    val status: String? = null,   // processing | published | rejected
)

@Serializable
data class Review(
    val id: String,
    val placeId: String,
    val rating: Int,
    val text: String? = null,
    val photos: List<Photo> = emptyList(),
    val author: Author = Author(),
    val helpfulCount: Int = 0,
    val createdAt: String = "",
    val editedAt: String? = null,
    val status: String = "published",
    val mine: Boolean = false,          // client-side: written by this device's user
)

@Serializable
data class ReviewsPage(val reviews: List<Review> = emptyList(), val next: String? = null)

@Serializable
data class PhotosPage(val photos: List<Photo> = emptyList(), val next: String? = null)

@Serializable
data class ReviewRequest(val rating: Int, val text: String? = null, val photoIds: List<String>? = null)

@Serializable
data class FlagRequest(val reason: String)

@Serializable
data class CreatePlaceRequest(
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val description: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val openingHours: String? = null,
    val amenities: List<String>? = null,
)

@Serializable
data class SuggestEditRequest(
    val name: String? = null,
    val category: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val phone: String? = null,
    val website: String? = null,
    val openingHours: String? = null,
    val comment: String? = null,
)

@Serializable
data class SuggestionCreated(val suggestionId: String = "")

@Serializable
data class VerifyRequest(val exists: Boolean, val correctLocation: Boolean? = null)

// --- Feedback ---------------------------------------------------------------------------------

@Serializable
data class FeedbackContext(
    val appVersion: String,
    val platform: String = "android",
    val osVersion: String,
    val deviceModel: String,
    val locale: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val routeId: String? = null,
    val placeId: String? = null,
)

@Serializable
data class FeedbackRequest(
    val category: String,
    val message: String,
    val rating: Int? = null,
    val screen: String,
    val context: FeedbackContext? = null,
    val screenshot: String? = null,
)

@Serializable
data class FeedbackCreated(val id: String, val createdAt: String = "")

@Serializable
data class FeedbackSummary(val id: String, val category: String, val message: String, val status: String = "received", val createdAt: String = "")

@Serializable
data class ReverseGeocode(val name: String, val address: String = "", val lat: Double, val lng: Double)

@Serializable
data class LatLngDto(val lat: Double, val lng: Double, val placeId: String? = null, val name: String? = null)

@Serializable
data class AvoidOptions(val tolls: Boolean = false, val highways: Boolean = false, val ferries: Boolean = false)

@Serializable
data class RouteRequest(
    val origin: LatLngDto,
    val destination: LatLngDto,
    val waypoints: List<LatLngDto> = emptyList(),
    val vehicle: String = "car",
    val avoid: AvoidOptions = AvoidOptions(),
    val departAt: String? = null,
    val alternatives: Int = 2,
)

@Serializable
data class RoutesResponse(val routes: List<Route> = emptyList())

@Serializable
data class Route(
    val id: String,
    val label: String = "alternative",
    val durationS: Double,
    val distanceM: Double,
    val trafficDurationS: Double? = null,
    val summary: String = "",
    val trafficDescription: String? = null,
    val vehicle: String? = null,
    val hasTolls: Boolean = false,
    val tollEstimateMyr: Double? = null,
    val tollFareComplete: Boolean = false,
    val tolls: List<TollPlaza> = emptyList(),
    val polyline: String,
    val bbox: List<Double> = emptyList(),
    val legs: List<Leg> = emptyList(),
    val trafficSegments: List<TrafficSegment> = emptyList(),
    val alerts: List<AlertOnRoute> = emptyList(),
) {
    val isBest get() = label == "best"
    val effectiveDurationS get() = trafficDurationS ?: durationS
    val maneuvers get() = legs.flatMap { it.maneuvers }
}

@Serializable
data class Leg(
    val maneuvers: List<Maneuver> = emptyList(),
    val durationS: Double? = null,
    val distanceM: Double? = null,
    val to: LegTarget? = null,
)

@Serializable
data class LegTarget(val lat: Double, val lng: Double, val name: String? = null)

@Serializable
data class TollPlaza(
    val plazaId: String = "",
    val name: String = "",
    val highway: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val fareMyr: Double? = null,
    val system: String = "open",   // open | closed_entry | closed_exit
    val source: String? = null,
)

@Serializable
data class Maneuver(
    val type: String,
    val instruction: String = "",
    val streetName: String? = null,
    val exitNumber: String? = null,
    val roadRef: String? = null,
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
    val beginShapeIndex: Int = 0,
    val endShapeIndex: Int = 0,
    val verbalPre: String? = null,
    val verbalPost: String? = null,
)

@Serializable
data class TrafficSegment(val fromIndex: Int, val toIndex: Int, val level: Int)

@Serializable
data class ReportedBy(val displayName: String = "", val mood: String? = null)

@Serializable
data class Report(
    val id: String,
    val type: String,
    val subtype: String? = null,
    val lat: Double,
    val lng: Double,
    val heading: Double? = null,
    val street: String? = null,
    val reportedBy: ReportedBy? = null,
    val createdAt: String,
    val expiresAt: String,
    val confirmations: Int = 0,
    val thanks: Int = 0,
    val comment: String? = null,
) {
    val point get() = GeoPoint(lat, lng)
}

/** Report + distanceAlongRouteM (flattened, as the API returns it). */
@Serializable
data class AlertOnRoute(
    val id: String,
    val type: String,
    val subtype: String? = null,
    val lat: Double,
    val lng: Double,
    val heading: Double? = null,
    val street: String? = null,
    val reportedBy: ReportedBy? = null,
    val createdAt: String,
    val expiresAt: String,
    val confirmations: Int = 0,
    val thanks: Int = 0,
    val distanceAlongRouteM: Double = 0.0,
) {
    fun toReport() = Report(id, type, subtype, lat, lng, heading, street, reportedBy, createdAt, expiresAt, confirmations, thanks)
}

@Serializable
data class RerouteRequest(val lat: Double, val lng: Double, val heading: Double?, val nextStopIndex: Int? = null)

@Serializable
data class DepartureSlot(val departAt: String, val durationS: Double)

@Serializable
data class DepartureTimesResponse(val slots: List<DepartureSlot> = emptyList())

@Serializable
data class Jam(
    val id: String,
    val level: Int,
    val speedKmh: Double = 0.0,
    val delayS: Double = 0.0,
    val lengthM: Double = 0.0,
    val polyline: String,
    val street: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class Driver(val lat: Double, val lng: Double, val heading: Double? = null, val mood: String? = null)

@Serializable
data class LiveResponse(
    val alerts: List<Report> = emptyList(),
    val jams: List<Jam> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val serverTime: String? = null,
)

@Serializable
data class CreateReportRequest(
    val type: String,
    val subtype: String? = null,
    val lat: Double,
    val lng: Double,
    val heading: Double? = null,
    val comment: String? = null,
)

@Serializable
data class VoteRequest(val stillThere: Boolean)

@Serializable
data class LocationPoint(
    val lat: Double,
    val lng: Double,
    val speedKmh: Double,
    val heading: Double?,
    val accuracyM: Double?,
    val ts: String,
)

@Serializable
data class LocationBatch(val routeId: String?, val points: List<LocationPoint>)

@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    @SerialName("errors") val errors: JsonElement? = null,
)


/**
 * Inbox: news about the user's PUBLIC contributions only (place published/rejected, report thanks,
 * review marked helpful, edit accepted, feedback status) and app announcements. GET /me/inbox.
 * Parsed leniently until the contract is final.
 */
@Serializable
data class InboxItem(
    val id: String,
    val kind: String = "announcement",
    val title: String = "",
    val body: String = "",
    val createdAt: String = "",
    val placeId: String? = null,
)
