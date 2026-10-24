package my.gimano.app.core.routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import my.gimano.app.core.events.EventJsonWriter
import my.gimano.app.core.events.EventTravelMode
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import java.util.concurrent.atomic.AtomicLong

/*
 * The route builder's editable route ("Bina laluan"), a port of the desktop draft model
 * (desktop/src-tauri/src/doc.rs `RouteDraft`, `RoutePoint`, `Leg`). It lives only on this phone: inside an event's
 * HostDraft (sealed in manotu-events.db) or as a personal route in the main DB (`personal_routes`, as route_json v1:
 * PersonalRouteJson). Nothing here touches the network.
 */

/** A WGS84 position, serializable (GeoPoint stays a plain core type). */
@Serializable
data class Pos(val lat: Double, val lng: Double) {
    val geo: GeoPoint get() = GeoPoint(lat, lng)
    val isValid: Boolean get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
    fun rounded6(): Pos = Pos(EventJsonWriter.round6(lat), EventJsonWriter.round6(lng))

    companion object {
        fun of(g: GeoPoint) = Pos(g.lat, g.lng)
    }
}

enum class LegState {
    /** Needs calculation ("perlu dikira"); nothing is drawn for it. */
    PENDING,
    /** Road geometry from `POST /routes` (`store: false`). */
    ROUTED,
    /** A drawn line snapped by `POST /routes/match`; [BuilderLeg.unmatched] lists drawn ranges with no road. */
    MATCHED,
    /** Straight line or drawn line kept as an explicit sketch ("lakaran", not navigable). */
    SKETCH,
    /** Routing failed or is unreachable; nothing is drawn (never a straight line pretending to be a road). */
    UNRESOLVED,
}

/** Inclusive range of drawn input points that matched no road. */
@Serializable
data class Span(val from: Int, val to: Int)

/** `legs[i]` joins `points[i]` and `points[i + 1]`. */
@Serializable
data class BuilderLeg(
    val state: LegState = LegState.PENDING,
    /** Bumped whenever the leg is invalidated, so late routing results are discarded. */
    val generation: Long,
    val geometry: List<Pos> = emptyList(),
    val distanceM: Long = 0,
    val durationS: Long? = null,
    /** Freehand line drawn in "Lukis" mode (input of `/routes/match`). */
    val drawn: List<Pos>? = null,
    val unmatched: List<Span> = emptyList(),
    /** Why the leg is unresolved: one of [RouteProblem]. */
    val problem: String? = null,
) {
    /** Has usable geometry (drawn on the map, counted in totals). */
    val resolved: Boolean get() = state == LegState.ROUTED || state == LegState.MATCHED || state == LegState.SKETCH
    val hasUnmatched: Boolean get() = state == LegState.MATCHED && unmatched.isNotEmpty()

    companion object {
        fun pending(): BuilderLeg = BuilderLeg(LegState.PENDING, Generations.next())
    }
}

object RouteProblem {
    const val OFFLINE = "offline"
    /** Demo data mode: there is no server, so roads are never calculated (not a connection problem). */
    const val DEMO = "demo"
    const val NO_ROUTE = "no_route"
    const val UNAVAILABLE = "unavailable"
    /** `POST /routes/match` is not on this server yet (404/405): "Lukis ikut jalan belum tersedia". */
    const val MATCH_UNAVAILABLE = "match_unavailable"
    const val ERROR = "error"
}

/** A route point: a stop of the event ([stopId]) or a shaping point. */
@Serializable
data class BuilderPoint(val id: String, val lat: Double, val lng: Double, val stopId: String? = null) {
    val pos: Pos get() = Pos(lat, lng)
    val geo: GeoPoint get() = GeoPoint(lat, lng)
}

@Serializable
data class BuilderRoute(
    val id: String,
    val title: String = "",
    /** [EventTravelMode.wire]: car|motorcycle|bicycle|walk. */
    val mode: String = EventTravelMode.CAR.wire,
    /** Optional local date `YYYY-MM-DD` (events only). */
    val day: String? = null,
    /** "Ikut jalan": off = straight-line sketch legs. */
    val followRoads: Boolean = true,
    val points: List<BuilderPoint> = emptyList(),
    /** Always `points.size - 1` long (or empty). */
    val legs: List<BuilderLeg> = emptyList(),
) {
    val isConsistent: Boolean get() = legs.size == maxOf(0, points.size - 1)
    val travelMode: EventTravelMode get() = EventTravelMode.entries.firstOrNull { it.wire == mode } ?: EventTravelMode.CAR

    fun encode(): ByteArray = Codec.encodeToString(serializer(), this).toByteArray()

    companion object {
        val Codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(bytes: ByteArray): BuilderRoute = Codec.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
    }
}

/** Unique across restarts (time-based base), like the desktop `next_generation`. */
object Generations {
    private val counter = AtomicLong(System.currentTimeMillis() * 1000)
    fun next(): Long = counter.incrementAndGet()
}

object RouteGeo {
    fun lengthM(points: List<Pos>): Double {
        var d = 0.0
        for (i in 1 until points.size) d += GeoMath.distanceM(points[i - 1].geo, points[i].geo)
        return d
    }

    /** Keeps at most [max] points, always including both ends (desktop `thin`). */
    fun thin(points: List<Pos>, max: Int): List<Pos> {
        if (points.size <= max || max < 2) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { k -> points[minOf(Math.round(k * step).toInt(), points.size - 1)] }
    }

    /** Moves a position by metres east/north (local flat approximation). */
    fun offset(p: Pos, eastM: Double, northM: Double): Pos {
        val mPerDeg = 111_320.0
        val lat = p.lat + northM / mPerDeg
        val lng = p.lng + eastM / (mPerDeg * maxOf(Math.cos(Math.toRadians(p.lat)), 0.01))
        return Pos(lat.coerceIn(-90.0, 90.0), lng.coerceIn(-180.0, 180.0)).rounded6()
    }
}
