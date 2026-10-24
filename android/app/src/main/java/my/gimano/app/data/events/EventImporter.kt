package my.gimano.app.data.events

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.gimano.app.core.events.DropLinks
import my.gimano.app.core.events.DropRef
import my.gimano.app.core.events.EventPackException
import my.gimano.app.core.events.EventPackReader
import my.gimano.app.core.events.EventPlan
import my.gimano.app.core.events.EventTransport
import my.gimano.app.core.events.ImportCandidate
import my.gimano.app.core.events.EventImport
import my.gimano.app.core.events.ImportDecision
import my.gimano.app.core.events.OpenedPack
import java.time.Instant

/** Why nothing was imported. */
enum class EventNotice {
    OWN_EVENT, CANNOT_OPEN, TOO_LONG, NOT_A_FILE, EXPIRED, DIFFERENT_ORGANIZER, ALREADY_IMPORTED, OLDER,
    /** "Laluan penuh ditambah": the event file upgraded a stops-only invitation, silently (§7.4). */
    FULL_ROUTE_ADDED,
    /** A file from a newer Manotu (unknown format or suite): update the app. */
    NEEDS_UPDATE,
    /** Short link (§5.6) unknown, expired or deleted (404 / 410): "Jemputan ini telah tamat atau dipadam". */
    DROP_GONE,
    /** Short link could not be fetched (offline, server error): "Cuba lagi". */
    NETWORK,
}

sealed interface EventImportUi {
    data object Idle : EventImportUi
    data object Working : EventImportUi
    /** Deferred link (§5.6): a copied `manotu.app/j/#…` link; "Buka jemputan dari pautan yang disalin?" Never auto-imported. */
    data class OfferCopied(val ref: DropRef) : EventImportUi
    /**
     * A `.manotu-event` file is waiting for its secret code. [wrong]: the last code did not open it (wrong code or a
     * damaged file); [invalid]: the last code is not a code (wrong length or a character outside the alphabet);
     * [opening]: the key is being derived (Argon2id, a second or so on a phone): "Membuka…" in the dialog.
     */
    data class NeedCode(val wrong: Boolean = false, val invalid: Boolean = false, val opening: Boolean = false) : EventImportUi
    /** Show title, alias, safety code, dates…; Simpan / Batal. */
    data class Review(
        val plan: EventPlan, val safetyCode: String, val decision: ImportDecision,
        /** The saved revision this one replaces (Newer / Fork): the sheet shows what changed. */
        val previous: EventPlan? = null,
    ) : EventImportUi
    /** [eventId]: the stored event to open from the notice ("Buka"), when there is one. */
    data class Notice(val kind: EventNotice, val eventId: String? = null, val storedRev: Long? = null) : EventImportUi
}

