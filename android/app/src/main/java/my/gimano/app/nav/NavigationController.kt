package my.gimano.app.nav

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.gimano.app.core.format.Lang
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.nav.LocationBatcher
import my.gimano.app.core.nav.ManeuverFormatter
import my.gimano.app.core.nav.NavProgress
import my.gimano.app.core.nav.NavigationTracker
import my.gimano.app.core.nav.OffRouteDetector
import my.gimano.app.core.nav.PuckPlacement
import my.gimano.app.core.nav.VoicePromptPlanner
import my.gimano.app.core.nav.headingDiff
import my.gimano.app.data.location.DriveSimulator
import my.gimano.app.data.location.Fix
import my.gimano.app.data.model.*
import my.gimano.app.data.repo.GimanoRepository
import my.gimano.app.data.settings.SoundMode
import java.time.Instant

data class NavDestination(val name: String, val address: String, val point: GeoPoint, val placeId: String?)

data class NavState(
    val active: Boolean = false,
    val route: Route? = null,
    val destination: NavDestination? = null,
    val stops: List<NavDestination> = emptyList(),
    val progress: NavProgress? = null,
    val fix: Fix? = null,
    val speedKmh: Int = 0,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val upcomingAlert: AlertOnRoute? = null,
    val alertDistanceM: Double? = null,
    val simulated: Boolean = false,
    val startedAt: Instant? = null,
    /** Where the puck is drawn: snapped to the route when close and heading-compatible. */
    val puck: GeoPoint? = null,
    val puckBearing: Double = 0.0,
    val puckSnapped: Boolean = false,
)

/**
 * Owns an active drive: consumes fixes, tracks progress, speaks prompts, detects off-route
 * (→ POST /routes/{id}/reroute) and batches location pings (→ POST /locations).
 * Lives in the app container so the foreground service and UI observe the same state.
 */
