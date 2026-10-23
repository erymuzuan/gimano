package my.gimano.app.core.issues

import my.gimano.app.core.geo.GeoPoint
import kotlin.math.cos

object IssueSupport {
    /** The server has no such endpoint (old backend): hide the feature rather than show errors. */
    fun endpointMissing(code: Int) = code == 404 || code == 405 || code == 501

    /** Bounding box (minLng, minLat, maxLng, maxLat) of [radiusM] around [p]. */
    fun bbox(p: GeoPoint, radiusM: Double): DoubleArray {
        val dLat = radiusM / 111_320.0
        val dLng = radiusM / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
        return doubleArrayOf(p.lng - dLng, p.lat - dLat, p.lng + dLng, p.lat + dLat)
    }

    /** Report sheet "Masalah jalan" type → issue kind. */
    val roadProblemKinds = linkedMapOf(
        "closed" to "road_closed_temp",
        "no_road" to "road_not_exist",
        "oneway" to "oneway_wrong_closed_dir",
        "no_turn" to "turn_not_allowed",
        "odd_route" to "odd_route",
        "new_road" to "new_road",
    )

    /** Duration chips for "Ditutup" → durationHint. */
    val closureDurations = listOf("unknown", "today", "days", "long_works")

    /** "Kenapa tak ikut laluan?" answer → issue kind; null = nothing is sent (jam / my own choice). */
    fun deviationKind(answer: String): String? = when (answer) {
        "closed" -> "road_closed_temp"
        "no_road" -> "road_not_exist"
        "oneway" -> "oneway_wrong_closed_dir"
        "no_turn" -> "turn_not_allowed"
        else -> null
    }

    val deviationAnswers = listOf("closed", "no_road", "oneway", "no_turn", "jam", "own_choice")

    /** Coordinates sent for a road issue: 5 dp (~1 m) is enough for the server to snap to the segment. */
    fun round5(v: Double) = Math.round(v * 1e5) / 1e5
}