/** Link / file → verified pack → import decision → user confirmation → [EventStore] (docs/events.md §7). */
class EventImporter(
    private val scope: CoroutineScope,
    private val reader: EventPackReader,
    private val store: EventStore,
    private val clock: () -> Instant = Instant::now,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** True when this phone organises the event with that organizer key (the host opening their own link). */
    private val isOwn: suspend (eventId: String, organizerPub: ByteArray) -> Boolean = { _, _ -> false },
    /** The short-link relay (§5.6); null = short links can't be fetched (NETWORK). */
    private val drops: DropApi? = null,
    /** A copied link was answered (opened or declined): remember it so it is not offered again. */
    private val onCopiedHandled: (DropRef) -> Unit = {},
) {
    private val _ui = MutableStateFlow<EventImportUi>(EventImportUi.Idle)
    val ui: StateFlow<EventImportUi> = _ui.asStateFlow()

    /** Event id (and optional stop id) to show after a save or "Buka". */
    private val _open = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 4)
    val open: SharedFlow<Pair<String, String?>> = _open

    private var pending: OpenedPack? = null
    /** The short link [pending] came from: kept with the event so later revisions arrive by themselves. */
    private var pendingDrop: DropRef? = null
    /** The short link a NETWORK notice can retry. */
    private var retryDrop: DropRef? = null
    /** Event ids being re-fetched in the background. */
    private val refreshing = HashSet<String>()
    private var pendingFile: ByteArray? = null
    /** From `manotu://event?stop=<id>#…`: select this stop after import; ignored if unknown. */
    private var stopHint: String? = null

    fun openLink(link: String, stop: String? = null) {
        // A short link (`/j/#<dropId>.<secret>`, `manotu://event?drop=…`) is fetched; anything else opens as `/a/`.
        DropLinks.parseOrNull(link)?.let { openDrop(it, stop); return }
        stopHint = stop
        pendingDrop = null
        run { reader.openLink(link) }
    }

    /** §5.6: GET the blob, decrypt with HKDF(secret), then the normal import (§7). */
    fun openDrop(ref: DropRef, stop: String? = null) {
        stopHint = stop
        retryDrop = null
        pendingDrop = null
        _ui.value = EventImportUi.Working
        scope.launch {
            val blob = try {
                fetch(ref)
            } catch (e: DropException) {
                if (e.kind == DropException.Kind.GONE) _ui.value = EventImportUi.Notice(EventNotice.DROP_GONE)
                else { retryDrop = ref; _ui.value = EventImportUi.Notice(EventNotice.NETWORK) }
                return@launch
            }
            val r = withContext(work) { runCatching { reader.openDrop(ref.secret, blob) } }
            r.onSuccess { decide(it, ref) }.onFailure { _ui.value = EventImportUi.Notice(EventNotice.CANNOT_OPEN) }
        }
    }

    private suspend fun fetch(ref: DropRef): ByteArray = (drops ?: throw DropException(DropException.Kind.UNAVAILABLE)).fetch(ref.dropId)

    /** "Cuba lagi" on a NETWORK notice. */
    fun retry() {
        val ref = retryDrop ?: return dismiss()
        openDrop(ref, stopHint)
    }

    /** Deferred link: offer a copied short link, only when nothing else is on screen. */
    fun offerCopied(ref: DropRef) {
        if (_ui.value == EventImportUi.Idle) _ui.value = EventImportUi.OfferCopied(ref)
    }

    fun acceptCopied() {
        val s = _ui.value as? EventImportUi.OfferCopied ?: return
        runCatching { onCopiedHandled(s.ref) }
        openDrop(s.ref)
    }

    fun declineCopied() {
        val s = _ui.value as? EventImportUi.OfferCopied ?: return
        runCatching { onCopiedHandled(s.ref) }
        _ui.value = EventImportUi.Idle
    }

    /**
     * The event was opened (or "Muat semula"): re-fetch its short link, if it came from one, quietly. A newer revision
     * or a fork shows the usual update sheet; the full route over a stops-only copy is saved silently; anything else
     * (same revision, offline) shows nothing. A link that is gone is forgotten.
     */
    fun refresh(eventId: String) {
        if (drops == null || !refreshing.add(eventId)) return
        scope.launch {
            try {
                val ref = runCatching { store.dropFor(eventId) }.getOrNull() ?: return@launch
                val blob = try { fetch(ref) } catch (e: DropException) {
                    if (e.kind == DropException.Kind.GONE) runCatching { store.setDrop(eventId, null, null) }
                    return@launch
                }
                val p = withContext(work) { runCatching { reader.openDrop(ref.secret, blob) }.getOrNull() } ?: return@launch
                if (p.event.id != eventId) return@launch
                if (runCatching { isOwn(p.event.id, p.organizerPub) }.getOrDefault(false)) return@launch
                when (val d = EventImport.decide(ImportCandidate.of(p), store.storedFor(p.event.id), clock())) {
                    is ImportDecision.UpgradeToFull -> runCatching { store.save(p, d) }
                    is ImportDecision.Newer, is ImportDecision.Fork -> if (_ui.value == EventImportUi.Idle) {
                        val previous = runCatching { store.load(p.event.id)?.plan }.getOrNull()
                        if (_ui.value == EventImportUi.Idle) {
                            pending = p
                            pendingDrop = ref
                            stopHint = null
                            _ui.value = EventImportUi.Review(p.event, p.safetyCode, d, previous)
                        }
                    }
                    else -> Unit
                }
            } finally {
                refreshing.remove(eventId)
            }
        }
    }

    /**
     * A `.manotu-event` file (or anything shared as one: WhatsApp often sends it as application/octet-stream). The
     * suite decides: suite 3 opens at once like a link; only suites 1 and 2 ask for the code. Anything without the
     * "MNTE" header, or a broken or unknown header, is "not an event file".
     */
    fun openFile(bytes: ByteArray) {
        stopHint = null
        pendingDrop = null
        if (!EventTransport.looksLikeFile(bytes)) { _ui.value = EventImportUi.Notice(EventNotice.NOT_A_FILE); return }
        val needsCode = try {
            EventTransport.fileNeedsCode(bytes)
        } catch (e: EventPackException) {
            _ui.value = EventImportUi.Notice(if (e.code == EventPackException.UNSUPPORTED_SUITE || e.code == EventPackException.UNSUPPORTED_FILE_FORMAT)
                EventNotice.NEEDS_UPDATE else EventNotice.NOT_A_FILE)
            return
        }
        if (!needsCode) { pendingFile = null; run { reader.openFileWithoutCode(bytes) }; return }
        pendingFile = bytes
        _ui.value = EventImportUi.NeedCode()
    }

    fun submitCode(code: String) {
        val bytes = pendingFile ?: return
        if ((_ui.value as? EventImportUi.NeedCode)?.opening == true) return
        _ui.value = EventImportUi.NeedCode(opening = true)
        scope.launch {
            val r = withContext(work) { runCatching { reader.openFile(bytes, code) } }
            r.onSuccess { pendingFile = null; decide(it) }
                .onFailure { e ->
                    val code0 = (e as? EventPackException)?.code
                    _ui.value = when (code0) {
                        // Wrong code and a damaged file are indistinguishable by design: let the user retype.
                        EventPackException.DECRYPT -> EventImportUi.NeedCode(wrong = true)
                        // Not a code for this file (typo, look-alike, or the other suite's length): checked before deriving.
                        EventPackException.INVALID_SECRET_CODE -> EventImportUi.NeedCode(invalid = true)
                        EventPackException.MALFORMED_FILE, EventPackException.UNSUPPORTED_FILE_FORMAT, EventPackException.UNSUPPORTED_SUITE ->
                            EventImportUi.Notice(EventNotice.NOT_A_FILE).also { pendingFile = null }
                        else -> EventImportUi.Notice(EventNotice.CANNOT_OPEN).also { pendingFile = null }
                    }
                }
        }
    }

    private fun run(open: () -> OpenedPack) {
        _ui.value = EventImportUi.Working
        scope.launch {
            val r = withContext(work) { runCatching(open) }
            r.onSuccess { decide(it) }.onFailure { e ->
                _ui.value = EventImportUi.Notice(
                    if ((e as? EventPackException)?.code == EventPackException.FRAGMENT_TOO_LONG) EventNotice.TOO_LONG else EventNotice.CANNOT_OPEN,
                )
            }
        }
    }

    private suspend fun decide(p: OpenedPack, drop: DropRef? = null) {
        pendingDrop = drop
        if (runCatching { isOwn(p.event.id, p.organizerPub) }.getOrDefault(false)) {
            // The host's own invitation: never "imported from someone else".
            _ui.value = EventImportUi.Notice(EventNotice.OWN_EVENT, p.event.id)
            return
        }
        val d = EventImport.decide(ImportCandidate.of(p), store.storedFor(p.event.id), clock())
        // Same organizer, nothing new to save: still keep the short link with the stored event, for later revisions.
        if (drop != null && (d == ImportDecision.AlreadyImported || d is ImportDecision.Older || d is ImportDecision.UpgradeToFull))
            runCatching { store.setDrop(p.event.id, p.organizerPub, drop) }
        _ui.value = when (d) {
            ImportDecision.Expired -> EventImportUi.Notice(EventNotice.EXPIRED)
            ImportDecision.DifferentOrganizer -> EventImportUi.Notice(EventNotice.DIFFERENT_ORGANIZER)
            ImportDecision.AlreadyImported -> EventImportUi.Notice(EventNotice.ALREADY_IMPORTED, p.event.id)
            is ImportDecision.Older -> EventImportUi.Notice(EventNotice.OLDER, p.event.id, d.storedRev)
            // Not a fork and nothing to review: the full route replaces the stops-only copy without a prompt.
            is ImportDecision.UpgradeToFull ->
                if (runCatching { store.save(p, d) }.getOrDefault(false)) EventImportUi.Notice(EventNotice.FULL_ROUTE_ADDED, p.event.id)
                else EventImportUi.Notice(EventNotice.ALREADY_IMPORTED, p.event.id)
            is ImportDecision.New, is ImportDecision.Fork, is ImportDecision.Newer -> {
                pending = p
                val previous = if (d is ImportDecision.New) null else runCatching { store.load(p.event.id)?.plan }.getOrNull()
                EventImportUi.Review(p.event, p.safetyCode, d, previous)
            }
        }
    }

    /** "Simpan" / "Guna pelan baharu". [openAfter] false: the caller navigates itself (drive to a moved stop). */
    fun confirm(openAfter: Boolean = true) {
        val s = _ui.value as? EventImportUi.Review ?: return
        val p = pending ?: return
        _ui.value = EventImportUi.Working
        scope.launch {
            // Re-decide against the store right before writing (another import may have landed meanwhile).
            val d = EventImport.decide(ImportCandidate.of(p), store.storedFor(p.event.id), clock())
            val drop = pendingDrop
            val ok = d::class == s.decision::class && runCatching { store.save(p, d, drop) }.getOrDefault(false)
            pending = null
            if (ok) { pendingDrop = null; _ui.value = EventImportUi.Idle; if (openAfter) emitOpen(p.event) else stopHint = null } else decide(p, drop)
        }
    }

    /** "Buka" on a notice. */
    fun openStored(eventId: String) {
        _ui.value = EventImportUi.Idle
        _open.tryEmit(eventId to stopHint)
        stopHint = null
    }

    /** "Batal" / dismiss: forget the pack, the file and the key. */
    fun dismiss() {
        pending = null
        pendingDrop = null
        retryDrop = null
        pendingFile = null
        stopHint = null
        _ui.value = EventImportUi.Idle
    }

    private fun emitOpen(e: EventPlan) {
        val stop = stopHint?.takeIf { id -> e.stops.any { it.id == id } }
        stopHint = null
        _open.tryEmit(e.id to stop)
    }
}
