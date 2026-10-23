package my.gimano.app.ui

import android.util.Log
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
import my.gimano.app.AppContainer
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.data.live.LiveEvent
import my.gimano.app.data.location.Fix
import my.gimano.app.data.model.*
import my.gimano.app.data.settings.SoundMode
import my.gimano.app.nav.NavDestination
import my.gimano.app.ui.map.CameraCommand
import my.gimano.app.ui.map.VisibleBounds
import java.time.Instant
import java.time.ZoneId

enum class SearchPurpose { NAVIGATE, SET_HOME, SET_WORK, ADD_STOP, ADD_FAVORITE }

sealed interface Screen {
    data object Home : Screen
    data class Search(val purpose: SearchPurpose = SearchPurpose.NAVIGATE) : Screen
    data object PlaceCard : Screen
    data object RoutePreview : Screen
    data object Navigating : Screen
    data object Settings : Screen
}

data class PlaceSel(val summary: PlaceSummary, val detail: Place? = null, val loading: Boolean = false, val saved: Boolean = false)

data class RoutePlan(
    val destination: NavDestination,
    val origin: GeoPoint,
    val routes: List<Route> = emptyList(),
    val decoded: Map<String, List<GeoPoint>> = emptyMap(),
    val selectedId: String? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val vehicle: String = "car",
    val avoid: AvoidOptions = AvoidOptions(),
    val departAt: Instant? = null,
    val countdownRunning: Boolean = false,
    val request: RouteRequest? = null,
) {
    val selected: Route? get() = routes.firstOrNull { it.id == selectedId } ?: routes.firstOrNull()
}

data class UiState(
    val screen: Screen = Screen.Home,
    val myLocation: Fix? = null,
    val user: User? = null,
    val saved: List<SavedPlace> = emptyList(),
    val recents: List<Recent> = emptyList(),
    val query: String = "",
    val results: List<PlaceSummary> = emptyList(),
    val searching: Boolean = false,
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
)

sealed interface UiEvent {
    data class Toast(@StringRes val res: Int) : UiEvent
    data class Share(val text: String) : UiEvent
    data class Dial(val phone: String) : UiEvent
    data class Web(val url: String) : UiEvent
}

/** Default position when no fix is available yet: KL Sentral. */
val DEFAULT_POINT = GeoPoint(3.1340, 101.6865)

