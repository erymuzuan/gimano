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
 * When a planned drive should ARRIVE. Same convention as iOS (ios/Gimano/Storage/PlannedDrives.swift),
 * stored in the shared settings table under `planned_drive:<id>`:
 *  repeating `{"arriveBy":"08:30","days":["Mo","Tu","We","Th","Fr"]}` · one-off `{"arriveAt":"2026-10-24T00:30:00Z"}`
 */
@Serializable
data class PlannedSchedule(val arriveBy: String? = null, val days: List<String>? = null, val arriveAt: String? = null) {
    val repeating get() = !days.isNullOrEmpty() && arriveBy != null

    /** Local arrival time shown in the editor ("08:30"). */
    fun timeOfDay(zone: ZoneId): String = arriveBy ?: arriveAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalTime().toString().take(5) }.getOrNull() } ?: "08:00"

    /** The next arrival target strictly after [now] (null for a one-off in the past). */
    fun nextArrival(now: Instant, zone: ZoneId): ZonedDateTime? {
        if (!repeating) return arriveAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.takeIf { it.isAfter(now) }?.atZone(zone)
        val t = LocalTime.parse(arriveBy)
        val today = now.atZone(zone).toLocalDate()
        val wanted = days.orEmpty().mapNotNull(::dayOf).toSet()
        for (i in 0..7) {
            val d = today.plusDays(i.toLong())
            val at = d.atTime(t).atZone(zone)
            if (d.dayOfWeek in wanted && at.toInstant().isAfter(now)) return at
        }
        return null
    }

    companion object {
        /** OSM-style day codes, as iOS stores them. */
        val WEEK = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        val WEEKDAYS = WEEK.take(5)
        fun dayOf(code: String): DayOfWeek? = WEEK.indexOfFirst { it.equals(code, ignoreCase = true) }.takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }
        fun repeating(arriveBy: String, days: List<String>) = PlannedSchedule(arriveBy = arriveBy, days = days)
        fun oneOff(date: LocalDate, time: String, zone: ZoneId) = PlannedSchedule(arriveAt = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toString())
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

    /** The reminder fires 15 minutes before "leave by" (same as iOS). */
    fun reminderAt(leaveBy: Instant): Instant = leaveBy.minusSeconds(15 * 60)
}
