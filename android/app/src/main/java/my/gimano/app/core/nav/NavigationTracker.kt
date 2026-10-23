package my.gimano.app.core.nav

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.data.model.Maneuver
import my.gimano.app.data.model.Route

/** Progress snapshot for the UI and voice guidance. */
data class NavProgress(
    val snapped: GeoPoint,
    val bearing: Double,
    val distanceAlongM: Double,
    val distanceFromRouteM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val nextManeuverIndex: Int,
    val nextManeuver: Maneuver?,
    val distanceToNextM: Double,
    val thenManeuver: Maneuver?,
    val arrived: Boolean,
)

/** Pure tracker: feed it fixes, it tells you where you are on the route. */
class NavigationTracker(val route: Route) {
    val geometry = RouteGeometry(Polyline.decode(route.polyline))
    val maneuvers: List<Maneuver> = route.maneuvers
    private var lastSegment = 0
    private val speedFactor: Double =
        if (geometry.lengthM > 0) route.effectiveDurationS / geometry.lengthM else 0.0 // s per metre

    fun update(p: GeoPoint): NavProgress? {
        val snap = geometry.snap(p, lastSegment, window = 60) ?: return null
        // If the windowed snap is poor (e.g. after a GPS gap), retry on the whole route.
        val s = if (snap.distanceFromRouteM > 80) geometry.snap(p)?.takeIf { it.distanceFromRouteM < snap.distanceFromRouteM } ?: snap else snap
        lastSegment = s.segmentIndex
        val along = s.distanceAlongM
        val remainingM = (geometry.lengthM - along).coerceAtLeast(0.0)

        // Next maneuver = first one whose begin point is ahead of us.
        var idx = maneuvers.indexOfFirst { geometry.distanceAtIndex(it.beginShapeIndex) > along + 5 }
        if (idx == -1 && maneuvers.isNotEmpty()) idx = maneuvers.lastIndex
        val next = maneuvers.getOrNull(idx)
        val distToNext = if (next != null) (geometry.distanceAtIndex(next.beginShapeIndex) - along).coerceAtLeast(0.0) else remainingM
        val then = maneuvers.getOrNull(idx + 1)
        val arrived = remainingM < 25
        return NavProgress(
            snapped = s.point,
            bearing = s.segmentBearing,
            distanceAlongM = along,
            distanceFromRouteM = s.distanceFromRouteM,
            remainingM = remainingM,
            remainingS = remainingM * speedFactor,
            nextManeuverIndex = idx,
            nextManeuver = next,
            distanceToNextM = distToNext,
            thenManeuver = then,
            arrived = arrived,
        )
    }

    companion object {
        /** Show the "then" strip only when the following maneuver comes quickly after the next one. */
        fun showThen(next: Maneuver?, then: Maneuver?): Boolean =
            next != null && then != null && next.type != "arrive" && next.distanceM < 400
    }
}

/**
 * Decides which voice prompts to speak. Each maneuver gets at most one "far" prompt
 * (distance depends on speed) and one "now" prompt.
 */
class VoicePromptPlanner {
    private val spokenFar = HashSet<Int>()
    private val spokenNear = HashSet<Int>()
    private val spokenNow = HashSet<Int>()

    enum class Stage { FAR, NEAR, NOW }

    fun farDistanceFor(speedKmh: Double): Double = when {
        speedKmh > 80 -> 2000.0
        speedKmh > 50 -> 1000.0
        else -> 500.0
    }

    fun check(maneuverIndex: Int, distanceM: Double, speedKmh: Double): Stage? {
        if (maneuverIndex < 0) return null
        val far = farDistanceFor(speedKmh)
        val nowDist = if (speedKmh > 60) 120.0 else 60.0
        return when {
            distanceM <= nowDist && spokenNow.add(maneuverIndex) -> Stage.NOW
            distanceM in (nowDist + 60)..(minOf(far, 400.0)) && maneuverIndex !in spokenNow && spokenNear.add(maneuverIndex) -> Stage.NEAR
            distanceM in 401.0..far && spokenFar.add(maneuverIndex) -> Stage.FAR
            else -> null
        }
    }

    fun reset() {
        spokenFar.clear(); spokenNear.clear(); spokenNow.clear()
    }
}

/** Heading difference helper exposed for the controller. */
fun headingDiff(a: Double?, b: Double): Double? = a?.let { GeoMath.angleDiff(it, b) }
