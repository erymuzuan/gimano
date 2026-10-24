package my.gimano.app.data.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.gimano.app.core.events.DraftIssue
import my.gimano.app.core.events.EventIds
import my.gimano.app.core.events.EventLimits
import my.gimano.app.core.events.EventPackException
import my.gimano.app.core.events.EventWizard
import my.gimano.app.core.events.HostInfo
import my.gimano.app.core.events.HostDraft
import my.gimano.app.core.events.HostDraftRules
import my.gimano.app.core.events.HostStop
import my.gimano.app.core.routes.BuilderRoute
import my.gimano.app.core.routes.Pos
import my.gimano.app.core.routes.RouteEdit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The event being organised ("Acara baharu" / edit), shared by the editor, the map picker and search (stops are
 * added from either). Autosaves to [HostStore]; the share screen's [publication] stays in memory only.
 */
class HostEditor(
    private val scope: CoroutineScope,
    private val store: HostStore,
    private val clock: () -> Instant = Instant::now,
    /** The short-link relay (§5.6); null = no short links (the share screen uses the full link). */
    private val drops: DropApi? = null,
    /** How long "Kongsi jemputan" waits for the short link before it falls back to the full link. */
    private val shortLinkTimeoutMs: Long = 5_500,
) {
    /** The short link of the publication on the share screen. */
    sealed interface ShortLink {
        data object Idle : ShortLink
        data object Working : ShortLink
        data class Ready(val link: String) : ShortLink
        /** Could not be made (offline, server error, too large, timeout): share the full link and say so in one line. */
        data class Failed(val kind: DropException.Kind?) : ShortLink
    }

    private val _shortLink = MutableStateFlow<ShortLink>(ShortLink.Idle)
    val shortLink: StateFlow<ShortLink> = _shortLink.asStateFlow()

    /** The event has a live short link ("Tamatkan pautan" is offered). */
    private val _shortLinkActive = MutableStateFlow(false)
    val shortLinkActive: StateFlow<Boolean> = _shortLinkActive.asStateFlow()

    private var linkJob: kotlinx.coroutines.Deferred<ShortLink>? = null

    private val _draft = MutableStateFlow<HostDraft?>(null)
    val draft: StateFlow<HostDraft?> = _draft.asStateFlow()

    /** Shown after "Kongsi jemputan" found problems. */
    private val _issues = MutableStateFlow<List<DraftIssue>>(emptyList())
    val issues: StateFlow<List<DraftIssue>> = _issues.asStateFlow()

    private val _publication = MutableStateFlow<Publication?>(null)
    val publication: StateFlow<Publication?> = _publication.asStateFlow()

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing.asStateFlow()

    /** The stop whose detail sheet is open. */
    private val _editingStop = MutableStateFlow<String?>(null)
    val editingStop: StateFlow<String?> = _editingStop.asStateFlow()

    /** The stop waiting for its vehicle entrance from the map picker. */
    var entranceFor: String? = null

    /** The event has been published at least once ("Ubah acara" instead of "Acara baharu"). */
    private val _published = MutableStateFlow(false)
    val published: StateFlow<Boolean> = _published.asStateFlow()
    private var publishedOnce: Boolean
        get() = _published.value
        set(v) { _published.value = v }
    private val mutex = Mutex()
    private var saveJob: Job? = null

    /** The wizard step on screen (kept here so it survives the map picker and search). */
    private val _step = MutableStateFlow(EventWizard.Step.TYPE)
    val step: StateFlow<EventWizard.Step> = _step.asStateFlow()

    /** Opened from a Semak row: "Seterusnya" goes straight back to Semak. */
    private val _backToReview = MutableStateFlow(false)
    val backToReview: StateFlow<Boolean> = _backToReview.asStateFlow()

    /** Kind (and day) of the next stop added from the map picker / search / saved places; null = by position. */
    var addKind: String? = null
    var addDay: Int = 0
    /** "Tukar": the next picked place moves this stop instead of adding one. */
    var relocate: String? = null

    /** Sealing the suite-2 file after "Acara sensitif" was switched on at the share screen. */
    private val _sealingCodeFile = MutableStateFlow(false)
    val sealingCodeFile: StateFlow<Boolean> = _sealingCodeFile.asStateFlow()

    fun newEvent(lang: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now()) {
        _draft.value = HostDraft.new(EventIds.newEventId(), lang, zone, today)
        publishedOnce = false
        reset()
        _step.value = EventWizard.Step.TYPE
    }

    /** An existing event opens on Semak (every row there jumps to its step). */
    suspend fun open(id: String): Boolean {
        val h = store.load(id) ?: return false
        _draft.value = h.draft
        publishedOnce = h.published != null
        reset()
        _shortLinkActive.value = h.shortLink
        _step.value = EventWizard.Step.REVIEW
        return true
    }

    private fun reset() {
        _issues.value = emptyList()
        _publication.value = null
        linkJob = null
        _shortLink.value = ShortLink.Idle
        _shortLinkActive.value = false
        _editingStop.value = null
        entranceFor = null
        addKind = null
        addDay = 0
        relocate = null
        _backToReview.value = false
    }

    // --- Wizard navigation ---------------------------------------------------------------------------------

    fun goTo(step: EventWizard.Step, fromReview: Boolean = false) {
        _backToReview.value = fromReview && step != EventWizard.Step.REVIEW
        _step.value = step
    }

    /** "Seterusnya": the next step, or Semak when the step was opened from Semak. */
    fun next() {
        val s = _step.value
        if (s == EventWizard.Step.REVIEW) return
        _step.value = if (_backToReview.value) EventWizard.Step.REVIEW else EventWizard.Step.entries[s.ordinal + 1]
        if (_step.value == EventWizard.Step.REVIEW) _backToReview.value = false
    }

    /** Back arrow: the previous step; false on the first step (or on Semak of an existing event), so the caller leaves. */
    fun back(): Boolean {
        val s = _step.value
        if (_backToReview.value) { _backToReview.value = false; _step.value = EventWizard.Step.REVIEW; return true }
        if (s == EventWizard.Step.TYPE || (s == EventWizard.Step.REVIEW && publishedOnce)) return false
        _step.value = EventWizard.Step.entries[s.ordinal - 1]
        return true
    }

    /** Problems found by "Kongsi jemputan": open the step of the first one. */
    fun showIssue(issue: DraftIssue) {
        goTo(EventWizard.stepFor(issue), fromReview = true)
        issue.stop?.let { i -> _draft.value?.stops?.getOrNull(i)?.let { _editingStop.value = it.id } }
    }

    // --- Info cards ---------------------------------------------------------------------------------------

    fun addInfo(kind: String) = update { d ->
        if (kind == HostInfo.AGENDA && d.info.any { it.kind == HostInfo.AGENDA }) d
        else if (kind != HostInfo.AGENDA && d.info.count { it.kind != HostInfo.AGENDA } >= EventLimits.MAX_INFO_BLOCKS) d
        else d.copy(info = d.info + HostInfo(EventIds.newItemId(), kind))
    }

    fun updateInfo(id: String, text: String) = update { d -> d.copy(info = d.info.map { if (it.id == id) it.copy(text = text) else it }) }

    fun removeInfo(id: String) = update { d -> d.copy(info = d.info.filterNot { it.id == id }) }

    // --- Sensitive (suite 2) ----------------------------------------------------------------------------

    /** "Hantar sebagai fail + kod" / "Acara sensitif". At the share screen, seals the suite-2 file when switched on. */
    fun setSensitive(on: Boolean) {
        update { it.copy(sensitive = on) }
        val p = _publication.value ?: return
        if (!on || p.codeFile() != null) return
        val id = _draft.value?.id ?: return
        _sealingCodeFile.value = true
        scope.launch {
            try {
                val f = mutex.withLock { store.sealCodeFile(id) }
                if (_publication.value === p) _publication.value = p.withCodeFile(f)
            } catch (e: EventPackException) {
                update { it.copy(sensitive = false) }
            } finally {
                _sealingCodeFile.value = false
            }
        }
    }

    fun update(f: (HostDraft) -> HostDraft) {
        val d = _draft.value ?: return
        _draft.value = f(d)
        if (_issues.value.isNotEmpty()) _issues.value = HostDraftRules.issues(_draft.value!!, clock())
        scheduleSave()
    }

    fun updateStop(id: String, f: (HostStop) -> HostStop) = update { d ->
        val before = d.stops.firstOrNull { it.id == id }
        val stops = d.stops.map { if (it.id == id) f(it) else it }
        val after = stops.firstOrNull { it.id == id }
        // Route points linked to a moved stop follow it (desktop `sync_stop_points`).
        val routes = if (before != null && after != null && (before.lat != after.lat || before.lng != after.lng))
            d.routes.map { RouteEdit.syncStop(it, id, Pos(after.lat, after.lng)) } else d.routes
        d.copy(stops = stops, routes = routes)
    }

    fun removeStop(id: String) {
        _editingStop.value = null
        update { d -> d.copy(stops = d.stops.filterNot { it.id == id }, routes = d.routes.map { RouteEdit.dropStop(it, id) }) }
    }

    // --- Routes ("Laluan", from the route builder) ---------------------------------------------------------

    /** Adds or replaces a route (builder "Simpan"). */
    fun saveRoute(r: BuilderRoute) = update { d ->
        if (d.routes.any { it.id == r.id }) d.copy(routes = d.routes.map { if (it.id == r.id) r else it })
        else d.copy(routes = (d.routes + r).take(EventLimits.MAX_ROUTES))
    }

    fun removeRoute(id: String) = update { d -> d.copy(routes = d.routes.filterNot { it.id == id }) }

    fun route(id: String): BuilderRoute? = _draft.value?.routes?.firstOrNull { it.id == id }

    /** A new route's default mode from the template (ride: bicycle, convoy: motorcycle, others: car). */
    fun defaultMode(): String = _draft.value?.let(EventWizard::mode) ?: my.gimano.app.core.events.EventTravelMode.CAR.wire

    /** "Laluan" mode buttons: new routes use it; the routes of [day] (or all, one-day) switch too (legs recalculated). */
    fun setMode(mode: String, dayDate: String?) = update { d ->
        d.copy(mode = mode, routes = d.routes.map { r ->
            if (r.mode != mode && (dayDate == null || r.day == null || r.day == dayDate)) RouteEdit.setOptions(r, mode = mode) else r
        })
    }

    /** "Lebih dari sehari": one more day / back to one day (stops of removed days move to the last day). */
    fun setDays(days: Int) = update { d ->
        val n = days.coerceIn(1, EventLimits.MAX_EVENT_DAYS.toInt())
        val endDate = if (n <= 1) null else d.localDate().plusDays((n - 1).toLong()).toString()
        d.copy(endDate = endDate, stops = d.stops.map { if (it.day > n - 1) it.copy(day = n - 1) else it })
    }

    fun moveStop(id: String, by: Int) = update { d ->
        val i = d.stops.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in d.stops.indices) d else d.copy(stops = d.stops.toMutableList().apply { add(j, removeAt(i)) })
    }

    /** From the map picker, search or a saved place; opens the new stop's sheet so the host can name it. */
    fun addStop(lat: Double, lng: Double, label: String) {
        val d = _draft.value ?: return
        val moving = relocate
        val preset = addLabel
        relocate = null
        addLabel = null
        if (moving != null && d.stops.any { it.id == moving }) {
            // "Tukar": the place moves; a name from search replaces an empty one.
            updateStop(moving) { it.copy(lat = lat, lng = lng, label = it.label.ifBlank { label.trim().take(EventLimits.STOP_LABEL_CHARS) }) }
            addKind = null
            return
        }
        if (d.stops.size >= EventLimits.MAX_STOPS) return
        val journey = EventWizard.isJourney(d.template)
        val kind = addKind ?: when {
            d.stops.isEmpty() -> if (journey) "start" else "venue"
            journey -> "other"
            else -> "parking"
        }
        val day = addDay.coerceIn(0, d.dayCount() - 1)
        addKind = null
        val name = label.trim().ifEmpty { preset.orEmpty() }.take(EventLimits.STOP_LABEL_CHARS)
        val s = HostStop(EventIds.newItemId(), kind, name, lat, lng, day = day)
        update { it.copy(stops = insertStop(it.stops, s)) }
        _editingStop.value = s.id
    }

    /** Preset name for the next added stop when the picked place has none ("Lobi", "Pendaftaran"…). */
    var addLabel: String? = null

    /** Journey order within a day: start first, finish last, other stops before that day's finish. */
    private fun insertStop(stops: List<HostStop>, s: HostStop): List<HostStop> {
        val out = stops.toMutableList()
        val sameDay = out.indices.filter { out[it].day == s.day }
        val at = when (s.kind) {
            "start" -> sameDay.firstOrNull() ?: out.size
            "finish" -> out.size
            else -> sameDay.firstOrNull { out[it].kind == "finish" } ?: out.size
        }
        out.add(at, s)
        return out
    }

    fun setEntrance(lat: Double, lng: Double) {
        val id = entranceFor ?: return
        entranceFor = null
        updateStop(id) { it.copy(entranceLat = lat, entranceLng = lng) }
        _editingStop.value = id
    }

    fun editStop(id: String?) { _editingStop.value = id }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val d = _draft.value ?: return
        mutex.withLock { runCatching { store.save(d) } }
    }

    /** The organizer safety code of this event (saves first: a new event gets its keys on its first save). */
    suspend fun safetyCode(): String? {
        val d = _draft.value ?: return null
        saveJob?.cancel()
        saveNow()
        return runCatching { store.safetyCode(d.id) }.getOrNull()
    }

    /** Leaving the editor: save; an untouched new event leaves nothing behind. */
    fun close() {
        val d = _draft.value
        saveJob?.cancel()
        scope.launch {
            if (d != null) {
                val blank = !publishedOnce && d.title.isBlank() && d.stops.isEmpty() && d.routes.isEmpty()
                mutex.withLock { runCatching { if (blank) store.delete(d.id) else store.save(d) } }
            }
        }
        _draft.value = null
        reset()
    }

    /** "Kongsi jemputan": validate (§4), save, seal. True when the share screen can open. */
    suspend fun publish(): Boolean {
        val d = _draft.value ?: return false
        val problems = HostDraftRules.issues(d, clock())
        _issues.value = problems
        if (problems.isNotEmpty()) return false
        _publishing.value = true
        try {
            saveJob?.cancel()
            saveNow()
            val p = mutex.withLock { store.publish(d.id) }
            publishedOnce = true
            linkJob = null
            _shortLink.value = ShortLink.Idle
            _publication.value = p
            // Republished: the existing short link gets the new revision right away (PUT), so it always shows the latest.
            if (_shortLinkActive.value) startShortLink()
            return true
        } catch (e: EventPackException) {
            _issues.value = listOf(DraftIssue(DraftIssue.Code.INVALID))
            return false
        } finally {
            _publishing.value = false
        }
    }

    fun closeShare() {
        _publication.value?.key?.fill(0)
        _publication.value = null
        linkJob = null
        _shortLink.value = ShortLink.Idle
    }

    // --- Short link (§5.6) ------------------------------------------------------------------------------------

    /**
     * Makes the short link of the publication on screen: POST the first time, PUT when the event already has one and
     * the uploaded revision is older, nothing when it is current. One job per publication; a failed one is retried on
     * the next call. Never longer than [shortLinkTimeoutMs].
     */
    fun startShortLink(): kotlinx.coroutines.Deferred<ShortLink> {
        linkJob?.let { j -> if (j.isActive || (j.isCompleted && runCatching { j.getCompleted() }.getOrNull() is ShortLink.Ready)) return j }
        val p = _publication.value
        val id = _draft.value?.id
        if (p == null || id == null) return kotlinx.coroutines.CompletableDeferred(ShortLink.Failed(null))
        _shortLink.value = ShortLink.Working
        val job = scope.async {
            val r = try {
                kotlinx.coroutines.withTimeout(shortLinkTimeoutMs) { syncDrop(id, p) }
            } catch (e: DropException) {
                ShortLink.Failed(e.kind)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ShortLink.Failed(DropException.Kind.NETWORK)
            } catch (e: EventPackException) {
                ShortLink.Failed(if (e.code == EventPackException.DROP_TOO_LARGE) DropException.Kind.TOO_LARGE else null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ShortLink.Failed(null)
            }
            if (_publication.value === p) _shortLink.value = r
            r
        }
        linkJob = job
        return job
    }

    /** The short link to share, or null to fall back to the full link. */
    suspend fun shortLinkForShare(): String? = (startShortLink().await() as? ShortLink.Ready)?.link

    private suspend fun syncDrop(id: String, p: Publication, retried: Boolean = false): ShortLink {
        val api = drops ?: throw DropException(DropException.Kind.UNAVAILABLE)
        val expires = my.gimano.app.core.events.EventDrops.expiresAt(p.plan)
        val existing = mutex.withLock { store.drop(id) }
        if (existing == null) {
            val secret = store.newDropSecret()
            try {
                val up = mutex.withLock { store.dropBlob(id, secret) } ?: return ShortLink.Failed(null)
                val created = api.create(up.blob, expires)
                val hd = HostDrop(created.dropId, my.gimano.app.core.events.B64u.encode(secret), created.writeToken, up.hash)
                mutex.withLock { store.saveDrop(id, hd) }
                _shortLinkActive.value = true
                return ShortLink.Ready(hd.ref.link())
            } finally {
                secret.fill(0)
            }
        }
        val current = mutex.withLock { store.publishedHash(id) }
        if (current != null && current == existing.uploadedHash) { _shortLinkActive.value = true; return ShortLink.Ready(existing.ref.link()) }
        val ref = existing.ref
        val up = mutex.withLock { store.dropBlob(id, ref.secret) } ?: return ShortLink.Failed(null)
        try {
            api.update(existing.dropId, existing.writeToken, up.blob, expires)
        } catch (e: DropException) {
            // Expired or deleted on the server: forget it and make a new link (once).
            if (e.kind != DropException.Kind.GONE || retried) throw e
            mutex.withLock { store.saveDrop(id, null) }
            return syncDrop(id, p, retried = true)
        }
        mutex.withLock { store.saveDrop(id, existing.copy(uploadedHash = up.hash)) }
        _shortLinkActive.value = true
        return ShortLink.Ready(ref.link())
    }

    /**
     * "Tamatkan pautan": DELETE the drop, then forget it. True when the link is gone (also when the server no longer
     * had it); false when the server could not be reached (the link stays, try again).
     */
    suspend fun endShortLink(): Boolean {
        val id = _draft.value?.id ?: return false
        if (!endDrop(id)) return false
        linkJob = null
        _shortLink.value = ShortLink.Idle
        _shortLinkActive.value = false
        return true
    }

    private suspend fun endDrop(id: String): Boolean {
        val existing = mutex.withLock { store.drop(id) } ?: return true
        val api = drops ?: return false
        val ok = try {
            kotlinx.coroutines.withTimeout(shortLinkTimeoutMs) { api.delete(existing.dropId, existing.writeToken) }
            true
        } catch (e: DropException) {
            e.kind == DropException.Kind.GONE
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            false
        }
        if (ok) mutex.withLock { store.saveDrop(id, null) }
        return ok
    }

    /** Deleting the event also ends its short link (best effort: offline, the server drops it at its expiry). */
    suspend fun delete() {
        val d = _draft.value ?: return
        saveJob?.cancel()
        runCatching { endDrop(d.id) }
        mutex.withLock { store.delete(d.id) }
        _draft.value = null
        reset()
    }
}
