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
}

sealed interface EventImportUi {
    data object Idle : EventImportUi
    data object Working : EventImportUi
    /** A `.manotu-event` file is waiting for its secret code. */
    data class NeedCode(val wrong: Boolean = false) : EventImportUi
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
) {
    private val _ui = MutableStateFlow<EventImportUi>(EventImportUi.Idle)
    val ui: StateFlow<EventImportUi> = _ui.asStateFlow()

    /** Event id (and optional stop id) to show after a save or "Buka". */
    private val _open = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 4)
    val open: SharedFlow<Pair<String, String?>> = _open

    private var pending: OpenedPack? = null
    private var pendingFile: ByteArray? = null
    /** From `manotu://event?stop=<id>#…`: select this stop after import; ignored if unknown. */
    private var stopHint: String? = null

    fun openLink(link: String, stop: String? = null) {
        stopHint = stop
        run { reader.openLink(link) }
    }

    fun openFile(bytes: ByteArray) {
        stopHint = null
        if (!EventTransport.looksLikeFile(bytes)) { _ui.value = EventImportUi.Notice(EventNotice.NOT_A_FILE); return }
        pendingFile = bytes
        _ui.value = EventImportUi.NeedCode()
    }

    fun submitCode(code: String) {
        val bytes = pendingFile ?: return
        _ui.value = EventImportUi.Working
        scope.launch {
            val r = withContext(work) { runCatching { reader.openFile(bytes, code) } }
            r.onSuccess { pendingFile = null; decide(it) }
                .onFailure { e ->
                    val code0 = (e as? EventPackException)?.code
                    _ui.value = when (code0) {
                        // Wrong code and a damaged file are indistinguishable by design: let the user retype.
                        EventPackException.INVALID_SECRET_CODE, EventPackException.DECRYPT -> EventImportUi.NeedCode(wrong = true)
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

    private suspend fun decide(p: OpenedPack) {
        if (runCatching { isOwn(p.event.id, p.organizerPub) }.getOrDefault(false)) {
            // The host's own invitation: never "imported from someone else".
            _ui.value = EventImportUi.Notice(EventNotice.OWN_EVENT, p.event.id)
            return
        }
        val d = EventImport.decide(ImportCandidate.of(p), store.storedFor(p.event.id), clock())
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
            val ok = d::class == s.decision::class && runCatching { store.save(p, d) }.getOrDefault(false)
            pending = null
            if (ok) { _ui.value = EventImportUi.Idle; if (openAfter) emitOpen(p.event) else stopHint = null } else decide(p)
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
