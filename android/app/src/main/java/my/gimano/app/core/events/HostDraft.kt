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
) {
    val entrance: GeoPoint? get() = if (entranceLat != null && entranceLng != null) GeoPoint(entranceLat, entranceLng) else null
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
) {
    companion object {
        const val DEFAULT_TZ = "Asia/Kuala_Lumpur"
        val TEMPLATES = listOf("kenduri", "dinner", "company", "ride", "convoy", "trip", "other")
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

    fun end(): OffsetDateTime {
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
data class DraftIssue(val code: Code, val stop: Int? = null) {
    enum class Code { TITLE_REQUIRED, TITLE_TOO_LONG, ALIAS_TOO_LONG, DESCRIPTION_TOO_LONG, BAD_TIME, TOO_LONG_EVENT, EXPIRED,
        NO_STOPS, TOO_MANY_STOPS, STOP_LABEL_REQUIRED, STOP_LABEL_TOO_LONG, STOP_NOTES_TOO_LONG, STOP_AUDIENCE_TOO_LONG, STOP_POSITION, INVALID }
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
        }
        return out
    }

    private fun opt(s: String) = s.trim().takeIf { it.isNotEmpty() }

    private fun kind(s: String) = runCatching { StopKind.valueOf(s.uppercase()) }.getOrDefault(StopKind.OTHER)

    private fun template(s: String) = runCatching { EventTemplate.valueOf(s.uppercase()) }.getOrDefault(EventTemplate.OTHER)

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
                arrive = null, dwellMin = null, notes = opt(s.notes), audience = opt(s.audience),
                // s.organizerNote is deliberately not read: it has no field in the pack.
            )
        },
        sessions = emptyList(), routes = emptyList(),
    )
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
        val info = old.title != new.title || old.description != new.description || old.organizerAlias != new.organizerAlias || old.expiresAt != new.expiresAt
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
