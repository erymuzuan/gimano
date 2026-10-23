package my.gimano.app.core.trip

import my.gimano.app.data.model.Maneuver
import my.gimano.app.data.model.Route
import java.time.Instant

/** A maneuver with route-global shape indexes and the leg it belongs to. */
data class LegManeuver(val maneuver: Maneuver, val leg: Int)

object RouteLegs {
    const val MAX_STOPS = 5

    /**
     * Flattens legs into maneuvers with GLOBAL shape indexes. The contract indexes into the whole
     * decoded polyline; if a server sends per-leg (restarting) indexes, they are offset here.
     */
    fun maneuvers(route: Route): List<LegManeuver> {
        val out = ArrayList<LegManeuver>()
        var offset = 0
        var prevEnd = 0
        route.legs.forEachIndexed { k, leg ->
            val first = leg.maneuvers.firstOrNull()
            if (k > 0 && first != null && first.beginShapeIndex < prevEnd) offset = prevEnd
            leg.maneuvers.forEach { m ->
                val g = if (offset == 0) m else m.copy(beginShapeIndex = m.beginShapeIndex + offset, endShapeIndex = m.endShapeIndex + offset)
                out += LegManeuver(g, k)
            }
            prevEnd = (out.lastOrNull()?.maneuver?.endShapeIndex ?: prevEnd).coerceAtLeast(out.lastOrNull()?.maneuver?.beginShapeIndex ?: prevEnd)
        }
        return out
    }

    /** Shape index at which each leg ends (its "arrive" point). */
    fun legEndShapeIndexes(route: Route): List<Int> =
        maneuvers(route).groupBy { it.leg }.toSortedMap().values.map { ms -> ms.last().maneuver.beginShapeIndex }

    /** Each leg's duration: the server's leg.durationS, else the sum of its maneuvers. */
    fun legDurations(route: Route): List<Double> = route.legs.map { l -> l.durationS ?: l.maneuvers.sumOf { it.durationS } }

    /**
     * ETA at every stop and the destination (one per leg). Leg durations are scaled so they add up
     * to the route's traffic-aware total.
     */
    fun stopEtas(route: Route, departAt: Instant): List<Instant> {
        val legs = legDurations(route)
        val raw = legs.sum()
        val scale = if (raw > 0) route.effectiveDurationS / raw else 1.0
        var t = 0.0
        return legs.map { d -> t += d * scale; departAt.plusSeconds(t.toLong()) }
    }
}

/** Editing the ordered list of intermediate stops (pure; the UI calls these). */
object StopList {
    fun <T> add(stops: List<T>, stop: T, max: Int = RouteLegs.MAX_STOPS): List<T> {
        require(stops.size < max) { "At most $max stops" }
        return stops + stop
    }

    /** Insert as the NEXT stop (used when adding a stop while driving). */
    fun <T> insertNext(stops: List<T>, stop: T, max: Int = RouteLegs.MAX_STOPS): List<T> {
        require(stops.size < max) { "At most $max stops" }
        return listOf(stop) + stops
    }

    fun <T> remove(stops: List<T>, index: Int): List<T> = stops.filterIndexed { i, _ -> i != index }

    fun <T> move(stops: List<T>, from: Int, to: Int): List<T> {
        if (from !in stops.indices) return stops
        val t = to.coerceIn(0, stops.lastIndex)
        val out = stops.toMutableList()
        out.add(t, out.removeAt(from))
        return out
    }
}
