package my.gimano.app.data.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import my.gimano.app.core.events.B64u
import my.gimano.app.core.events.EventHashes
import my.gimano.app.core.events.EventParser
import my.gimano.app.core.events.EventPlan
import my.gimano.app.core.events.ImportDecision
import my.gimano.app.core.events.OpenedPack
import my.gimano.app.core.events.StoredEvent
import my.gimano.app.core.events.StoredRevision
import java.time.Instant

/** A saved event as shown in the list and on the event screen (the active revision). */
data class SavedEvent(
    val plan: EventPlan,
    val hash: String,
    val safetyCode: String,
    /** Other kept revisions with the same `rev` (a fork the user can switch to). */
    val alternatives: List<String> = emptyList(),
)

/**
 * Imported events on this phone only (docs/events.md §7). Payloads are sealed by [vault]; the DB file is excluded
 * from every backup. Never logs event content.
 */
class EventStore(
    private val dao: EventDao,
    private val vault: EventVault,
    private val tx: suspend (suspend () -> Unit) -> Unit,
    private val clock: () -> Instant = Instant::now,
    /** Told after every change that can move the next expiry (schedules the purge job). */
    private val onChanged: suspend () -> Unit = {},
) {
    private val _version = MutableStateFlow(0L)
    /** Bumped on every write: screens reload when it changes. */
    val version: StateFlow<Long> = _version.asStateFlow()

    suspend fun storedFor(eventId: String): StoredEvent? {
        val pin = dao.pin(eventId) ?: return null
        val revs = dao.revisions(eventId).mapNotNull { r -> B64u.decodeExact(r.hash, 32)?.let { StoredRevision(r.rev, it, stopsOnly = r.stopsOnly) } }
        return StoredEvent(pin.organizerPub, revs)
    }

    /** Applies an accepted decision (New / Newer / Fork / UpgradeToFull); anything else stores nothing. Returns true if saved. */
    suspend fun save(p: OpenedPack, d: ImportDecision, drop: my.gimano.app.core.events.DropRef? = null): Boolean {
        val now = clock().toEpochMilli()
        val id = p.event.id
        val hash = p.revisionHashB64u
        val row = EventRevisionEntity(hash, id, p.event.rev, active = false, importedAt = now, payload = vault.seal(p.eventBytes),
            stopsOnly = p.event.stopsOnly)
        val expires = p.event.expiresAt.toEpochMilli()
        when (d) {
            is ImportDecision.New -> tx {
                dao.insertPin(EventPinEntity(id, p.organizerPub, expires, now, dropRef = drop?.let { vault.seal(it.encode()) }))
                dao.insertRevision(row)
                dao.activate(id, hash)
            }
            is ImportDecision.Newer -> tx {
                val pin = dao.pin(id) ?: error("no pin")
                check(pin.organizerPub.contentEquals(p.organizerPub))
                dao.insertRevision(row)
                dao.activate(id, hash)
                dao.dropPayloadsBelow(id, p.event.rev)   // superseded revisions: hash only
                dao.updatePin(pin.copy(expiresAt = expires, dropRef = drop?.let { vault.seal(it.encode()) } ?: pin.dropRef))
            }
            // Keep both sides; the one just opened becomes the shown one, the other stays selectable.
            is ImportDecision.Fork -> tx {
                val pin = dao.pin(id) ?: error("no pin")
                check(pin.organizerPub.contentEquals(p.organizerPub))
                dao.insertRevision(row)
                dao.activate(id, hash)
                dao.updatePin(pin.copy(expiresAt = expires, dropRef = drop?.let { vault.seal(it.encode()) } ?: pin.dropRef))
            }
            // Same revision hash: the full event's bytes replace the stored variant's payload; active state unchanged.
            is ImportDecision.UpgradeToFull -> {
                check(!p.event.stopsOnly)
                var n = 0
                tx {
                    val pin = dao.pin(id) ?: error("no pin")
                    check(pin.organizerPub.contentEquals(p.organizerPub))
                    n = dao.replacePayload(id, hash, row.payload!!)
                }
                if (n == 0) return false
            }
            else -> return false
        }
        changed()
        return true
    }

    private fun decode(r: EventRevisionEntity): EventPlan? =
        r.payload?.let { runCatching { EventParser.parse(vault.open(it)) }.getOrNull() }

    /** Saved, not yet expired events, soonest first. */
    fun events(): Flow<List<SavedEvent>> = dao.activeFlow().map { rows ->
        val now = clock()
        rows.mapNotNull { r -> decode(r)?.let { r to it } }
            .filterNot { (_, plan) -> plan.isExpired(now) }
            .map { (r, plan) -> SavedEvent(plan, r.hash, "") }
            .sortedBy { it.plan.start }
    }

    suspend fun load(eventId: String): SavedEvent? {
        val pin = dao.pin(eventId) ?: return null
        val revs = dao.revisions(eventId)
        val active = revs.firstOrNull { it.active } ?: return null
        val plan = decode(active) ?: return null
        if (plan.isExpired(clock())) return null
        val alts = revs.filter { it.rev == active.rev && it.hash != active.hash && it.payload != null }.map { it.hash }
        return SavedEvent(plan, active.hash, EventHashes.safetyCode(pin.organizerPub), alts)
    }

    /** Fork: show the other revision. */
    suspend fun choose(eventId: String, hash: String) {
        val r = dao.revisions(eventId).firstOrNull { it.hash == hash && it.payload != null } ?: return
        val plan = decode(r) ?: return
        tx {
            dao.activate(eventId, hash)
            dao.pin(eventId)?.let { dao.updatePin(it.copy(expiresAt = plan.expiresAt.toEpochMilli())) }
        }
        changed()
    }

    /** The short link a stored event came from (§5.6), re-fetched when the event is opened. */
    suspend fun dropFor(eventId: String): my.gimano.app.core.events.DropRef? =
        dao.pin(eventId)?.dropRef?.let { runCatching { my.gimano.app.core.events.DropRef.decode(vault.open(it)) }.getOrNull() }

    /**
     * Remembers (or with null forgets) the short link of a stored event. Only for an event already pinned with
     * [organizerPub], so a link to someone else's event never attaches to this one.
     */
    suspend fun setDrop(eventId: String, organizerPub: ByteArray?, drop: my.gimano.app.core.events.DropRef?) {
        tx {
            val pin = dao.pin(eventId) ?: return@tx
            if (organizerPub != null && !pin.organizerPub.contentEquals(organizerPub)) return@tx
            dao.updatePin(pin.copy(dropRef = drop?.let { vault.seal(it.encode()) }))
        }
    }

    /** Any imported event on this phone (ends the deferred-link window). */
    suspend fun hasAny(): Boolean = dao.pinsFlow().first().isNotEmpty()

    /** Deletes the event, its pinned key and every revision. */
    suspend fun delete(eventId: String) {
        tx { dao.deleteRevisions(eventId); dao.deletePin(eventId) }
        changed()
    }

    /** §7.5: everything of an event goes at `expiresAt`. Run on start-up, when the list opens and from the expiry job. */
    suspend fun purgeExpired(): Int {
        val ids = dao.expiredIds(clock().toEpochMilli())
        if (ids.isNotEmpty()) tx { ids.forEach { dao.deleteRevisions(it); dao.deletePin(it) } }
        changed()
        return ids.size
    }

    suspend fun nextExpiry(): Instant? = dao.nextExpiry()?.let(Instant::ofEpochMilli)

    private suspend fun changed() {
        _version.value = _version.value + 1
        runCatching { onChanged() }
    }
}
