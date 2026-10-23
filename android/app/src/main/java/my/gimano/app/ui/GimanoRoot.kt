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
fun GimanoRoot(vm: MainViewModel, onMic: () -> Unit, onExit: () -> Unit, onRequestPrecise: () -> Unit = {}) {
    val container = (LocalContext.current.applicationContext as my.gimano.app.GimanoApp).container
    val fvm: my.gimano.app.ui.feedback.FeedbackViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = my.gimano.app.ui.feedback.FeedbackViewModel.Factory(container))
    val bvm: my.gimano.app.ui.backup.BackupViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = my.gimano.app.ui.backup.BackupViewModel.Factory(container))
    val feedbackDraft by fvm.draft.collectAsStateWithLifecycle()
    val cvm: my.gimano.app.ui.contribute.ContributeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = my.gimano.app.ui.contribute.ContributeViewModel.Factory(container))
    val pvm: my.gimano.app.ui.planned.PlannedViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = my.gimano.app.ui.planned.PlannedViewModel.Factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val upd by container.updater.ui.collectAsStateWithLifecycle()
    val homeSheetDp by vm.homeSheetDp.collectAsStateWithLifecycle()
    var updateSheetOpen by remember { mutableStateOf(true) }
    var forceUpdateSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { container.updater.check() }   // on launch; at most every 6 h
    LaunchedEffect(Unit) { container.crashes.onAppStart() }
    // data objects' toString() is a compile-time constant (survives R8); data classes → "Search(purpose=…)".
    LaunchedEffect(ui.screen) { container.crashes.screen = ui.screen.toString().substringBefore('(').lowercase() }
    val crashPending by container.crashes.pending.collectAsStateWithLifecycle()
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
                is UiEvent.ToastText -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(e.text) }
                is UiEvent.OpenFeedback -> scope.launch {
                    // Capture what's behind the sheet first (map included: PixelCopy reads the window).
                    val shot = (context as? android.app.Activity)?.let { my.gimano.app.ui.feedback.ScreenCapture.capture(it) }
                    val jpeg = shot?.let { my.gimano.app.ui.feedback.ScreenCapture.toJpeg(it) }
                    fvm.open(e.screen, jpeg, e.routeId, e.placeId)
                }
                is UiEvent.AddPlace -> { vm.startAddPlace(e.at, e.name); cvm.startAdd(e.at, e.name) }
                is UiEvent.PlanDrive -> pvm.newPlan(e.dest, e.origin, e.waypoints, e.vehicle)
                is UiEvent.Dial -> runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e.phone}"))) }
                is UiEvent.Web -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url))) }
            }
        }
    }

    LaunchedEffect(Unit) {
        cvm.events.collect { e ->
            when (e) {
                is my.gimano.app.ui.contribute.ContribEvent.Message -> { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(context.getString(e.res)) }
                is my.gimano.app.ui.contribute.ContribEvent.Text -> { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(e.text) }
                is my.gimano.app.ui.contribute.ContribEvent.ShowPlace -> vm.showPlace(e.summary)
            }
        }
    }
    LaunchedEffect(Unit) {
        fvm.result.collect { r ->
            val msg = when (r) {
                my.gimano.app.data.feedback.FeedbackSendResult.Sent -> R.string.fb_sent
                my.gimano.app.data.feedback.FeedbackSendResult.Queued -> R.string.fb_queued
                is my.gimano.app.data.feedback.FeedbackSendResult.Rejected -> R.string.fb_rejected
            }
            snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(context.getString(msg))
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
        puckAccuracyM = if (navigating) nav.accuracyM ?: nav.fix?.accuracyM?.toDouble() else ui.myLocation?.accuracyM?.toDouble(),
        pin = when (screen) {
            Screen.PlaceCard -> ui.place?.summary?.let { my.gimano.app.core.geo.GeoPoint(it.lat, it.lng) }
            Screen.PinActions -> ui.pin?.place?.let { my.gimano.app.core.geo.GeoPoint(it.lat, it.lng) }
            Screen.RoutePreview -> ui.plan?.destination?.point
            else -> if (navigating) nav.destination?.point else null
        },
        privatePins = if (screen == Screen.Home || screen == Screen.PlaceCard) ui.privatePins else emptyList(),
        stops = when {
            navigating -> nav.stops.map { it.point }
            screen == Screen.RoutePreview -> ui.plan?.stops.orEmpty().map { it.point }
            else -> emptyList()
        },
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
                ui.user, inboxUnread = ui.inbox.count { it.id !in settings.inboxRead },
                updateVersion = (upd.decision as? my.gimano.app.core.update.UpdateDecision.Available)?.manifest?.versionName,
                onUpdate = { scope.launch { drawer.close() }; updateSheetOpen = true; forceUpdateSheet = true; vm.goHome() },
                onProfile = { scope.launch { drawer.close() }; vm.open(Screen.Contributions) },
                onFavourites = { scope.launch { drawer.close() }; vm.open(Screen.Favourites) },
                onPlanned = { scope.launch { drawer.close() }; vm.open(Screen.Planned) },
                onInbox = { scope.launch { drawer.close() }; vm.open(Screen.Inbox) },
                onContributions = { scope.launch { drawer.close() }; vm.open(Screen.Contributions) },
                onAddPlace = { scope.launch { drawer.close() }; vm.startAddPlace(vm.here(), ""); cvm.startAdd(vm.here(), "") },
                onBackup = { scope.launch { drawer.close() }; vm.open(Screen.Backup) },
                onTheme = { scope.launch { drawer.close() }; vm.open(Screen.Theme) },
                onFeedback = { scope.launch { drawer.close() }; vm.requestFeedback("home") },
                onSettings = { scope.launch { drawer.close() }; vm.openSettings() },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GimanoMap(
                styleUrl = if (Gimano.colors.isDark) my.gimano.app.BuildConfig.MAP_STYLE_DARK_URL else settings.mapStyleUrl,
                state = renderState,
                camera = ui.camera,
                handle = handle,
                bottomInsetDp = if (screen == Screen.Home) homeSheetDp else 0,
                modifier = Modifier.fillMaxSize(),
                onCameraIdle = vm::onCameraIdle,
                onMapClick = { if (screen == Screen.Home) vm.selectAlert(null) },
                onMapLongClick = vm::dropPin,
                onAlertClick = { id -> if (screen == Screen.Home || screen == Screen.PlaceCard) { vm.goHome(); vm.selectAlert(id) } },
                onRouteClick = { id -> if (screen == Screen.RoutePreview) vm.selectRoute(id) },
                onPrivateClick = { id -> if (screen == Screen.Home || screen == Screen.PlaceCard) vm.showPrivate(id) },
            )
            when (screen) {
                Screen.Home -> {
                    MapTopControls(ui, settings, vm, vm.isDemo || fallback, showMenu = true, onMenu = { scope.launch { drawer.open() } })
                    HomeOverlay(ui, settings, vm, onMic)
                }
                is Screen.PickOnMap -> ui.pick?.let { my.gimano.app.ui.pick.PickOnMapOverlay(it, vm) }
                Screen.PinActions -> {
                    MapTopControls(ui, settings, vm, vm.isDemo || fallback, showMenu = false, onMenu = {})
                    ui.pin?.let { my.gimano.app.ui.pick.PinActionsSheet(it, vm, Modifier.align(Alignment.BottomCenter)) }
                }
                Screen.PlaceCard -> {
                    MapTopControls(ui, settings, vm, vm.isDemo || fallback, showMenu = false, onMenu = {})
                    ui.place?.let { PlaceCard(it, vm, cvm, settings.apiBaseUrl, Modifier.align(Alignment.BottomCenter)) }
                }
                Screen.RoutePreview -> ui.plan?.let { RoutePreviewOverlay(it, settings, vm, handle) }
                Screen.Navigating -> NavigationOverlay(nav, ui, settings, vm)
                is Screen.Search -> SearchScreen(ui, screen.purpose, vm, onMic)
                Screen.Settings -> SettingsScreen(settings, vm, fallback, ui.user?.displayName, onBackup = { vm.open(Screen.Backup) }, onMyFeedback = { vm.open(Screen.MyFeedback) }, onTheme = { vm.open(Screen.Theme) }, onAccount = { vm.open(Screen.Account) }, onAbout = { vm.open(Screen.About) })
                Screen.About -> my.gimano.app.ui.about.AboutScreen(container, live = !fallback && !settings.demoMode, onBack = vm::back, onLicences = { vm.open(Screen.Licences) })
                Screen.Licences -> my.gimano.app.ui.about.LicencesScreen(onBack = vm::back)
                Screen.Account -> my.gimano.app.ui.account.AccountScreen(container, vm, ui.user, settings.displayName)
                Screen.Backup -> my.gimano.app.ui.backup.BackupScreen(bvm, onBack = vm::back)
                Screen.MyFeedback -> my.gimano.app.ui.feedback.MyFeedbackScreen(fvm, onBack = vm::back, onNew = { vm.requestFeedback("settings") })
                Screen.Contributions -> my.gimano.app.ui.contribute.ContributionsScreen(container, cvm, vm, ui.user)
                Screen.AddPlace -> my.gimano.app.ui.contribute.AddPlaceScreen(cvm, handle, onBack = vm::back)
                Screen.Favourites -> my.gimano.app.ui.more.FavouritesScreen(ui.saved, ui.myLocation?.point, settings.lastBackupAt, vm)
                Screen.Inbox -> my.gimano.app.ui.inbox.InboxScreen(ui.inbox, settings.inboxRead, onBack = vm::back, onRefresh = { vm.refreshInbox() }, onMarkAll = { vm.markInboxRead() })
                Screen.Theme -> my.gimano.app.ui.more.ThemeScreen(settings.theme, onPick = { vm.setTheme(it) }, onBack = vm::back)
                Screen.Planned -> my.gimano.app.ui.planned.PlannedDrivesScreen(pvm, settings.use24h, onBack = vm::back,
                    onAdd = { vm.openSearch(SearchPurpose.PLAN_DRIVE) }, onGo = vm::goPlanned)
            }
            // Below the right-hand FAB column (3 × 64 dp + gaps) so it never covers controls.
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 250.dp, start = 16.dp, end = 16.dp))
        }
    }

    ui.favoriteSheet?.let { my.gimano.app.ui.sheets.FavoriteSheet(it, vm) }
    // In-app update (sideloaded APK): sheet on Home when not snoozed, blocking gate below minSupported.
    val lang = container.lang().tag.take(2)
    when (val d = upd.decision) {
        is my.gimano.app.core.update.UpdateDecision.Available ->
            if ((!d.snoozed || forceUpdateSheet) && updateSheetOpen && ui.screen == Screen.Home && !nav.active) my.gimano.app.ui.update.UpdateSheet(
                d.manifest, upd, lang, onUpdate = { scope.launch { container.updater.updateNow() } },
                onLater = { updateSheetOpen = false; forceUpdateSheet = false; container.updater.later() },
            )
        is my.gimano.app.core.update.UpdateDecision.Required ->
            if (ui.screen != Screen.Backup) my.gimano.app.ui.update.UpdateRequiredGate(
                d.manifest, upd, lang, onUpdate = { scope.launch { container.updater.updateNow() } }, onBackup = { vm.open(Screen.Backup) },
            )
        else -> Unit
    }
    my.gimano.app.ui.update.UpdateDialogs(container.updater, upd)
    LaunchedEffect(upd.justUpdatedTo) {
        upd.justUpdatedTo?.let { v -> snackbar.showSnackbar(context.getString(R.string.updated_to, v)); container.updater.dismissUpdatedNote() }
    }
    if (crashPending.isNotEmpty() && ui.screen == Screen.Home && !nav.active) my.gimano.app.ui.feedback.CrashSheet(container.crashes, crashPending)
    ui.askPrecise?.let { my.gimano.app.ui.common.PreciseLocationDialog(onAllow = onRequestPrecise, onKeep = vm::onPreciseAnswered) }
    if (ui.precision == my.gimano.app.core.location.LocationPrecision.APPROXIMATE && (ui.screen == Screen.Home || ui.screen == Screen.Navigating)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp).padding(top = if (ui.screen == Screen.Navigating) 250.dp else 96.dp)) {
            my.gimano.app.ui.common.ApproximateBanner(Modifier.align(Alignment.TopCenter))
        }
    }
    ui.namePin?.let { my.gimano.app.ui.pick.NamePinDialog(onName = vm::nameArrivedPin) }
    ui.vetPrompt?.let { if (ui.screen == Screen.Home) my.gimano.app.ui.contribute.NearbyVerifySheet(it, vm, cvm) }
    ui.tollSheet?.let { r ->
        my.gimano.app.ui.sheets.TollSheet(r, onDismiss = { vm.openTolls(null) }, onAvoidTolls = {
            vm.openTolls(null); ui.plan?.let { p -> vm.setAvoid(p.avoid.copy(tolls = true)) }
        })
    }
    if (feedbackDraft.open) my.gimano.app.ui.feedback.FeedbackSheet(fvm, feedbackDraft, here = { vm.here().let { it.lat to it.lng } }, onSend = {
        val h = vm.here(); fvm.send(h.lat, h.lng)
    })
    if (!settings.privacyCardDismissed && screen == Screen.Home) {
        AlertDialog(
            onDismissRequest = vm::dismissPrivacyCard,
            text = { my.gimano.app.ui.common.PrivacyCard(onBackup = { vm.dismissPrivacyCard(); vm.open(Screen.Backup) }) },
            confirmButton = { TextButton(onClick = vm::dismissPrivacyCard) { Text(stringResource(R.string.got_it), fontWeight = FontWeight.ExtraBold) } },
            // Manotu's first run: offer (never automatic) to bring places over from a Gimano/Manotu backup file or Drive.
            // The Backup screen asks which backup and confirms before anything is replaced.
            dismissButton = if (my.gimano.app.BuildConfig.FLAVOR == "manotu") {
                { TextButton(onClick = { vm.dismissPrivacyCard(); vm.open(Screen.Backup) }) { Text(stringResource(R.string.restore_from_gimano), fontWeight = FontWeight.ExtraBold) } }
            } else null,
        )
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
