package my.gimano.app.core.format

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** App languages. Formatters are pure Kotlin (no Android resources) so they are unit-testable. */
enum class Lang(val tag: String) {
    EN("en"), MS("ms");

    companion object {
        fun from(tag: String?): Lang = if (tag?.lowercase()?.startsWith("ms") == true) MS else EN
    }
}

object Formatters {
    /** "30 Nov 2026" (Malay month names in MS). */
    fun shortDate(d: java.time.LocalDate, lang: Lang): String =
        d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag(if (lang == Lang.MS) "ms-MY" else "en-GB")))


    /**
     * Distance for lists and cards: "910 m", "5.1 km", "260 km".
     * < 1 km rounds to 10 m; < 10 km one decimal; otherwise whole km.
     */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return when {
            m < 995 -> "${roundTo(m, 10)} m"
            m < 9_950 -> String.format(Locale.US, "%.1f km", m / 1000.0)
            else -> "${(m / 1000.0).roundToLong()} km"
        }
    }

    /**
     * Distance for the turn banner: coarser steps the further away, the way a driver reads it.
     * < 100 m → 10 m steps, < 500 m → 50 m steps, < 1 km → 100 m steps, then km.
     */
    fun navDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return when {
            m < 100 -> "${roundTo(m, 10)} m"
            m < 500 -> "${roundTo(m, 50)} m"
            m < 950 -> "${roundTo(m, 100)} m"
            else -> distance(m)
        }
    }

    private fun roundTo(v: Double, step: Int): Int = ((v / step).roundToInt() * step)

    /** "45 s" is never shown to drivers; minimum is "1 min". "10 min", "1 h 5 min" / "1 j 5 min". */
    fun duration(seconds: Double, lang: Lang = Lang.EN): String {
        val totalMin = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        val hUnit = if (lang == Lang.MS) "j" else "h"
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h $hUnit"
            else -> "$h $hUnit $m min"
        }
    }

    /** Short duration for route bubbles: "12 min", "1:05 h" style kept readable: "1 h 5". */
    fun durationShort(seconds: Double, lang: Lang = Lang.EN): String = duration(seconds, lang)

    /** ETA clock time: "8:13 AM" (12h) or "08:13" (24h) in the given zone. */
    fun etaClock(now: Instant, remainingS: Double, zone: ZoneId, use24h: Boolean, lang: Lang = Lang.EN): String {
        val eta = ZonedDateTime.ofInstant(now.plusSeconds(remainingS.roundToLong()), zone)
        return clock(eta, use24h, lang)
    }

    fun clock(t: ZonedDateTime, use24h: Boolean, lang: Lang = Lang.EN): String {
        if (use24h) return t.format(DateTimeFormatter.ofPattern("HH:mm"))
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val suffix = if (t.hour < 12) (if (lang == Lang.MS) "PG" else "AM") else (if (lang == Lang.MS) "PTG" else "PM")
        return String.format(Locale.US, "%d:%02d %s", h12, t.minute, suffix)
    }

    /** "10 min • 3.7 km" line under the ETA. */
    fun remainingLine(remainingS: Double, remainingM: Double, lang: Lang = Lang.EN): String =
        "${duration(remainingS, lang)} • ${distance(remainingM)}"

    fun speedKmh(metersPerSecond: Float?): Int = ((metersPerSecond ?: 0f) * 3.6f).roundToInt().coerceAtLeast(0)

    // --- Report / alert time display -------------------------------------------------------

    /** How long ago a report was made: "Just now", "5 min ago", "2 h ago" (ms: "Baru sahaja", "5 min lalu"). */
    fun reportAge(createdAt: Instant, now: Instant, lang: Lang = Lang.EN): String {
        val s = Duration.between(createdAt, now).seconds.coerceAtLeast(0)
        val min = s / 60
        return when {
            min < 1 -> if (lang == Lang.MS) "Baru sahaja" else "Just now"
            min < 60 -> if (lang == Lang.MS) "$min min lalu" else "$min min ago"
            min < 60 * 24 -> {
                val h = min / 60
                if (lang == Lang.MS) "$h j lalu" else "$h h ago"
            }
            else -> {
                val d = min / (60 * 24)
                if (lang == Lang.MS) "$d hari lalu" else if (d == 1L) "1 day ago" else "$d days ago"
            }
        }
    }

    /** Remaining TTL of an alert: "Expires in 12 min", "Expires in 2 h 5 min", "Expired". */
    fun reportTimeLeft(expiresAt: Instant, now: Instant, lang: Lang = Lang.EN): String {
        val s = Duration.between(now, expiresAt).seconds
        if (s <= 0) return if (lang == Lang.MS) "Tamat tempoh" else "Expired"
        val text = if (s < 60) (if (lang == Lang.MS) "< 1 min" else "< 1 min") else duration(s.toDouble(), lang)
        return if (lang == Lang.MS) "Tamat dalam $text" else "Expires in $text"
    }

    /** Fraction of TTL left (1 = fresh, 0 = expired); drives the shrinking bar on the alert card. */
    fun reportTtlFraction(createdAt: Instant, expiresAt: Instant, now: Instant): Float {
        val total = Duration.between(createdAt, expiresAt).toMillis().toDouble()
        if (total <= 0) return 0f
        val left = Duration.between(now, expiresAt).toMillis().toDouble()
        return (left / total).coerceIn(0.0, 1.0).toFloat()
    }

    fun parseInstant(iso: String?): Instant? = try {
        if (iso == null) null else Instant.parse(iso)
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    /** ISO-8601 instant (UTC, from a manifest or Drive) → "yyyy-MM-dd HH:mm" in the phone's zone. Unparseable input is shown as-is. */
    fun localStamp(iso: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        runCatching {
            java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }.getOrElse { iso.take(16).replace('T', ' ') }
}
