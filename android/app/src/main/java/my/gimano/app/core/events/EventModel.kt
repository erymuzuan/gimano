package my.gimano.app.core.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import my.gimano.app.core.geo.GeoPoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/*
 * Event JSON v1 (docs/events.md §4), read side only. Mirrors desktop/crates/event-core `model.rs` + `validate.rs`:
 * unknown fields ignored; optional fields missing or null; unknown template / stop kind read as OTHER; unknown
 * lang / mode / source rejected; text lengths count code points.
 */

enum class EventTemplate { KENDURI, DINNER, COMPANY, RIDE, CONVOY, TRIP, OTHER }
enum class StopKind { PARKING, DROPOFF, ENTRANCE, VENUE, MEAL, CHECKPOINT, FUEL, WATER, REST, PRAYER, ACCOMMODATION, START, FINISH, OTHER }
enum class EventTravelMode(val wire: String) { CAR("car"), MOTORCYCLE("motorcycle"), BICYCLE("bicycle"), WALK("walk") }
enum class GeometrySource(val wire: String) {
    ROUTED("routed"), SKETCH("sketch"), GPX("gpx");
    val navigable get() = this == ROUTED
}

data class TimeWindow(val from: OffsetDateTime, val to: OffsetDateTime)

data class EventStop(
    val id: String,
    val kind: StopKind,
    val label: String,
    val lat: Double,
    val lng: Double,
    val entrance: GeoPoint?,
    val arrive: TimeWindow?,
    val dwellMin: Long?,
    val notes: String?,
    val audience: String?,
) {
    val pin: GeoPoint get() = GeoPoint(lat, lng)
    /** Where navigation goes: the vehicle entrance if present, else the pin (§7.7). */
    val navigationTarget: GeoPoint get() = entrance ?: pin
}

data class EventSession(val id: String, val title: String, val start: OffsetDateTime, val end: OffsetDateTime, val stopId: String?)

data class RouteLeg(val fromStopId: String, val toStopId: String, val distanceM: Long, val durationS: Long?, val source: GeometrySource)

data class EventRoute(
    val id: String,
    val title: String?,
    val mode: EventTravelMode,
    val day: String?,
    val stopIds: List<String>,
    /** Precision-6 polyline; null only in a stops-only variant (§5.5), whose [points] are then empty. */
    val polyline: String?,
    val points: List<GeoPoint>,
    val source: GeometrySource,
    val distanceM: Long,
    val durationS: Long?,
    val ascentM: Long?,
    val descentM: Long?,
    val legs: List<RouteLeg>,
)

/** `"variant": {"kind": "stops-only", "fullHash": …}` (§5.5): the event without route geometry. */
data class EventVariant(val kind: String, val fullHash: String) {
    val fullHashBytes: ByteArray? get() = B64u.decodeExact(fullHash, 32)

    companion object {
        const val STOPS_ONLY = "stops-only"
        fun stopsOnly(fullHash: ByteArray) = EventVariant(STOPS_ONLY, B64u.encode(fullHash))
    }
}

data class EventPlan(
    val id: String,
    val rev: Long,
    /** b64u(SHA-256(previous revision bytes)); null for rev 1. */
    val parent: String?,
    val publishedAt: Instant,
    val template: EventTemplate,
    val title: String,
    val description: String?,
    val lang: String,
    val tz: String,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val expiresAt: Instant,
    val organizerAlias: String?,
    val stops: List<EventStop>,
    val sessions: List<EventSession>,
    val routes: List<EventRoute>,
    /** Non-null only on a stops-only variant (§5.5): no route lines; the event file has them. */
    val variant: EventVariant? = null,
) {
    val stopsOnly: Boolean get() = variant?.kind == EventVariant.STOPS_ONLY
    fun stop(id: String): EventStop? = stops.firstOrNull { it.id == id }
    val polylinePoints: Int get() = routes.sumOf { it.points.size }
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)
}

// --- Wire DTOs (strings for every enum/time so the reader decides what is unknown) ------------------------

@Serializable internal data class RawLatLng(val lat: Double, val lng: Double)
@Serializable internal data class RawWindow(val from: String, val to: String)
@Serializable internal data class RawOrganizer(val alias: String? = null)

@Serializable
internal data class RawStop(
    val id: String, val kind: String, val label: String, val lat: Double, val lng: Double,
    val entrance: RawLatLng? = null, val arrive: RawWindow? = null, val dwellMin: Long? = null,
    val notes: String? = null, val audience: String? = null,
)

