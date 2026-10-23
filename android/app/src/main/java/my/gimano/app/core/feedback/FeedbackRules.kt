package my.gimano.app.core.feedback

import kotlin.math.roundToLong

object FeedbackRules {
    val CATEGORIES = listOf("bug", "idea", "map_data", "route", "search", "other")
    const val MAX_MESSAGE = 2000
    const val MAX_SCREENSHOT_BYTES = 1_500_000

    /** While navigating, feedback opens only when (nearly) stopped or a passenger confirmed. */
    fun allowedWhileNavigating(speedKmh: Int, passengerConfirmed: Boolean): Boolean = speedKmh < 5 || passengerConfirmed

    fun validMessage(m: String): Boolean = m.trim().length in 1..MAX_MESSAGE

    /** Privacy rule: diagnostics location is rounded to 2 decimal places (~1 km) and only sent when opted in. */
    fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

    /** Retry backoff for the outbox: 30 s, 1 min, 2 min … capped at 1 h. */
    fun backoffSeconds(attempts: Int): Long = (30L shl attempts.coerceIn(0, 7)).coerceAtMost(3_600)

    /** 4xx except 408/429 are permanent (don't retry); everything else is retried. */
    fun isPermanentFailure(httpCode: Int?): Boolean = httpCode != null && httpCode in 400..499 && httpCode != 408 && httpCode != 429 && httpCode != 404
}
