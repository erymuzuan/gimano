package my.gimano.app.core.nav

/**
 * Decides when the driver has left the route. A single noisy GPS fix must not trigger a
 * reroute, so we require several consecutive fixes beyond a threshold that widens with
 * reported GPS accuracy, and enforce a cooldown between reroute requests.
 */
class OffRouteDetector(
    private val baseThresholdM: Double = 50.0,
    private val requiredConsecutive: Int = 3,
    private val cooldownMs: Long = 15_000,
    private val maxAccuracyM: Double = 100.0,
) {
    private var consecutive = 0
    private var lastTriggerMs = Long.MIN_VALUE / 2

    fun thresholdFor(accuracyM: Double?): Double =
        maxOf(baseThresholdM, (accuracyM ?: 0.0) * 1.5)

    /**
     * @param distanceFromRouteM distance from the snapped route line.
     * @param accuracyM GPS horizontal accuracy (null = unknown).
     * @param headingDiffDeg optional |heading - route bearing|; driving the opposite way counts as off route.
     * @return true exactly when a reroute should be requested now.
     */
    fun update(distanceFromRouteM: Double, accuracyM: Double?, nowMs: Long, headingDiffDeg: Double? = null, speedKmh: Double = 0.0): Boolean {
        // Ignore fixes that are too inaccurate to say anything.
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        val wrongWay = headingDiffDeg != null && speedKmh > 15 && kotlin.math.abs(headingDiffDeg) > 135
        val off = distanceFromRouteM > thresholdFor(accuracyM) || (wrongWay && distanceFromRouteM > baseThresholdM / 2)
        consecutive = if (off) consecutive + 1 else 0
        if (consecutive >= requiredConsecutive && nowMs - lastTriggerMs >= cooldownMs) {
            lastTriggerMs = nowMs
            consecutive = 0
            return true
        }
        return false
    }

    val isOffRoute: Boolean get() = consecutive > 0

    fun reset() {
        consecutive = 0
    }
}
