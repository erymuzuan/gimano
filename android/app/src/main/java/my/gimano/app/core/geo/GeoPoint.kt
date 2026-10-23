package my.gimano.app.core.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 point. Kept independent of MapLibre / Android so the core logic is unit-testable. */
data class GeoPoint(val lat: Double, val lng: Double)

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun toRad(d: Double) = d * PI / 180.0
    fun toDeg(r: Double) = r * 180.0 / PI

    /** Great-circle distance in metres. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLng = toRad(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(toRad(a.lat)) * cos(toRad(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing a→b in degrees [0, 360). */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val dLng = toRad(b.lng - a.lng)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (toDeg(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference b - a in degrees, in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    /** Linear interpolation between two points (fine for the short segments of a route). */
    fun lerp(a: GeoPoint, b: GeoPoint, t: Double) =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)

    /**
     * Projects [p] onto segment a-b using a local equirectangular approximation.
     * Returns the fraction along the segment (clamped to [0,1]) and the distance in metres.
     */
    fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
        val kx = cos(toRad(p.lat)) * EARTH_RADIUS_M * PI / 180.0
        val ky = EARTH_RADIUS_M * PI / 180.0
        val ax = (a.lng - p.lng) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lng - p.lng) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return t to sqrt(cx * cx + cy * cy)
    }

    /** Destination point given start, bearing (deg) and distance (m). */
    fun offset(p: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / EARTH_RADIUS_M
        val br = toRad(bearingDeg)
        val la1 = toRad(p.lat)
        val lo1 = toRad(p.lng)
        val la2 = kotlin.math.asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(br))
        val lo2 = lo1 + atan2(sin(br) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return GeoPoint(toDeg(la2), toDeg(lo2))
    }
}
