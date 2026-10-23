package my.gimano.app.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import my.gimano.app.AppContainer
import my.gimano.app.R
import my.gimano.app.core.GLog
import my.gimano.app.core.feedback.FeedbackRules
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.core.trip.StopList
import my.gimano.app.core.trip.TravelMode
import my.gimano.app.data.live.LiveEvent
import my.gimano.app.data.local.Favorites
import my.gimano.app.data.local.LegacyMigration
import my.gimano.app.data.local.LocalHit
import my.gimano.app.data.location.Fix
import my.gimano.app.data.model.*
import my.gimano.app.data.settings.SoundMode
import my.gimano.app.nav.NavDestination
import my.gimano.app.ui.map.CameraCommand
import my.gimano.app.ui.map.VisibleBounds
import java.time.Instant
import java.time.ZoneId

enum class SearchPurpose { NAVIGATE, SET_HOME, SET_WORK, ADD_STOP, ADD_STOP_PREVIEW, ADD_FAVORITE, PLAN_DRIVE }

sealed interface Screen {
    data object Home : Screen
    data class Search(val purpose: SearchPurpose = SearchPurpose.NAVIGATE) : Screen
    data object PlaceCard : Screen
    data object RoutePreview : Screen
    data object Navigating : Screen
    data object Settings : Screen
    data object Backup : Screen
    data object MyFeedback : Screen
    data object Contributions : Screen
    data object AddPlace : Screen
    data object Planned : Screen
    data object Favourites : Screen
    data object Inbox : Screen
    data object Theme : Screen
    data object Account : Screen
    /** "Pilih pada peta" (canvas CariPeta): pin fixed at the centre, the map moves underneath. */
    data class PickOnMap(val purpose: SearchPurpose = SearchPurpose.NAVIGATE) : Screen
    /** What a dropped/chosen pin can become (canvas CariPin). */
    data object PinActions : Screen
    data object About : Screen
    data object Licences : Screen
}

data class PlaceSel(
    val summary: PlaceSummary, val detail: Place? = null, val loading: Boolean = false, val savedId: String? = null,
    /** "Just for me" place from the local DB (never on the server). */
    val privatePlace: my.gimano.app.data.local.PrivatePlaceEntity? = null,
    val privatePhotos: List<java.io.File> = emptyList(),
)

data class RoutePlan(
    val destination: NavDestination,
    val origin: GeoPoint,
    val stops: List<NavDestination> = emptyList(),
    val routes: List<Route> = emptyList(),
    val decoded: Map<String, List<GeoPoint>> = emptyMap(),
    val selectedId: String? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val mode: TravelMode = TravelMode.CAR,
    val avoid: AvoidOptions = AvoidOptions(),
    val departAt: Instant? = null,
    val countdownRunning: Boolean = false,
    val request: RouteRequest? = null,
    val computedAt: Instant = Instant.now(),
) {
    val selected: Route? get() = routes.firstOrNull { it.id == selectedId } ?: routes.firstOrNull()
}

/** The favourite sheet: a new favourite from a place, or editing an existing one. */
data class FavoriteDraft(
    val place: PlaceSummary?,
    val editingId: String? = null,
    val kind: String = "favorite",
    val label: String = "",
    val icon: String = "star",
)

data class UiState(
    val screen: Screen = Screen.Home,
    val myLocation: Fix? = null,
    val user: User? = null,
    val saved: List<SavedPlace> = emptyList(),
    val recents: List<Recent> = emptyList(),
    val query: String = "",
    val results: List<PlaceSummary> = emptyList(),
    val localHits: List<LocalHit> = emptyList(),
    val searching: Boolean = false,
    val searchedOnce: Boolean = false,
    val activeCategory: String? = null,
    val place: PlaceSel? = null,
    val plan: RoutePlan? = null,
    val alerts: List<Report> = emptyList(),
    val jams: List<Pair<Jam, List<GeoPoint>>> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val selectedAlertId: String? = null,
    val reportOpen: Boolean = false,
    val camera: CameraCommand? = null,
    val following: Boolean = true,
    val departureSlots: List<DepartureSlot>? = null,
    val navExpanded: Boolean = false,
    val confirmStop: Boolean = false,
    val favoriteSheet: FavoriteDraft? = null,
    val tollSheet: Route? = null,
    val passengerPrompt: Boolean = false,
    /** Where the add-a-place flow starts (map centre / long-press point) and the typed name. */
    val addPlaceSeed: Pair<GeoPoint, String>? = null,
    /** Private places drawn from the local DB only. */
    val privatePins: List<Pair<String, GeoPoint>> = emptyList(),
    val inbox: List<InboxItem> = emptyList(),
    /** What the user granted (Android 12+ "Approximate" breaks turn-by-turn). Re-checked on every resume. */
    val precision: my.gimano.app.core.location.LocationPrecision = my.gimano.app.core.location.LocationPrecision.PRECISE,
    /** Show the "Navigasi perlukan lokasi tepat" explainer; [PreciseAsk.beforeNav] = start the drive afterwards. */
    val askPrecise: PreciseAsk? = null,
    val correctedQuery: String? = null,
    /** The last search failed (network), as opposed to "nothing matched". */
    val searchFailed: Boolean = false,
    val suggestions: List<SearchSuggestion> = emptyList(),
    /** "cari 'x' sahaja": the user turned the spelling correction off for this query. */
    val exactSearch: Boolean = false,
    val resultSort: my.gimano.app.core.search.ResultSort = my.gimano.app.core.search.ResultSort.RELEVANT,
    val pick: PickState? = null,
    val pin: PinSel? = null,
    /** "Nak namakan tempat ini?" after arriving at an unnamed pin (once). */
    val namePin: NavDestination? = null,
    /** Nearby pending community place to confirm (shown only while parked; see VerifyPromptGate). */
    val vetPrompt: PlaceSummary? = null,
)

data class PreciseAsk(val beforeNav: Boolean)

/** State of "Pilih pada peta" while the user moves the map. */
data class PickState(
    val center: GeoPoint,
    /** Address of the point under the pin (GET /geocode/reverse after the map settles). */
    val label: String? = null,
    val area: String? = null,
    /** Full reverse-geocoded address, used as the chosen point's address. */
    val address: String? = null,
    /** "Adakah ini FamilyMart (40 m)?": a known place within 50 m of the pin. */
    val nearPoi: PlaceSummary? = null,
    val loading: Boolean = false,
    /** Where back goes. */
    val returnTo: Screen = Screen.Home,
    /** Favourite being placed ("pin at the gate"), when picking for the favourite sheet. */
    val favoriteDraft: FavoriteDraft? = null,
)

/** A pin and its actions: Navigasi ke sini · Simpan · Tambah tempat · Kongsi. */
data class PinSel(val place: PlaceSummary, val etaS: Double? = null, val routeM: Double? = null)

sealed interface UiEvent {
    data class Toast(@StringRes val res: Int) : UiEvent
    data class ToastText(val text: String) : UiEvent
    data class Share(val text: String) : UiEvent
    data class Dial(val phone: String) : UiEvent
    data class Web(val url: String) : UiEvent
    /** Open the feedback sheet; the root captures a screenshot of what's behind it first. */
    data class OpenFeedback(val screen: String, val routeId: String?, val placeId: String?) : UiEvent
    /** Open the planned-drive editor for this destination. */
    data class PlanDrive(
        val dest: my.gimano.app.data.planned.PlacePoint, val origin: my.gimano.app.data.planned.PlacePoint?,
        val waypoints: List<my.gimano.app.data.planned.PlacePoint>, val vehicle: String,
    ) : UiEvent
    /** Open the add-a-place flow at this point (round 3). */
    data class AddPlace(val at: GeoPoint, val name: String) : UiEvent
}

