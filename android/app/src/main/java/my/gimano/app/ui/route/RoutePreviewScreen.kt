package my.gimano.app.ui.route

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.nav.RouteGeometry
import my.gimano.app.core.trip.RouteLegs
import my.gimano.app.core.trip.TollBadge
import my.gimano.app.core.trip.TravelMode
import my.gimano.app.data.model.AvoidOptions
import my.gimano.app.data.model.Route
import my.gimano.app.data.settings.AppSettings
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.RoutePlan
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.common.Labels
import my.gimano.app.ui.common.Pill
import my.gimano.app.ui.common.SheetSurface
import my.gimano.app.ui.map.MapHandle
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.sheets.tollBadgeText
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

private const val COUNTDOWN_MS = 10_000

@Composable
fun RoutePreviewOverlay(plan: RoutePlan, settings: AppSettings, vm: MainViewModel, handle: MapHandle) {
    val c = Gimano.colors
    val lang = currentLang()
    Box(Modifier.fillMaxSize()) {
        RouteBubbles(plan, handle, vm)

        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            Surface(color = c.surface, shadowElevation = 6.dp, shape = RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg)) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
                        StopsPanel(plan, settings, vm, Modifier.weight(1f).padding(top = 10.dp))
                        Overflow(vm)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.mode.isMotorised) AvoidDropdown(plan.avoid) { vm.cancelCountdown(); vm.setAvoid(it) }
                plan.departAt?.let { t ->
                    val clock = Formatters.clock(ZonedDateTime.ofInstant(t, ZoneId.systemDefault()), settings.use24h, lang)
                    Pill(stringResource(R.string.leave_at, clock), icon = Icons.Filled.Schedule, onClick = { vm.chooseDeparture(null) })
                }
            }
        }

        SheetSurface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
                ModeTabs(plan.mode) { vm.cancelCountdown(); vm.setMode(it) }
                Spacer(Modifier.height(14.dp))
                val r = plan.selected
                when {
                    plan.loading -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = c.brand, trackColor = c.surfaceAlt)
                        Text(stringResource(R.string.loading_routes), Modifier.padding(vertical = 22.dp), color = c.textMuted, fontSize = 17.sp)
                    }
                    plan.error || r == null -> {
                        Text(stringResource(R.string.route_error), Modifier.padding(vertical = 12.dp), color = c.text, fontSize = 17.sp)
                        OutlinedButton(onClick = { vm.setAvoid(plan.avoid) }) { Text(stringResource(R.string.retry)) }
                    }
                    else -> RouteInfo(r, plan, vm)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = vm::openLeaveLater,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = c.surfaceAlt, contentColor = c.text),
                        enabled = !plan.loading && !plan.error,
                    ) { Text(stringResource(R.string.leave_later), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
                    GoNowButton(plan, Modifier.weight(1f), onGo = vm::startNavigation)
                }
            }
        }
    }
}

@Composable
private fun Overflow(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more), tint = Gimano.colors.text) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plan_this_drive), fontWeight = FontWeight.Bold) },
                leadingIcon = { GIcon("recent", null, size = 24.dp) },
                onClick = { open = false; vm.planThisDrive() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_feedback), fontWeight = FontWeight.Bold) },
                leadingIcon = { GIcon("feedback", null, size = 24.dp) },
                onClick = { open = false; vm.requestFeedback("route_preview") },
            )
        }
    }
}

