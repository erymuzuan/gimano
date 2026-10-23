package my.gimano.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Complaints v1 (docs/complaints.md, docs/api.md "Issues"). Public contributions under the pseudonym; deviation answers
 * carry only the kind + the segment point/heading, never a track. Field aliases keep the app tolerant while the server
 * contract settles.
 */

object IssueKind {
    const val ROAD_CLOSED_TEMP = "road_closed_temp"
    const val ROAD_NOT_EXIST = "road_not_exist"
    const val ONEWAY_CLOSED_DIR = "oneway_wrong_closed_dir"
    const val ONEWAY_OPEN_DIR = "oneway_wrong_open_dir"
    const val TURN_NOT_ALLOWED = "turn_not_allowed"
    const val ODD_ROUTE = "odd_route"
    const val NEW_ROAD = "new_road"
    const val PLACE_CLOSED_PERM = "place_closed_perm"
    const val PLACE_CLOSED_TEMP = "place_closed_temp"
    const val PLACE_HOURS_WRONG = "place_hours_wrong"
    const val PLACE_ENTRANCE_WRONG = "place_entrance_wrong"
    const val PLACE_INFO_WRONG = "place_info_wrong"
    const val PLACE_DUPLICATE = "place_duplicate"

    val road = setOf(ROAD_CLOSED_TEMP, ROAD_NOT_EXIST, ONEWAY_CLOSED_DIR, ONEWAY_OPEN_DIR, TURN_NOT_ALLOWED)
}

object IssueState {
    const val REPORTED = "reported"
    const val SUSPECTED = "suspected"
    const val CONFIRMED = "confirmed"
    val open = setOf(REPORTED, SUSPECTED, CONFIRMED)
}

@Serializable
data class CreateIssueRequest(
    val kind: String,
    val subtype: String? = null,
    val lat: Double,
    val lng: Double,
    val heading: Double? = null,
    val placeId: String? = null,
    val vehicle: String? = null,
    /** unknown | today | few_days | long_roadworks (road_closed_temp; docs/api.md). */
    val durationHint: String? = null,
    /** report | deviation | place_edit */
    val source: String,
    /** Per kind (docs/api.md "Issues"): until, openingHours, navLat/navLng(+kind), toLat/toLng, trace, from/to, comment… */
    val payload: JsonObject? = null,
)

@Serializable
data class Issue(
    val id: String,
    val kind: String,
    val subtype: String? = null,
    val state: String = IssueState.SUSPECTED,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val heading: Double? = null,
    val placeId: String? = null,
    @JsonNames("road", "street", "segmentName") val roadName: String? = null,
    val segment: IssueSegment? = null,
    val summary: String? = null,
    @JsonNames("confirmationsWeighted", "votes", "confirmedBy") val confirmations: Double = 0.0,
    val required: Double? = null,
    val createdAt: String = "",
    val expiresAt: String? = null,
    val lastEvidenceAt: String? = null,
    /** place_closed_temp: closed until (ISO date); also read from payload.until. */
    val until: String? = null,
    val payload: JsonObject? = null,
    val mine: Boolean = false,
    val myVote: String? = null,
) {
    val road: String? get() = roadName ?: segment?.name
    val untilDate: String? get() = until ?: (payload?.get("until") as? JsonPrimitive)?.contentOrNull
}

@Serializable
data class IssueSegment(val wayId: Long? = null, val seq: Int? = null, val name: String? = null, val highway: String? = null, val direction: String? = null)

/** POST /issues/{id}/votes → the issue's tallies. */
@Serializable
data class IssueVoteResult(
    val state: String = IssueState.SUSPECTED,
    val confirmationsWeighted: Double = 0.0,
    val refutationsWeighted: Double = 0.0,
    val required: Double? = null,
)

/** `PlaceSummary.badge` / `Place.badge` (open place_closed_perm / place_closed_temp / place_hours_wrong). */
@Serializable
data class PlaceBadge(
    val issueId: String,
    val kind: String,
    val state: String = IssueState.SUSPECTED,
    /** In the request language: "Dilaporkan tutup · sahkan?", "Tutup sementara sehingga 2026-11-01", … */
    val label: String? = null,
    val until: String? = null,
) {
    fun toIssue(lat: Double, lng: Double) = Issue(issueId, kind, state = state, lat = lat, lng = lng, until = until, summary = label)
}

@Serializable
data class IssueVoteRequest(
    /** still | cleared | unsure */
    val answer: String,
    /** Like place vetting: geohash-6 cell + how close (≤ 1000 m). */
    val proximity: VerifyRequest.Proximity,
)

@Serializable
data class IssuesPage(val issues: List<Issue> = emptyList())

/** `issues[]` on a route: issues on it (warn) or avoided by it ("Mengelak …"). */
@Serializable
data class RouteIssue(
    val id: String,
    val kind: String,
    val state: String = IssueState.SUSPECTED,
    val subtype: String? = null,
    /** true: this route goes around it; false/null: the route passes it (warn). */
    val avoided: Boolean? = null,
    /** Older alias: "avoided" | "on_route". */
    @JsonNames("effect") val relation: String? = null,
    @JsonNames("road", "street", "segmentName") val roadName: String? = null,
    val segment: IssueSegment? = null,
    val summary: String? = null,
    @JsonNames("confirmationsWeighted", "votes", "confirmedBy") val confirmations: Double = 0.0,
    val createdAt: String? = null,
    val lastEvidenceAt: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val distanceAlongRouteM: Double? = null,
) {
    val isAvoided: Boolean get() = avoided == true || relation == "avoided"
    val road: String? get() = roadName ?: segment?.name
}
