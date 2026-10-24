package my.gimano.app.core.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import my.gimano.app.core.geo.GeoPoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * The organiser's editable copy of an event (Acara baharu / edit). Lives only on this phone, sealed by the events
 * vault in manotu-events.db (excluded from backups). Organiser-only notes live here and are never written into a pack.
 */

@Serializable
data class HostStop(
    val id: String,
    val kind: String = "venue",
    val label: String = "",
    val lat: Double,
    val lng: Double,
    val entranceLat: Double? = null,
    val entranceLng: Double? = null,
    /** Guest-visible. */
    val notes: String = "",
    /** Guest-visible free text ("Semua", "Keluarga"). */
    val audience: String = "",
    /** For the organiser only: never written into a pack. */
    val organizerNote: String = "",
    /** Local arrival time `HH:mm` (journey timeline), empty = none. Written as `arrive` (from = this, to = + [dwellMin]). */
    val arrive: String = "",
    /** Minutes spent here ("Rehat 30 min"), written as `dwellMin`. */
    val dwellMin: Int? = null,
    /** Day of a multi-day event, 0 = the first day. */
    val day: Int = 0,
) {
    val entrance: GeoPoint? get() = if (entranceLat != null && entranceLng != null) GeoPoint(entranceLat, entranceLng) else null
}

/**
 * One guest-info card of the wizard's "Info" step: a plain multi-line text. [kind] is an `info[].kind` (§4.1) or
 * [AGENDA], whose lines ("11:00 Ketibaan") become `sessions`.
 */
@Serializable
data class HostInfo(val id: String, val kind: String, val text: String = "") {
    companion object {
        const val AGENDA = "agenda"
        val KINDS = listOf("dress", "safety", "documents", "bring", "contact", "other")
    }
}