@OptIn(FlowPreview::class)
class MainViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repo
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
        refreshAccount()
        viewModelScope.launch {
            queryFlow.debounce(220).distinctUntilChanged().collect { runSearch(it) }
        }
        viewModelScope.launch {
            boundsFlow.debounce(400).collect { b -> if (b != null) loadLive(b) }
        }
        viewModelScope.launch {
            // Refresh live overlays periodically even when the camera is still.
            while (isActive) {
                delay(45_000)
                boundsFlow.value?.let { loadLive(it) }
            }
        }
        viewModelScope.launch {
            c.liveHub.events.collect(::onLiveEvent)
        }
        viewModelScope.launch {
            c.navigation.state.collect { s ->
                val p = s.progress
                if (s.active && p != null && _ui.value.following && _ui.value.screen == Screen.Navigating) {
                    _ui.update { it.copy(camera = CameraCommand.Follow(s.puck ?: p.snapped, s.puckBearing, zoom = 17.0, tilt = 50.0, bottomDp = 170, topDp = 420)) }
                }
            }
        }
        if (!repo.isDemo) c.liveHub.start()
        viewModelScope.launch {
            // If the app had to fall back to demo data, stop hammering the hub.
            repo.fallbackActive.collect { if (it) c.liveHub.stop() }
        }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            runCatching { repo.ensureAuth() }.onSuccess { u -> _ui.update { it.copy(user = u) } }
                .onFailure { Log.w("VM", "auth failed", it) }
            reloadPlaces()
        }
    }

    private suspend fun reloadPlaces() {
        runCatching { repo.places() }.onSuccess { p -> _ui.update { it.copy(saved = p) } }
        runCatching { repo.recents() }.onSuccess { r -> _ui.update { it.copy(recents = r) } }
    }

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
        _ui.update { it.copy(myLocation = f) }
        if (!centeredOnce) {
            centeredOnce = true
            _ui.update { it.copy(camera = CameraCommand.MoveTo(f.point, 15.0, animate = false)) }
        }
    }

    fun here(): GeoPoint = nav.value.fix?.point ?: _ui.value.myLocation?.point ?: boundsFlow.value?.center ?: DEFAULT_POINT

    fun recenter() {
        _ui.update { it.copy(following = true) }
        val s = nav.value
        val p = s.progress
        if (s.active && p != null) {
            _ui.update { it.copy(camera = CameraCommand.Follow(p.snapped, p.bearing, bottomDp = 170, topDp = 420)) }
        } else {
            _ui.update { it.copy(camera = CameraCommand.Reset2D(here(), 15.5)) }
        }
    }

    fun onUserPanned() {
        if (_ui.value.following) _ui.update { it.copy(following = false) }
    }

    // --- Live map ---------------------------------------------------------------------------

    fun onCameraIdle(b: VisibleBounds) {
        boundsFlow.value = b
    }

    private suspend fun loadLive(b: VisibleBounds) {
        // Clamp silly-large viewports (zoomed far out) to keep payloads small.
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
            .onFailure { Log.w("VM", "live failed: ${it.message}") }
    }

    private fun onLiveEvent(e: LiveEvent) {
        _ui.update { s ->
            when (e) {
                is LiveEvent.AlertCreated -> s.copy(alerts = s.alerts.filterNot { it.id == e.report.id } + e.report)
                is LiveEvent.AlertUpdated -> s.copy(alerts = s.alerts.map { if (it.id == e.report.id) e.report else it })
                is LiveEvent.AlertExpired -> s.copy(alerts = s.alerts.filterNot { it.id == e.reportId }, selectedAlertId = s.selectedAlertId.takeIf { it != e.reportId })
                is LiveEvent.JamUpdated -> {
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
                    _ui.update { s -> s.copy(alerts = s.alerts.filterNot { it.id == r.id } + r) }
                    _events.tryEmit(UiEvent.Toast(R.string.report_sent))
                }
                .onFailure { _events.tryEmit(UiEvent.Toast(R.string.report_failed)) }
        }
    }

    // --- Search -----------------------------------------------------------------------------

    fun openSearch(purpose: SearchPurpose = SearchPurpose.NAVIGATE, initial: String = "") {
        _ui.update { it.copy(screen = Screen.Search(purpose), query = initial, results = emptyList(), activeCategory = null) }
        queryFlow.value = initial
    }

    fun onQuery(q: String) {
        _ui.update { it.copy(query = q, activeCategory = null) }
        queryFlow.value = q
    }

    private suspend fun runSearch(q: String) {
        if (q.isBlank()) {
            _ui.update { it.copy(results = if (it.activeCategory != null) it.results else emptyList(), searching = false) }
            return
        }
        _ui.update { it.copy(searching = true) }
        val me = here()
        val res = runCatching { repo.search(q, me.lat, me.lng, 12) }.getOrDefault(emptyList())
        if (_ui.value.query == q) _ui.update { it.copy(results = res.withDistanceFrom(me), searching = false) }
    }

    fun searchCategory(cat: String) {
        _ui.update { it.copy(activeCategory = cat, query = "", searching = true) }
        queryFlow.value = ""
        viewModelScope.launch {
            val me = here()
            val res = runCatching { repo.nearby(me.lat, me.lng, cat, 10_000) }.getOrDefault(emptyList())
            _ui.update { it.copy(results = res.withDistanceFrom(me), searching = false) }
        }
    }

    private fun List<PlaceSummary>.withDistanceFrom(me: GeoPoint) =
        map { if (it.distanceM == null) it.copy(distanceM = GeoMath.distanceM(me, GeoPoint(it.lat, it.lng))) else it }

    fun pickResult(p: PlaceSummary) {
        when ((_ui.value.screen as? Screen.Search)?.purpose ?: SearchPurpose.NAVIGATE) {
            SearchPurpose.NAVIGATE -> showPlace(p)
            SearchPurpose.SET_HOME -> savePlace("home", p, R.string.home_set)
            SearchPurpose.SET_WORK -> savePlace("work", p, R.string.work_set)
            SearchPurpose.ADD_FAVORITE -> {
                viewModelScope.launch {
                    runCatching { repo.addFavorite(SavePlaceRequest(p.name, p.lat, p.lng, p.address, p.id)) }
                    reloadPlaces()
                    _events.tryEmit(UiEvent.Toast(R.string.place_saved))
                }
                goHome()
            }
            SearchPurpose.ADD_STOP -> {
                _ui.update { it.copy(screen = Screen.Navigating) }
                viewModelScope.launch {
                    val ok = c.navigation.addStop(NavDestination(p.name, p.address, GeoPoint(p.lat, p.lng), p.id))
                    if (ok) _events.tryEmit(UiEvent.Toast(R.string.stop_added))
                }
            }
        }
    }

    private fun savePlace(kind: String, p: PlaceSummary, @StringRes msg: Int) {
        viewModelScope.launch {
            runCatching { repo.setPlace(kind, SavePlaceRequest(p.name, p.lat, p.lng, p.address, p.id)) }
            reloadPlaces()
            _events.tryEmit(UiEvent.Toast(msg))
        }
        goHome()
    }

    // --- Place card -------------------------------------------------------------------------

    fun showPlace(p: PlaceSummary) {
        val saved = _ui.value.saved.any { it.placeId == p.id && p.id.isNotEmpty() }
        _ui.update { it.copy(screen = Screen.PlaceCard, place = PlaceSel(p, loading = true, saved = saved), camera = CameraCommand.MoveTo(GeoPoint(p.lat, p.lng), 15.5), following = false) }
        if (p.id.startsWith("pin:")) {
            _ui.update { it.copy(place = it.place?.copy(loading = false)) }
            return
        }
        viewModelScope.launch {
            val d = runCatching { repo.place(p.id) }.getOrNull()
            _ui.update { s -> if (s.place?.summary?.id == p.id) s.copy(place = s.place.copy(detail = d, loading = false)) else s }
        }
    }

    fun dropPin(g: GeoPoint) {
        if (_ui.value.screen != Screen.Home && _ui.value.screen != Screen.PlaceCard) return
        viewModelScope.launch {
            val r = runCatching { repo.reverse(g.lat, g.lng) }.getOrNull()
            showPlace(PlaceSummary("pin:${g.lat},${g.lng}", r?.name ?: String.format(java.util.Locale.US, "%.5f, %.5f", g.lat, g.lng), "other", r?.address ?: "", g.lat, g.lng, GeoMath.distanceM(here(), g)))
        }
    }

    fun savePlaceAsFavorite() {
        val p = _ui.value.place?.summary ?: return
        viewModelScope.launch {
            runCatching { repo.addFavorite(SavePlaceRequest(p.name, p.lat, p.lng, p.address, p.id.takeUnless { it.startsWith("pin:") })) }
            reloadPlaces()
            _ui.update { s -> s.copy(place = s.place?.copy(saved = true)) }
            _events.tryEmit(UiEvent.Toast(R.string.place_saved))
        }
    }

    fun call(phone: String) = _events.tryEmit(UiEvent.Dial(phone))
    fun openWeb(url: String) = _events.tryEmit(UiEvent.Web(url))

    fun sharePlace() {
        val p = _ui.value.place?.summary ?: return
        _events.tryEmit(UiEvent.Share("${p.name}\n${p.address}\n${osmLink(GeoPoint(p.lat, p.lng))}"))
    }

    private fun osmLink(p: GeoPoint) = String.format(java.util.Locale.US, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=17/%.5f/%.5f", p.lat, p.lng, p.lat, p.lng)

    // --- Route planning ---------------------------------------------------------------------

    fun routeTo(dest: NavDestination) {
        val origin = here()
        val st = settings.current
        val prev = _ui.value.plan
        val plan = RoutePlan(
            destination = dest, origin = origin, vehicle = st.vehicle,
            avoid = prev?.avoid ?: AvoidOptions(),
        )
        _ui.update { it.copy(screen = Screen.RoutePreview, plan = plan, following = false, selectedAlertId = null) }
        fetchRoutes(plan)
    }

    fun routeToPlace() {
        val p = _ui.value.place?.summary ?: return
        routeTo(NavDestination(p.name, p.address, GeoPoint(p.lat, p.lng), p.id.takeUnless { it.startsWith("pin:") }))
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
                destination = LatLngDto(plan.destination.point.lat, plan.destination.point.lng, plan.destination.placeId),
                vehicle = plan.vehicle,
                avoid = plan.avoid,
                departAt = plan.departAt?.toString(),
                alternatives = 2,
            )
            val res = runCatching { repo.routes(req) }
            val routes = res.getOrNull().orEmpty()
            if (routes.isEmpty()) {
                _ui.update { it.copy(plan = it.plan?.copy(loading = false, error = true)) }
                return@launch
            }
            val decoded = routes.associate { it.id to runCatching { Polyline.decode(it.polyline) }.getOrDefault(emptyList()) }
            val best = routes.firstOrNull { it.isBest } ?: routes.first()
            _ui.update {
                it.copy(
                    plan = it.plan?.copy(routes = routes, decoded = decoded, selectedId = best.id, loading = false, request = req, countdownRunning = plan.departAt == null),
                    camera = CameraCommand.Fit(decoded.values.flatten() + plan.origin + plan.destination.point, topDp = 170, bottomDp = 380),
                )
            }
        }
    }

    fun selectRoute(id: String) {
        _ui.update { s -> s.copy(plan = s.plan?.copy(selectedId = id, countdownRunning = false)) }
    }

    fun cancelCountdown() = _ui.update { s -> s.copy(plan = s.plan?.copy(countdownRunning = false)) }

    fun setVehicle(v: String) {
        val plan = _ui.value.plan ?: return
        if (plan.vehicle == v) return
        viewModelScope.launch {
            settings.setVehicle(v)
            runCatching { repo.updateMe(UpdateMeRequest(vehicle = v)) }
        }
        fetchRoutes(plan.copy(vehicle = v))
    }

    fun setVehicleHome(v: String) {
        viewModelScope.launch {
            settings.setVehicle(v)
            runCatching { repo.updateMe(UpdateMeRequest(vehicle = v)) }
        }
    }

    fun setAvoid(a: AvoidOptions) {
        val plan = _ui.value.plan ?: return
        fetchRoutes(plan.copy(avoid = a))
    }

    fun openLeaveLater() {
        cancelCountdown()
        val plan = _ui.value.plan ?: return
        viewModelScope.launch {
            val slots = runCatching {
                repo.departureTimes(plan.origin.lat, plan.origin.lng, plan.destination.point.lat, plan.destination.point.lng)
            }.getOrDefault(emptyList())
            _ui.update { it.copy(departureSlots = slots) }
        }
    }

    fun openBestTimeFromNav() {
        val s = nav.value
        val d = s.destination ?: return
        val o = s.fix?.point ?: here()
        viewModelScope.launch {
            val slots = runCatching { repo.departureTimes(o.lat, o.lng, d.point.lat, d.point.lng) }.getOrDefault(emptyList())
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

    fun startNavigation() {
        val plan = _ui.value.plan ?: return
        val route = plan.selected ?: return
        val simulate = repo.isDemo || settings.current.simulateDrive || _ui.value.myLocation == null
        c.navigation.start(route, plan.destination, plan.request, simulate)
        _ui.update { it.copy(screen = Screen.Navigating, following = true, navExpanded = false, plan = plan.copy(countdownRunning = false)) }
        viewModelScope.launch { reloadPlaces() }
    }

    fun stopNavigation() {
        c.navigation.stop()
        _ui.update { it.copy(screen = Screen.Home, plan = null, navExpanded = false, following = true, camera = CameraCommand.Reset2D(here(), 15.0)) }
    }

    fun setNavExpanded(v: Boolean) = _ui.update { it.copy(navExpanded = v) }

    fun overview() {
        val r = nav.value.route ?: return
        val pts = runCatching { Polyline.decode(r.polyline) }.getOrDefault(emptyList())
        _ui.update { it.copy(following = false, camera = CameraCommand.Fit(pts, topDp = 200, bottomDp = 260)) }
    }

    fun shareDrive() {
        val s = nav.value
        val d = s.destination ?: return
        val eta = s.progress?.let { Formatters.etaClock(Instant.now(), it.remainingS, ZoneId.systemDefault(), settings.current.use24h, c.lang()) } ?: "-"
        _events.tryEmit(UiEvent.Share("__share_drive__|${d.name}|$eta|${osmLink(d.point)}"))
        setNavExpanded(false)
    }

    fun addStop() {
        setNavExpanded(false)
        openSearch(SearchPurpose.ADD_STOP)
    }

    // --- Misc -------------------------------------------------------------------------------

    fun goHome() {
        val nav = nav.value
        _ui.update { it.copy(screen = if (nav.active) Screen.Navigating else Screen.Home, place = null, departureSlots = null) }
    }

    fun back() {
        val s = _ui.value
        when (s.screen) {
            is Screen.Search -> if ((s.screen as Screen.Search).purpose == SearchPurpose.ADD_STOP) _ui.update { it.copy(screen = Screen.Navigating) } else goHome()
            Screen.PlaceCard -> goHome()
            Screen.RoutePreview -> if (s.place != null) _ui.update { it.copy(screen = Screen.PlaceCard, plan = null) } else _ui.update { it.copy(screen = Screen.Home, plan = null) }
            Screen.Settings -> goHome()
            Screen.Navigating -> setNavExpanded(false)
            Screen.Home -> Unit
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

    // Settings
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

    fun toast(@StringRes r: Int) = _events.tryEmit(UiEvent.Toast(r))

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(c) as T
    }
}
