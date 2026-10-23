package my.gimano.app.core.location

/** What the user granted. Android 12+ users can pick "Approximate", which breaks turn-by-turn navigation. */
enum class LocationPrecision {
    PRECISE, APPROXIMATE, NONE;

    val wire get() = name.lowercase()

    companion object {
        fun of(fine: Boolean, coarse: Boolean) = when {
            fine -> PRECISE
            coarse -> APPROXIMATE
            else -> NONE
        }
    }
}

object FixRules {
    /** Fixes worse than this never snap the puck or advance instructions. */
    const val MAX_USABLE_ACCURACY_M = 100.0
    const val WEAK_ACCURACY_M = 50.0
    const val WEAK_AFTER_MS = 10_000L
    /** Camera follow resumes by itself this long after the user panned during navigation. */
    const val FOLLOW_RESUME_MS = 10_000L

    fun usable(accuracyM: Double?, simulated: Boolean = false) = simulated || accuracyM == null || accuracyM <= MAX_USABLE_ACCURACY_M

    fun shouldResumeFollow(lastPanMs: Long?, nowMs: Long, navigating: Boolean) =
        navigating && lastPanMs != null && nowMs - lastPanMs >= FOLLOW_RESUME_MS
}

/** "Isyarat GPS lemah · ±xx m": accuracy worse than 50 m for more than 10 s. */
class GpsQuality(private val weakM: Double = FixRules.WEAK_ACCURACY_M, private val afterMs: Long = FixRules.WEAK_AFTER_MS) {
    private var badSince: Long? = null

    /** @return the accuracy to show in the chip, or null when the signal is fine. */
    fun onFix(accuracyM: Double?, nowMs: Long): Int? {
        if (accuracyM == null || accuracyM <= weakM) { badSince = null; return null }
        val since = badSince ?: nowMs.also { badSince = it }
        return if (nowMs - since >= afterMs) accuracyM.toInt() else null
    }

    fun reset() { badSince = null }
}