class NavigationController(
    private val scope: CoroutineScope,
    private val repo: GimanoRepository,
    private val fixSource: FixSource,
    private val voice: Speaker,
    private val service: NavServiceControl,
    private val soundMode: () -> SoundMode,
    private val langProvider: () -> Lang,
) {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private var job: Job? = null
    private var tracker: NavigationTracker? = null
    private val offRoute = OffRouteDetector()
    private val planner = VoicePromptPlanner()
    private val batcher = LocationBatcher()
    private val announcedAlerts = HashSet<String>()
    private val dismissedAlerts = HashSet<String>()
    private var lastRequest: RouteRequest? = null

    fun start(route: Route, destination: NavDestination, request: RouteRequest?, simulate: Boolean) {
        stopInternal(flush = false)
        lastRequest = request
        tracker = NavigationTracker(route)
        offRoute.reset(); planner.reset(); announcedAlerts.clear(); dismissedAlerts.clear()
        _state.value = NavState(active = true, route = route, destination = destination, simulated = simulate, startedAt = Instant.now())
        val lang = langProvider()
        val first = route.maneuvers.firstOrNull()
        if (soundMode() == SoundMode.ON && first != null) {
            voice.speak(ManeuverFormatter.spokenNow(first, lang), lang, flush = true)
        }
        service.start()
        job = scope.launch {
            fixes(route, simulate).collect { onFix(it) }
        }
    }

    private fun fixes(route: Route, simulate: Boolean): Flow<Fix> {
        val t = tracker!!
        return if (simulate) {
            val avg = (route.distanceM / route.effectiveDurationS * 3.6).coerceIn(25.0, 70.0)
            DriveSimulator.drive(t.geometry, avg * 1.6)
        } else fixSource.updates()
    }

    private suspend fun onFix(fix: Fix) {
        val t = tracker ?: return
        val p = t.update(fix.point) ?: return
        val speed = ((fix.speedMps ?: 0f) * 3.6f).toInt()
        val lang = langProvider()
        val sound = soundMode()

        // Voice prompts for maneuvers.
        val next = p.nextManeuver
        if (sound == SoundMode.ON && next != null && !p.arrived) {
            when (planner.check(p.nextManeuverIndex, p.distanceToNextM, speed.toDouble())) {
                VoicePromptPlanner.Stage.FAR, VoicePromptPlanner.Stage.NEAR ->
                    voice.speak(ManeuverFormatter.spokenApproach(next, p.distanceToNextM, lang), lang)
                VoicePromptPlanner.Stage.NOW -> if (next.type != "arrive") voice.speak(ManeuverFormatter.spokenNow(next, lang), lang)
                null -> Unit
            }
        }

        // Alerts on the route ahead.
        val alerts = _state.value.route?.alerts.orEmpty()
        val ahead = alerts
            .filter { it.id !in dismissedAlerts }
            .map { it to (it.distanceAlongRouteM - p.distanceAlongM) }
            .filter { it.second in -60.0..800.0 }
            .minByOrNull { it.second }
        if (ahead != null && sound != SoundMode.OFF && announcedAlerts.add(ahead.first.id)) {
            voice.speak(alertPhrase(ahead.first.type, lang), lang)
        }

        // Location pings.
        batcher.offer(
            LocationPoint(fix.point.lat, fix.point.lng, speed.toDouble(), fix.bearing?.toDouble(), fix.accuracyM?.toDouble(), Instant.ofEpochMilli(fix.timeMs).toString()),
            fix.timeMs,
        )?.let { send(it) }

        val placement = PuckPlacement.place(fix.point, fix.bearing?.toDouble(), speed.toDouble(), p)
        _state.update {
            it.copy(
                puck = placement.point, puckBearing = placement.bearing, puckSnapped = placement.snapped,
                progress = p, fix = fix, speedKmh = speed, arrived = p.arrived,
                upcomingAlert = ahead?.first, alertDistanceM = ahead?.second,
            )
        }

        if (p.arrived) {
            if (sound == SoundMode.ON) voice.speak(if (lang == Lang.MS) "Anda telah tiba di destinasi" else "You have arrived at your destination", lang)
            finishTracking()
            return
        }

        // Off-route → reroute.
        if (!fix.simulated && offRoute.update(p.distanceFromRouteM, fix.accuracyM?.toDouble(), fix.timeMs, headingDiff(fix.bearing?.toDouble(), p.bearing), speed.toDouble())) {
            reroute(fix)
        }
    }

    private fun alertPhrase(type: String, lang: Lang): String {
        val en = mapOf(
            "police" to "Police reported ahead", "accident" to "Accident reported ahead", "hazard" to "Hazard reported ahead",
            "traffic" to "Traffic jam ahead", "closure" to "Road closed ahead", "blocked_lane" to "Lane blocked ahead",
            "map_issue" to "Map issue reported ahead", "weather" to "Bad weather reported ahead", "animal" to "Animal reported ahead",
        )
        val ms = mapOf(
            "police" to "Polis dilaporkan di hadapan", "accident" to "Kemalangan dilaporkan di hadapan", "hazard" to "Bahaya dilaporkan di hadapan",
            "traffic" to "Kesesakan di hadapan", "closure" to "Jalan ditutup di hadapan", "blocked_lane" to "Lorong terhalang di hadapan",
            "map_issue" to "Isu peta dilaporkan di hadapan", "weather" to "Cuaca buruk dilaporkan di hadapan", "animal" to "Haiwan dilaporkan di hadapan",
        )
        return (if (lang == Lang.MS) ms else en)[type] ?: (if (lang == Lang.MS) "Laporan di hadapan" else "Report ahead")
    }

    private suspend fun reroute(fix: Fix) {
        val route = _state.value.route ?: return
        _state.update { it.copy(rerouting = true) }
        val lang = langProvider()
        if (soundMode() == SoundMode.ON) voice.speak(if (lang == Lang.MS) "Mengira semula laluan" else "Recalculating route", lang)
        try {
            val routes = repo.reroute(route.id, RerouteRequest(fix.point.lat, fix.point.lng, fix.bearing?.toDouble()))
            routes.firstOrNull()?.let { replaceRoute(it) }
        } catch (e: Exception) {
            Log.w("Nav", "Reroute failed", e)
        } finally {
            _state.update { it.copy(rerouting = false) }
        }
    }

    private fun replaceRoute(route: Route) {
        tracker = NavigationTracker(route)
        planner.reset()
        offRoute.reset()
        _state.update { it.copy(route = route) }
    }

    /** Add a stop: re-plan from the current position via [stop] to the destination. */
    suspend fun addStop(stop: NavDestination): Boolean {
        val s = _state.value
        val dest = s.destination ?: return false
        val here = s.fix?.point ?: s.progress?.snapped ?: return false
        val base = lastRequest ?: RouteRequest(LatLngDto(here.lat, here.lng), LatLngDto(dest.point.lat, dest.point.lng, dest.placeId))
        val req = base.copy(
            origin = LatLngDto(here.lat, here.lng),
            waypoints = listOf(LatLngDto(stop.point.lat, stop.point.lng, stop.placeId)),
            alternatives = 0, departAt = null,
        )
        return try {
            val r = repo.routes(req).firstOrNull() ?: return false
            lastRequest = req
            val simulate = s.simulated
            val destination = s.destination
            start(r, destination, req, simulate)
            _state.update { it.copy(stops = listOf(stop)) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun dismissAlert(id: String) {
        dismissedAlerts += id
        _state.update { if (it.upcomingAlert?.id == id) it.copy(upcomingAlert = null) else it }
    }

    /** Ends the drive from any exit path (End button, Back, notification, Done, task removed). */
    fun stop() = stopInternal(flush = true)

    /**
     * Arrived: stop GPS, pings and the foreground service (and its notification) but keep the
     * final state so the UI can show the arrival card until the driver taps Done.
     */
    private fun finishTracking() {
        job?.cancel()
        job = null
        batcher.drain()?.let { send(it) }
        service.stop()
    }

    private fun stopInternal(flush: Boolean) {
        job?.cancel()
        job = null
        voice.stop()
        if (flush) batcher.drain()?.let { send(it) }
        val wasActive = _state.value.active
        _state.value = NavState()
        if (wasActive) service.stop()
    }

    private fun send(points: List<LocationPoint>) {
        val routeId = _state.value.route?.id
        scope.launch { runCatching { repo.postLocations(LocationBatch(routeId, points)) } }
    }

}
