package my.gimano.app.core.trip

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint

/** Where a route preview starts. Only a recent GPS fix may be called "Your location". */
enum class OriginSource { GPS, CHOSEN, LOCATING, UNKNOWN }

object RouteOrigin {
    /** Older fixes are refreshed (or the user is asked) before routing from them. */
    const val MAX_FIX_AGE_MS = 120_000L
    /** Closer than this to the first target → "Anda sudah di sini", no route. */
    const val SAME_SPOT_M = 50.0

    sealed interface Gps {
        data class Fresh(val point: GeoPoint) : Gps
        data class Stale(val point: GeoPoint, val ageMs: Long) : Gps
        data object None : Gps
    }

    /** Never the map camera centre or a default point: no fix → [Gps.None]. */
    fun classify(fix: GeoPoint?, fixTimeMs: Long?, nowMs: Long, maxAgeMs: Long = MAX_FIX_AGE_MS): Gps {
        if (fix == null || fixTimeMs == null || fixTimeMs <= 0) return Gps.None
        val age = nowMs - fixTimeMs
        return if (age in -60_000..maxAgeMs) Gps.Fresh(fix) else Gps.Stale(fix, age)
    }

    fun sameSpot(a: GeoPoint, b: GeoPoint): Boolean = GeoMath.distanceM(a, b) < SAME_SPOT_M
}
