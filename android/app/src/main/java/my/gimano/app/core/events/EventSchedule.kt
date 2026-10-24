package my.gimano.app.core.events

import java.time.Instant
import java.time.ZoneId

/** Display helpers for an event; pure, unit-tested. */
object EventSchedule {
    /** The event's own zone (IANA `tz`), else the offset of its start. */
    fun zone(plan: EventPlan): ZoneId = runCatching { ZoneId.of(plan.tz) }.getOrElse { plan.start.offset }

    /**
     * "Seterusnya": among stops with an arrival window and sessions held at a stop, the one that hasn't ended and
     * starts earliest; with no times at all, the first stop while the event hasn't ended.
     * When everything is in the past, the last stop.
     */
    fun nextStop(plan: EventPlan, now: Instant): EventStop? {
        if (plan.stops.isEmpty()) return null
        data class Slot(val from: Instant, val to: Instant, val stop: EventStop)
        val slots = plan.stops.mapNotNull { s -> s.arrive?.let { Slot(it.from.toInstant(), it.to.toInstant(), s) } } +
            plan.sessions.mapNotNull { x -> x.stopId?.let(plan::stop)?.let { Slot(x.start.toInstant(), x.end.toInstant(), it) } }
        slots.filter { !it.to.isBefore(now) }.minByOrNull { it.from }?.let { return it.stop }
        return if (slots.isEmpty() && now.isBefore(plan.end.toInstant())) plan.stops.first() else plan.stops.last()
    }

    /** Everything the map should fit: pins, entrances and route geometry. */
    fun mapPoints(plan: EventPlan) = plan.stops.flatMap { listOfNotNull(it.pin, it.entrance) } + plan.routes.flatMap { it.points }
}
