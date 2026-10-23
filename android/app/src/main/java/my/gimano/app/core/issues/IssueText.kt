package my.gimano.app.core.issues

import java.time.Duration
import java.time.Instant

/** Small pure helpers for the complaints UI text. */
object IssueText {
    /** Whole drivers for "disahkan N pemandu" (weights are 1/1.5/2; never round a single vote up to 2). */
    fun drivers(weighted: Double): Int = kotlin.math.floor(weighted + 1e-9).toInt().coerceAtLeast(0)

    /** "2 j lalu" / "2 h ago" style age; null when unknown. */
    fun age(createdAt: String?, now: Instant, ms: Boolean): String? {
        val t = createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val m = Duration.between(t, now).toMinutes().coerceAtLeast(0)
        return when {
            m < 1 -> if (ms) "baru tadi" else "just now"
            m < 60 -> if (ms) "$m min lalu" else "$m min ago"
            m < 48 * 60 -> if (ms) "${m / 60} j lalu" else "${m / 60} h ago"
            else -> if (ms) "${m / 1440} hari lalu" else "${m / 1440} d ago"
        }
    }

    /** Issues of a route the preview banner shows: avoided ones first, then open issues on the route. */
    fun bannerIssues(issues: List<my.gimano.app.data.model.RouteIssue>): List<my.gimano.app.data.model.RouteIssue> =
        issues.filter { it.state in my.gimano.app.data.model.IssueState.open }
            .sortedWith(compareByDescending<my.gimano.app.data.model.RouteIssue> { it.isAvoided }.thenByDescending { it.state == "confirmed" })

    /** "Masih ditutup?" candidates: open road issues within [radiusM] of [me], not yet answered. */
    fun stillClosedCandidate(
        issues: List<my.gimano.app.data.model.Issue>, me: my.gimano.app.core.geo.GeoPoint, answered: Set<String>, radiusM: Double = 300.0,
    ): my.gimano.app.data.model.Issue? = issues.asSequence()
        .filter { it.kind in my.gimano.app.data.model.IssueKind.road && it.state in my.gimano.app.data.model.IssueState.open && it.id !in answered }
        .map { it to my.gimano.app.core.geo.GeoMath.distanceM(me, my.gimano.app.core.geo.GeoPoint(it.lat, it.lng)) }
        .filter { it.second <= radiusM }
        .minByOrNull { it.second }?.first

    /** Latest allowed "Tutup sementara sehingga" date (spec: max 60 days). */
    const val MAX_TEMP_CLOSED_DAYS = 60L
}
