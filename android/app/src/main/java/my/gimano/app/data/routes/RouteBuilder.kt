package my.gimano.app.data.routes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.gimano.app.core.routes.BuilderRoute
import my.gimano.app.core.routes.EditHistory
import my.gimano.app.core.routes.LegState
import my.gimano.app.core.routes.Pos
import my.gimano.app.core.routes.RouteEdit
import my.gimano.app.core.routes.RouteProblem

/** "Titik ke titik" (tap to add points) or "Lukis" (draw with a finger; the map does not pan meanwhile). */
enum class BuilderTool { POINTS, DRAW }

/** An event stop a point can snap to (event context only). */
data class StopRef(val id: String, val label: String, val pos: Pos)

/** Where the route goes on "Simpan". */
sealed interface BuilderTarget {
    /** A route of the event in the editor. */
    data object Event : BuilderTarget
    /** A personal route ("Laluan saya"), on this phone only. [isNew] until first saved. */
    data class Personal(val isNew: Boolean) : BuilderTarget
}

data class BuilderUi(
    val route: BuilderRoute,
    val target: BuilderTarget,
    val stops: List<StopRef> = emptyList(),
    val tool: BuilderTool = BuilderTool.POINTS,
    /** The point whose action sheet is open. */
    val selected: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val computing: Boolean = false,
    /** This server has no `/routes/match` yet: "Lukis ikut jalan belum tersedia". */
    val matchUnavailable: Boolean = false,
    /** Changed since opened or last saved. */
    val dirty: Boolean = false,
    /** Back pressed with unsaved changes: ask before discarding. */
    val confirmDiscard: Boolean = false,
)

/**
 * The route builder session (one at a time, like [my.gimano.app.data.events.HostEditor]). Every edit goes through
 * [edit] (undo history) and then calculates only the legs it invalidated, through [router]. Routing results never go
 * into the undo history; results for legs edited meanwhile are dropped (generation check). Successful results are
 * cached for the session so undo/redo does not ask the server again.
 */
class RouteBuilder(private val scope: CoroutineScope, private val router: LegRouter, private val parallel: Int = 3) {
    private val _ui = MutableStateFlow<BuilderUi?>(null)
    val ui: StateFlow<BuilderUi?> = _ui.asStateFlow()

    private val history = EditHistory<BuilderRoute>()
    private val cache = HashMap<Pair<String, RouteEdit.JobKind>, RouteEdit.JobResult>()
    private var computeJob: Job? = null
    private var onSave: (suspend (BuilderRoute) -> Unit)? = null

    val route: BuilderRoute? get() = _ui.value?.route

    fun start(route: BuilderRoute, target: BuilderTarget, stops: List<StopRef> = emptyList(), save: suspend (BuilderRoute) -> Unit) {
        computeJob?.cancel()
        history.clear()
        cache.clear()
        onSave = save
        _ui.value = BuilderUi(route = route, target = target, stops = stops)
        compute()
    }

    fun close() {
        computeJob?.cancel()
        history.clear()
        cache.clear()
        onSave = null
        _ui.value = null
    }

    /** Applies one edit; false when it was not possible (nothing changes). */
    fun edit(f: (BuilderRoute) -> BuilderRoute?): Boolean {
        val u = _ui.value ?: return false
        val next = f(u.route) ?: return false
        if (next == u.route) return false
        history.record(u.route)
        _ui.value = u.copy(route = next, canUndo = history.canUndo, canRedo = history.canRedo, dirty = true,
            selected = u.selected?.takeIf { it in next.points.indices })
        compute()
        return true
    }

    fun undo() {
        val u = _ui.value ?: return
        val prev = history.undo(u.route) ?: return
        _ui.value = u.copy(route = prev, canUndo = history.canUndo, canRedo = history.canRedo, dirty = true, selected = null)
        compute()
    }

    fun redo() {
        val u = _ui.value ?: return
        val next = history.redo(u.route) ?: return
        _ui.value = u.copy(route = next, canUndo = history.canUndo, canRedo = history.canRedo, dirty = true, selected = null)
        compute()
    }

    // --- Gestures and actions ---------------------------------------------------------------------------------

