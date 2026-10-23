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
    }

    private var candidate: Deviation? = null
    private var oldRoute: RouteGeometry? = null
    private var leftAlongM = 0.0
    var confirmed: Deviation? = null
        private set

    fun reset() { candidate = null; oldRoute = null; confirmed = null }

    /**
     * [alongM] = last on-route position along [route]; the segment reported is the one the route wanted next:
     * [SEGMENT_INTO_M] past the decision point, with its direction.
     */
    fun onLeftRoute(alongM: Double, distanceToNextManeuverM: Double, road: String?, route: RouteGeometry) {
        if (confirmed != null || candidate != null) return
        if (distanceToNextManeuverM > DECISION_M) return
        val (pt, bearing) = route.pointAt(alongM + distanceToNextManeuverM + SEGMENT_INTO_M)
        candidate = Deviation(pt, bearing, road?.takeIf { it.isNotBlank() }, route.pointAt(alongM + distanceToNextManeuverM).first)
        oldRoute = route
        leftAlongM = alongM
    }

    /** Returns the deviation the moment it is confirmed (once). */
    fun onFix(p: GeoPoint): Deviation? {
        val c = candidate ?: return null
        val g = oldRoute ?: return null
        val from = g.segmentAt(leftAlongM)
        val endSeg = if (leftAlongM + LOOKAHEAD_M >= g.lengthM) g.points.size - 2 else g.segmentAt(leftAlongM + LOOKAHEAD_M)
        val window = endSeg - from + 1
        val snap = g.snap(p, from + 2, window.coerceAtLeast(1))
        if (snap != null && snap.distanceFromRouteM <= REJOIN_M && snap.distanceAlongM >= leftAlongM - 20) {
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