@Serializable internal data class RawSession(val id: String, val title: String, val start: String, val end: String, val stopId: String? = null)

@Serializable
internal data class RawLeg(val fromStopId: String, val toStopId: String, val distanceM: Long, val durationS: Long? = null, val source: String)

@Serializable
internal data class RawRoute(
    val id: String, val title: String? = null, val mode: String, val day: String? = null, val stopIds: List<String>? = null,
    val polyline: String? = null, val source: String, val distanceM: Long, val durationS: Long? = null,
    val ascentM: Long? = null, val descentM: Long? = null, val legs: List<RawLeg>? = null,
)

@Serializable
internal data class RawEvent(
    val id: String, val rev: Long, val parent: String? = null, val publishedAt: String, val template: String,
    val title: String, val description: String? = null, val lang: String, val tz: String,
    val start: String, val end: String, val expiresAt: String, val organizer: RawOrganizer? = null,
    val stops: List<RawStop>? = null, val sessions: List<RawSession>? = null, val routes: List<RawRoute>? = null,
    val variant: RawVariant? = null,
)

/** `kind` stays a string so an unknown kind is `unsupported_variant`, not malformed JSON. */
@Serializable internal data class RawVariant(val kind: String, val fullHash: String)

@Serializable internal data class RawEnvelope(val v: Long, val event: String, val pub: String, val sig: String)

internal val EventJson = Json { ignoreUnknownKeys = true; isLenient = false; coerceInputValues = false; explicitNulls = false }

/** Parses and validates signed event bytes (call only after the signature verified). */
object EventParser {
    fun parse(bytes: ByteArray): EventPlan {
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrElse { fail(EventPackException.MALFORMED_EVENT) }
        val raw = try {
            EventJson.decodeFromString(RawEvent.serializer(), text)
        } catch (e: Exception) {
            fail(EventPackException.MALFORMED_EVENT, e.message ?: "malformed")
        }
        return EventValidator.validate(raw)
    }
}

internal object EventValidator {
    private fun invalid(path: String, reason: String): Nothing = fail(EventPackException.INVALID_EVENT, "$path: $reason")

    private fun u32(v: Long?): Boolean = v == null || v in 0..0xFFFF_FFFFL

    private fun chars(s: String) = s.codePointCount(0, s.length)

    private fun text(path: String, s: String, min: Int, max: Int) {
        val n = chars(s)
        if (n < min || (min > 0 && s.isBlank())) invalid(path, "is required")
        if (n > max) invalid(path, "is too long")
    }

    private fun optText(path: String, s: String?, max: Int) { if (s != null) text(path, s, 0, max) }

    private fun id(path: String, s: String, bytes: Int) { if (B64u.decodeExact(s, bytes) == null) invalid(path, "is not a valid id") }

    private fun sixDecimals(v: Double) = Math.abs(Math.round(v * 1e6) / 1e6 - v) <= 1e-9

