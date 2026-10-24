package my.gimano.app.data.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import my.gimano.app.core.events.EventJsonWriter
import my.gimano.app.core.events.EventLinks
import my.gimano.app.core.events.EventPackException
import my.gimano.app.core.events.EventPackWriter
import my.gimano.app.core.events.EventParser
import my.gimano.app.core.events.EventPlan
import my.gimano.app.core.events.EventRevisions
import my.gimano.app.core.events.HostDraft
import my.gimano.app.core.events.HostDraftRules
import my.gimano.app.core.events.SealedPack
import my.gimano.app.core.events.SecretCode
import my.gimano.app.core.events.decodeHostDraft
import java.time.Instant

/** An event this phone organises, as listed and edited. */
data class HostedEvent(
    val draft: HostDraft,
    /** The last published revision (null before the first "Kongsi jemputan"). */
    val published: EventPlan?,
    /** The draft has guest-visible changes not yet published. */
    val changed: Boolean,
)

/**
 * One sealed invitation, held in memory only while the share screen is open. [key] is the pack key: it is in the link
 * and is the file's secret code; never log it.
 */
class Publication(val plan: EventPlan, val key: ByteArray, val sealed: SealedPack, val reused: Boolean, val previous: EventPlan?) {
    val fragmentLength: Int get() = EventLinks.fragmentUnchecked(key, sealed).length
    /** Null when the fragment would exceed 16 000 characters: offer the file instead. */
    fun link(site: String): String? = runCatching { EventLinks.link(site, key, sealed) }.getOrNull()
    fun file(): ByteArray = EventLinks.file(sealed)
    val secretCode: String get() = SecretCode.encode(key)
}

/**
 * Host side of Events (docs/events.md): drafts, the per-event organizer key and pack key, and published revisions,
 * all on this phone only, sealed by [vault] in manotu-events.db (excluded from backups). Never logs event content or
 * keys and never touches the network.
 */
class HostStore(
    private val dao: HostEventDao,
    private val vault: EventVault,
    private val writer: EventPackWriter,
    private val clock: () -> Instant = Instant::now,
    private val onChanged: suspend () -> Unit = {},
) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun draftOf(e: HostEventEntity): HostDraft? = runCatching { decodeHostDraft(vault.open(e.draft)) }.getOrNull()

    private fun publishedOf(e: HostEventEntity): ByteArray? = e.published?.let { runCatching { vault.open(it) }.getOrNull() }

    private fun view(e: HostEventEntity): HostedEvent? {
        val d = draftOf(e) ?: return null
        val bytes = publishedOf(e)
        val pub = bytes?.let { runCatching { EventParser.parse(it) }.getOrNull() }
        val changed = pub == null || runCatching {
            !EventJsonWriter.write(HostDraftRules.toPlan(d, pub.rev, pub.parent, pub.publishedAt)).contentEquals(bytes)
        }.getOrDefault(true)
        return HostedEvent(d, pub, changed)
    }

    fun events(): Flow<List<HostedEvent>> = dao.allFlow().map { rows ->
        val now = clock().toEpochMilli()
        rows.filter { it.expiresAt == null || it.expiresAt > now }.mapNotNull(::view)
    }

    suspend fun load(id: String): HostedEvent? = dao.get(id)?.takeIf { it.expiresAt == null || it.expiresAt > clock().toEpochMilli() }?.let(::view)

    suspend fun isHosted(id: String): Boolean = dao.get(id) != null

    /** A new event with a fresh organizer seed and pack key (64 bytes, sealed). */
    suspend fun create(d: HostDraft) {
        val seed = writer.newSeed()
        val key = writer.newPackKey()
        val secrets = seed + key
        try {
            dao.insert(HostEventEntity(d.id, vault.seal(d.encode()), vault.seal(secrets), null, null, null, clock().toEpochMilli()))
        } finally {
            seed.fill(0); key.fill(0); secrets.fill(0)
        }
        changed()
    }

    suspend fun save(d: HostDraft) {
        val e = dao.get(d.id) ?: return create(d)
        dao.update(e.copy(draft = vault.seal(d.encode()), updatedAt = clock().toEpochMilli()))
        changed()
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        changed()
    }

    /** Opening one's own invitation link: the organizer key in the pack is this phone's key for that event. */
    suspend fun isOwn(eventId: String, organizerPub: ByteArray): Boolean {
        val e = dao.get(eventId) ?: return false
        val s = runCatching { vault.open(e.secrets) }.getOrNull() ?: return false
        return try { writer.organizerPub(s.copyOfRange(0, 32)).contentEquals(organizerPub) } finally { s.fill(0) }
    }

    /**
     * Seals the next revision (or the unchanged last one again, with a fresh nonce) and records it. Throws
     * [EventPackException] when the event does not validate.
     */
    suspend fun publish(id: String): Publication {
        val e = dao.get(id) ?: throw EventPackException(EventPackException.INVALID_EVENT, "unknown event")
        val d = draftOf(e) ?: throw EventPackException(EventPackException.INVALID_EVENT, "draft unreadable")
        val prepared = EventRevisions.prepare(d, publishedOf(e), clock())
        val s = vault.open(e.secrets)
        try {
            val seed = s.copyOfRange(0, 32)
            val key = s.copyOfRange(32, 64)
            val sealed = try { writer.seal(prepared.bytes, seed, key) } finally { seed.fill(0) }
            if (!prepared.reused) {
                dao.update(e.copy(published = vault.seal(prepared.bytes), publishedRev = prepared.plan.rev,
                    expiresAt = prepared.plan.expiresAt.toEpochMilli(), updatedAt = clock().toEpochMilli()))
                changed()
            }
            return Publication(prepared.plan, key, sealed, prepared.reused, prepared.previous)
        } finally {
            s.fill(0)
        }
    }

    /** §7.5 for the host too: keys, draft and revisions go at the published `expiresAt`. */
    suspend fun purgeExpired(): Int {
        val n = dao.deleteExpired(clock().toEpochMilli())
        if (n > 0) changed()
        return n
    }

    suspend fun nextExpiry(): Instant? = dao.nextExpiry()?.let(Instant::ofEpochMilli)

    private suspend fun changed() {
        _version.value = _version.value + 1
        runCatching { onChanged() }
    }
}
