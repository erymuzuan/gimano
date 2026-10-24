package my.gimano.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.model.Recent
import my.gimano.app.data.settings.AppSettings
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.SearchPurpose
import my.gimano.app.ui.UiState
import my.gimano.app.ui.common.*
import my.gimano.app.ui.report.AlertCard
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import my.gimano.app.ui.theme.Sizes
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun MapTopControls(
    ui: UiState,
    settings: AppSettings,
    vm: MainViewModel,
    demo: Boolean,
    showMenu: Boolean,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    showRecenter: Boolean = true,
) {
    var layersOpen by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
        if (showMenu) MenuFab(stringResource(R.string.menu), Modifier.align(Alignment.TopStart), onClick = onMenu)
        if (demo) {
            Pill(
                stringResource(R.string.demo_data), Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                color = Gimano.colors.brand, textColor = Gimano.colors.brandOn, icon = Icons.Filled.Science,
                onClick = { vm.openSettings() },
            )
        }
        Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SoundFab(settings.soundMode, onClick = vm::cycleSound)
            Box {
                RoundFab("layers", stringResource(R.string.layers), onClick = { layersOpen = true })
                DropdownMenu(layersOpen, onDismissRequest = { layersOpen = false }) {
                    LayerItem(R.string.layer_traffic, "traffic", settings.showTraffic) { vm.setLayers(it, settings.showAlerts, settings.showDrivers) }
                    LayerItem(R.string.layer_reports, "report", settings.showAlerts) { vm.setLayers(settings.showTraffic, it, settings.showDrivers) }
                    LayerItem(R.string.layer_drivers, "map", settings.showDrivers) { vm.setLayers(settings.showTraffic, settings.showAlerts, it) }
                }
            }
            if (showRecenter && !ui.following) RoundFab(Icons.Filled.MyLocation, stringResource(R.string.recenter), onClick = vm::recenter)
        }
    }
}

