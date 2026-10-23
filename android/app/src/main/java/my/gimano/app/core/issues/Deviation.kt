package my.gimano.app.core.issues

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.nav.RouteGeometry
import java.time.LocalDate

/**
 * "Kenapa tak ikut laluan?" (AduanLencong). Everything stays on the phone: the only thing that may ever be sent is
 * the answer plus [point]/[heading]/[road] of the route segment the driver left. No track is kept.
 */
data class Deviation(val point: GeoPoint, val heading: Double, val road: String?, val junction: GeoPoint = point)

/**
 * Detects "left the suggested route at a decision point and didn't rejoin":
 * - [onLeftRoute] when the drive goes off-route (reroute), with the last on-route position and the distance to the
 *   next manoeuvre there. Only near a decision point (≤ [DECISION_M] to the next manoeuvre) counts.
 * - [onFix] afterwards: back within [REJOIN_M] of the old route ahead → rejoined (forget it); [CONFIRM_M] away from the
 *   departure point without rejoining → confirmed. The first confirmed deviation of a drive is kept.
 */
class DeviationTracker {
    companion object {
        const val DECISION_M = 200.0
        const val REJOIN_M = 30.0
        const val CONFIRM_M = 500.0
        /** Only the next stretch of the old route counts as "rejoining". */
        const val LOOKAHEAD_M = 3_000.0
        const val SEGMENT_INTO_M = 30.0
        const val REJOIN_PAST_M = 150.0
    }

    private var candidate: Deviation? = null
    private var oldRoute: RouteGeometry? = null
    private var leftAlongM = 0.0
    private var decisionAlongM = 0.0
    var confirmed: Deviation? = null
        private set

    fun reset() { candidate = null; oldRoute = null; confirmed = null }

    /**
     * [alongM] = last on-route position along [route]. The decision point is the next manoeuvre if it is within
     * [DECISION_M] ahead, else the previous one if it was passed within [DECISION_M] (off-route is noticed a few
     * seconds after the turn). The segment reported is the one the route wanted: [SEGMENT_INTO_M] past that point.
     */
    fun onLeftRoute(
        alongM: Double, distanceToNextManeuverM: Double, nextRoad: String?, route: RouteGeometry,
        sinceLastManeuverM: Double? = null, lastRoad: String? = null,
    ) {
        if (confirmed != null || candidate != null) return
        val (decision, road) = when {
            distanceToNextManeuverM <= DECISION_M -> (alongM + distanceToNextManeuverM) to nextRoad
            sinceLastManeuverM != null && sinceLastManeuverM <= DECISION_M -> (alongM - sinceLastManeuverM) to lastRoad
            else -> return
        }
        val (pt, bearing) = route.pointAt(decision + SEGMENT_INTO_M)
        candidate = Deviation(pt, bearing, road?.takeIf { it.isNotBlank() }, route.pointAt(decision).first)
        oldRoute = route
        leftAlongM = alongM
        decisionAlongM = decision
    }

    /** Returns the deviation the moment it is confirmed (once). */
    fun onFix(p: GeoPoint): Deviation? {
        val c = candidate ?: return null
        val g = oldRoute ?: return null
        val from = g.segmentAt(leftAlongM)
        val endSeg = if (decisionAlongM + LOOKAHEAD_M >= g.lengthM) g.points.size - 2 else g.segmentAt(decisionAlongM + LOOKAHEAD_M)
        val window = endSeg - from + 1
        val snap = g.snap(p, from + 2, window.coerceAtLeast(1))
        // Rejoined = back on the old route clearly past the decision point (a slip road that runs beside the road the
        // driver stayed on must not count as rejoining).
        if (snap != null && snap.distanceFromRouteM <= REJOIN_M && snap.distanceAlongM >= decisionAlongM + REJOIN_PAST_M) {
            candidate = null; oldRoute = null      // rejoined: the driver just took a shortcut back
            return null
        }
        if (GeoMath.distanceM(c.point, p) >= CONFIRM_M) {
            confirmed = c
            candidate = null; oldRoute = null
            return c
        }
        return null
    }
}

/** At most once a day, never after "Jangan tanya lagi", and only when not driving. */
object DeviationPromptGate {
    const val STOPPED_KMH = 5

    fun mayAsk(neverAsk: Boolean, lastAskedDay: String?, today: LocalDate, speedKmh: Int?): Boolean =
        !neverAsk && lastAskedDay != today.toString() && (speedKmh ?: 0) <= STOPPED_KMH
}

/** Last [maxM] metres driven (points ≥ [stepM] apart), memory only. */
class RecentTrace(private val maxM: Double = 1_500.0, private val stepM: Double = 15.0) {
    private val pts = ArrayDeque<GeoPoint>()
    private val gaps = ArrayDeque<Double>()
    private var total = 0.0

    fun add(p: GeoPoint) {
        val last = pts.lastOrNull()
        if (last != null) {
            val d = GeoMath.distanceM(last, p)
            if (d < stepM) return
            gaps.addLast(d); total += d
        }
        pts.addLast(p)
        while (pts.size > 2 && total - gaps.first() >= maxM) { total -= gaps.removeFirst(); pts.removeFirst() }
    }

    fun points(): List<GeoPoint> = pts.toList()
    fun lengthM(): Double = total
    fun clear() { pts.clear(); gaps.clear(); total = 0.0 }
}

/** Debug "Simulate a detour": follow the route to the first turn ≥ 300 m in, then carry straight on for 900 m. */
object SimDetour {
    fun geometry(route: RouteGeometry, maneuverAlongM: List<Double>): RouteGeometry {
        val turnAt = maneuverAlongM.firstOrNull { it >= 300 && it < route.lengthM - 50 } ?: (route.lengthM / 2)
        val head = route.slice(0.0, turnAt)
        val bearing = route.pointAt((turnAt - 20).coerceAtLeast(0.0)).second
        val tail = (1..18).map { GeoMath.offset(head.last(), bearing, it * 50.0) }
        return RouteGeometry(head + tail)
    }
}
