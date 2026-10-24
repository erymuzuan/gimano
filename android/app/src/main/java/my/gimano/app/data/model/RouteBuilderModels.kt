package my.gimano.app.data.model

import kotlinx.serialization.Serializable

/*
 * Route builder requests (docs/events.md §6, docs/api.md "Routing"). Bodies carry coordinates and the travel mode only:
 * no names, place ids, event ids or notes. They are sent without the account token (the routing endpoints are anonymous).
 */

/** A coordinate and nothing else. */
@Serializable
data class Coord(val lat: Double, val lng: Double)

/** `POST /routes` with `"store": false` (a server without it ignores the flag). */
@Serializable
data class StatelessRouteRequest(
    val origin: Coord,
    val destination: Coord,
    val waypoints: List<Coord> = emptyList(),
    val vehicle: String,
    val store: Boolean = false,
)

/** `POST /routes/match`: a drawn line, 2–5000 points in drawing order. */
@Serializable
data class MatchLineRequest(val points: List<Coord>, val vehicle: String)

@Serializable
data class UnmatchedRangeDto(val fromIndex: Int, val toIndex: Int)

@Serializable
data class MatchLineResponse(
    val polyline: String,
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
    val unmatched: List<UnmatchedRangeDto> = emptyList(),
)