    fun tapMap(at: Pos) { if (_ui.value?.tool == BuilderTool.POINTS) edit { RouteEdit.addPoint(it, at) } }
    fun drawn(line: List<Pos>) = edit { RouteEdit.addDrawnLeg(it, line) }
    fun movePoint(i: Int, to: Pos) = edit { RouteEdit.movePoint(it, i, to) }
    fun deletePoint(i: Int) { edit { RouteEdit.deletePoint(it, i) }; select(null) }
    fun reorderPoint(i: Int, delta: Int) = edit { RouteEdit.reorderPoint(it, i, delta) }
    fun snapToStop(i: Int, s: StopRef) = edit { RouteEdit.snapToStop(it, i, s.id, s.pos) }
    fun unlinkStop(i: Int) = edit { RouteEdit.unlinkStop(it, i) }
    fun addStopPoint(s: StopRef) = edit { RouteEdit.addPoint(it, s.pos, s.id) }
    fun reverse() = edit(RouteEdit::reverse)
    fun outAndBack() = edit(RouteEdit::outAndBack)
    fun closeLoop() = edit(RouteEdit::closeLoop)
    fun clear() { edit(RouteEdit::clear); select(null) }
    fun keepAsSketch(leg: Int) = edit { RouteEdit.keepLegAsSketch(it, leg) }
    fun retry() = edit(RouteEdit::retryUnresolved)
    fun setMode(mode: String) = edit { RouteEdit.setOptions(it, mode = mode) }
    fun setFollowRoads(on: Boolean) = edit { RouteEdit.setOptions(it, followRoads = on) }
    fun setTitle(t: String) = edit { it.copy(title = t) }
    fun setDay(day: String?) = edit { it.copy(day = day) }

    /** "Tambah titik" on an unmatched stretch: a point in its middle; both halves are routed point to point. */
    fun addPointInGap(leg: Int) = edit { r ->
        val l = r.legs.getOrNull(leg) ?: return@edit null
        val d = l.drawn ?: return@edit null
        val s = l.unmatched.firstOrNull() ?: return@edit null
        RouteEdit.insertPoint(r, leg, d[((s.from + s.to) / 2).coerceIn(0, d.size - 1)])
    }

    /** "Lukis semula": remove the drawn leg (and its end point) and draw again. */
    fun redraw(leg: Int) {
        val r = route ?: return
        if (leg != r.legs.size - 1) return
        if (edit { RouteEdit.deletePoint(it, leg + 1) }) setTool(BuilderTool.DRAW)
    }

    fun setTool(t: BuilderTool) = _ui.update { it?.copy(tool = t) }
    fun select(i: Int?) = _ui.update { it?.copy(selected = i?.takeIf { j -> j in it.route.points.indices }) }

    /** Back: leaves at once when nothing changed, else asks first ([BuilderUi.confirmDiscard]). True = may leave. */
    fun requestBack(): Boolean {
        val u = _ui.value ?: return true
        if (!u.dirty) return true
        _ui.value = u.copy(confirmDiscard = true)
        return false
    }

    fun dismissDiscard() = _ui.update { it?.copy(confirmDiscard = false) }

    /** "Simpan": hands the route to the event or the personal store. */
    suspend fun save(): Boolean {
        val u = _ui.value ?: return false
        val s = onSave ?: return false
        s(u.route)
        _ui.value = _ui.value?.copy(dirty = false, target = (u.target as? BuilderTarget.Personal)?.copy(isNew = false) ?: u.target)
        return true
    }

    // --- Routing -----------------------------------------------------------------------------------------------

    private fun compute() {
        if (computeJob?.isActive == true) return
        val r = route ?: return
        if (RouteEdit.pendingJobs(r).isEmpty()) return
        computeJob = scope.launch {
            _ui.update { it?.copy(computing = true) }
            try {
                while (true) {
                    val jobs = route?.let(RouteEdit::pendingJobs).orEmpty()
                    if (jobs.isEmpty()) break
                    val batch = jobs.take(parallel)
                    val results = batch.map { job ->
                        async {
                            val key = job.mode to job.kind
                            cache[key] ?: router.run(job).also { if (it !is RouteEdit.JobResult.Failed) cache[key] = it }
                        }
                    }.awaitAll()
                    var applied = false
                    batch.zip(results).forEach { (job, res) ->
                        val u = _ui.value ?: return@forEach
                        val next = RouteEdit.applyJob(u.route, job, res) ?: return@forEach
                        applied = true
                        val noMatch = res is RouteEdit.JobResult.Failed && res.problem == RouteProblem.MATCH_UNAVAILABLE
                        _ui.value = u.copy(route = next, matchUnavailable = u.matchUnavailable || noMatch)
                    }
                    // Every result was stale (edited meanwhile): the next round picks up the new pending legs.
                    if (!applied && route?.let(RouteEdit::pendingJobs).orEmpty().map { it.generation } == batch.map { it.generation }) break
                }
            } finally {
                _ui.update { it?.copy(computing = false) }
            }
        }
    }

    /** Legs that still need calculating, for the sheet ("2 kaki perlu dikira"). */
    fun openLegs(r: BuilderRoute): Int = r.legs.count { it.state == LegState.PENDING || it.state == LegState.UNRESOLVED }
}
