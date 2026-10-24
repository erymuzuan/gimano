package my.gimano.app.data.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) {
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

    fun newEvent(lang: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now()) {
        _draft.value = HostDraft.new(EventIds.newEventId(), lang, zone, today)
        publishedOnce = false
        reset()
    }

    suspend fun open(id: String): Boolean {
        val h = store.load(id) ?: return false
        _draft.value = h.draft
        publishedOnce = h.published != null
        reset()
        return true
    }

    private fun reset() {
        _issues.value = emptyList()
        _publication.value = null
        _editingStop.value = null
        entranceFor = null
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
    fun defaultMode(): String = when (_draft.value?.template) {
        "ride" -> my.gimano.app.core.events.EventTravelMode.BICYCLE.wire
        "convoy" -> my.gimano.app.core.events.EventTravelMode.MOTORCYCLE.wire
        else -> my.gimano.app.core.events.EventTravelMode.CAR.wire
    }

    fun moveStop(id: String, by: Int) = update { d ->
        val i = d.stops.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in d.stops.indices) d else d.copy(stops = d.stops.toMutableList().apply { add(j, removeAt(i)) })
    }

    /** From the map picker, search or a saved place; opens the new stop's sheet so the host can name it. */
    fun addStop(lat: Double, lng: Double, label: String) {
        val d = _draft.value ?: return
        if (d.stops.size >= EventLimits.MAX_STOPS) return
        val kind = if (d.stops.isEmpty()) "venue" else "parking"
        val s = HostStop(EventIds.newItemId(), kind, label.trim().take(EventLimits.STOP_LABEL_CHARS), lat, lng)
        update { it.copy(stops = it.stops + s) }
        _editingStop.value = s.id
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
            _publication.value = p
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
    }

    suspend fun delete() {
        val d = _draft.value ?: return
        saveJob?.cancel()
        mutex.withLock { store.delete(d.id) }
        _draft.value = null
        reset()
    }
}
