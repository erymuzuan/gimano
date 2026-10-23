package my.gimano.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.geo.Polyline
import my.gimano.app.ui.home.HomeOverlay
import my.gimano.app.ui.home.MapTopControls
import my.gimano.app.ui.map.GimanoMap
import my.gimano.app.ui.map.MapHandle
import my.gimano.app.ui.map.MapRenderState
import my.gimano.app.ui.map.RouteDraw
import my.gimano.app.ui.menu.SideMenu
import my.gimano.app.ui.navigation.NavigationOverlay
import my.gimano.app.ui.place.PlaceCard
import my.gimano.app.ui.report.ReportSheet
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.route.RoutePreviewOverlay
import my.gimano.app.ui.search.SearchScreen
import my.gimano.app.ui.settings.SettingsScreen
import my.gimano.app.ui.theme.Gimano
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun GimanoRoot(vm: MainViewModel, onMic: () -> Unit, onExit: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val fallback by vm.fallbackActive.collectAsStateWithLifecycle()
    val handle = remember { MapHandle() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val unreachableMsg = stringResource(R.string.server_unreachable)
    val retryLabel = stringResource(R.string.retry)
    LaunchedEffect(fallback) {
        if (fallback && !settings.demoMode) {
            val r = snackbar.showSnackbar(unreachableMsg, retryLabel, withDismissAction = true, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.retryServer()
        }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Toast -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(context.getString(e.res)) }
                is UiEvent.Share -> {
                    val text = if (e.text.startsWith("__share_drive__|")) {
                        val (_, name, eta, link) = e.text.split("|")
                        context.getString(R.string.share_drive_text, name, eta, link)
                    } else e.text
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                }
                is UiEvent.Dial -> runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e.phone}"))) }
                is UiEvent.Web -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url))) }
            }
        }
    }

    val screen = ui.screen
    BackHandler(enabled = drawer.isOpen || screen != Screen.Home) {
        if (drawer.isOpen) scope.launch { drawer.close() } else vm.back()
    }
    LaunchedEffect(handle.userMovedCamera) {
        if (handle.userMovedCamera) {
            vm.onUserPanned()
            if (screen == Screen.RoutePreview) vm.cancelCountdown()
            handle.clearUserMoved()
        }
    }
    // If navigation ends elsewhere (notification "Stop"), leave the driving screen.
    LaunchedEffect(nav.active) {
        if (!nav.active && screen == Screen.Navigating) vm.stopNavigation()
    }

    val navPoints = remember(nav.route?.id) { nav.route?.let { runCatching { Polyline.decode(it.polyline) }.getOrNull() }.orEmpty() }
    val navigating = screen == Screen.Navigating || (screen is Screen.Search && nav.active)
    val renderState = MapRenderState(
        routes = when {
            navigating && nav.route != null -> listOf(RouteDraw(nav.route!!.id, navPoints, true, nav.route!!.trafficSegments))
            screen == Screen.RoutePreview -> ui.plan?.routes.orEmpty().map { r ->
                RouteDraw(r.id, ui.plan?.decoded?.get(r.id).orEmpty(), r.id == ui.plan?.selected?.id, r.trafficSegments)
            }
            else -> emptyList()
        },
        alerts = ui.alerts + if (screen == Screen.RoutePreview) ui.plan?.selected?.alerts.orEmpty().map { it.toReport() }.filter { a -> ui.alerts.none { it.id == a.id } } else emptyList(),
        jams = ui.jams,
        drivers = ui.drivers,
        puck = if (navigating) nav.puck ?: nav.fix?.point else ui.myLocation?.point,
        puckBearing = if (navigating) nav.puckBearing.toFloat() else ui.myLocation?.bearing ?: 0f,
        pin = when (screen) {
            Screen.PlaceCard -> ui.place?.summary?.let { my.gimano.app.core.geo.GeoPoint(it.lat, it.lng) }
            Screen.RoutePreview -> ui.plan?.destination?.point
            else -> if (navigating) nav.destination?.point else null
        },
        stops = if (navigating) nav.stops.map { it.point } else emptyList(),
        showTraffic = settings.showTraffic,
        showAlerts = settings.showAlerts,
        showDrivers = settings.showDrivers,
        navigating = navigating,
    )

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            SideMenu(
                ui.user,
                onProfile = { scope.launch { drawer.close() }; vm.openSettings() },
                onPlanned = { scope.launch { drawer.close() }; vm.toast(R.string.coming_soon) },
                onInbox = { scope.launch { drawer.close() }; vm.toast(R.string.coming_soon) },
                onSettings = { scope.launch { drawer.close() }; vm.openSettings() },
                onHelp = { scope.launch { drawer.close() }; vm.openWeb("https://github.com/") },
                onExit = onExit,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GimanoMap(
                styleUrl = settings.mapStyleUrl,
                state = renderState,
                camera = ui.camera,
                handle = handle,
                modifier = Modifier.fillMaxSize(),
                onCameraIdle = vm::onCameraIdle,
                onMapClick = { if (screen == Screen.Home) vm.selectAlert(null) },
                onMapLongClick = vm::dropPin,
                onAlertClick = { id -> if (screen == Screen.Home || screen == Screen.PlaceCard) { vm.goHome(); vm.selectAlert(id) } },
                onRouteClick = { id -> if (screen == Screen.RoutePreview) vm.selectRoute(id) },
            )
            when (screen) {
                Screen.Home -> {
                    MapTopControls(ui, settings, vm, vm.isDemo || fallback, showMenu = true, onMenu = { scope.launch { drawer.open() } })
                    HomeOverlay(ui, settings, vm, onMic)
                }
                Screen.PlaceCard -> {
                    MapTopControls(ui, settings, vm, vm.isDemo || fallback, showMenu = false, onMenu = {})
                    ui.place?.let { PlaceCard(it, vm, Modifier.align(Alignment.BottomCenter)) }
                }
                Screen.RoutePreview -> ui.plan?.let { RoutePreviewOverlay(it, settings, vm, handle) }
                Screen.Navigating -> NavigationOverlay(nav, ui, settings, vm)
                is Screen.Search -> SearchScreen(ui, screen.purpose, vm, onMic)
                Screen.Settings -> SettingsScreen(settings, vm, fallback, ui.user?.displayName)
            }
            // Below the right-hand FAB column (3 × 64 dp + gaps) so it never covers controls.
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 250.dp, start = 16.dp, end = 16.dp))
        }
    }

    if (ui.reportOpen) {
        ReportSheet(onDismiss = { vm.openReport(false) }, onSubmit = vm::submitReport)
    }
    ui.departureSlots?.let { slots -> DepartureDialog(slots, settings.use24h, vm) }
}

