package my.gimano.app.data.planned

import my.gimano.app.core.trip.LeaveBy
import java.time.Instant
import java.time.ZoneId

/**
 * "Leave by" for a planned drive (user decision: the user's position goes to the server only when routing).
 *
 * - A drive with a SAVED start (Home, Work, a place) may ask GET /routes/departure-times in the background (boot,
 *   app update, after a reminder, save/edit): the request carries that saved place, not where the user is.
 * - A "where I am" drive (no saved start) never makes a background call and never uses the live/last GPS fix:
 *   its leave-by stays null, the reminder fires [FALLBACK_S] before arrival with "Open Manotu for the best time",
 *   and the traffic-based time comes only when the user opens the drive and routes it.
 */
object LeavePlanner {
    /** Without traffic data: assume 30 minutes. */
    const val FALLBACK_S = 1800L

    data class Plan(val arrive: Instant, val leaveBy: Instant?)

    /** The departure-times request: origin lat/lng, destination lat/lng, vehicle. */
    fun interface DepartureTimes {
        suspend fun slots(oLat: Double, oLng: Double, dLat: Double, dLng: Double, vehicle: String?): List<LeaveBy.Slot>
    }

    suspend fun plan(d: PlannedDrive, now: Instant, zone: ZoneId, departureTimes: DepartureTimes): Plan? {
        val arrive = d.schedule.nextArrival(now, zone) ?: return null
        val origin = d.origin ?: return Plan(arrive.toInstant(), null)   // "where I am": no request at all
        val slots = runCatching { departureTimes.slots(origin.lat, origin.lng, d.destination.lat, d.destination.lng, d.vehicle) }
            .getOrDefault(emptyList())
            .filter { it.departAt.atZone(zone).toLocalDate() == arrive.toLocalDate() }
        return Plan(arrive.toInstant(), LeaveBy.compute(arrive.toInstant(), slots, FALLBACK_S.toDouble()))
    }

    /** When the reminder fires: 15 min before leave-by, or (no leave-by) 15 min before arrival − [FALLBACK_S]. */
    fun reminderAt(p: Plan): Instant = LeaveBy.reminderAt(p.leaveBy ?: p.arrive.minusSeconds(FALLBACK_S))
}