/** Your location → stops (drag to reorder, × to remove, ETA each) → destination, plus "Add a stop". */
@Composable
private fun StopsPanel(plan: RoutePlan, settings: AppSettings, vm: MainViewModel, modifier: Modifier) {
    val c = Gimano.colors
    val lang = currentLang()
    val etas = plan.selected?.let { r -> if (r.legs.size == plan.stops.size + 1) RouteLegs.stopEtas(r, plan.departAt ?: Instant.now()) else null }
    fun eta(i: Int) = etas?.getOrNull(i)?.let { Formatters.clock(ZonedDateTime.ofInstant(it, ZoneId.systemDefault()), settings.use24h, lang) }
    val density = LocalDensity.current
    val rowPx = with(density) { 44.dp.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(c.brand))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.your_location), fontSize = 15.sp, color = c.textMuted, fontWeight = FontWeight.SemiBold)
        }
        plan.stops.forEachIndexed { i, s ->
            val offset = if (dragging == i) dragDy.roundToInt() else 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(44.dp).offset { IntOffset(0, offset) }
                    .then(if (dragging == i) Modifier.shadow(6.dp, RoundedCornerShape(12.dp)).background(c.surface, RoundedCornerShape(12.dp)) else Modifier),
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(c.brandMist), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Black, color = c.brandInk)
                }
                Spacer(Modifier.width(6.dp))
                Text(s.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                eta(i)?.let { Text(it, fontSize = 13.sp, color = c.textMuted, fontWeight = FontWeight.Bold) }
                val dragLabel = stringResource(R.string.reorder_stop)
                Icon(
                    Icons.Filled.DragHandle, dragLabel, tint = c.textMuted,
                    modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(plan.stops) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = i; dragDy = 0f },
                            onDrag = { change, d -> change.consume(); dragDy += d.y },
                            onDragEnd = {
                                val to = (i + (dragDy / rowPx).roundToInt()).coerceIn(0, plan.stops.lastIndex)
                                dragging = -1; dragDy = 0f
                                vm.moveStop(i, to)
                            },
                            onDragCancel = { dragging = -1; dragDy = 0f },
                        )
                    }.semantics { contentDescription = dragLabel },
                )
                IconButton(onClick = { vm.removeStop(i) }, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, stringResource(R.string.remove_stop), tint = c.textMuted) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 34.dp)) {
            Icon(Icons.Filled.Place, null, tint = c.report, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(plan.destination.name, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Black, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (plan.stops.isNotEmpty()) eta(plan.stops.size)?.let { Text(it, fontSize = 13.sp, color = c.textMuted, fontWeight = FontWeight.Bold) }
        }
        if (plan.stops.size < RouteLegs.MAX_STOPS) {
            Row(
                Modifier.clip(RoundedCornerShape(Radius.pill)).clickable(onClick = vm::addStopInPreview).padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GIcon("add_stop", null, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_stop), fontWeight = FontWeight.ExtraBold, color = c.brand, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun RouteInfo(r: Route, plan: RoutePlan, vm: MainViewModel) {
    val c = Gimano.colors
    val lang = currentLang()
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Formatters.duration(r.effectiveDurationS, lang), fontSize = 30.sp, fontWeight = FontWeight.Black, color = if (r.isBest) c.brand else c.text)
        if (r.isBest && plan.routes.size > 1) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.best), Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(Radius.sm)).background(c.brand).padding(horizontal = 8.dp, vertical = 2.dp),
                color = c.brandOn, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(Formatters.distance(r.distanceM), fontSize = 17.sp, color = c.textMuted, modifier = Modifier.padding(bottom = 4.dp))
    }
    if (r.summary.isNotBlank()) Text(r.summary, fontSize = 16.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        val bits = buildList {
            if (plan.mode.isMotorised) Labels.traffic(r.trafficDescription)?.let { add(it) }
            if (r.alerts.isNotEmpty()) add(stringResource(R.string.reports_on_route, r.alerts.size))
        }
        Text(bits.joinToString(" · "), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.weight(1f))
        // Toll / fare badge (car & motorcycle only). Tap → plazas with fares and sources.
        val badge = if (plan.mode.isMotorised) tollBadgeText(TollBadge.of(r)) else null
        if (badge != null) {
            Row(
                Modifier.clip(RoundedCornerShape(Radius.pill)).background(c.brandMist).clickable { vm.cancelCountdown(); vm.openTolls(r) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GIcon("fare", null, size = 22.dp)
                Spacer(Modifier.width(6.dp))
                Text(badge, fontWeight = FontWeight.Black, fontSize = 15.sp, color = c.brandInk)
            }
        }
    }
}

/** Primary button that fills left→right as a countdown and auto-starts navigation. */
@Composable
private fun GoNowButton(plan: RoutePlan, modifier: Modifier, onGo: () -> Unit) {
    val c = Gimano.colors
    val progress = remember { Animatable(0f) }
    val enabled = !plan.loading && !plan.error && plan.selected != null
    LaunchedEffect(plan.countdownRunning, plan.selectedId, plan.loading) {
        if (plan.countdownRunning && enabled) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(COUNTDOWN_MS, easing = LinearEasing))
            onGo()
        } else {
            progress.snapTo(1f)
        }
    }
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier.height(56.dp).clip(shape)
            .background(if (enabled) c.brand.copy(alpha = 0.55f) else c.surfaceAlt)
            .clickable(enabled = enabled, onClick = onGo),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(c.brand))
        Text(stringResource(R.string.go_now), color = if (enabled) c.brandOn else c.textMuted, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Four big travel-mode tabs: Car, Motorcycle, Bicycle, Walk. */
@Composable
private fun ModeTabs(mode: TravelMode, onSelect: (TravelMode) -> Unit) {
    val c = Gimano.colors
    val labels = mapOf(TravelMode.CAR to R.string.car, TravelMode.MOTORCYCLE to R.string.motorbike, TravelMode.BICYCLE to R.string.bicycle, TravelMode.WALK to R.string.walk)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TravelMode.entries.forEach { m ->
            val sel = m == mode
            val label = stringResource(labels.getValue(m))
            Column(
                Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(Radius.tile))
                    .background(if (sel) c.brandMist else c.surfaceAlt)
                    .then(if (sel) Modifier.border(2.dp, c.brand, RoundedCornerShape(Radius.tile)) else Modifier)
                    .clickable { onSelect(m) }
                    .semantics { contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                GIcon(m.icon, null, size = 34.dp, stroke = if (sel) c.brandInk else c.textMuted, fill = if (sel) c.brandTint else c.outline)
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = if (sel) c.text else c.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AvoidDropdown(avoid: AvoidOptions, onChange: (AvoidOptions) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = listOf(avoid.tolls, avoid.highways, avoid.ferries).count { it }
    Box {
        Pill(
            stringResource(R.string.avoid) + if (active > 0) " ($active)" else "",
            icon = Icons.Filled.Tune,
            color = if (active > 0) Gimano.colors.brand else Gimano.colors.surface,
            textColor = if (active > 0) Gimano.colors.brandOn else Gimano.colors.text,
            onClick = { open = true },
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            AvoidItem(R.string.avoid_tolls, "toll", avoid.tolls) { onChange(avoid.copy(tolls = it)) }
            AvoidItem(R.string.avoid_highways, "lane", avoid.highways) { onChange(avoid.copy(highways = it)) }
            AvoidItem(R.string.avoid_ferries, "flood", avoid.ferries) { onChange(avoid.copy(ferries = it)) }
        }
    }
}

@Composable
private fun AvoidItem(label: Int, icon: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), fontWeight = FontWeight.Bold) },
        leadingIcon = { GIcon(icon, null, size = 24.dp) },
        trailingIcon = { Checkbox(checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun RouteBubbles(plan: RoutePlan, handle: MapHandle, vm: MainViewModel) {
    val c = Gimano.colors
    val lang = currentLang()
    @Suppress("UNUSED_VARIABLE") val tick = handle.cameraVersion
    val geoms = remember(plan.decoded) { plan.decoded.mapValues { RouteGeometry(it.value) } }
    Box(Modifier.fillMaxSize()) {
        plan.routes.forEachIndexed { i, r ->
            val g = geoms[r.id] ?: return@forEachIndexed
            if (g.points.size < 2) return@forEachIndexed
            val frac = when (i) { 0 -> 0.5; 1 -> 0.38; else -> 0.62 }
            val anchor = handle.project(g.pointAt(g.lengthM * frac).first) ?: return@forEachIndexed
            val selected = r.id == plan.selected?.id
            Box(
                Modifier.layout { m, cons ->
                    val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
                    layout(p.width, p.height) { p.place(anchor.x.roundToInt() - p.width / 2, anchor.y.roundToInt() - p.height - 6) }
                },
            ) {
                Column(
                    Modifier.shadow(4.dp, RoundedCornerShape(Radius.md)).clip(RoundedCornerShape(Radius.md))
                        .background(if (selected) c.routeActive else c.surface)
                        .clickable { vm.selectRoute(r.id) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (r.isBest && plan.routes.size > 1) Text(stringResource(R.string.best), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else c.brand, lineHeight = 12.sp)
                    Text(Formatters.duration(r.effectiveDurationS, lang), fontSize = 15.sp, fontWeight = FontWeight.Black, color = if (selected) Color.White else c.text, lineHeight = 17.sp)
                }
            }
        }
    }
}

