package my.gimano.app.core.trip

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a planned drive should ARRIVE. Stored as JSON in planned_drives.depart_at (shared schema):
 * `{"arriveBy":"08:30","days":["mo","tu","we","th","fr"],"date":null}` (repeating) or
 * `{"arriveBy":"08:30","days":[],"date":"2026-10-24"}` (one-off).
 */
@Serializable
data class PlannedSchedule(val arriveBy: String, val days: List<String> = emptyList(), val date: String? = null) {
    val repeating get() = days.isNotEmpty()

    /** The next arrival target strictly after [now] (null for a one-off in the past). */
    fun nextArrival(now: Instant, zone: ZoneId): ZonedDateTime? {
        val t = LocalTime.parse(arriveBy)
        val today = now.atZone(zone).toLocalDate()
        if (!repeating) {
            val d = date?.let(LocalDate::parse) ?: today
            return d.atTime(t).atZone(zone).takeIf { it.toInstant().isAfter(now) }
        }
        val wanted = days.mapNotNull(::dayOf).toSet()
        for (i in 0..7) {
            val d = today.plusDays(i.toLong())
            val at = d.atTime(t).atZone(zone)
            if (d.dayOfWeek in wanted && at.toInstant().isAfter(now)) return at
        }
        return null
    }

    companion object {
        val WEEK = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
        val WEEKDAYS = WEEK.take(5)
        fun dayOf(code: String): DayOfWeek? = WEEK.indexOf(code).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }
    }
}

object LeaveBy {
    /** A departure-times slot: leaving at [departAt] takes [durationS]. */
    data class Slot(val departAt: Instant, val durationS: Double)

    /**
     * Latest moment to leave and still arrive by [arriveBy]: uses the latest slot whose arrival is
     * in time (its duration is the best estimate for that hour); falls back to the closest slot,
     * then to [fallbackDurationS].
     */
    fun compute(arriveBy: Instant, slots: List<Slot>, fallbackDurationS: Double): Instant {
        val ok = slots.filter { !it.departAt.plusSeconds(it.durationS.toLong()).isAfter(arriveBy) }.maxByOrNull { it.departAt }
        val d = ok?.durationS
            ?: slots.minByOrNull { Duration.between(it.departAt.plusSeconds(it.durationS.toLong()), arriveBy).abs() }?.durationS
            ?: fallbackDurationS
        return arriveBy.minusSeconds(d.toLong())
    }

    /** The reminder fires 10 minutes before "leave by". */
    fun reminderAt(leaveBy: Instant): Instant = leaveBy.minusSeconds(10 * 60)
}
