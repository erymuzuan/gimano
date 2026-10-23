package my.gimano.app.core.nav

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import kotlin.math.abs

/**
 * Where to draw the driver's puck. A fix close to the route (≤ [SNAP_RADIUS_M]) and heading
 * the same way is drawn ON the route line, so the arrow never wanders into buildings beside
 * the road. Anything else is drawn where GPS says, which is also what the off-route detector
 * sees, so the driver can watch a reroute happen.
 */
object PuckPlacement {
    const val SNAP_RADIUS_M = 30.0
    const val MAX_HEADING_DIFF_DEG = 60.0
    /** Below this speed GPS heading is noise, so it is not used to veto snapping. */
    const val MIN_SPEED_FOR_HEADING_KMH = 10.0

    data class Placement(val point: GeoPoint, val bearing: Double, val snapped: Boolean)

    fun place(raw: GeoPoint, rawBearing: Double?, speedKmh: Double, progress: NavProgress?): Placement {
        if (progress == null) return Placement(raw, rawBearing ?: 0.0, false)
        val headingOk = rawBearing == null || speedKmh < MIN_SPEED_FOR_HEADING_KMH ||
            abs(GeoMath.angleDiff(rawBearing, progress.bearing)) <= MAX_HEADING_DIFF_DEG
        return if (progress.distanceFromRouteM <= SNAP_RADIUS_M && headingOk) {
            Placement(progress.snapped, progress.bearing, true)
        } else {
            Placement(raw, rawBearing ?: progress.bearing, false)
        }
    }
}
