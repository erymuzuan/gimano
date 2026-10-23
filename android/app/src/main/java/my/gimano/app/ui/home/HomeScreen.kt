package my.gimano.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            if (!ui.following) RoundFab(Icons.Filled.MyLocation, stringResource(R.string.recenter), onClick = vm::recenter)
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

@Composable
fun HomeOverlay(ui: UiState, settings: AppSettings, vm: MainViewModel, onMic: () -> Unit) {
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // handle (8+5+12) + search 64 + gap 16 + 2 rows of tiles (76+6+20) + gaps + bottom padding
    val favs = ui.saved.filter { it.kind == "favorite" }
    val favRowH = if (favs.isNotEmpty()) Sizes.shortcutTile + 34.dp else 0.dp
    val collapsed = 25.dp + Sizes.searchField + 16.dp + (Sizes.shortcutTile + 30.dp) * 2 + favRowH + 12.dp + 16.dp + navInset
    val expanded = screenH * 0.82f
    val minPx = with(density) { collapsed.toPx() }
    val maxPx = with(density) { expanded.toPx() }
    val sheet = remember(minPx) { Animatable(minPx) }
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta -> scope.launch { sheet.snapTo((sheet.value - delta).coerceIn(minPx, maxPx)) } }
    val progress = ((sheet.value - minPx) / (maxPx - minPx)).coerceIn(0f, 1f)
    val sheetDp = with(density) { sheet.value.toDp() }
    val me = ui.myLocation?.point
    val home = ui.saved.firstOrNull { it.kind == "home" }
    val work = ui.saved.firstOrNull { it.kind == "work" }

    fun category(cat: String) {
        vm.openSearch()
        vm.searchCategory(cat)
    }

    Box(Modifier.fillMaxSize()) {
        val selected = ui.alerts.firstOrNull { it.id == ui.selectedAlertId }
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomStart)
                .offset { IntOffset(0, -(sheet.value.roundToInt())) }
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
            ReportFab(Modifier.align(Alignment.BottomEnd)) { vm.openReport(true) }
        }

        SheetSurface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetDp)) {
            Column(
                Modifier.fillMaxWidth().draggable(
                    dragState, Orientation.Vertical,
                    onDragStopped = { v ->
                        val target = when {
                            v < -600 -> maxPx
                            v > 600 -> minPx
                            sheet.value > (minPx + maxPx) / 2 -> maxPx
                            else -> minPx
                        }
                        scope.launch { sheet.animateTo(target) }
                    },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                GrabHandle()
                Spacer(Modifier.height(12.dp))
                WhereToField(onClick = { vm.openSearch() }, onMic = onMic)
                Spacer(Modifier.height(16.dp))
                // 4-column shortcut grid: Home, Work, Fuel, Food / Surau, Parking, EV, Recent
                val rows = listOf(
                    listOf(
                        Triple("home", home?.name?.takeIf { it != "home" } ?: stringResource(R.string.home)) { if (home != null) vm.routeToSaved(home) else vm.openSearch(SearchPurpose.SET_HOME) },
                        Triple("work", work?.name?.takeIf { it != "work" } ?: stringResource(R.string.work)) { if (work != null) vm.routeToSaved(work) else vm.openSearch(SearchPurpose.SET_WORK) },
                        Triple("fuel", stringResource(R.string.sc_fuel)) { category("fuel") },
                        Triple("food", stringResource(R.string.sc_food)) { category("restaurant") },
                    ),
                    listOf(
                        Triple("surau", stringResource(R.string.sc_surau)) { category("mosque") },
                        Triple("parking", stringResource(R.string.sc_parking)) { category("parking") },
                        Triple("ev", stringResource(R.string.sc_ev)) { category("ev_charging") },
                        Triple("recent", stringResource(R.string.sc_recent)) { scope.launch { sheet.animateTo(maxPx) }; Unit },
                    ),
                )
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { (icon, label, action) ->
                            // Long-press Home/Work to change or remove them.
                            val sp = when (icon) { "home" -> home; "work" -> work; else -> null }
                            ShortcutTile(icon, label, Modifier.width(84.dp), onLongClick = sp?.let { { vm.editFavorite(it) } }, onClick = { action() })
                        }
                    }
                }
                if (favs.isNotEmpty()) {
                    // Labelled favourites (Kampung, Rumah Mertua…): tap to go, long-press to edit/reorder/delete.
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        items(favs, key = { it.id }) { f ->
                            ShortcutTile(f.icon, f.name, Modifier.width(84.dp), onLongClick = { vm.editFavorite(f) }, onClick = { vm.routeToSaved(f) })
                        }
                        item { ShortcutTile("star", stringResource(R.string.add_favorite), Modifier.width(84.dp), onClick = { vm.openSearch(SearchPurpose.ADD_FAVORITE) }) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).alpha((progress * 3f).coerceAtMost(1f)), contentPadding = PaddingValues(bottom = navInset + 16.dp)) {
                if (!settings.privacyCardDismissed) item {
                    PrivacyCard(onBackup = { vm.open(my.gimano.app.ui.Screen.Backup) }, onDismiss = vm::dismissPrivacyCard, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                item { SectionLabel(stringResource(R.string.recents)) }
                items(ui.recents, key = { it.id }) { r -> RecentRow(r, me) { vm.routeToRecent(r) } }
                if (favs.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.saved_places)) }
                    items(favs, key = { "f" + it.id }) { f ->
                        PlaceRow(f.icon, f.name, f.address, me?.let { Formatters.distance(GeoMath.distanceM(it, GeoPoint(f.lat, f.lng))) }, onClick = { vm.routeToSaved(f) })
                    }
                }
                item {
                    TextButton(onClick = { vm.openSearch(SearchPurpose.ADD_FAVORITE) }, Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_favorite_title), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(t: String) {
    Text(t, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = GimanoType.label, color = Gimano.colors.textMuted)
}

/** 64 dp "Where to?" search field with a 30 dp search glyph and a mic button. */
@Composable
fun WhereToField(onClick: () -> Unit, onMic: () -> Unit) {
    val c = Gimano.colors
    val micLabel = stringResource(R.string.voice_search)
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(Sizes.searchField).clip(RoundedCornerShape(Radius.search)).background(c.surfaceAlt)
            .clickable(onClick = onClick).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GIcon("search", null, size = Sizes.searchIcon)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.where_to), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = c.text, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onMic).semantics { contentDescription = micLabel },
            contentAlignment = Alignment.Center,
        ) { GIcon("mic", null, size = 28.dp) }
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

