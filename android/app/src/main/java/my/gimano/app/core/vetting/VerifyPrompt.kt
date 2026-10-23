package my.gimano.app.core.vetting

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint

/**
 * When may the app ask "Is this place here?" (docs/identity-and-reputation.md §4, canvas "Nearby driver: verify prompt"):
 * only while the app is open, not navigating, after the phone has been stationary (< 5 km/h) for 60 s,
 * and at most once every 30 minutes. No background location is used for this.
 */
class VerifyPromptGate(
    private val stationaryKmh: Double = 5.0,
    private val stationaryMs: Long = 60_000,
    private val minGapMs: Long = 30 * 60_000,
) {
    private var stillSince: Long? = null
    private var lastPromptAt: Long? = null

    /** Feed every location fix; returns true when a prompt may be shown now. */
    fun onFix(speedKmh: Double?, nowMs: Long, navigating: Boolean, appInForeground: Boolean): Boolean {
        if (navigating || !appInForeground || speedKmh == null || speedKmh >= stationaryKmh) {
            stillSince = null
            return false
        }
        val since = stillSince ?: nowMs.also { stillSince = it }
        if (nowMs - since < stationaryMs) return false
        val last = lastPromptAt
        return last == null || nowMs - last >= minGapMs
    }

    /** Call when a prompt was actually shown (or a fetch found nothing worth asking, to avoid hammering). */
    fun markPrompted(nowMs: Long) { lastPromptAt = nowMs }
}

object Proximity {
    const val TOWN_M = 1000
    const val RURAL_M = 5000

    fun limitM(rural: Boolean) = if (rural) RURAL_M else TOWN_M

    /** The `withinM` to send, or null when the phone is too far to vote. Distance is computed on the phone only. */
    fun withinM(me: GeoPoint, place: GeoPoint, rural: Boolean): Int? =
        limitM(rural).takeIf { GeoMath.distanceM(me, place) <= it }
}
