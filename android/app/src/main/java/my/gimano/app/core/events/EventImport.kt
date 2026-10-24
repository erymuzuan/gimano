package my.gimano.app.core.events

import java.time.Instant

/**
 * One stored revision of an event (two share a `rev` after the user kept both sides of a fork). [hash] is the
 * **revision hash**: SHA-256 of the stored bytes, or `fullHash` when they are a stops-only variant (§5.5).
 */
data class StoredRevision(val rev: Long, val hash: ByteArray, val stopsOnly: Boolean = false) {
    override fun equals(other: Any?) = other is StoredRevision && rev == other.rev && hash.contentEquals(other.hash) && stopsOnly == other.stopsOnly
    override fun hashCode() = 31 * (31 * rev.hashCode() + hash.contentHashCode()) + stopsOnly.hashCode()
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
    /** Revision hash (`fullHash` for a stops-only variant). */
    val revisionHash: ByteArray,
    val stopsOnly: Boolean = false,
) {
    companion object {
        fun of(p: OpenedPack) = ImportCandidate(p.event.rev, p.event.parent?.let { B64u.decodeExact(it, 32) }, p.event.expiresAt, p.organizerPub,
            p.revisionHash, p.event.stopsOnly)
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
    /**
     * "sudah diimport": the same revision is already stored (identical bytes, the full event when a stops-only variant
     * arrives, or the same variant again). Nothing changes.
     */
    data object AlreadyImported : ImportDecision
    /**
     * "Laluan penuh ditambah": the full event of a stored stops-only variant arrived. Replace the stored variant of
     * [rev] with it silently (no prompt, not a fork).
     */
    data class UpgradeToFull(val rev: Long) : ImportDecision
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
        is UpgradeToFull -> "upgrade_to_full"
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
        // Revision hashes (§7.4): a variant and its full event are the same revision, never a fork.
        val same = stored.revisions.filter { it.rev == c.rev && it.hash.contentEquals(c.revisionHash) }
        if (same.isNotEmpty()) {
            return if (!c.stopsOnly && same.all { it.stopsOnly }) ImportDecision.UpgradeToFull(c.rev) else ImportDecision.AlreadyImported
        }
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
