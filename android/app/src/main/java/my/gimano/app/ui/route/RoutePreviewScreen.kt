package my.gimano.app.ui.route

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.nav.RouteGeometry
import my.gimano.app.data.model.AvoidOptions
import my.gimano.app.data.model.Route
import my.gimano.app.data.settings.AppSettings
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.RoutePlan
import my.gimano.app.ui.common.FloatingSurface
import my.gimano.app.ui.common.Pill
import my.gimano.app.ui.common.SheetSurface
import my.gimano.app.ui.common.Labels
import my.gimano.app.ui.map.MapHandle
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.Radius
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

private const val COUNTDOWN_MS = 10_000

@Composable
fun RoutePreviewOverlay(plan: RoutePlan, settings: AppSettings, vm: MainViewModel, handle: MapHandle) {
    val c = Gimano.colors
    val lang = currentLang()
    Box(Modifier.fillMaxSize()) {
        // Duration bubbles anchored on each route.
        RouteBubbles(plan, handle, vm)

        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            // Header: Your location → Destination
            Surface(color = c.surface, shadowElevation = 6.dp, shape = RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg)) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c.brand))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.your_location), fontSize = 15.sp, color = c.textMuted)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Place, null, tint = c.report, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(plan.destination.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvoidDropdown(plan.avoid) { vm.cancelCountdown(); vm.setAvoid(it) }
                plan.departAt?.let { t ->
                    val clock = Formatters.clock(ZonedDateTime.ofInstant(t, ZoneId.systemDefault()), settings.use24h, lang)
                    Pill(stringResource(R.string.leave_at, clock), icon = Icons.Filled.Schedule, onClick = { vm.chooseDeparture(null) })
                }
            }
        }

        SheetSurface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
                VehicleTabs(plan.vehicle) { vm.cancelCountdown(); vm.setVehicle(it) }
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
                    else -> RouteInfo(r, plan)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = vm::openLeaveLater,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = c.surfaceAlt, contentColor = c.text),
                        enabled = !plan.loading && !plan.error,
                    ) { Text(stringResource(R.string.leave_later), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    GoNowButton(plan, Modifier.weight(1f), onGo = vm::startNavigation)
                }
            }
        }
    }
}

@Composable
private fun RouteInfo(r: Route, plan: RoutePlan) {
    val c = Gimano.colors
    val lang = currentLang()
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            Formatters.duration(r.effectiveDurationS, lang), fontSize = 30.sp, fontWeight = FontWeight.Bold,
            color = if (r.isBest) c.brand else c.text,
        )
        if (r.isBest) {
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
    val bits = buildList {
        Labels.traffic(r.trafficDescription)?.let { add(it) }
        add(
            when {
                r.hasTolls && r.tollEstimateMyr != null -> stringResource(R.string.tolls_estimate, String.format(java.util.Locale.US, "%.2f", r.tollEstimateMyr))
                r.hasTolls -> stringResource(R.string.has_tolls)
                else -> stringResource(R.string.no_tolls)
            },
        )
        if (r.alerts.isNotEmpty()) add(stringResource(R.string.reports_on_route, r.alerts.size))
    }
    Text(bits.joinToString(" · "), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.padding(top = 2.dp))
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
        Text(stringResource(R.string.go_now), color = if (enabled) c.brandOn else c.textMuted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VehicleTabs(vehicle: String, onSelect: (String) -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(Radius.pill)).background(c.surfaceAlt).padding(4.dp)) {
        listOf(Triple("car", R.string.car, Icons.Filled.DirectionsCar), Triple("motorcycle", R.string.motorbike, Icons.Filled.TwoWheeler)).forEach { (v, label, icon) ->
            val sel = v == vehicle
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Radius.pill)).background(if (sel) c.surface else Color.Transparent)
                    .clickable { onSelect(v) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (sel) c.brand else c.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) c.text else c.textMuted, fontSize = 15.sp)
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
            AvoidItem(R.string.avoid_tolls, Icons.Filled.Toll, avoid.tolls) { onChange(avoid.copy(tolls = it)) }
            AvoidItem(R.string.avoid_highways, Icons.Filled.AddRoad, avoid.highways) { onChange(avoid.copy(highways = it)) }
            AvoidItem(R.string.avoid_ferries, Icons.Filled.DirectionsBoat, avoid.ferries) { onChange(avoid.copy(ferries = it)) }
        }
    }
}

@Composable
private fun AvoidItem(label: Int, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { Checkbox(checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun RouteBubbles(plan: RoutePlan, handle: MapHandle, vm: MainViewModel) {
    val c = Gimano.colors
    val lang = currentLang()
    @Suppress("UNUSED_VARIABLE") val tick = handle.cameraVersion // recompose on camera moves
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
                    if (r.isBest) Text(stringResource(R.string.best), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else c.brand, lineHeight = 12.sp)
                    Text(Formatters.duration(r.effectiveDurationS, lang), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else c.text, lineHeight = 17.sp)
                }
            }
        }
    }
}