    private fun coord(path: String, lat: Double, lng: Double) {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) invalid(path, "coordinate out of range")
        if (!sixDecimals(lat) || !sixDecimals(lng)) invalid(path, "coordinate has more than 6 decimals")
    }

    private fun time(s: String): OffsetDateTime = try {
        OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (e: Exception) {
        fail(EventPackException.MALFORMED_EVENT, "bad time")
    }

    private fun tzOk(s: String) = s.isNotEmpty() && s.length <= EventLimits.TZ_CHARS && !s.startsWith('/') && !s.endsWith('/') &&
        !s.contains("//") && s.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '/' || it == '_' || it == '-' || it == '+' }

    private fun template(s: String) = runCatching { EventTemplate.valueOf(s.uppercase()) }.getOrNull()?.takeIf { s == s.lowercase() } ?: EventTemplate.OTHER

    private fun kind(s: String) = runCatching { StopKind.valueOf(s.uppercase()) }.getOrNull()?.takeIf { s == s.lowercase() } ?: StopKind.OTHER

    private fun mode(s: String) = EventTravelMode.entries.firstOrNull { it.wire == s } ?: fail(EventPackException.MALFORMED_EVENT, "unknown mode")

    private fun source(s: String) = GeometrySource.entries.firstOrNull { it.wire == s } ?: fail(EventPackException.MALFORMED_EVENT, "unknown source")

    fun validate(e: RawEvent): EventPlan {
        // Type-level checks serde does while deserializing (u32 ranges, enums, times) → malformed_event.
        if (!u32(e.rev)) fail(EventPackException.MALFORMED_EVENT, "rev")
        if (e.lang != "ms" && e.lang != "en") fail(EventPackException.MALFORMED_EVENT, "unknown lang")
        val publishedAt = time(e.publishedAt).toInstant()
        val start = time(e.start)
        val end = time(e.end)
        val expiresAt = time(e.expiresAt).toInstant()
        val rawStops = e.stops.orEmpty()
        val rawSessions = e.sessions.orEmpty()
        val rawRoutes = e.routes.orEmpty()
        rawStops.forEach { s ->
            if (!u32(s.dwellMin)) fail(EventPackException.MALFORMED_EVENT, "dwellMin")
            s.arrive?.let { time(it.from); time(it.to) }
        }
        rawSessions.forEach { time(it.start); time(it.end) }
        val modes = rawRoutes.map { r ->
            listOf(r.distanceM, r.durationS, r.ascentM, r.descentM).forEach { if (!u32(it)) fail(EventPackException.MALFORMED_EVENT, "route number") }
            r.legs.orEmpty().forEach { l ->
                if (!u32(l.distanceM) || !u32(l.durationS)) fail(EventPackException.MALFORMED_EVENT, "leg number")
                source(l.source)
            }
            mode(r.mode) to source(r.source)
        }

        // validate.rs `check`: the variant rules (§5.5) with their own codes, before every §4 rule.
        val stopsOnly = when (val v = e.variant) {
            null -> false
            else -> if (v.kind == EventVariant.STOPS_ONLY) true else fail(EventPackException.UNSUPPORTED_VARIANT, "variant.kind")
        }
        rawRoutes.forEachIndexed { i, r ->
            if (stopsOnly && r.polyline != null) fail(EventPackException.VARIANT_HAS_POLYLINE, "routes[$i]")
            if (!stopsOnly && r.polyline == null) fail(EventPackException.MISSING_POLYLINE, "routes[$i]")
        }

        // validate.rs, in the same order.
        if (stopsOnly && B64u.decodeExact(e.variant!!.fullHash, 32) == null) invalid("variant.fullHash", "is not a SHA-256 hash")
        id("id", e.id, EventLimits.EVENT_ID_BYTES)
        if (e.rev == 0L) invalid("rev", "must be 1 or more")
        when {
            e.parent == null && e.rev == 1L -> Unit
            e.parent != null && e.rev == 1L -> invalid("parent", "must be null for rev 1")
            e.parent == null -> invalid("parent", "is required after rev 1")
            B64u.decodeExact(e.parent, 32) == null -> invalid("parent", "is not a SHA-256 hash")
        }
        text("title", e.title, 1, EventLimits.TITLE_CHARS)
        optText("description", e.description, EventLimits.DESCRIPTION_CHARS)
        if (!tzOk(e.tz)) invalid("tz", "is not an IANA time zone name")
        if (end.isBefore(start)) invalid("end", "ends before it starts")
        if (Duration.between(start, end) > Duration.ofDays(EventLimits.MAX_EVENT_DAYS)) invalid("end", "is more than 14 days after start")
        e.organizer?.let { optText("organizer.alias", it.alias, EventLimits.ALIAS_CHARS) }

        if (rawStops.size > EventLimits.MAX_STOPS) invalid("stops", "has more than 200 stops")
        val stopIds = HashSet<String>()
        val stops = rawStops.mapIndexed { i, s ->
            val p = "stops[$i]"
            id("$p.id", s.id, EventLimits.ITEM_ID_BYTES)
            if (!stopIds.add(s.id)) invalid("$p.id", "is duplicated")
            text("$p.label", s.label, 1, EventLimits.STOP_LABEL_CHARS)
            coord("$p.lat", s.lat, s.lng)
            s.entrance?.let { coord("$p.entrance", it.lat, it.lng) }
            val arrive = s.arrive?.let { TimeWindow(time(it.from), time(it.to)) }
            if (arrive != null && arrive.to.isBefore(arrive.from)) invalid("$p.arrive", "ends before it starts")
            if (s.dwellMin != null && s.dwellMin > EventLimits.MAX_DWELL_MIN) invalid("$p.dwellMin", "is too long")
            optText("$p.notes", s.notes, EventLimits.STOP_NOTES_CHARS)
            optText("$p.audience", s.audience, EventLimits.AUDIENCE_CHARS)
            EventStop(s.id, kind(s.kind), s.label, s.lat, s.lng, s.entrance?.let { GeoPoint(it.lat, it.lng) }, arrive, s.dwellMin, s.notes, s.audience)
        }
        fun knownStop(path: String, sid: String) { if (sid !in stopIds) invalid(path, "refers to an unknown stop") }

        if (rawSessions.size > EventLimits.MAX_SESSIONS) invalid("sessions", "has more than 50 sessions")
        val sessionIds = HashSet<String>()
        val sessions = rawSessions.mapIndexed { i, s ->
            val p = "sessions[$i]"
            id("$p.id", s.id, EventLimits.ITEM_ID_BYTES)
            if (!sessionIds.add(s.id)) invalid("$p.id", "is duplicated")
            text("$p.title", s.title, 1, EventLimits.SESSION_TITLE_CHARS)
            val ss = time(s.start)
            val se = time(s.end)
            if (se.isBefore(ss)) invalid("$p.end", "ends before it starts")
            s.stopId?.let { knownStop("$p.stopId", it) }
            EventSession(s.id, s.title, ss, se, s.stopId)
        }

        if (rawRoutes.size > EventLimits.MAX_ROUTES) invalid("routes", "has more than 20 routes")
        val routeIds = HashSet<String>()
        var points = 0
        val routes = rawRoutes.mapIndexed { i, r ->
            val p = "routes[$i]"
            id("$p.id", r.id, EventLimits.ITEM_ID_BYTES)
            if (!routeIds.add(r.id)) invalid("$p.id", "is duplicated")
            optText("$p.title", r.title, EventLimits.ROUTE_TITLE_CHARS)
            r.day?.let { d -> if (d.length != 10 || runCatching { LocalDate.parse(d) }.isFailure) invalid("$p.day", "is not a YYYY-MM-DD date") }
            r.stopIds.orEmpty().forEachIndexed { j, sid -> knownStop("$p.stopIds[$j]", sid) }
            val decoded = if (r.polyline == null) emptyList() else try {
                EventPolyline.decode(r.polyline, EventLimits.MAX_POLYLINE_POINTS - points)
            } catch (x: EventPolyline.TooManyPoints) {
                invalid("$p.polyline", "routes have more than 50 000 polyline points in total")
            } catch (x: IllegalArgumentException) {
                invalid("$p.polyline", "is not a valid precision-6 polyline")
            }
            if (!stopsOnly && decoded.size < 2) invalid("$p.polyline", "needs at least 2 points")
            points += decoded.size
            r.legs.orEmpty().forEachIndexed { j, l ->
                knownStop("$p.legs[$j].fromStopId", l.fromStopId)
                knownStop("$p.legs[$j].toStopId", l.toStopId)
            }
            val (mode, src) = modes[i]
            EventRoute(r.id, r.title, mode, r.day, r.stopIds.orEmpty(), r.polyline, decoded, src, r.distanceM, r.durationS, r.ascentM, r.descentM,
                r.legs.orEmpty().map { RouteLeg(it.fromStopId, it.toStopId, it.distanceM, it.durationS, source(it.source)) })
        }

        return EventPlan(e.id, e.rev, e.parent, publishedAt, template(e.template), e.title, e.description, e.lang, e.tz, start, end, expiresAt,
            e.organizer?.alias, stops, sessions, routes, e.variant?.let { EventVariant(it.kind, it.fullHash) })
    }
}

