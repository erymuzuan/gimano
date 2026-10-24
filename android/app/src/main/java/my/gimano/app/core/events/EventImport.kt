package my.gimano.app.core.events

import java.time.Instant

/** One stored revision of an event (two share a `rev` after the user kept both sides of a fork). */
data class StoredRevision(val rev: Long, val hash: ByteArray) {
    override fun equals(other: Any?) = other is StoredRevision && rev == other.rev && hash.contentEquals(other.hash)
    override fun hashCode() = 31 * rev.hashCode() + hash.contentHashCode()
}

/** The local record for one event id: the pinned organizer key and every revision kept. */
data class StoredEvent(val organizerPub: ByteArray, val revisions: List<StoredRevision>) {
    override fun equals(other: Any?) = other is StoredEvent && organizerPub.contentEquals(other.organizerPub) && revisions == other.revisions
    override fun hashCode() = 31 * organizerPub.contentHashCode() + revisions.hashCode()
}

class ImportCandidate(
    val rev: Long,
    val parent: ByteArray?,
    val expiresAt: Instant,
    val organizerPub: ByteArray,
    val revisionHash: ByteArray,
) {
    companion object {
        fun of(p: OpenedPack) = ImportCandidate(p.event.rev, p.event.parent?.let { B64u.decodeExact(it, 32) }, p.event.expiresAt, p.organizerPub, p.revisionHash)
    }
}

enum class ParentCheck {
    /** `parent` is the hash of the stored rev − 1 (or rev 1 with no parent). */
    MATCHES,
    /** No stored rev − 1: revisions were missed. Allowed. */
    MISSED_REVISIONS,
    /** A stored rev − 1 exists with a different hash. Warn, still allowed. */
    MISMATCH,
}

/** docs/events.md §7 steps 3–5; mirrors event-core `import.rs`. */
sealed interface ImportDecision {
    /** "Acara telah tamat": do not import. */
    data object Expired : ImportDecision
    /** First time this id is seen: show alias + safety code, pin the organizer key on confirm. */
    data class New(val safetyCode: String) : ImportDecision
    /** "penganjur berbeza": reject. */
    data object DifferentOrganizer : ImportDecision
    /** Identical bytes already stored: nothing to do. */
    data object AlreadyImported : ImportDecision
    /** "versi lebih lama": keep the stored revision. */
    data class Older(val storedRev: Long) : ImportDecision
    /** Same rev, different bytes: warn, keep both, the user chooses. */
    data class Fork(val rev: Long) : ImportDecision
    /** Replaces the stored latest revision. */
    data class Newer(val storedRev: Long, val parent: ParentCheck) : ImportDecision

    /** Stable names used by the test vectors' `expected.decision`. */
    val wire: String get() = when (this) {
        Expired -> "expired"
        is New -> "new"
        DifferentOrganizer -> "different_organizer"
        AlreadyImported -> "already_imported"
        is Older -> "older"
        is Fork -> "fork"
        is Newer -> "newer"
    }
}

object EventImport {
    /** Expired at or after `expiresAt`. */
    fun isExpired(expiresAt: Instant, now: Instant) = !now.isBefore(expiresAt)

    /** [stored] must be the record for the candidate's event id, if any. */
    fun decide(c: ImportCandidate, stored: StoredEvent?, now: Instant): ImportDecision {
        if (isExpired(c.expiresAt, now)) return ImportDecision.Expired
        if (stored == null) return ImportDecision.New(EventHashes.safetyCode(c.organizerPub))
        if (!stored.organizerPub.contentEquals(c.organizerPub)) return ImportDecision.DifferentOrganizer
        if (stored.revisions.any { it.hash.contentEquals(c.revisionHash) }) return ImportDecision.AlreadyImported
        val storedRev = stored.revisions.maxOfOrNull { it.rev } ?: 0L
        if (c.rev < storedRev) return ImportDecision.Older(storedRev)
        if (c.rev == storedRev) return ImportDecision.Fork(storedRev)
        val parent = if (c.rev == 1L && c.parent == null) ParentCheck.MATCHES else {
            val previous = stored.revisions.filter { it.rev == c.rev - 1 }
            when {
                previous.isEmpty() -> ParentCheck.MISSED_REVISIONS
                previous.any { p -> c.parent != null && p.hash.contentEquals(c.parent) } -> ParentCheck.MATCHES
                else -> ParentCheck.MISMATCH
            }
        }
        return ImportDecision.Newer(storedRev, parent)
    }
}