@Composable
private fun DepartureDialog(slots: List<my.gimano.app.data.model.DepartureSlot>, use24h: Boolean, vm: MainViewModel) {
    val c = Gimano.colors
    val lang = currentLang()
    val best = slots.minByOrNull { it.durationS }
    AlertDialog(
        onDismissRequest = vm::closeSlots,
        confirmButton = { TextButton(onClick = vm::closeSlots) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.best_time_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(stringResource(R.string.leave_now), Modifier.fillMaxWidth().clickable { vm.chooseDeparture(null) }.padding(vertical = 12.dp), fontWeight = FontWeight.SemiBold, color = c.brand)
                }
                items(slots) { s ->
                    val t = Formatters.parseInstant(s.departAt) ?: return@items
                    Row(Modifier.fillMaxWidth().clickable { vm.chooseDeparture(s) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Formatters.clock(ZonedDateTime.ofInstant(t, ZoneId.systemDefault()), use24h, lang), Modifier.weight(1f), fontSize = 17.sp, color = c.text)
                        Text(
                            Formatters.duration(s.durationS, lang), fontSize = 16.sp,
                            fontWeight = if (s == best) FontWeight.Bold else FontWeight.Normal,
                            color = if (s == best) c.brand else c.textMuted,
                        )
                    }
                }
            }
        },
    )
}