/** Google encoded polyline, precision 6, strict like route-core `decode_polyline`. */
object EventPolyline {
    class TooManyPoints : IllegalStateException()

    fun decode(s: String, maxPoints: Int): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var i = 0
        var lat = 0L
        var lng = 0L
        while (i < s.length) {
            if (out.size >= maxPoints) throw TooManyPoints()
            val (dLat, i1) = read(s, i)
            lat += dLat
            if (i1 >= s.length) throw IllegalArgumentException("truncated")
            val (dLng, i2) = read(s, i1)
            lng += dLng
            i = i2
            val la = lat / 1e6
            val lo = lng / 1e6
            if (la !in -90.0..90.0 || lo !in -180.0..180.0) throw IllegalArgumentException("out of range")
            out += GeoPoint(la, lo)
        }
        return out
    }

    private fun read(s: String, start: Int): Pair<Long, Int> {
        var i = start
        var result = 0L
        var shift = 0
        while (true) {
            if (i >= s.length) throw IllegalArgumentException("truncated")
            val b = s[i].code
            if (b !in 63..126) throw IllegalArgumentException("invalid char")
            i++
            if (shift > 35) throw IllegalArgumentException("overflow")
            val chunk = (b - 63).toLong()
            result = result or ((chunk and 0x1f) shl shift)
            shift += 5
            if (chunk < 0x20) break
        }
        val v = result ushr 1
        return (if (result and 1L == 1L) v.inv() else v) to i
    }
}