@Composable
private fun LayerItem(label: Int, icon: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), fontWeight = FontWeight.ExtraBold) },
        leadingIcon = { GIcon(icon, null, size = 26.dp) },
        trailingIcon = { Checkbox(checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

/** One chip of the home row: Home/Work, a saved place, a category or "+ Add". */
private data class HomeChip(
    val key: String, val icon: String, val label: String, val tone: ChipTone,
    val onLongClick: (() -> Unit)? = null, val onClick: () -> Unit,
)

/**
 * Home panel with three states (HomeSheetState): PEEK (default: search + one row of chips),
 * HALF (recents first) and FULL (recents, saved places, category grid, privacy card).
 * Drag the header or the list, tap the handle, tap the map or press Back to collapse.
 */
@Composable
fun HomeOverlay(ui: UiState, settings: AppSettings, vm: MainViewModel, onMic: () -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val screenH = maxHeight.value
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Heights follow the real text size (non-linear font scaling included); the peek stays within 25 % of the screen.
    val searchDp = HomeSheetLayout.searchHeight(with(density) { 18.sp.toDp().value } * 1.36f)
    val chipDp = HomeSheetLayout.chipHeight(with(density) { 13.sp.toDp().value } * 1.36f)
    val chipRows = HomeSheetLayout.chipRows(density.fontScale, screenH, searchDp, chipDp, navInset.value)
    val peekDp = HomeSheetLayout.peekHeight(chipRows, searchDp, chipDp, navInset.value)
    val anchors = remember(peekDp, screenH, statusInset) { HomeSheetLayout.anchors(peekDp, screenH, statusInset.value) }
    fun px(dp: Float) = dp * density.density
    val anchorsPx = anchors.map(::px)
    // The map keeps the puck centred in the area above the peek.
    LaunchedEffect(peekDp) { vm.setHomeSheetDp(peekDp.toInt()) }

    val state by vm.homeSheet.collectAsStateWithLifecycle()
    var heightPx by remember { mutableFloatStateOf(px(HomeSheetLayout.heightOf(state, anchors))) }
    val scope = rememberCoroutineScope()
    var anim by remember { mutableStateOf<Job?>(null) }
    fun animateTo(target: Float) {
        anim?.cancel()
        anim = scope.launch { animate(heightPx, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> heightPx = v } }
    }
    fun go(s: HomeSheetState) { vm.setHomeSheet(s); animateTo(anchorsPx[s.ordinal]) }
    fun dragBy(dyPx: Float): Float {   // dy > 0: finger down (shrink). Returns the part used.
        anim?.cancel()
        val next = (heightPx - dyPx).coerceIn(anchorsPx.first(), anchorsPx.last())
        val used = heightPx - next
        heightPx = next
        // Reaching the peek (or the top) commits that state: the list leaves composition at the peek, so the
        // gesture's fling never arrives to settle it (the S24 "fully open" at peek height, chips missing).
        HomeSheetLayout.stateAtEnd(next / density.density, anchors)?.let { if (it != state) vm.setHomeSheet(it) }
        return used
    }
    fun release(downVelocityPx: Float) = go(HomeSheetLayout.settle(heightPx / density.density, -downVelocityPx / density.density, anchors, from = state))
    // External changes (map tap, drive ended, app reopened, font scale or rotation) move the sheet too.
    LaunchedEffect(state, anchors) { if (abs(heightPx - anchorsPx[state.ordinal]) > 1f) animateTo(anchorsPx[state.ordinal]) }
    BackHandler(enabled = state != HomeSheetState.PEEK) { go(HomeSheetState.PEEK) }

    val peekPx = anchorsPx[0]
    val halfPx = anchorsPx[1]
    val progress = ((heightPx - peekPx) / (halfPx - peekPx)).coerceIn(0f, 1f)
    val sheetDp = with(density) { heightPx.toDp() }
    val me = ui.myLocation?.point
    val home = ui.saved.firstOrNull { it.kind == "home" }
    val work = ui.saved.firstOrNull { it.kind == "work" }
    val favs = ui.saved.filter { it.kind == "favorite" }
    val editLabel = stringResource(R.string.edit)

    fun category(cat: String) {
        vm.openSearch()
        vm.searchCategory(cat)
    }
    val categories = listOf(
        Triple("fuel", stringResource(R.string.sc_fuel)) { category("fuel") },
        Triple("food", stringResource(R.string.sc_food)) { category("restaurant") },
        Triple("surau", stringResource(R.string.sc_surau)) { category("mosque") },
        Triple("parking", stringResource(R.string.sc_parking)) { category("parking") },
        Triple("ev", stringResource(R.string.sc_ev)) { category("ev_charging") },
        Triple("atm", stringResource(R.string.sc_atm)) { category("atm") },
        Triple("shop", stringResource(R.string.sc_shop)) { category("convenience") },
    )
    val homeLabel = stringResource(R.string.home)
    val workLabel = stringResource(R.string.work)
    val addLabel = stringResource(R.string.add_favorite)
    val chips = buildList {
        // Unset Home/Work open "set Home/Work"; set ones route there, long-press edits.
        add(HomeChip("home", "home", homeLabel, ChipTone.PLACE, home?.let { h -> { vm.editFavorite(h) } }) {
            if (home != null) vm.routeToSaved(home) else vm.openSearch(SearchPurpose.SET_HOME)
        })
        add(HomeChip("work", "work", workLabel, ChipTone.PLACE, work?.let { w -> { vm.editFavorite(w) } }) {
            if (work != null) vm.routeToSaved(work) else vm.openSearch(SearchPurpose.SET_WORK)
        })
        favs.forEach { f -> add(HomeChip("f" + f.id, "star", f.name, ChipTone.SAVED, { vm.editFavorite(f) }) { vm.routeToSaved(f) }) }
        categories.forEach { (icon, label, action) -> add(HomeChip("c$icon", icon, label, ChipTone.CATEGORY) { action() }) }
        add(HomeChip("add", "more", addLabel, ChipTone.SAVED) { vm.openSearch(SearchPurpose.ADD_FAVORITE) })
    }

    val selected = ui.alerts.firstOrNull { it.id == ui.selectedAlertId }
    // Speed, vehicle, report and recentre sit just above the sheet and fade out as it opens.
    if (progress < 0.99f) Box(
        Modifier.fillMaxWidth().align(Alignment.BottomStart)
            .offset { IntOffset(0, -(heightPx.roundToInt())) }
            .alpha(1f - progress)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (selected != null) {
            AlertCard(selected, me, onVote = { vm.vote(selected, it) }, onClose = { vm.selectAlert(null) }, modifier = Modifier.padding(bottom = 100.dp))
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Speedometer(((ui.myLocation?.speedMps ?: 0f) * 3.6f).roundToInt(), null)
                Spacer(Modifier.width(10.dp))
                val moto = settings.vehicle == "motorcycle"
                Pill(
                    stringResource(if (moto) R.string.motorbike else R.string.car),
                    icon = if (moto) Icons.Filled.TwoWheeler else Icons.Filled.DirectionsCar,
                    onClick = { vm.setVehicleHome(if (moto) "car" else "motorcycle") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Attribution()
        }
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!ui.following) RoundFab(Icons.Filled.MyLocation, stringResource(R.string.recenter), size = 56.dp, onClick = vm::recenter)
            ReportFab { vm.openReport(true) }
        }
    }

    val list = remember { LazyListState() }
    val nested = remember(anchorsPx) {
        object : NestedScrollConnection {
            // Finger up: open the sheet before the list scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0 && source == NestedScrollSource.UserInput && heightPx < anchorsPx.last()) Offset(0f, -dragBy(available.y)) else Offset.Zero
            // Finger down with the list at its top: close the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, -dragBy(available.y)) else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity {
                // Settle unless resting at the current state's height (a drag clamped onto another anchor must
                // still switch the state).
                if (HomeSheetLayout.needsSettle(heightPx / density.density, state, anchors)) { release(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    val sheetLabel = stringResource(R.string.home_sheet)
    val stateLabel = stringResource(when (state) { HomeSheetState.PEEK -> R.string.home_sheet_peek; HomeSheetState.HALF -> R.string.home_sheet_half; HomeSheetState.FULL -> R.string.home_sheet_full })
    val expandLabel = stringResource(R.string.home_sheet_expand)
    val collapseLabel = stringResource(R.string.home_sheet_collapse)
    SheetSurface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetDp)) {
        Column(
            Modifier.fillMaxWidth().draggable(
                rememberDraggableState { dragBy(it) }, Orientation.Vertical,
                onDragStopped = { v -> release(v) },
            ),
        ) {
            // Handle: tap cycles peek → half → full → peek. TalkBack: "Search panel, collapsed. Swipe up for recent places."
            Box(
                Modifier.fillMaxWidth().height(HomeSheetLayout.HANDLE_BLOCK.dp)
                    // One node; the state lives in the description so TalkBack reads the whole sentence once
                    // (and uiautomator, which drops stateDescription, can verify it).
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$sheetLabel, $stateLabel"
                        if (state != HomeSheetState.FULL) expand { go(if (state == HomeSheetState.PEEK) HomeSheetState.HALF else HomeSheetState.FULL); true }
                        if (state != HomeSheetState.PEEK) collapse { go(HomeSheetState.PEEK); true }
                    }
                    .clickable(onClickLabel = if (state == HomeSheetState.FULL) collapseLabel else expandLabel) { go(state.next()) },
                contentAlignment = Alignment.TopCenter,
            ) { GrabHandle(Modifier.padding(top = 8.dp)) }
            WhereToField(onClick = { vm.openSearch() }, onMic = onMic, height = searchDp.dp)
            Spacer(Modifier.height(HomeSheetLayout.GAP.dp))
            // Full shows the category grid instead of the chip row.
            if (state != HomeSheetState.FULL) {
                ChipRows(chips, chipRows, chipDp.dp, editLabel)
                Spacer(Modifier.height(HomeSheetLayout.BOTTOM.dp))
            }
        }
        // Not composed while collapsed, so TalkBack doesn't read hidden rows.
        if (heightPx > peekPx + 2f) LazyColumn(
            Modifier.fillMaxWidth().weight(1f).nestedScroll(nested).alpha((progress * 2f).coerceAtMost(1f)),
            state = list,
            contentPadding = PaddingValues(bottom = navInset + 16.dp),
        ) {
            item { SectionLabel(stringResource(R.string.recents)) }
            if (ui.recents.isEmpty()) item {
                Text(stringResource(R.string.recents_empty), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = GimanoType.caption, color = Gimano.colors.textMuted)
            }
            items(ui.recents, key = { it.id }) { r ->
                PlaceRow("recent", r.name, r.address, me?.let { Formatters.distance(GeoMath.distanceM(it, GeoPoint(r.lat, r.lng))) }, onClick = { vm.routeToRecent(r) },
                    leading = { TwoToneTile("recent", if (Gimano.colors.isDark) Gimano.colors.surfaceAlt else Color(0xFFECEEF1)) })
            }
            item { SectionLabel(stringResource(R.string.saved_places)) }
            items(listOfNotNull(home, work) + favs, key = { "s" + it.id }) { f ->
                val isFav = f.kind == "favorite"
                val title = when (f.kind) { "home" -> homeLabel; "work" -> workLabel; else -> f.name }
                PlaceRow(f.icon, title, f.address, me?.let { Formatters.distance(GeoMath.distanceM(it, GeoPoint(f.lat, f.lng))) }, onClick = { vm.routeToSaved(f) },
                    leading = {
                        if (isFav) TwoToneTile("star", if (Gimano.colors.isDark) Color(0xFF3A2E12) else Color(0xFFFFF1CC), TwoTonePalette.GOLD)
                        else TwoToneTile(f.kind, Gimano.colors.brandMist)
                    })
            }
            item {
                TextButton(onClick = { vm.openSearch(SearchPurpose.ADD_FAVORITE) }, Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_favorite_title), fontWeight = FontWeight.ExtraBold)
                }
            }
            item { SectionLabel(stringResource(R.string.categories)) }
            item {
                val grid = categories + Triple("more", stringResource(R.string.sc_more)) { vm.openSearch() }
                Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grid.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (icon, label, action) -> CategoryTile(icon, label, Modifier.weight(1f), onClick = { action() }) }
                        }
                    }
                }
            }
            if (!settings.privacyCardDismissed) item {
                PrivacyCard(onBackup = { vm.open(my.gimano.app.ui.Screen.Backup) }, onDismiss = vm::dismissPrivacyCard, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

/** One horizontally scrollable row of chips; at large font scales two rows (scrolling together). */
@Composable
private fun ChipRows(chips: List<HomeChip>, rows: Int, chipHeight: Dp, editLabel: String) {
    val split = if (rows >= 2) (chips.size + 1) / 2 else chips.size
    Column(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(HomeSheetLayout.CHIP_ROW_GAP.dp),
    ) {
        listOf(chips.take(split), chips.drop(split)).filter { it.isNotEmpty() }.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    key(c.key) {
                        PillChip(c.icon, c.label, c.tone, Modifier.height(chipHeight), onLongClick = c.onLongClick,
                            onLongClickLabel = if (c.onLongClick != null) editLabel else null, onClick = c.onClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun TwoToneTile(icon: String, bg: Color, palette: TwoTonePalette = TwoTonePalette.COBALT) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
        TwoToneIcon(icon, null, size = 28.dp, palette = palette)
    }
}

@Composable
private fun SectionLabel(t: String) {
    Text(t, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = GimanoType.label, color = Gimano.colors.textMuted)
}

/** "Where to?" search field: 52 dp (grows with very large text), pale fill with a 2 dp outline, and a mic button. */
@Composable
fun WhereToField(onClick: () -> Unit, onMic: () -> Unit, height: Dp = 52.dp) {
    val c = Gimano.colors
    val micLabel = stringResource(R.string.voice_search)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(height).clip(shape).background(if (c.isDark) c.surfaceAlt else c.background)
            .border(2.dp, c.outline, shape).clickable(onClick = onClick).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GIcon("search", null, size = 24.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.where_to), fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onMic).semantics { contentDescription = micLabel },
            contentAlignment = Alignment.Center,
        ) { GIcon("mic", null, size = 24.dp) }
    }
}

@Composable
fun IconTile(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Gimano.colors.brandMist), contentAlignment = Alignment.Center, content = content)
}

@Composable
fun RecentRow(r: Recent, me: GeoPoint?, onClick: () -> Unit) {
    PlaceRow("recent", r.name, r.address, me?.let { Formatters.distance(GeoMath.distanceM(it, GeoPoint(r.lat, r.lng))) }, onClick)
}

@Composable
fun PlaceRow(icon: String, title: String, subtitle: String, trailing: String?, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null, badge: String? = null) {
    val c = Gimano.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else IconTile { GIcon(icon, null, size = 26.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) Text(badge, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.brand)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
        }
    }
}