/** Default position when no fix is available yet: KL Sentral. */
val DEFAULT_POINT = GeoPoint(3.1340, 101.6865)

@OptIn(FlowPreview::class)
class MainViewModel(private val c: AppContainer) : ViewModel() {
    val container: AppContainer get() = c
    private val repo = c.repo
    private val local = c.local
    private val settings = c.settings
    val nav = c.navigation.state
    val settingsState = settings.state
    val fallbackActive = repo.fallbackActive

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val queryFlow = MutableStateFlow("")
    private val boundsFlow = MutableStateFlow<VisibleBounds?>(null)
    private var locationJob: Job? = null
    private var centeredOnce = false

    val isDemo: Boolean get() = repo.isDemo

    init {
        viewModelScope.launch { local.savedPlaces.collect { p -> _ui.update { it.copy(saved = p) } } }
        viewModelScope.launch { local.recents.collect { r -> _ui.update { it.copy(recents = r) } } }
        viewModelScope.launch { local.privatePlaces.collect { l -> _ui.update { it.copy(privatePins = l.map { p -> p.id to GeoPoint(p.lat, p.lng) }) } } }
        refreshAccount()
        viewModelScope.launch { queryFlow.debounce(220).distinctUntilChanged().collect { runSearch(it) } }
        viewModelScope.launch { boundsFlow.debounce(400).collect { b -> if (b != null) loadLive(b) } }
        viewModelScope.launch {
            while (isActive) {
                delay(45_000)
                if (repo.fallbackActive.value && !settings.current.demoMode && repo.probeRemote()) {
                    c.liveHub.start(); refreshAccount()
                }
                boundsFlow.value?.let { loadLive(it) }
                runCatching { c.feedback.retryDue() }
            }
        }
        viewModelScope.launch { c.liveHub.events.collect(::onLiveEvent) }
        viewModelScope.launch {
            c.navigation.state.collect { s ->
                // Arrived at an unnamed pin → "Nak namakan tempat ini?" once.
                val pd = pinDestination
                if (s.arrived && pd != null && s.destination != null && GeoMath.distanceM(pd, s.destination.point) < 30) {
                    pinDestination = null
                    _ui.update { it.copy(namePin = s.destination) }
                }
                val p = s.progress
                // Follow the puck even before there is route progress (poor fixes), so it never sits off-screen.
                val target = s.puck ?: p?.snapped
                if (s.active && target != null && _ui.value.following && _ui.value.screen == Screen.Navigating) {
                    _ui.update { it.copy(camera = CameraCommand.Follow(target, s.puckBearing, zoom = if (p == null) 15.0 else if (s.mode.isMotorised) 17.0 else 18.0, tilt = if (p == null) 0.0 else 50.0, bottomDp = 170, topDp = 420)) }
                }
            }
        }
        if (!repo.isDemo) c.liveHub.start()
        viewModelScope.launch { repo.fallbackActive.collect { if (it) c.liveHub.stop() } }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            runCatching { repo.ensureAuth() }.onSuccess { u -> _ui.update { it.copy(user = u) } }
                .onFailure { GLog.w("VM", "auth failed: ${it.javaClass.simpleName}") }
            migrateLegacy()
            refreshInbox()
            runCatching { c.feedback.retryDue() }
        }
    }

    /** One-time: pull any server-side favourites/recents into the device, then delete them there. */
    private val migrationLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun migrateLegacy() = migrationLock.withLock {
        if (settings.current.legacyMigrated || repo.isDemo) return@withLock
        val outcome = LegacyMigration(
            export = { repo.exportMyData() },
            import = { d -> local.importLegacy(d.savedPlaces, d.recents) },
            deleteRemote = { repo.deleteMyData() },
            markDone = { settings.setLegacyMigrated() },
        ).run()
        GLog.i("VM", "legacy migration: $outcome")
    }

    // --- Inbox (public-contribution news; read state kept on the device) -------------------------

    fun refreshInbox() = viewModelScope.launch {
        runCatching { repo.inbox() }.onSuccess { l -> _ui.update { it.copy(inbox = l.sortedByDescending { m -> m.createdAt }) } }
    }

    fun markInboxRead() = viewModelScope.launch { settings.markInboxRead(_ui.value.inbox.map { it.id }) }

    /** Shown under "Can't reach the server": path + status or exception type only (no query, no coordinates). */
    fun fallbackReason(): String? = repo.lastFallbackReason

    fun retryServer() {
        repo.retryRemote()
        c.liveHub.start()
        refreshAccount()
        boundsFlow.value?.let { b -> viewModelScope.launch { loadLive(b) } }
    }

    // --- Location -------------------------------------------------------------------------

    fun onLocationPermission(granted: Boolean) {
        if (!granted) {
            if (!centeredOnce) {
                centeredOnce = true
                _ui.update { it.copy(camera = CameraCommand.MoveTo(DEFAULT_POINT, 14.5, animate = false)) }
            }
            return
        }
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            c.locations.lastKnown()?.let(::onFix)
            c.locations.updates(2_000).collect(::onFix)
        }
    }

    private fun onFix(f: Fix) {
        val prev = _ui.value.myLocation
        c.lastFix = f.point
        c.lastFixAccuracyM = f.accuracyM?.toInt()
        _ui.update { it.copy(myLocation = f) }
        maybeAskNearby(f, prev)
        if (!centeredOnce) {
            centeredOnce = true
            _ui.update { it.copy(camera = CameraCommand.MoveTo(f.point, 14.5, animate = false)) }
        }
    }

    // ---------------------------------------------------------------- nearby verify prompt

    private val vetGate = my.gimano.app.core.vetting.VerifyPromptGate()

    private fun maybeAskNearby(f: Fix, prev: Fix?) {
        if (f.simulated) return
        val kmh = f.speedMps?.let { it * 3.6 } ?: prev?.let { p ->
            val dt = (f.timeMs - p.timeMs) / 1000.0
            if (dt in 0.5..30.0) GeoMath.distanceM(p.point, f.point) / dt * 3.6 else null
        }
        val fg = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (!vetGate.onFix(kmh, f.timeMs, nav.value.active, fg)) return
        val s = _ui.value
        if (s.screen != Screen.Home || s.vetPrompt != null || s.reportOpen) return
        vetGate.markPrompted(f.timeMs)
        viewModelScope.launch {
            val cell = my.gimano.app.core.vetting.Geohash.encode(f.point.lat, f.point.lng, 6)
            val answered = c.vetting.answered()
            val pick = runCatching { repo.pendingNearby(cell) }.getOrDefault(emptyList())
                .filter { it.id !in answered }
                .mapNotNull { p -> my.gimano.app.core.vetting.Proximity.withinM(f.point, GeoPoint(p.lat, p.lng), p.rural)?.let { p to GeoMath.distanceM(f.point, GeoPoint(p.lat, p.lng)) } }
                .minByOrNull { it.second }
                ?: return@launch
            if (_ui.value.screen == Screen.Home && !nav.value.active) _ui.update { it.copy(vetPrompt = pick.first.copy(distanceM = pick.second)) }
        }
    }

    /** Recovery code accepted by the server: refresh the profile under the restored pseudonym. */
    fun onIdentityRestored(name: String) {
        _events.tryEmit(UiEvent.ToastText(c.app.getString(R.string.recovery_restored, name)))
        refreshAccount()
    }

    fun dismissNearby(remember: Boolean) {
        val p = _ui.value.vetPrompt ?: return
        _ui.update { it.copy(vetPrompt = null) }
        if (remember) viewModelScope.launch { c.vetting.markAnswered(p.id) }
    }

    fun here(): GeoPoint = nav.value.fix?.point ?: _ui.value.myLocation?.point ?: boundsFlow.value?.center ?: DEFAULT_POINT

    fun recenter() {
        _ui.update { it.copy(following = true) }
        val s = nav.value
        val p = s.progress
        if (s.active && p != null) {
            _ui.update { it.copy(camera = CameraCommand.Follow(s.puck ?: p.snapped, s.puckBearing, bottomDp = 170, topDp = 420)) }
        } else {
            _ui.update { it.copy(camera = CameraCommand.Reset2D(here(), 15.5)) }
        }
    }

    /** Height of the collapsed home sheet: the map's bottom inset on Home, so the puck is never under it. */
    private val _homeSheetDp = kotlinx.coroutines.flow.MutableStateFlow(0)
    val homeSheetDp: kotlinx.coroutines.flow.StateFlow<Int> = _homeSheetDp
    fun setHomeSheetDp(dp: Int) { _homeSheetDp.value = dp }

    private var lastPanMs: Long? = null
    private var followResumeJob: Job? = null

    /** During navigation, follow resumes by itself 10 s after the last pan (the puck must not stay off-screen). */
    fun onUserPanned() {
        if (_ui.value.following) _ui.update { it.copy(following = false) }
        lastPanMs = System.currentTimeMillis()
        followResumeJob?.cancel()
        followResumeJob = viewModelScope.launch {
            delay(my.gimano.app.core.location.FixRules.FOLLOW_RESUME_MS)
            val s = _ui.value
            if (!s.following && s.screen == Screen.Navigating &&
                my.gimano.app.core.location.FixRules.shouldResumeFollow(lastPanMs, System.currentTimeMillis(), nav.value.active)) recenter()
        }
    }

    // --- Live map ---------------------------------------------------------------------------

    fun onCameraIdle(b: VisibleBounds) {
        if (_ui.value.screen is Screen.PickOnMap) onPickMoved(b.center)
        boundsFlow.value = b
    }

    private suspend fun loadLive(b: VisibleBounds) {
        val span = (b.maxLat - b.minLat).coerceAtMost(1.0) / 2
        val spanLng = (b.maxLng - b.minLng).coerceAtMost(1.0) / 2
        val cLat = (b.minLat + b.maxLat) / 2
        val cLng = (b.minLng + b.maxLng) / 2
        val box = VisibleBounds(cLng - spanLng, cLat - span, cLng + spanLng, cLat + span)
        c.liveHub.subscribe(box.minLng, box.minLat, box.maxLng, box.maxLat)
        runCatching { repo.live(box.minLng, box.minLat, box.maxLng, box.maxLat) }
            .onSuccess { r ->
                val jams = r.jams.mapNotNull { j -> runCatching { j to Polyline.decode(j.polyline) }.getOrNull() }
                _ui.update { it.copy(alerts = r.alerts, jams = jams, drivers = r.drivers) }
            }
            .onFailure { GLog.w("VM", "live failed: ${it.javaClass.simpleName}") }
    }

    private fun onLiveEvent(e: LiveEvent) {
        _ui.update { s ->
            when (e) {
                is LiveEvent.AlertCreated -> s.copy(alerts = s.alerts.filterNot { it.id == e.report.id } + e.report)
                is LiveEvent.AlertUpdated -> s.copy(alerts = s.alerts.map { if (it.id == e.report.id) e.report else it })
                is LiveEvent.AlertExpired -> s.copy(alerts = s.alerts.filterNot { it.id == e.reportId }, selectedAlertId = s.selectedAlertId.takeIf { it != e.reportId })
                is LiveEvent.JamUpdated -> {
                    if (e.jam.level == 0) return@update s.copy(jams = s.jams.filterNot { it.first.id == e.jam.id })
                    val pts = runCatching { Polyline.decode(e.jam.polyline) }.getOrNull() ?: return@update s
                    s.copy(jams = s.jams.filterNot { it.first.id == e.jam.id } + (e.jam to pts))
                }
            }
        }
    }

    fun selectAlert(id: String?) = _ui.update { it.copy(selectedAlertId = id) }

    fun vote(report: Report, stillThere: Boolean) {
        viewModelScope.launch {
            runCatching { repo.vote(report.id, stillThere) }
                .onSuccess { upd -> _ui.update { s -> s.copy(alerts = s.alerts.map { if (it.id == upd.id) upd else it }) } }
            _events.tryEmit(UiEvent.Toast(R.string.thanks_vote))
            c.navigation.dismissAlert(report.id)
            _ui.update { it.copy(selectedAlertId = null) }
        }
    }

    fun dismissNavAlert(id: String) = c.navigation.dismissAlert(id)

    // --- Reports ----------------------------------------------------------------------------

    fun openReport(open: Boolean) = _ui.update { it.copy(reportOpen = open) }

    fun submitReport(type: String, subtype: String?, comment: String?) {
        val fix = nav.value.fix ?: _ui.value.myLocation
        val p = fix?.point ?: here()
        _ui.update { it.copy(reportOpen = false) }
        viewModelScope.launch {
            runCatching { repo.createReport(CreateReportRequest(type, subtype, p.lat, p.lng, fix?.bearing?.toDouble(), comment?.takeIf { it.isNotBlank() })) }
                .onSuccess { r ->
                    runCatching { my.gimano.app.data.places.ContributionLog(c.db.outbox()).report(r.id, r.type) }
                    _ui.update { s -> s.copy(alerts = s.alerts.filterNot { it.id == r.id } + r) }
                    _events.tryEmit(UiEvent.Toast(R.string.report_sent))
                }
                .onFailure { _events.tryEmit(UiEvent.Toast(R.string.report_failed)) }
        }
    }

    // --- Search -----------------------------------------------------------------------------

    fun openSearch(purpose: SearchPurpose = SearchPurpose.NAVIGATE, initial: String = "") {
        _ui.update { it.copy(screen = Screen.Search(purpose), query = initial, results = emptyList(), localHits = emptyList(), activeCategory = null, searchedOnce = false,
            correctedQuery = null, suggestions = emptyList(), exactSearch = false) }
        queryFlow.value = initial
    }

    fun onQuery(q: String) {
        _ui.update { it.copy(query = q, activeCategory = null, exactSearch = false) }
        queryFlow.value = q
    }

    private suspend fun runSearch(q: String) {
        if (q.isBlank()) {
            _ui.update { it.copy(results = if (it.activeCategory != null) it.results else emptyList(), localHits = emptyList(), searching = false, searchedOnce = false) }
            return
        }
        _ui.update { it.copy(searching = true) }
        val me = here()
        // Local favourites and private places are matched on the device and shown first.
        val localHits = runCatching { local.search(q, me) }.getOrDefault(emptyList())
        // Ranking origin = the user. (Sending the map centre as `near` ranked results around wherever an earlier
        // search or the picker had left the map, which the search screen doesn't even show.)
        val near: my.gimano.app.data.repo.GeoPointLite? = null
        val got = runCatching { repo.searchFull(q, me.lat, me.lng, 20, exact = _ui.value.exactSearch, near = near) }
        val full = got.getOrDefault(SearchResponse())
        if (_ui.value.query == q) _ui.update {
            it.copy(results = full.results.withDistanceFrom(me), localHits = localHits, searching = false, searchedOnce = true, searchFailed = got.isFailure,
                correctedQuery = full.correctedQuery?.takeIf { c -> !c.equals(q.trim(), ignoreCase = true) }, suggestions = full.suggestions)
        }
    }

    /** Quick category chips (GET /search/nearby). Surau also includes mosques and vice versa. */
    fun searchCategory(cat: String) {
        _ui.update { it.copy(activeCategory = cat, query = "", searching = true, localHits = emptyList()) }
        queryFlow.value = ""
        viewModelScope.launch {
            val me = here()
            val cats = when (cat) { "mosque", "surau" -> listOf("surau", "mosque"); else -> listOf(cat) }
            val res = cats.flatMap { k -> runCatching { repo.nearby(me.lat, me.lng, k, 10_000) }.getOrDefault(emptyList()) }
                .distinctBy { it.id }.withDistanceFrom(me).sortedBy { it.distanceM ?: Double.MAX_VALUE }
            _ui.update { it.copy(results = res, searching = false, searchedOnce = true) }
        }
    }

    /** "cari 'x' sahaja": search the literal text, without the server's spelling fix. */
    fun searchExact() {
        val q = _ui.value.query
        _ui.update { it.copy(exactSearch = true, correctedQuery = null) }
        viewModelScope.launch { runSearch(q) }
    }

    fun setResultSort(s: my.gimano.app.core.search.ResultSort) = _ui.update { it.copy(resultSort = s) }

    /** A "Mungkin anda maksudkan" chip: a place goes straight to it; otherwise search the suggested text/category. */
    fun pickSuggestion(s: SearchSuggestion) {
        when {
            s.lat != null && s.lng != null -> pickResult(PlaceSummary(s.placeId ?: "pin:${s.lat},${s.lng}", s.label, s.category ?: "other", "", s.lat, s.lng,
                GeoMath.distanceM(here(), GeoPoint(s.lat, s.lng))))
            s.category != null && s.query == null -> searchCategory(s.category)
            else -> onQuery(s.query ?: s.label)
        }
    }

    // --- Pilih pada peta (select on map) ------------------------------------------------------

    private var pickJob: Job? = null

    /**
     * Opens the map picker. Starts at [start], else the searched area (first results), else the user's location.
     * [purpose] decides what "Pilih titik ini" does (same as choosing a search result for that purpose).
     */
    fun openPickOnMap(purpose: SearchPurpose = (_ui.value.screen as? Screen.Search)?.purpose ?: SearchPurpose.NAVIGATE,
                      start: GeoPoint? = null, favorite: FavoriteDraft? = null) {
        val s = _ui.value
        val at = start ?: s.results.firstOrNull()?.let { GeoPoint(it.lat, it.lng) } ?: here()
        _ui.update {
            it.copy(screen = Screen.PickOnMap(purpose), following = false, favoriteSheet = null,
                pick = PickState(at, returnTo = s.screen, favoriteDraft = favorite), camera = CameraCommand.Reset2D(at, 17.5))
        }
        onPickMoved(at)
    }

    /** Camera settled while picking: look up the address under the pin after ~400 ms of stillness. */
    fun onPickMoved(center: GeoPoint) {
        if (_ui.value.screen !is Screen.PickOnMap) return
        _ui.update { it.copy(pick = it.pick?.copy(center = center, loading = true)) }
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            delay(400)
            val r = runCatching { repo.reverse(center.lat, center.lng) }.getOrNull()
            val poi = r?.let { rv ->
                rv.nearby.mapNotNull { p -> GeoMath.distanceM(center, GeoPoint(p.lat, p.lng)).takeIf { it <= 50 }?.let { p.copy(distanceM = it) } }.minByOrNull { it.distanceM ?: 0.0 }
                    ?: GeoMath.distanceM(center, GeoPoint(rv.lat, rv.lng)).takeIf { it <= 50 && rv.name.isNotBlank() && !rv.address.startsWith(rv.name) }
                        ?.let { d -> PlaceSummary("pin:${rv.lat},${rv.lng}", rv.name, "other", rv.address, rv.lat, rv.lng, d) }
            }
            _ui.update {
                it.copy(pick = it.pick?.copy(loading = false, nearPoi = poi, address = r?.address?.takeIf { a -> a.isNotBlank() },
                    label = r?.let(::pickTitle),
                    area = r?.let { rv -> listOfNotNull(rv.area, rv.city).filter { a -> a.isNotBlank() }.distinct().joinToString(", ").ifBlank { null } ?: rv.address.substringAfter(", ", "").ifBlank { null } }))
            }
        }
    }

    /** "Dekat 18, Jalan SS 2/24" from the structured reverse fields; older servers: first part of the address. */
    private fun pickTitle(r: ReverseGeocode): String? {
        val street = r.street?.takeIf { it.isNotBlank() }
        val core = when {
            street != null && !r.houseNumber.isNullOrBlank() -> "${r.houseNumber}, $street"
            street != null -> street
            else -> r.address.substringBefore(",").takeIf { it.isNotBlank() }
        } ?: return r.label
        return c.app.getString(R.string.pick_near, core)
    }

    /** "Adakah ini …?": snap the pin to that place. */
    fun pickPoi() {
        val p = _ui.value.pick?.nearPoi ?: return
        _ui.update { it.copy(camera = CameraCommand.Reset2D(GeoPoint(p.lat, p.lng), 18.0)) }
    }

    /** "Pilih titik ini". */
    fun finishPick() {
        val s = _ui.value
        val pk = s.pick ?: return
        val purpose = (s.screen as? Screen.PickOnMap)?.purpose ?: SearchPurpose.NAVIGATE
        val poi = pk.nearPoi?.takeIf { GeoMath.distanceM(pk.center, GeoPoint(it.lat, it.lng)) <= 10 }
        val p = poi ?: PlaceSummary(
            "pin:${pk.center.lat},${pk.center.lng}", pk.label ?: c.app.getString(R.string.pin_on_map), "other",
            pk.address ?: "", pk.center.lat, pk.center.lng, GeoMath.distanceM(here(), pk.center),
        )
        pk.favoriteDraft?.let { d ->
            // Favourite placed precisely ("pin at the gate"): back to the sheet with the new point.
            _ui.update { it.copy(pick = null, screen = pk.returnTo, favoriteSheet = d.copy(place = p.copy(name = d.place?.name ?: p.name))) }
            return
        }
        _ui.update { it.copy(pick = null) }
        pickResultFor(purpose, p, fromMap = true)
    }

    // --- Pin actions (canvas CariPin) -----------------------------------------------------------

    fun showPin(p: PlaceSummary) {
        val g = GeoPoint(p.lat, p.lng)
        _ui.update { it.copy(screen = Screen.PinActions, pin = PinSel(p), place = null, following = false, camera = CameraCommand.MoveTo(g, 16.5)) }
        viewModelScope.launch {
            val me = here()
            val r = runCatching {
                repo.routes(RouteRequest(LatLngDto(me.lat, me.lng), LatLngDto(g.lat, g.lng), vehicle = settings.current.vehicle, alternatives = 0))
            }.getOrNull()?.firstOrNull()
            _ui.update { s -> if (s.pin?.place?.id == p.id) s.copy(pin = s.pin.copy(etaS = r?.durationS, routeM = r?.distanceM)) else s }
        }
    }

    private var pinDestination: GeoPoint? = null

    fun pinNavigate() {
        val p = _ui.value.pin?.place ?: return
        pinDestination = GeoPoint(p.lat, p.lng)
        routeTo(p.toDestination())
    }

    /** Simpan: a favourite on this phone only. */
    fun pinSave() {
        val p = _ui.value.pin?.place ?: return
        _ui.update { it.copy(favoriteSheet = FavoriteDraft(p, label = "", icon = "star")) }
    }

    /** Tambah tempat: the existing add-place flow at this point ("Untuk saya" or public is asked there). */
    fun pinAddPlace() {
        val p = _ui.value.pin?.place ?: return
        _events.tryEmit(UiEvent.AddPlace(GeoPoint(p.lat, p.lng), ""))
    }

    /** Kongsi: plain coordinates + a geo: and an OpenStreetMap link. No account, no Gimano server. */
    fun pinShare() {
        val p = _ui.value.pin?.place ?: return
        val ll = String.format(java.util.Locale.US, "%.6f,%.6f", p.lat, p.lng)
        val text = listOfNotNull(p.name.takeIf { !it.startsWith("pin:") }, p.address.takeIf { it.isNotBlank() && it != p.name }, ll,
            "geo:$ll?q=$ll", "https://www.openstreetmap.org/?mlat=${ll.substringBefore(',')}&mlon=${ll.substringAfter(',')}#map=18/${ll.replace(',', '/')}").joinToString("\n")
        _events.tryEmit(UiEvent.Share(text))
    }

    /** "Nak namakan tempat ini?" → saved as a favourite on the phone. */
    fun nameArrivedPin(name: String?) {
        val d = _ui.value.namePin ?: return
        _ui.update { it.copy(namePin = null) }
        if (name.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { local.addFavorite(Favorites.normalizeLabel(name).ifEmpty { name.take(Favorites.MAX_LABEL) }, "star", d.point.lat, d.point.lng, d.address, null) }
            _events.tryEmit(UiEvent.Toast(R.string.favorite_saved))
        }
    }

    private fun List<PlaceSummary>.withDistanceFrom(me: GeoPoint) =
        map { if (it.distanceM == null) it.copy(distanceM = GeoMath.distanceM(me, GeoPoint(it.lat, it.lng))) else it }

    fun pickLocal(h: LocalHit) {
        pickResult(PlaceSummary(if (h.kind == "private") h.id else (h.placeId ?: "pin:${h.lat},${h.lng}"), h.name, "other", h.address, h.lat, h.lng, GeoMath.distanceM(here(), GeoPoint(h.lat, h.lng))))
    }

    fun pickResult(p: PlaceSummary) = pickResultFor((_ui.value.screen as? Screen.Search)?.purpose ?: SearchPurpose.NAVIGATE, p)

    private fun pickResultFor(purpose: SearchPurpose, p: PlaceSummary, fromMap: Boolean = false) {
        when (purpose) {
            SearchPurpose.NAVIGATE -> if (fromMap || p.id.startsWith("pin:") || my.gimano.app.core.search.ResultGrouping.isSearchOnly(p.id)) showPin(p) else showPlace(p)
            SearchPurpose.SET_HOME -> setHomeWork("home", p, R.string.home_set)
            SearchPurpose.SET_WORK -> setHomeWork("work", p, R.string.work_set)
            SearchPurpose.ADD_FAVORITE -> {
                showPlace(p)
                openFavoriteSheet()
            }
            SearchPurpose.ADD_STOP -> {
                _ui.update { it.copy(screen = Screen.Navigating) }
                viewModelScope.launch {
                    val ok = c.navigation.addStop(p.toDestination())
                    _events.tryEmit(UiEvent.Toast(if (ok) R.string.stop_added else R.string.stops_full))
                }
            }
            SearchPurpose.PLAN_DRIVE -> {
                _ui.update { it.copy(screen = Screen.Planned) }
                _events.tryEmit(UiEvent.PlanDrive(p.toPoint(), null, emptyList(), settings.current.vehicle))
            }
            SearchPurpose.ADD_STOP_PREVIEW -> {
                val plan = _ui.value.plan
                _ui.update { it.copy(screen = Screen.RoutePreview) }
                if (plan != null) runCatching { StopList.add(plan.stops, p.toDestination()) }
                    .onSuccess { fetchRoutes(plan.copy(stops = it)) }
                    .onFailure { _events.tryEmit(UiEvent.Toast(R.string.stops_full)) }
            }
        }
    }

    private fun PlaceSummary.toPoint() = my.gimano.app.data.planned.PlacePoint(lat, lng, name, address,
        id.takeUnless { it.startsWith("pin:") || it.startsWith("private:") || my.gimano.app.core.search.ResultGrouping.isSearchOnly(it) })
    private fun NavDestination.toPoint() = my.gimano.app.data.planned.PlacePoint(point.lat, point.lng, name, address, placeId)
    private fun my.gimano.app.data.planned.PlacePoint.toDestination() = NavDestination(name, address, GeoPoint(lat, lng), placeId)

    /** Route preview overflow → "Plan this drive". */
    fun planThisDrive() {
        val plan = _ui.value.plan ?: return
        cancelCountdown()
        _events.tryEmit(UiEvent.PlanDrive(plan.destination.toPoint(), null, plan.stops.map { it.toPoint() }, plan.mode.wire))
        _ui.update { it.copy(screen = Screen.Planned) }
    }

    /** Planned drive "Go" or its reminder notification → route preview. */
    fun goPlanned(d: my.gimano.app.data.planned.PlannedDrive) {
        viewModelScope.launch { settings.setVehicle(d.vehicle) }
        routeTo(d.destination.toDestination(), d.waypoints.map { it.toDestination() })
    }

    fun openPlannedDrive(id: String) = viewModelScope.launch { c.planned.byId(id)?.let { goPlanned(it) } }

    private fun PlaceSummary.toDestination() = NavDestination(name, address, GeoPoint(lat, lng),
        id.takeUnless { it.startsWith("pin:") || it.startsWith("private:") || my.gimano.app.core.search.ResultGrouping.isSearchOnly(it) })

    private fun setHomeWork(kind: String, p: PlaceSummary, @StringRes msg: Int) {
        viewModelScope.launch {
            local.setHomeWork(kind, p.name, p.lat, p.lng, p.address, p.id.takeUnless { it.startsWith("pin:") || it.startsWith("private:") || my.gimano.app.core.search.ResultGrouping.isSearchOnly(it) })
            _events.tryEmit(UiEvent.Toast(msg))
        }
        goHome()
    }

    fun canAddPlaceFromSearch(): Boolean = _ui.value.searchedOnce && !_ui.value.searching && !_ui.value.searchFailed && _ui.value.results.isEmpty() && _ui.value.localHits.isEmpty()

    fun retrySearch() { val q = _ui.value.query; viewModelScope.launch { runSearch(q) } }

    fun startAddPlace(at: GeoPoint, name: String) {
        _ui.update { it.copy(screen = Screen.AddPlace, addPlaceSeed = at to name, place = null, following = false, camera = CameraCommand.MoveTo(at, 18.0)) }
    }

    fun showPrivate(id: String) {
        viewModelScope.launch {
            val e = local.privatePlace(id) ?: return@launch
            showPlace(PlaceSummary("private:$id", e.name, e.category, e.address ?: "", e.lat, e.lng, GeoMath.distanceM(here(), GeoPoint(e.lat, e.lng))))
        }
    }

    fun deletePrivatePlace(id: String) = viewModelScope.launch {
        local.privatePhotos(id).forEach { java.io.File(java.io.File(c.app.filesDir, "photos"), it.fileName).delete() }
        local.deletePrivatePlace(id)
        goHome()
    }

    fun addPlaceFromSearch() {
        val name = _ui.value.query.trim()
        _events.tryEmit(UiEvent.AddPlace(boundsFlow.value?.center ?: here(), name))
    }

    // --- Place card -------------------------------------------------------------------------

    fun showPlace(p: PlaceSummary) {
        val savedId = _ui.value.saved.firstOrNull { (it.placeId != null && it.placeId == p.id) || (GeoMath.distanceM(GeoPoint(it.lat, it.lng), GeoPoint(p.lat, p.lng)) < 15) }?.id
        _ui.update { it.copy(screen = Screen.PlaceCard, place = PlaceSel(p, loading = true, savedId = savedId), camera = CameraCommand.MoveTo(GeoPoint(p.lat, p.lng), 15.5), following = false) }
        if (p.id.startsWith("private:")) {
            viewModelScope.launch {
                val id = p.id.removePrefix("private:")
                val e = local.privatePlace(id)
                val photos = local.privatePhotos(id).map { java.io.File(java.io.File(c.app.filesDir, "photos"), it.fileName) }.filter { it.exists() }
                _ui.update { s -> if (s.place?.summary?.id == p.id) s.copy(place = s.place.copy(loading = false, privatePlace = e, privatePhotos = photos)) else s }
            }
            return
        }
        if (p.id.startsWith("pin:")) {
            _ui.update { it.copy(place = it.place?.copy(loading = false)) }
            return
        }
        viewModelScope.launch {
            val me = here()
            val d = runCatching { repo.place(p.id, me.lat, me.lng) }.getOrNull()
            _ui.update { s -> if (s.place?.summary?.id == p.id) s.copy(place = s.place.copy(detail = d, loading = false)) else s }
        }
    }

    fun refreshPlace() {
        val p = _ui.value.place?.summary ?: return
        if (p.id.startsWith("pin:") || p.id.startsWith("private:")) return
        viewModelScope.launch {
            val d = runCatching { repo.place(p.id, here().lat, here().lng) }.getOrNull() ?: return@launch
            _ui.update { s -> if (s.place?.summary?.id == p.id) s.copy(place = s.place.copy(detail = d)) else s }
        }
    }

    /** Long-press on the home map → straight to the pin and its actions (canvas CariRules). */
    fun dropPin(g: GeoPoint) {
        if (_ui.value.screen != Screen.Home && _ui.value.screen != Screen.PlaceCard && _ui.value.screen != Screen.PinActions) return
        showPin(PlaceSummary("pin:${g.lat},${g.lng}", c.app.getString(R.string.pin_on_map), "other", "", g.lat, g.lng, GeoMath.distanceM(here(), g)))
        viewModelScope.launch {
            val r = runCatching { repo.reverse(g.lat, g.lng) }.getOrNull() ?: return@launch
            val label = pickTitle(r)
            _ui.update { s -> if (s.pin?.place?.id == "pin:${g.lat},${g.lng}") s.copy(pin = s.pin.copy(place = s.pin.place.copy(name = label ?: s.pin.place.name, address = r.address))) else s }
        }
    }

    fun call(phone: String) = _events.tryEmit(UiEvent.Dial(phone))
    fun openWeb(url: String) = _events.tryEmit(UiEvent.Web(url))

    fun sharePlace() {
        val p = _ui.value.place?.summary ?: return
        _events.tryEmit(UiEvent.Share("${p.name}\n${p.address}\n${osmLink(GeoPoint(p.lat, p.lng))}"))
    }

    private fun osmLink(p: GeoPoint) = String.format(java.util.Locale.US, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=17/%.5f/%.5f", p.lat, p.lng, p.lat, p.lng)

    // --- Favourites (on-device) ---------------------------------------------------------------

    /** Place card "Save": opens the label/icon sheet. */
    fun openFavoriteSheet() {
        val p = _ui.value.place?.summary ?: return
        val existing = _ui.value.saved.firstOrNull { it.id == _ui.value.place?.savedId }
        _ui.update {
            it.copy(favoriteSheet = if (existing != null) FavoriteDraft(p, existing.id, existing.kind, existing.name, existing.icon)
            else FavoriteDraft(p, label = "", icon = "star"))
        }
    }

    fun editFavorite(sp: SavedPlace) {
        _ui.update { it.copy(favoriteSheet = FavoriteDraft(null, sp.id, sp.kind, sp.name, sp.icon)) }
    }

    fun updateFavoriteDraft(d: FavoriteDraft) = _ui.update { it.copy(favoriteSheet = d) }
    fun closeFavoriteSheet() = _ui.update { it.copy(favoriteSheet = null) }

    fun saveFavorite() {
        val d = _ui.value.favoriteSheet ?: return
        val label = Favorites.normalizeLabel(d.label)
        viewModelScope.launch {
            try {
                if (d.editingId != null) {
                    local.updateFavorite(d.editingId, label, d.icon)
                } else {
                    val p = d.place ?: return@launch
                    val sp = local.addFavorite(label.ifEmpty { p.name.take(Favorites.MAX_LABEL) }, d.icon, p.lat, p.lng, p.address, p.id.takeUnless { it.startsWith("pin:") || it.startsWith("private:") })
                    _ui.update { s -> s.copy(place = s.place?.copy(savedId = sp.id)) }
                }
                _ui.update { it.copy(favoriteSheet = null) }
                _events.tryEmit(UiEvent.Toast(R.string.place_saved))
            } catch (e: IllegalStateException) {
                _events.tryEmit(UiEvent.Toast(R.string.favorites_full))
            }
        }
    }

    fun setDraftAsHomeWork(kind: String) {
        val d = _ui.value.favoriteSheet ?: return
        val p = d.place ?: _ui.value.saved.firstOrNull { it.id == d.editingId }?.let { PlaceSummary(it.placeId ?: "pin:", it.name, "other", it.address, it.lat, it.lng) } ?: return
        viewModelScope.launch {
            local.setHomeWork(kind, p.name, p.lat, p.lng, p.address, p.id.takeUnless { it.startsWith("pin:") || it.startsWith("private:") })
            _ui.update { it.copy(favoriteSheet = null) }
            _events.tryEmit(UiEvent.Toast(if (kind == "home") R.string.home_set else R.string.work_set))
        }
    }

    fun deleteFavorite(id: String) {
        viewModelScope.launch {
            local.deleteSaved(id)
            _ui.update { s -> s.copy(favoriteSheet = null, place = s.place?.let { if (it.savedId == id) it.copy(savedId = null) else it }) }
        }
    }

    fun moveFavorite(id: String, delta: Int) = viewModelScope.launch { local.moveFavorite(id, delta) }

    // --- Route planning ---------------------------------------------------------------------

    fun routeTo(dest: NavDestination, stops: List<NavDestination> = emptyList()) {
        val origin = here()
        val st = settings.current
        val prev = _ui.value.plan
        val plan = RoutePlan(destination = dest, origin = origin, stops = stops, mode = TravelMode.from(st.vehicle), avoid = prev?.avoid ?: AvoidOptions())
        GLog.d("VM", "route preview, ${stops.size} stops")
        _ui.update { it.copy(screen = Screen.RoutePreview, plan = plan, following = false, selectedAlertId = null) }
        viewModelScope.launch { local.recordRecent(dest.name, dest.address, dest.point.lat, dest.point.lng, dest.placeId) }
        fetchRoutes(plan)
    }

    fun routeToPlace() {
        val p = _ui.value.place?.summary ?: return
        routeTo(p.toDestination())
    }

    fun routeToSaved(sp: SavedPlace) = routeTo(NavDestination(sp.name, sp.address, GeoPoint(sp.lat, sp.lng), sp.placeId))
    fun routeToRecent(r: Recent) = routeTo(NavDestination(r.name, r.address, GeoPoint(r.lat, r.lng), r.placeId))

    private var routeJob: Job? = null

    private fun fetchRoutes(plan: RoutePlan) {
        routeJob?.cancel()
        _ui.update { it.copy(plan = plan.copy(loading = true, error = false, countdownRunning = false)) }
        routeJob = viewModelScope.launch {
            val req = RouteRequest(
                origin = LatLngDto(plan.origin.lat, plan.origin.lng),
                destination = plan.destination.toDto(),
                waypoints = plan.stops.map { it.toDto() },
                vehicle = plan.mode.wire,
                avoid = if (plan.mode.isMotorised) plan.avoid else AvoidOptions(),
                departAt = plan.departAt?.toString(),
                alternatives = if (plan.stops.isEmpty()) 2 else 0,
            )
            val routes = runCatching { repo.routes(req) }.getOrNull().orEmpty()
            if (routes.isEmpty()) {
                _ui.update { it.copy(plan = it.plan?.copy(loading = false, error = true)) }
                return@launch
            }
            val decoded = routes.associate { it.id to runCatching { Polyline.decode(it.polyline) }.getOrDefault(emptyList()) }
            val best = routes.firstOrNull { it.isBest } ?: routes.first()
            _ui.update {
                it.copy(
                    plan = it.plan?.copy(routes = routes, decoded = decoded, selectedId = best.id, loading = false, request = req,
                        countdownRunning = plan.departAt == null && plan.stops.isEmpty(), computedAt = Instant.now()),
                    camera = CameraCommand.Fit(decoded.values.flatten() + plan.origin + plan.destination.point, topDp = 170, bottomDp = 430),
                )
            }
        }
    }

    fun selectRoute(id: String) = _ui.update { s -> s.copy(plan = s.plan?.copy(selectedId = id, countdownRunning = false)) }
    fun cancelCountdown() = _ui.update { s -> s.copy(plan = s.plan?.copy(countdownRunning = false)) }

    /** Travel mode tabs. The choice is remembered on the device and on the profile (PATCH /me). */
    fun setMode(m: TravelMode) {
        val plan = _ui.value.plan ?: return
        if (plan.mode == m) return
        rememberMode(m)
        fetchRoutes(plan.copy(mode = m))
    }

    fun rememberMode(m: TravelMode) {
        viewModelScope.launch {
            settings.setVehicle(m.wire)
            runCatching { repo.updateMe(UpdateMeRequest(vehicle = m.wire)) }
        }
    }

    fun setAvoid(a: AvoidOptions) {
        val plan = _ui.value.plan ?: return
        fetchRoutes(plan.copy(avoid = a))
    }

    // Stops in route preview
    fun addStopInPreview() {
        val plan = _ui.value.plan ?: return
        cancelCountdown()
        if (plan.stops.size >= my.gimano.app.core.trip.RouteLegs.MAX_STOPS) { _events.tryEmit(UiEvent.Toast(R.string.stops_full)); return }
        openSearch(SearchPurpose.ADD_STOP_PREVIEW)
    }

    fun removeStop(index: Int) {
        val plan = _ui.value.plan ?: return
        fetchRoutes(plan.copy(stops = StopList.remove(plan.stops, index)))
    }

    fun moveStop(from: Int, to: Int) {
        val plan = _ui.value.plan ?: return
        if (from == to) return
        fetchRoutes(plan.copy(stops = StopList.move(plan.stops, from, to)))
    }

    fun openTolls(route: Route?) = _ui.update { it.copy(tollSheet = route) }

    fun openLeaveLater() {
        cancelCountdown()
        val plan = _ui.value.plan ?: return
        viewModelScope.launch {
            val slots = runCatching {
                repo.departureTimes(plan.origin.lat, plan.origin.lng, plan.destination.point.lat, plan.destination.point.lng, plan.mode.wire)
            }.getOrDefault(emptyList())
            _ui.update { it.copy(departureSlots = slots) }
        }
    }

    fun openBestTimeFromNav() {
        val s = nav.value
        val d = s.destination ?: return
        val o = s.fix?.point ?: here()
        viewModelScope.launch {
            val slots = runCatching { repo.departureTimes(o.lat, o.lng, d.point.lat, d.point.lng, s.mode.wire) }.getOrDefault(emptyList())
            _ui.update { it.copy(departureSlots = slots) }
        }
    }

    fun closeSlots() = _ui.update { it.copy(departureSlots = null) }

    fun chooseDeparture(slot: DepartureSlot?) {
        val plan = _ui.value.plan
        _ui.update { it.copy(departureSlots = null) }
        if (plan == null || _ui.value.screen != Screen.RoutePreview) return
        fetchRoutes(plan.copy(departAt = slot?.let { Formatters.parseInstant(it.departAt) }))
    }

    // --- Navigation -------------------------------------------------------------------------

    private var preciseAskedThisSession = false

    /** Called on start and every resume (users can switch to Approximate in Settings at any time). */
    fun refreshPrecision() {
        val p = c.locations.precision()
        _ui.update { it.copy(precision = p) }
        if (p == my.gimano.app.core.location.LocationPrecision.APPROXIMATE && !preciseAskedThisSession && _ui.value.askPrecise == null) {
            preciseAskedThisSession = true
            _ui.update { it.copy(askPrecise = PreciseAsk(beforeNav = false)) }
        }
    }

    /** The explainer was answered (after the system dialog, if the user chose to ask again). */
    fun onPreciseAnswered() {
        val ask = _ui.value.askPrecise
        _ui.update { it.copy(askPrecise = null, precision = c.locations.precision()) }
        if (ask?.beforeNav == true) startNavigation(skipPreciseCheck = true)
    }

    fun startNavigation(skipPreciseCheck: Boolean = false) {
        if (!skipPreciseCheck && !repo.isDemo && c.locations.precision() == my.gimano.app.core.location.LocationPrecision.APPROXIMATE) {
            _ui.update { it.copy(askPrecise = PreciseAsk(beforeNav = true), plan = it.plan?.copy(countdownRunning = false)) }
            return
        }
        val plan = _ui.value.plan ?: return
        val route = plan.selected ?: return
        // Release: never fake a drive just because GPS has no fix yet (debug keeps that for desk testing).
        val simulate = repo.isDemo || settings.current.simulateDrive || (my.gimano.app.BuildConfig.DEBUG && _ui.value.myLocation == null)
        c.navigation.start(route.copy(vehicle = route.vehicle ?: plan.mode.wire), plan.destination, plan.request, simulate, plan.stops)
        _ui.update { it.copy(screen = Screen.Navigating, following = true, navExpanded = false, plan = plan.copy(countdownRunning = false)) }
    }

    fun stopNavigation() {
        c.navigation.stop()
        viewModelScope.launch { c.share.stop() }
        _ui.update { it.copy(screen = Screen.Home, plan = null, navExpanded = false, confirmStop = false, passengerPrompt = false, following = true, camera = CameraCommand.Reset2D(here(), 15.0)) }
    }

    fun continueAfterStop() = c.navigation.continueAfterStop()

    /** Back / End during a drive asks first; never traps the driver, never keeps navigating silently. */
    fun askStop(v: Boolean) = _ui.update { it.copy(confirmStop = v) }

    fun setNavExpanded(v: Boolean) = _ui.update { it.copy(navExpanded = v) }

    fun overview() {
        val r = nav.value.route ?: return
        val pts = runCatching { Polyline.decode(r.polyline) }.getOrDefault(emptyList())
        _ui.update { it.copy(following = false, camera = CameraCommand.Fit(pts, topDp = 200, bottomDp = 260)) }
    }

    /** "Share my drive": end-to-end encrypted live ETA link (docs/api.md "Share my drive"). */
    val sharing = c.share.active

    fun shareDrive() {
        val s = nav.value
        val d = s.destination ?: return
        setNavExpanded(false)
        viewModelScope.launch {
            try {
                val share = c.share.start()
                val eta = s.progress?.let { Formatters.etaClock(Instant.now(), it.remainingS, ZoneId.systemDefault(), settings.current.use24h, c.lang()) } ?: "-"
                _events.tryEmit(UiEvent.Share("__share_drive__|${d.name}|$eta|${share.link}"))
            } catch (e: Exception) {
                _events.tryEmit(UiEvent.Toast(R.string.share_failed))
            }
        }
    }

    fun stopSharing() = viewModelScope.launch { c.share.stop(); _events.tryEmit(UiEvent.Toast(R.string.sharing_stopped)) }

    fun addStop() {
        setNavExpanded(false)
        if (nav.value.stops.size >= my.gimano.app.core.trip.RouteLegs.MAX_STOPS) { _events.tryEmit(UiEvent.Toast(R.string.stops_full)); return }
        openSearch(SearchPurpose.ADD_STOP)
    }

    // --- Feedback -----------------------------------------------------------------------------

    fun requestFeedback(screen: String) {
        val s = _ui.value
        if (s.screen == Screen.Navigating && nav.value.active && !FeedbackRules.allowedWhileNavigating(nav.value.speedKmh, false)) {
            _ui.update { it.copy(passengerPrompt = true) }
            return
        }
        openFeedbackNow(screen)
    }

    fun confirmPassenger(yes: Boolean) {
        _ui.update { it.copy(passengerPrompt = false) }
        if (yes) openFeedbackNow("navigation") else _events.tryEmit(UiEvent.Toast(R.string.feedback_later))
    }

    private fun openFeedbackNow(screen: String) {
        setNavExpanded(false)
        _events.tryEmit(UiEvent.OpenFeedback(screen, nav.value.route?.id ?: _ui.value.plan?.selected?.id, _ui.value.place?.summary?.id?.takeUnless { it.startsWith("pin:") || it.startsWith("private:") }))
    }

    // --- Misc -------------------------------------------------------------------------------

    fun goHome() {
        val nav = nav.value
        _ui.update { it.copy(screen = if (nav.active) Screen.Navigating else Screen.Home, place = null, departureSlots = null) }
    }

    fun open(screen: Screen) = _ui.update { it.copy(screen = screen) }

    fun back() {
        val s = _ui.value
        when {
            s.favoriteSheet != null -> closeFavoriteSheet()
            s.tollSheet != null -> openTolls(null)
            else -> when (s.screen) {
                is Screen.Search -> when ((s.screen as Screen.Search).purpose) {
                    SearchPurpose.ADD_STOP -> _ui.update { it.copy(screen = Screen.Navigating) }
                    SearchPurpose.ADD_STOP_PREVIEW -> _ui.update { it.copy(screen = Screen.RoutePreview) }
                    else -> goHome()
                }
                Screen.PlaceCard -> goHome()
                is Screen.PickOnMap -> {
                    val pk = s.pick
                    _ui.update { it.copy(pick = null, screen = pk?.returnTo ?: Screen.Home, favoriteSheet = pk?.favoriteDraft) }
                }
                Screen.PinActions -> { _ui.update { it.copy(pin = null) }; goHome() }
                Screen.RoutePreview -> if (s.place != null) _ui.update { it.copy(screen = Screen.PlaceCard, plan = null) } else _ui.update { it.copy(screen = Screen.Home, plan = null) }
                Screen.Settings -> goHome()
                Screen.Backup, Screen.MyFeedback, Screen.Account, Screen.About -> _ui.update { it.copy(screen = Screen.Settings) }
                Screen.Licences -> _ui.update { it.copy(screen = Screen.About) }
                Screen.Contributions, Screen.AddPlace, Screen.Planned, Screen.Favourites, Screen.Inbox -> goHome()
                Screen.Theme -> goHome()
                Screen.Navigating -> when {
                    s.navExpanded -> setNavExpanded(false)
                    nav.value.arrived -> stopNavigation()
                    else -> askStop(true)
                }
                Screen.Home -> Unit
            }
        }
    }

    fun openSettings() = _ui.update { it.copy(screen = Screen.Settings) }

    fun cycleSound() {
        val next = when (settings.current.soundMode) {
            SoundMode.ON -> SoundMode.ALERTS_ONLY
            SoundMode.ALERTS_ONLY -> SoundMode.OFF
            SoundMode.OFF -> SoundMode.ON
        }
        viewModelScope.launch { settings.setSoundMode(next) }
        _events.tryEmit(UiEvent.Toast(when (next) { SoundMode.ON -> R.string.sound_on; SoundMode.ALERTS_ONLY -> R.string.sound_alerts; SoundMode.OFF -> R.string.sound_off }))
    }

    fun setLayers(traffic: Boolean, alerts: Boolean, drivers: Boolean) {
        viewModelScope.launch { settings.setLayers(traffic, alerts, drivers) }
    }

    fun setDemoMode(v: Boolean) {
        viewModelScope.launch {
            settings.setDemoMode(v)
            if (!v) retryServer() else { c.liveHub.stop(); refreshAccount() }
            boundsFlow.value?.let { loadLive(it) }
        }
    }
    fun setSimulate(v: Boolean) = viewModelScope.launch { settings.setSimulateDrive(v) }
    fun setVoiceLang(v: String) = viewModelScope.launch { settings.setVoiceLang(v) }
    fun setSoundMode(v: SoundMode) = viewModelScope.launch { settings.setSoundMode(v) }
    fun setUse24h(v: Boolean) = viewModelScope.launch { settings.setUse24h(v) }
    fun setVehicleHome(v: String) = rememberMode(TravelMode.from(v))
    fun setApiBaseUrl(v: String) = viewModelScope.launch {
        settings.setApiBaseUrl(v)
        c.liveHub.stop()
        retryServer()
    }
    fun setMapStyleUrl(v: String) = viewModelScope.launch { settings.setMapStyleUrl(v) }
    fun setDisplayName(v: String) = viewModelScope.launch {
        settings.setDisplayName(v)
        runCatching { repo.updateMe(UpdateMeRequest(displayName = v)) }.onSuccess { u -> _ui.update { it.copy(user = u) } }
    }
    fun setTheme(v: String) = viewModelScope.launch {
        settings.setTheme(v)
        local.putSetting("theme", v)   // also in the shared settings table (travels with backups)
    }
    fun dismissPrivacyCard() = viewModelScope.launch { settings.setPrivacyCardDismissed() }

    fun toast(@StringRes r: Int) = _events.tryEmit(UiEvent.Toast(r))

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(c) as T
    }
}