@Serializable
data class HostDraft(
    val id: String,
    val template: String = "kenduri",
    val title: String = "",
    val description: String = "",
    val lang: String = "ms",
    val tz: String = DEFAULT_TZ,
    /** Local date `YYYY-MM-DD`. */
    val date: String,
    /** Local `HH:mm`. */
    val startTime: String = "11:00",
    /** Local `HH:mm`; earlier than [startTime] means the next day. */
    val endTime: String = "16:00",
    /** Custom access end (UTC instant); null = end + 24 h. */
    val expiresAt: String? = null,
    /** Event-only alias, never an account name. */
    val alias: String = "",
    val stops: List<HostStop> = emptyList(),
    /** For the organiser only: never written into a pack. */
    val organizerNotes: String = "",
    /** Routes from the route builder ("Laluan"); published as `routes[]` once every leg is calculated. */
    val routes: List<my.gimano.app.core.routes.BuilderRoute> = emptyList(),
    /** Last local date `YYYY-MM-DD` of a multi-day event ("Lebih dari sehari"); null = one day. */
    val endDate: String? = null,
    /** Guest info cards (agenda + `info[]`), in the order shown. */
    val info: List<HostInfo> = emptyList(),
    /** "Acara sensitif": share a suite-2 file + 8-character code instead of the open file (§5.2.1). */
    val sensitive: Boolean = false,
    /** Travel mode chosen in the wizard's "Laluan" step (new routes use it); null = from the template. */
    val mode: String? = null,
) {
    companion object {
        const val DEFAULT_TZ = "Asia/Kuala_Lumpur"
        val TEMPLATES = listOf("kenduri", "meeting", "seminar", "dinner", "picnic", "other", "trip", "company", "ride", "convoy", "hike")
        val KINDS = listOf("parking", "dropoff", "entrance", "venue", "meal", "checkpoint", "fuel", "water", "rest", "prayer",
            "accommodation", "start", "finish", "other")

        /** An IANA zone the reader accepts, else Kuala Lumpur (e.g. "GMT+08:00" is not a zone name). */
        fun zoneName(z: ZoneId): String = z.id.takeIf { id -> id.contains('/') && id.all { it.isLetterOrDigit() || it in "/_-+" } } ?: DEFAULT_TZ

        fun new(id: String, lang: String, zone: ZoneId, today: LocalDate): HostDraft =
            HostDraft(id = id, lang = if (lang == "en") "en" else "ms", tz = zoneName(zone), date = today.plusDays(7).toString())

        val Codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    fun zone(): ZoneId = runCatching { ZoneId.of(tz) }.getOrElse { ZoneId.of(DEFAULT_TZ) }
    fun localDate(): LocalDate = LocalDate.parse(date)
    fun localStart(): LocalTime = LocalTime.parse(startTime)
    fun localEnd(): LocalTime = LocalTime.parse(endTime)

    fun start(): OffsetDateTime = LocalDateTime.of(localDate(), localStart()).atZone(zone()).toOffsetDateTime()

    /** "Lebih dari sehari". */
    val multiDay: Boolean get() = endDate != null

    /** Days of the event (1 for a one-day event). */
    fun dayCount(): Int = endDate?.let { runCatching { (java.time.temporal.ChronoUnit.DAYS.between(localDate(), LocalDate.parse(it)) + 1).toInt() }.getOrNull() }
        ?.coerceIn(1, EventLimits.MAX_EVENT_DAYS.toInt() + 1) ?: 1

    fun dayDate(i: Int): LocalDate = localDate().plusDays(i.toLong())

    fun end(): OffsetDateTime {
        endDate?.let { e -> return LocalDateTime.of(LocalDate.parse(e), localEnd()).atZone(zone()).toOffsetDateTime() }
        val endDay = if (localEnd().isBefore(localStart())) localDate().plusDays(1) else localDate()
        return LocalDateTime.of(endDay, localEnd()).atZone(zone()).toOffsetDateTime()
    }

    /** Default access end: `end` + 24 h (event-core `default_expiry`). */
    fun defaultExpiry(): Instant = end().toInstant().plus(Duration.ofHours(24))

    fun expires(): Instant = expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: defaultExpiry()

    fun encode(): ByteArray = Codec.encodeToString(serializer(), this).toByteArray()
}

fun decodeHostDraft(bytes: ByteArray): HostDraft = HostDraft.Codec.decodeFromString(HostDraft.serializer(), bytes.toString(Charsets.UTF_8))

/** Why "Kongsi jemputan" is not possible yet; [stop] is the stop index for stop problems. */
data class DraftIssue(val code: Code, val stop: Int? = null, val route: Int? = null, val info: Int? = null) {
    enum class Code { TITLE_REQUIRED, TITLE_TOO_LONG, ALIAS_TOO_LONG, DESCRIPTION_TOO_LONG, BAD_TIME, TOO_LONG_EVENT, EXPIRED,
        NO_STOPS, TOO_MANY_STOPS, STOP_LABEL_REQUIRED, STOP_LABEL_TOO_LONG, STOP_NOTES_TOO_LONG, STOP_AUDIENCE_TOO_LONG, STOP_POSITION, INVALID,
        /** A route still has legs to calculate ("perlu dikira"). */
        ROUTE_NOT_READY,
        /** Part of a drawn route matched no road: fix it or keep it as a sketch. */
        ROUTE_UNMATCHED,
        TOO_MANY_ROUTES,
        /** Routes have more than 50 000 polyline points in total. */
        TOO_MANY_ROUTE_POINTS,
        /** An info card is over 1000 characters ([DraftIssue.info] is its index). */
        INFO_TOO_LONG,
        TOO_MANY_INFO,
        /** An agenda line has no time ("11:00 Ketibaan"), or a session title is too long, or more than 50 sessions. */
        AGENDA_INVALID,
        /** An arrival time is not `HH:mm`. */
        STOP_TIME }

    /**
     * Where the editor scrolls to when "Kongsi jemputan" is blocked by this issue: "title", "alias", "description",
     * "time", "expiry", "add_stop", "stop:<i>", "route:<i>", "routes", or "issues" (the list of problems at the end).
     */
    val anchor: String get() = when (code) {
        Code.TITLE_REQUIRED, Code.TITLE_TOO_LONG -> "title"
        Code.ALIAS_TOO_LONG -> "alias"
        Code.DESCRIPTION_TOO_LONG -> "description"
        Code.BAD_TIME, Code.TOO_LONG_EVENT -> "time"
        Code.EXPIRED -> "expiry"
        Code.NO_STOPS, Code.TOO_MANY_STOPS -> "add_stop"
        Code.INFO_TOO_LONG, Code.TOO_MANY_INFO, Code.AGENDA_INVALID -> "info"
        Code.STOP_LABEL_REQUIRED, Code.STOP_LABEL_TOO_LONG, Code.STOP_NOTES_TOO_LONG, Code.STOP_AUDIENCE_TOO_LONG, Code.STOP_POSITION, Code.STOP_TIME ->
            stop?.let { "stop:$it" } ?: "add_stop"
        Code.ROUTE_NOT_READY, Code.ROUTE_UNMATCHED -> route?.let { "route:$it" } ?: "routes"
        Code.TOO_MANY_ROUTES, Code.TOO_MANY_ROUTE_POINTS -> "routes"
        Code.INVALID -> "issues"
    }
}

object HostDraftRules {
    fun chars(s: String) = s.codePointCount(0, s.length)

    private fun coordOk(lat: Double, lng: Double) = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    /** §4 limits, checked before publishing (the written bytes are validated again by [EventParser]). */
    fun issues(d: HostDraft, now: Instant): List<DraftIssue> {
        val out = ArrayList<DraftIssue>()
        val title = d.title.trim()
        if (title.isEmpty()) out += DraftIssue(DraftIssue.Code.TITLE_REQUIRED)
        if (chars(title) > EventLimits.TITLE_CHARS) out += DraftIssue(DraftIssue.Code.TITLE_TOO_LONG)
        if (chars(d.alias.trim()) > EventLimits.ALIAS_CHARS) out += DraftIssue(DraftIssue.Code.ALIAS_TOO_LONG)
        if (chars(d.description.trim()) > EventLimits.DESCRIPTION_CHARS) out += DraftIssue(DraftIssue.Code.DESCRIPTION_TOO_LONG)
        val times = runCatching { d.start() to d.end() }.getOrNull()
        if (times == null) out += DraftIssue(DraftIssue.Code.BAD_TIME)
        else {
            if (Duration.between(times.first, times.second) > Duration.ofDays(EventLimits.MAX_EVENT_DAYS)) out += DraftIssue(DraftIssue.Code.TOO_LONG_EVENT)
            if (!d.expires().isAfter(now)) out += DraftIssue(DraftIssue.Code.EXPIRED)
        }
        if (d.stops.isEmpty()) out += DraftIssue(DraftIssue.Code.NO_STOPS)
        if (d.stops.size > EventLimits.MAX_STOPS) out += DraftIssue(DraftIssue.Code.TOO_MANY_STOPS)
        d.stops.forEachIndexed { i, s ->
            val label = s.label.trim()
            if (label.isEmpty()) out += DraftIssue(DraftIssue.Code.STOP_LABEL_REQUIRED, i)
            if (chars(label) > EventLimits.STOP_LABEL_CHARS) out += DraftIssue(DraftIssue.Code.STOP_LABEL_TOO_LONG, i)
            if (chars(s.notes.trim()) > EventLimits.STOP_NOTES_CHARS) out += DraftIssue(DraftIssue.Code.STOP_NOTES_TOO_LONG, i)
            if (chars(s.audience.trim()) > EventLimits.AUDIENCE_CHARS) out += DraftIssue(DraftIssue.Code.STOP_AUDIENCE_TOO_LONG, i)
            val e = s.entrance
            if (!coordOk(s.lat, s.lng) || (e != null && !coordOk(e.lat, e.lng))) out += DraftIssue(DraftIssue.Code.STOP_POSITION, i)
            if (s.arrive.isNotBlank() && HostAgenda.time(s.arrive) == null) out += DraftIssue(DraftIssue.Code.STOP_TIME, i)
        }
        // Info cards (§4.1) and the agenda (sessions).
        val blocks = d.info.filter { it.kind != HostInfo.AGENDA && it.text.isNotBlank() }
        if (blocks.size > EventLimits.MAX_INFO_BLOCKS) out += DraftIssue(DraftIssue.Code.TOO_MANY_INFO)
        d.info.forEachIndexed { i, b ->
            if (b.kind != HostInfo.AGENDA && chars(b.text.trim()) > EventLimits.INFO_TEXT_CHARS) out += DraftIssue(DraftIssue.Code.INFO_TOO_LONG, info = i)
            if (b.kind == HostInfo.AGENDA && HostAgenda.problems(b.text)) out += DraftIssue(DraftIssue.Code.AGENDA_INVALID, info = i)
        }
        // Routes (§4): every leg calculated or kept as a sketch; at most 20 routes and 50 000 points. Empty routes are
        // left out of the pack (like the desktop), not an error.
        val stopIds = d.stops.map { it.id }.toSet()
        var routes = 0
        var points = 0
        d.routes.forEachIndexed { i, r ->
            when (val a = my.gimano.app.core.routes.RouteAssembly.toEventRoute(r, stopIds)) {
                is my.gimano.app.core.routes.Assembled.Ok -> { routes++; points += a.route.points.size }
                is my.gimano.app.core.routes.Assembled.Blocked -> when (a.why) {
                    my.gimano.app.core.routes.RouteBlock.EMPTY -> Unit
                    my.gimano.app.core.routes.RouteBlock.NOT_READY -> out += DraftIssue(DraftIssue.Code.ROUTE_NOT_READY, route = i)
                    my.gimano.app.core.routes.RouteBlock.UNMATCHED -> out += DraftIssue(DraftIssue.Code.ROUTE_UNMATCHED, route = i)
                }
            }
        }
        if (routes > EventLimits.MAX_ROUTES) out += DraftIssue(DraftIssue.Code.TOO_MANY_ROUTES)
        if (points > EventLimits.MAX_POLYLINE_POINTS) out += DraftIssue(DraftIssue.Code.TOO_MANY_ROUTE_POINTS)
        return out
    }

    /** The routes that go into the pack: complete ones only (others are blocked by [issues] or left out when empty). */
    fun packRoutes(d: HostDraft): List<EventRoute> {
        val stopIds = d.stops.map { it.id }.toSet()
        return d.routes.mapNotNull { (my.gimano.app.core.routes.RouteAssembly.toEventRoute(it, stopIds) as? my.gimano.app.core.routes.Assembled.Ok)?.route }
    }

    private fun opt(s: String) = s.trim().takeIf { it.isNotEmpty() }

    private fun kind(s: String) = runCatching { StopKind.valueOf(s.uppercase()) }.getOrDefault(StopKind.OTHER)

    private fun template(s: String) = runCatching { EventTemplate.valueOf(s.uppercase()) }.getOrDefault(EventTemplate.OTHER)

    /** Agenda lines that are not "HH:mm Title" (shown under the card; they block "Kongsi jemputan"). */
    fun agendaBadLines(text: String): List<String> = ArrayList<String>().also { bad -> HostAgenda.parse(text) { bad += it } }

    /** The guest-visible event (desktop `build_event`): trimmed text, coordinates rounded to 6 decimals, organiser notes left out. */
    fun toPlan(d: HostDraft, rev: Long, parent: String?, publishedAt: Instant): EventPlan = EventPlan(
        id = d.id, rev = rev, parent = parent, publishedAt = publishedAt,
        template = template(d.template), title = d.title.trim(), description = opt(d.description),
        lang = if (d.lang == "en") "en" else "ms", tz = d.tz.trim(), start = d.start(), end = d.end(), expiresAt = d.expires(),
        organizerAlias = opt(d.alias),
        stops = d.stops.map { s ->
            EventStop(
                id = s.id, kind = kind(s.kind), label = s.label.trim(),
                lat = EventJsonWriter.round6(s.lat), lng = EventJsonWriter.round6(s.lng),
                entrance = s.entrance?.let { GeoPoint(EventJsonWriter.round6(it.lat), EventJsonWriter.round6(it.lng)) },
                arrive = arrive(d, s), dwellMin = s.dwellMin?.toLong()?.takeIf { it in 1..EventLimits.MAX_DWELL_MIN },
                notes = opt(s.notes), audience = opt(s.audience),
                // s.organizerNote is deliberately not read: it has no field in the pack.
            )
        },
        sessions = HostAgenda.sessions(d), routes = packRoutes(d),
        info = d.info.filter { it.kind != HostInfo.AGENDA && it.text.isNotBlank() }.map { InfoBlock(infoKind(it.kind), it.text.trim()) },
    )

    private fun infoKind(s: String) = runCatching { InfoKind.valueOf(s.uppercase()) }.getOrDefault(InfoKind.OTHER)

    /** `arrive` of a stop with a time: from = its day at that time, to = from + dwell. */
    private fun arrive(d: HostDraft, s: HostStop): TimeWindow? {
        val t = HostAgenda.time(s.arrive) ?: return null
        val from = runCatching { LocalDateTime.of(d.dayDate(s.day.coerceIn(0, d.dayCount() - 1)), t).atZone(d.zone()).toOffsetDateTime() }.getOrNull() ?: return null
        return TimeWindow(from, from.plusMinutes((s.dwellMin ?: 0).toLong().coerceIn(0, EventLimits.MAX_DWELL_MIN)))
    }
}

/**
 * The wizard's "Atur cara" card: plain text, one item per line or separated by " · ", each starting with a time
 * ("11:00 Ketibaan", "12:30–14:00 Jamuan"); "Hari 2" / "Day 2" lines switch day on a multi-day event. Becomes
 * `sessions` (§4) with ids derived from the event id and position, so an unchanged agenda gives the same bytes.
 */
object HostAgenda {
    data class Item(val day: Int, val start: LocalTime, val end: LocalTime?, val title: String)

    private val DAY = Regex("""^(?:hari|day)\s*(\d{1,2})\s*:?$""", RegexOption.IGNORE_CASE)
    private val ITEM = Regex("""^(\d{1,2})[:.](\d{2})(?:\s*[-–]\s*(\d{1,2})[:.](\d{2}))?\s*[-–:]?\s*(.+)$""")
    private val TIME = Regex("""^(\d{1,2})[:.](\d{2})$""")

    /** `H:mm`, `HH:mm` or `HH.mm`; null when it is not a time of day. */
    fun time(s: String): LocalTime? = TIME.matchEntire(s.trim())?.let { m -> hm(m.groupValues[1], m.groupValues[2]) }

    private fun hm(h: String, m: String): LocalTime? = runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull()

    private fun segments(text: String): List<String> = text.lines().flatMap { it.split('·') }.map { it.trim() }.filter { it.isNotEmpty() }

    /** The items in order; [bad] receives segments that are neither an item nor a day line. */
    fun parse(text: String, bad: (String) -> Unit = {}): List<Item> {
        var day = 0
        val out = ArrayList<Item>()
        for (seg in segments(text)) {
            val dayLine = DAY.matchEntire(seg)
            if (dayLine != null) { day = (dayLine.groupValues[1].toInt() - 1).coerceAtLeast(0); continue }
            val m = ITEM.matchEntire(seg)
            val start = m?.let { hm(it.groupValues[1], it.groupValues[2]) }
            val title = m?.groupValues?.get(5)?.trim().orEmpty()
            if (m == null || start == null || title.isEmpty()) { bad(seg); continue }
            out += Item(day, start, if (m.groupValues[3].isNotEmpty()) hm(m.groupValues[3], m.groupValues[4]) else null, title)
        }
        return out
    }

    /** A line without a time, a title over 120 characters, or more than 50 items. */
    fun problems(text: String): Boolean {
        var bad = false
        val items = parse(text) { bad = true }
        return bad || items.size > EventLimits.MAX_SESSIONS || items.any { HostDraftRules.chars(it.title) > EventLimits.SESSION_TITLE_CHARS }
    }

    fun text(d: HostDraft): String = d.info.filter { it.kind == HostInfo.AGENDA }.joinToString("\n") { it.text }

    /** The agenda as `sessions`; each ends at its own end time, else the next item's start, else the event end (or + 1 h). */
    fun sessions(d: HostDraft): List<EventSession> {
        val items = parse(text(d)).take(EventLimits.MAX_SESSIONS)
        if (items.isEmpty()) return emptyList()
        val days = d.dayCount()
        val zone = d.zone()
        val eventEnd = runCatching { d.end() }.getOrNull()
        fun at(day: Int, t: LocalTime) = LocalDateTime.of(d.dayDate(day.coerceIn(0, days - 1)), t).atZone(zone).toOffsetDateTime()
        return items.mapIndexed { i, it ->
            val start = at(it.day, it.start)
            val next = items.getOrNull(i + 1)?.let { n -> at(n.day, n.start) }
            val end = it.end?.let { e -> at(it.day, e) }?.takeIf { e -> !e.isBefore(start) }
                ?: next?.takeIf { n -> !n.isBefore(start) && n.toLocalDate() == start.toLocalDate() }
                ?: eventEnd?.takeIf { e -> !e.isBefore(start) && e.toLocalDate() == start.toLocalDate() }
                ?: start.plusHours(1)
            val id = B64u.encode(EventHashes.sha256("${d.id}|session|$i".toByteArray()).copyOf(EventLimits.ITEM_ID_BYTES))
            val title = it.title.let { t -> if (HostDraftRules.chars(t) <= EventLimits.SESSION_TITLE_CHARS) t else t.substring(0, t.offsetByCodePoints(0, EventLimits.SESSION_TITLE_CHARS)) }
            EventSession(id, title, start, end, null)
        }
    }
}

/** The bytes to seal next: a new revision, or the last published one again when nothing changed. */
class PreparedRevision(val plan: EventPlan, val bytes: ByteArray, val previous: EventPlan?, val reused: Boolean)

/** Revisions like the desktop `publish::prepare` (docs/events.md §4, §7.4). */
object EventRevisions {
    /**
     * rev 1: `parent` null. Later: same `id`, `rev + 1`, `parent` = b64u(SHA-256(previous bytes)), new `publishedAt`;
     * unless the guest-visible content is unchanged, in which case the previous bytes are re-sealed as they are.
     * Throws [EventPackException] (`invalid_event`) when the result would not validate.
     */
    fun prepare(d: HostDraft, previousBytes: ByteArray?, now: Instant): PreparedRevision {
        val at = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val previous = previousBytes?.let { EventParser.parse(it) }
        if (previous == null) {
            val plan = HostDraftRules.toPlan(d, 1, null, at)
            return PreparedRevision(plan, checked(plan, now), null, reused = false)
        }
        require(previous.id == d.id) { "different event" }
        val same = HostDraftRules.toPlan(d, previous.rev, previous.parent, previous.publishedAt)
        if (EventJsonWriter.write(same).contentEquals(previousBytes)) {
            if (!previous.expiresAt.isAfter(now)) fail(EventPackException.INVALID_EVENT, "expiresAt: access would already have ended")
            return PreparedRevision(previous, previousBytes, previous, reused = true)
        }
        val next = HostDraftRules.toPlan(d, previous.rev + 1, B64u.encode(EventHashes.revisionHash(previousBytes)), at)
        return PreparedRevision(next, checked(next, now), previous, reused = false)
    }

    private fun checked(p: EventPlan, now: Instant): ByteArray {
        if (!p.expiresAt.isAfter(now)) fail(EventPackException.INVALID_EVENT, "expiresAt: access would already have ended")
        val bytes = EventJsonWriter.write(p)
        EventParser.parse(bytes)   // the reader's own validation, before anything is signed
        return bytes
    }
}

/** What changed between two revisions of the same event (guest "Pelan telah berubah"). */
data class StopChange(val before: EventStop?, val after: EventStop?, val moved: Boolean, val renamed: Boolean, val details: Boolean) {
    val added get() = before == null
    val removed get() = after == null
    /** How far the navigation target moved, in metres (0 when added / removed). */
    val movedM: Double get() = if (before != null && after != null) my.gimano.app.core.geo.GeoMath.distanceM(before.navigationTarget, after.navigationTarget) else 0.0
}

data class PlanDiff(val timeChanged: Boolean, val infoChanged: Boolean, val stops: List<StopChange>) {
    val isEmpty get() = !timeChanged && !infoChanged && stops.isEmpty()

    /** The stop the guest may be driving to was moved or removed. */
    fun targetChanged(stopId: String): StopChange? = stops.firstOrNull { (it.before?.id == stopId) && (it.moved || it.removed) }
}

object EventDiff {
    /** Navigation targets closer than this are "the same place" (rounding, a nudge of the pin). */
    const val MOVED_M = 10.0

    fun diff(old: EventPlan, new: EventPlan): PlanDiff {
        val time = !old.start.isEqual(new.start) || !old.end.isEqual(new.end)
        val info = old.title != new.title || old.description != new.description || old.organizerAlias != new.organizerAlias || old.expiresAt != new.expiresAt ||
            old.info != new.info || old.sessions.map { Triple(it.title, it.start.toInstant(), it.end.toInstant()) } != new.sessions.map { Triple(it.title, it.start.toInstant(), it.end.toInstant()) } ||
            old.routes.map { it.id to it.polyline } != new.routes.map { it.id to it.polyline }
        val changes = ArrayList<StopChange>()
        val newById = new.stops.associateBy { it.id }
        for (b in old.stops) {
            val a = newById[b.id]
            if (a == null) { changes += StopChange(b, null, moved = false, renamed = false, details = false); continue }
            val moved = my.gimano.app.core.geo.GeoMath.distanceM(b.navigationTarget, a.navigationTarget) > MOVED_M ||
                my.gimano.app.core.geo.GeoMath.distanceM(b.pin, a.pin) > MOVED_M
            val renamed = b.label != a.label
            val details = b.kind != a.kind || b.notes != a.notes || b.audience != a.audience || b.arrive != a.arrive || b.dwellMin != a.dwellMin
            if (moved || renamed || details) changes += StopChange(b, a, moved, renamed, details)
        }
        val oldIds = old.stops.map { it.id }.toSet()
        new.stops.filter { it.id !in oldIds }.forEach { changes += StopChange(null, it, moved = false, renamed = false, details = false) }
        return PlanDiff(time, info, changes)
    }
}
