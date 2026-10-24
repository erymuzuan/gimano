package my.gimano.app.ui.routes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.events.EventLimits
import my.gimano.app.core.events.EventTravelMode
import my.gimano.app.core.events.GeometrySource
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.routes.BuilderRoute
import my.gimano.app.core.routes.GpxWriter
import my.gimano.app.core.routes.DrawGesture
import my.gimano.app.core.routes.LegState
import my.gimano.app.core.routes.Pos
import my.gimano.app.core.routes.RouteAssembly
import my.gimano.app.core.routes.RouteProblem
import my.gimano.app.data.routes.BuilderTarget
import my.gimano.app.data.routes.BuilderTool
import my.gimano.app.data.routes.BuilderUi
import my.gimano.app.data.routes.RouteBuilder
import my.gimano.app.data.routes.StopRef
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.map.BuilderDraw
import my.gimano.app.ui.map.BuilderLine
import my.gimano.app.ui.map.BuilderMark
import my.gimano.app.ui.map.MapHandle
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.io.File

/*
 * Route builder ("Bina laluan"): full-screen map, one short hint, round undo / redo / ⋮ over a bottom sheet with
 * the totals, the tool (points or draw), "Ikut jalan", the travel mode and "Simpan". Icons first, few words.
 * Every map gesture has a non-map path: the point list (move up / down, delete, add at the map centre).
 */

// --- Map side: what to draw and the touch handling ---------------------------------------------------------

/** Finger state shared between the map's touch interceptor and the map layers (drag preview, line being drawn). */
@Stable
class BuilderTouch(private val builder: RouteBuilder, private val handle: MapHandle, private val hitPx: Float, private val slopPx: Float, private val stepPx: Float) {
    var drawing by mutableStateOf<List<GeoPoint>>(emptyList())
        private set
    var drag by mutableStateOf<Pair<Int, GeoPoint>?>(null)
        private set
    private var dragIndex: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private val draw = DrawGesture(stepPx)

    private fun hit(u: BuilderUi, x: Float, y: Float): Int? {
        var best: Int? = null
        var bestD = hitPx * hitPx
        // Last points first: a loop's end sits on its start.
        for (i in u.route.points.indices.reversed()) {
            val o = handle.project(u.route.points[i].geo) ?: continue
            val d = (o.x - x) * (o.x - x) + (o.y - y) * (o.y - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun finish(end: DrawGesture.End?) {
        drawing = emptyList()
        val line = end?.points?.mapNotNull { handle.unproject(it.x, it.y) }.orEmpty()
        if (line.size >= 2) builder.drawn(line.map(Pos::of))
    }

    /**
     * "Lukis": one finger draws, two fingers move and zoom the map ([DrawGesture]). When the second finger lands, the
     * map has not seen this gesture's first finger (it was consumed for drawing), so it gets a synthetic ACTION_DOWN for
     * it first and then every event of the gesture.
     */
    private fun onDrawTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draw.down(ev.x, ev.y)
                drawing = listOfNotNull(handle.unproject(ev.x, ev.y))
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (draw.phase != DrawGesture.Phase.DRAWING) return draw.phase != DrawGesture.Phase.MAP
                finish(draw.secondFinger())
                val first = MotionEvent.obtain(ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.getX(0), ev.getY(0), ev.metaState)
                try { v.onTouchEvent(first) } finally { first.recycle() }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draw.phase == DrawGesture.Phase.MAP) return false
                if (draw.move(ev.x, ev.y, ev.pointerCount)) handle.unproject(ev.x, ev.y)?.let { drawing = drawing + it }
                return draw.phase == DrawGesture.Phase.DRAWING
            }
            MotionEvent.ACTION_UP -> {
                val toMap = draw.phase == DrawGesture.Phase.MAP
                finish(draw.up(ev.x, ev.y))
                return !toMap
            }
            MotionEvent.ACTION_CANCEL -> {
                val toMap = draw.phase == DrawGesture.Phase.MAP
                draw.cancel(); drawing = emptyList()
                return !toMap
            }
            else -> return draw.phase != DrawGesture.Phase.MAP
        }
    }

    /** True = consumed (the map does not pan or click). */
    fun onTouch(v: View, ev: MotionEvent): Boolean {
        val u = builder.ui.value ?: return false
        // A drawing gesture stays one even if the tool changes mid-gesture.
        if (draw.phase != DrawGesture.Phase.IDLE || (u.tool == BuilderTool.DRAW && ev.actionMasked == MotionEvent.ACTION_DOWN)) return onDrawTouch(v, ev)
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                downX = x; downY = y; lastX = x; lastY = y
                dragIndex = hit(u, x, y)
                return dragIndex != null
            }
            MotionEvent.ACTION_MOVE -> {
                val i = dragIndex ?: return false
                if (!moved && (x - downX) * (x - downX) + (y - downY) * (y - downY) > slopPx * slopPx) moved = true
                if (moved) handle.unproject(x, y)?.let { drag = i to it }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val i = dragIndex ?: return false
                dragIndex = null
                val d = drag
                drag = null
                if (moved && d != null) builder.movePoint(i, Pos.of(d.second)) else builder.select(i)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val was = dragIndex != null
                dragIndex = null; drag = null
                return was
            }
            // A second finger while dragging a point is ignored (the map stays still).
            else -> return dragIndex != null
        }
    }
}

@Composable
fun rememberBuilderTouch(builder: RouteBuilder, handle: MapHandle): BuilderTouch {
    val d = LocalDensity.current.density
    return remember(builder, handle, d) { BuilderTouch(builder, handle, hitPx = 28f * d, slopPx = 8f * d, stepPx = 6f * d) }
}

/** The builder's lines and points for the map layers. Pending / unresolved legs draw nothing (never a fake road). */
fun builderDraw(u: BuilderUi, touch: BuilderTouch?): BuilderDraw {
    val r = u.route
    val drag = touch?.drag
    val lines = ArrayList<BuilderLine>()
    r.legs.forEachIndexed { i, l ->
        when (l.state) {
            LegState.ROUTED -> lines += BuilderLine(l.geometry.map { it.geo }, "routed")
            LegState.MATCHED -> {
                lines += BuilderLine(l.geometry.map { it.geo }, "routed")
                val d = l.drawn.orEmpty()
                l.unmatched.forEach { s -> if (s.to < d.size) lines += BuilderLine(d.subList(maxOf(0, s.from - 1), minOf(d.size, s.to + 2)).map { it.geo }, "unmatched") }
            }
            LegState.SKETCH -> lines += BuilderLine(l.geometry.map { it.geo }, "sketch")
            LegState.PENDING, LegState.UNRESOLVED -> {
                // The user's own drawn line stays visible (faint) while it is matched or after it failed.
                l.drawn?.let { d -> lines += BuilderLine(d.map { it.geo }, if (l.problem == RouteProblem.MATCH_UNAVAILABLE) "unmatched" else "drawn") }
            }
        }
    }
    touch?.drawing?.takeIf { it.size >= 2 }?.let { lines += BuilderLine(it, "drawing") }
    val n = r.points.size
    val marks = r.points.mapIndexed { i, p ->
        val role = when {
            i == 0 -> "start"
            i == n - 1 && n > 1 -> "end"
            p.stopId != null -> "stop"
            else -> "shape"
        }
        BuilderMark(if (drag?.first == i) drag.second else p.geo, role, selected = u.selected == i || drag?.first == i)
    }
    return BuilderDraw(lines, marks)
}

// --- Labels and icons --------------------------------------------------------------------------------------

private val MODES = listOf(EventTravelMode.CAR, EventTravelMode.MOTORCYCLE, EventTravelMode.BICYCLE, EventTravelMode.WALK)

@Composable
internal fun modeLabel(m: String): String = stringResource(when (m) {
    "motorcycle" -> R.string.rb_mode_motorcycle
    "bicycle" -> R.string.rb_mode_bicycle
    "walk" -> R.string.rb_mode_walk
    else -> R.string.rb_mode_car
})

/** The app's own duotone travel-mode icon. */
@Composable
internal fun ModeIcon(m: String, contentDescription: String?, size: androidx.compose.ui.unit.Dp = 24.dp, onBrand: Boolean = false) {
    val c = Gimano.colors
    GIcon(if (m in setOf("car", "motorcycle", "bicycle", "walk")) m else "car", contentDescription, size = size,
        stroke = if (onBrand) c.brandOn else c.brandInk, fill = if (onBrand) c.brandTint else c.brandTint)
}

@Composable
private fun pointLabel(u: BuilderUi, i: Int): String {
    val p = u.route.points[i]
    val stop = p.stopId?.let { id -> u.stops.firstOrNull { it.id == id } }
    val n = u.route.points.size
    val role = when {
        stop != null -> stop.label.ifBlank { stringResource(R.string.host_stop_unnamed) }
        i == 0 -> stringResource(R.string.rb_point_start)
        i == n - 1 && n > 1 -> stringResource(R.string.rb_point_end)
        else -> stringResource(R.string.rb_point_shape)
    }
    return stringResource(R.string.rb_point_desc, stringResource(R.string.rb_point_n, i + 1), role)
}

// --- The overlay -------------------------------------------------------------------------------------------

/**
 * Everything over the map while building. [eventTitle] is the event's title (event context) for the save sheet.
 * [onClose] leaves the builder; [onDeleteRoute] removes an event route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteBuilderOverlay(
    builder: RouteBuilder, handle: MapHandle, touch: BuilderTouch, eventTitle: String?, eventDays: List<String>,
    onBack: () -> Unit, onClose: () -> Unit, onDeleteRoute: (String) -> Unit,
    /** Event context: keep a copy in "Laluan saya" instead (name, route); null hides the choice. */
    onSaveToMine: (suspend (String, BuilderRoute) -> Unit)? = null,
) {
    val c = Gimano.colors
    val lang = currentLang()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val u = builder.ui.collectAsStateWithLifecycle().value ?: return
    val r = u.route
    val sum = remember(r) { RouteAssembly.summary(r) }
    var menu by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var nameOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var gpxAsk by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val forEvent = u.target == BuilderTarget.Event
    val untitled = stringResource(R.string.rb_untitled)

    Box(Modifier.fillMaxSize()) {
        // Top: back, the route's mode + name, one short hint.
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f).clip(RoundedCornerShape(Radius.pill)).background(c.surface).clickable { nameOpen = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModeIcon(r.mode, stringResource(R.string.rb_route_mode, modeLabel(r.mode)), size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(r.title.ifBlank { stringResource(R.string.rb_new) }, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                        color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (u.computing) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "" }, color = c.brand, strokeWidth = 2.dp)
                }
            }
            val hint = when {
                touch.drawing.isNotEmpty() -> R.string.rb_hint_drawing
                u.tool == BuilderTool.DRAW -> R.string.rb_hint_draw
                r.points.isEmpty() -> R.string.rb_hint_start
                else -> R.string.rb_hint_next
            }
            Text(stringResource(hint), Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(Radius.pill))
                .background(c.navBanner).padding(horizontal = 14.dp, vertical = 7.dp), color = c.navBannerOn, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // Floating round buttons above the sheet.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End) {
                RoundButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.rb_undo), enabled = u.canUndo) { builder.undo() }
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.rb_redo), enabled = u.canRedo) { builder.redo() }
                Spacer(Modifier.width(10.dp))
                Box {
                    RoundButton(Icons.Filled.MoreVert, stringResource(R.string.rb_more)) { menu = true }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        MenuRow(Icons.Filled.SwapVert, R.string.rb_reverse, r.points.size >= 2) { menu = false; builder.reverse() }
                        MenuRow(Icons.Filled.SyncAlt, R.string.rb_out_back, r.points.size >= 2) { menu = false; builder.outAndBack() }
                        MenuRow(Icons.Filled.Loop, R.string.rb_close_loop, r.points.size >= 2 && r.points.first().pos != r.points.last().pos) { menu = false; builder.closeLoop() }
                        MenuRow(Icons.Filled.FormatListNumbered, R.string.rb_point_list, true) { menu = false; listOpen = true }
                        MenuRow(Icons.Filled.Edit, R.string.rb_name_day, true) { menu = false; nameOpen = true }
                        MenuRow(Icons.Filled.DeleteSweep, R.string.rb_clear, r.points.isNotEmpty()) { menu = false; builder.clear() }
                        if (forEvent) MenuRow(Icons.Filled.Delete, R.string.rb_delete_route, true) { menu = false; askDelete = true }
                    }
                }
            }
            BuilderSheet(u, sum, lang, builder, onSave = { saveOpen = true })
        }
    }

    u.selected?.let { i -> if (i in r.points.indices) PointSheet(u, i, builder) }
    if (listOpen) PointListSheet(u, builder, onDismiss = { listOpen = false }, centre = { handle.bounds()?.center })
    if (nameOpen) NameSheet(u, forEvent, eventDays, builder, onDismiss = { nameOpen = false })
    if (saveOpen) SaveSheet(u, sum, lang, eventTitle, onDismiss = { saveOpen = false },
        onShareGpx = { gpxAsk = true },
        onSave = { name ->
            if (!forEvent || name != r.title) builder.setTitle(if (!forEvent && name.isBlank()) untitled else name)
            scope.launch { if (builder.save()) { saveOpen = false; onClose() } }
        },
        onSaveToMine = onSaveToMine?.let { f -> { name: String ->
            scope.launch { f(name.ifBlank { untitled }, r.copy(id = my.gimano.app.core.events.EventIds.newItemId(), title = name.ifBlank { untitled },
                points = r.points.map { it.copy(stopId = null) })); saveOpen = false; onClose() }
        } })
    if (gpxAsk) AlertDialog(
        onDismissRequest = { gpxAsk = false },
        icon = { Icon(Icons.Filled.Lock, null, tint = c.brand) },
        text = { Text(stringResource(R.string.rb_gpx_confirm)) },
        confirmButton = { TextButton(onClick = { gpxAsk = false; shareGpx(context, r.title.ifBlank { untitled }, r) }) {
            Text(stringResource(R.string.rb_share_gpx), fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = { gpxAsk = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        text = { Text(stringResource(R.string.rb_delete_confirm)) },
        confirmButton = { TextButton(onClick = { askDelete = false; onDeleteRoute(r.id) }) {
            Text(stringResource(R.string.delete), fontWeight = FontWeight.ExtraBold, color = c.report) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (u.confirmDiscard) AlertDialog(
        onDismissRequest = builder::dismissDiscard,
        text = { Text(stringResource(R.string.rb_discard)) },
        confirmButton = { TextButton(onClick = { builder.dismissDiscard(); onClose() }) {
            Text(stringResource(R.string.rb_discard_yes), fontWeight = FontWeight.ExtraBold, color = c.report) } },
        dismissButton = { TextButton(onClick = builder::dismissDiscard) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Gimano.colors
    Box(Modifier.size(52.dp).clip(CircleShape).background(c.surface).border(1.dp, c.outline, CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) c.text else c.outline)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val c = Gimano.colors
    DropdownMenuItem(enabled = enabled, leadingIcon = { Icon(icon, null, tint = if (enabled) c.brand else c.outline) },
        text = { Text(stringResource(label), fontWeight = FontWeight.Bold) }, onClick = onClick)
}

@Composable
private fun BuilderSheet(u: BuilderUi, sum: my.gimano.app.core.routes.RouteSummary, lang: my.gimano.app.core.format.Lang, builder: RouteBuilder, onSave: () -> Unit) {
    val c = Gimano.colors
    val r = u.route
    val unavailable = stringResource(R.string.rb_unavailable)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)).background(c.surface)
        .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp)) {
        // Totals: icons instead of words (TalkBack reads the label with the value).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat(Icons.Filled.Straighten, stringResource(R.string.rb_distance), Formatters.distance(sum.distanceM.toDouble()))
            Spacer(Modifier.width(16.dp))
            Stat(Icons.Filled.Schedule, stringResource(R.string.rb_time), sum.durationS?.let { Formatters.duration(it.toDouble(), lang) } ?: "–",
                spoken = if (sum.durationS == null) unavailable else null)
            Spacer(Modifier.weight(1f))
            if (sum.source != null && sum.source != GeometrySource.ROUTED) Badge(stringResource(R.string.rb_sketch))
        }
        StatusBanner(u, sum, builder)
        // Tool, "Ikut jalan".
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val tools = stringResource(R.string.rb_tools)
            Row(Modifier.clip(RoundedCornerShape(Radius.pill)).background(c.surfaceAlt).padding(3.dp).selectableGroup().semantics { contentDescription = tools }) {
                ToolSegment(Icons.Filled.Timeline, stringResource(R.string.rb_tool_points), u.tool == BuilderTool.POINTS) { builder.setTool(BuilderTool.POINTS) }
                ToolSegment(Icons.Filled.Gesture, stringResource(R.string.rb_tool_draw), u.tool == BuilderTool.DRAW) { builder.setTool(BuilderTool.DRAW) }
            }
            Spacer(Modifier.weight(1f))
            val follow = stringResource(R.string.rb_follow_roads)
            Row(Modifier.clip(RoundedCornerShape(Radius.pill)).clickable(role = Role.Switch) { builder.setFollowRoads(!r.followRoads) }
                .semantics(mergeDescendants = true) { contentDescription = follow }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AddRoad, null, tint = if (r.followRoads) c.brand else c.textMuted)
                Spacer(Modifier.width(6.dp))
                Switch(r.followRoads, onCheckedChange = null)
            }
        }
        // Start at an event place (empty route, event context).
        if (r.points.isEmpty() && u.stops.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                u.stops.forEach { s ->
                    val label = s.label.ifBlank { stringResource(R.string.host_stop_unnamed) }
                    val spoken = stringResource(R.string.rb_start_at_stop, label)
                    AssistChip(onClick = { builder.addStopPoint(s) }, modifier = Modifier.semantics { contentDescription = spoken },
                        leadingIcon = { Icon(Icons.Filled.Place, null, tint = c.brand, modifier = Modifier.size(18.dp)) },
                        label = { Text(label, maxLines = 1) })
                }
            }
        }
        // Travel mode (icons) and "Simpan".
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val modes = stringResource(R.string.rb_modes)
            Row(Modifier.weight(1f).selectableGroup().semantics { contentDescription = modes }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MODES.forEach { m ->
                    val on = r.mode == m.wire
                    val label = modeLabel(m.wire)
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(Radius.md)).background(if (on) c.brand else c.brandMist)
                        .selectable(on, role = Role.RadioButton) { builder.setMode(m.wire) }.semantics { contentDescription = label },
                        contentAlignment = Alignment.Center) { ModeIcon(m.wire, null, size = 26.dp, onBrand = on) }
                }
            }
            Button(onClick = onSave, enabled = r.points.size >= 2, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.rb_save), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, value: String, spoken: String? = null, muted: Boolean = false) {
    val c = Gimano.colors
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: ${spoken ?: value}" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.brand, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, fontSize = if (muted) 13.sp else 17.sp, fontWeight = if (muted) FontWeight.Bold else FontWeight.Black, color = if (muted) c.textMuted else c.text)
    }
}

@Composable
private fun Badge(text: String) {
    val c = Gimano.colors
    Text(text, Modifier.clip(RoundedCornerShape(Radius.pill)).border(1.5.dp, c.brand, RoundedCornerShape(Radius.pill)).padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.brand)
}

@Composable
private fun ToolSegment(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Gimano.colors
    Box(Modifier.size(width = 56.dp, height = 42.dp).clip(RoundedCornerShape(Radius.pill)).background(if (on) c.brand else c.surfaceAlt)
        .selectable(on, role = Role.RadioButton, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) c.brandOn else c.textMuted)
    }
}

/** Legs to calculate, failures, unmatched drawing: short line + actions. Never a straight line in their place. */
@Composable
private fun StatusBanner(u: BuilderUi, sum: my.gimano.app.core.routes.RouteSummary, builder: RouteBuilder) {
    val c = Gimano.colors
    val r = u.route
    val unmatched = r.legs.indexOfFirst { it.hasUnmatched }
    val noMatch = r.legs.indexOfFirst { it.state == LegState.UNRESOLVED && it.problem == RouteProblem.MATCH_UNAVAILABLE }
    val failed = r.legs.filter { it.state == LegState.UNRESOLVED && it.problem != RouteProblem.MATCH_UNAVAILABLE }
    when {
        noMatch >= 0 -> Banner(stringResource(R.string.rb_match_unavailable)) {
            BannerAction(stringResource(R.string.rb_keep_sketch)) { builder.keepAsSketch(noMatch) }
            if (noMatch == r.legs.size - 1) BannerAction(stringResource(R.string.rb_delete_point)) { builder.deletePoint(noMatch + 1) }
        }
        unmatched >= 0 -> Banner(stringResource(R.string.rb_unmatched)) {
            if (unmatched == r.legs.size - 1) IconAction(Icons.Filled.Gesture, stringResource(R.string.rb_redraw)) { builder.redraw(unmatched) }
            IconAction(Icons.Filled.AddLocationAlt, stringResource(R.string.rb_add_point)) { builder.addPointInGap(unmatched) }
            IconAction(Icons.Filled.Timeline, stringResource(R.string.rb_keep_sketch)) { builder.keepAsSketch(unmatched) }
        }
        failed.isNotEmpty() -> {
            val offline = failed.any { it.problem == RouteProblem.OFFLINE || it.problem == RouteProblem.UNAVAILABLE }
            Banner(stringResource(R.string.rb_open_legs, sum.openLegs) + " · " +
                stringResource(if (offline) R.string.rb_offline else R.string.rb_no_route)) {
                BannerAction(stringResource(R.string.rb_retry), Icons.Filled.Refresh) { builder.retry() }
                val li = r.legs.indexOfFirst { it.state == LegState.UNRESOLVED }
                if (li >= 0) BannerAction(stringResource(R.string.rb_keep_sketch)) { builder.keepAsSketch(li) }
            }
        }
        sum.pendingLegs > 0 -> Text(stringResource(R.string.rb_open_legs, sum.pendingLegs), Modifier.padding(top = 8.dp), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = c.textMuted)
    }
}

@Composable
private fun Banner(text: String, actions: @Composable RowScope.() -> Unit) {
    val c = Gimano.colors
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.reportTint.copy(alpha = 0.55f)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = c.report, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), content = actions)
    }
}

@Composable
private fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = Gimano.colors
    IconButton(onClick = onClick) { Icon(icon, description, tint = c.brand) }
}

@Composable
private fun BannerAction(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = Gimano.colors
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(16.dp), tint = c.brand); Spacer(Modifier.width(4.dp)) }
        Text(label, fontWeight = FontWeight.ExtraBold, color = c.brand)
    }
}

// --- Sheets --------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointSheet(u: BuilderUi, i: Int, builder: RouteBuilder) {
    val c = Gimano.colors
    val p = u.route.points[i]
    ModalBottomSheet(onDismissRequest = { builder.select(null) }, containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(pointLabel(u, i), style = GimanoType.title, color = c.text)
            u.stops.filter { it.id != p.stopId }.forEach { s ->
                ActionRow(Icons.Filled.Place, stringResource(R.string.rb_snap_to, s.label.ifBlank { stringResource(R.string.host_stop_unnamed) })) {
                    builder.snapToStop(i, s); builder.select(null)
                }
            }
            if (p.stopId != null) ActionRow(Icons.Filled.LinkOff, stringResource(R.string.rb_unlink)) { builder.unlinkStop(i) }
            Row(Modifier.padding(top = 4.dp)) {
                IconButton(onClick = { builder.reorderPoint(i, -1); builder.select(i - 1) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.host_move_up), tint = c.text) }
                IconButton(onClick = { builder.reorderPoint(i, 1); builder.select(i + 1) }, enabled = i < u.route.points.size - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.host_move_down), tint = c.text) }
            }
            ActionRow(Icons.Filled.Delete, stringResource(R.string.rb_delete_point), danger = true) { builder.deletePoint(i) }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) c.report else c.brand)
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = if (danger) c.report else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Senarai titik": the non-map path for every gesture (TalkBack / no touch precision). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointListSheet(u: BuilderUi, builder: RouteBuilder, onDismiss: () -> Unit, centre: () -> GeoPoint?) {
    val c = Gimano.colors
    val live = builder.ui.collectAsStateWithLifecycle().value ?: u
    val pts = live.route.points
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.rb_point_list), style = GimanoType.title, color = c.text)
            pts.indices.forEach { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pointLabel(live, i), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 2)
                    IconButton(onClick = { builder.reorderPoint(i, -1) }, enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.host_move_up), tint = c.textMuted) }
                    IconButton(onClick = { builder.reorderPoint(i, 1) }, enabled = i < pts.size - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.host_move_down), tint = c.textMuted) }
                    IconButton(onClick = { builder.deletePoint(i) }) { Icon(Icons.Filled.Delete, stringResource(R.string.rb_delete_point), tint = c.report) }
                }
            }
            ActionRow(Icons.Filled.AddLocationAlt, stringResource(R.string.rb_add_at_centre)) {
                centre()?.let { builder.edit { r -> my.gimano.app.core.routes.RouteEdit.addPoint(r, Pos.of(it)) } }
            }
            live.stops.forEach { s ->
                ActionRow(Icons.Filled.Place, stringResource(R.string.rb_add_stop_point) + ": " + s.label.ifBlank { stringResource(R.string.host_stop_unnamed) }) { builder.addStopPoint(s) }
            }
            Text(stringResource(R.string.rb_privacy), fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSheet(u: BuilderUi, forEvent: Boolean, days: List<String>, builder: RouteBuilder, onDismiss: () -> Unit) {
    val c = Gimano.colors
    var name by remember { mutableStateOf(u.route.title) }
    ModalBottomSheet(onDismissRequest = { if (name != u.route.title) builder.setTitle(name); onDismiss() }, containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            OutlinedTextField(name, { v -> name = cap(v, EventLimits.ROUTE_TITLE_CHARS) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rb_name)) },
                singleLine = true, shape = RoundedCornerShape(Radius.md))
            if (forEvent && days.size > 1) {
                Text(stringResource(R.string.rb_day), style = GimanoType.label, color = c.brandInk, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(u.route.day == null, onClick = { builder.setDay(null) }, label = { Text(stringResource(R.string.rb_day_none)) })
                    days.forEachIndexed { k, d ->
                        FilterChip(u.route.day == d, onClick = { builder.setDay(d) }, label = { Text("${stringResource(R.string.rb_day)} ${k + 1} · $d") })
                    }
                }
            }
            Button(onClick = { if (name != u.route.title) builder.setTitle(name); onDismiss() }, Modifier.padding(top = 16.dp).fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.done), fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(
    u: BuilderUi, sum: my.gimano.app.core.routes.RouteSummary, lang: my.gimano.app.core.format.Lang, eventTitle: String?,
    onDismiss: () -> Unit, onShareGpx: () -> Unit, onSave: (String) -> Unit, onSaveToMine: ((String) -> Unit)?,
) {
    val c = Gimano.colors
    val inEvent = u.target == BuilderTarget.Event
    var toMine by remember { mutableStateOf(!inEvent) }
    val forEvent = inEvent && !toMine
    var name by remember { mutableStateOf(u.route.title) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            Text(stringResource(R.string.rb_save_title), style = GimanoType.title, color = c.text)
            OutlinedTextField(name, { v -> name = cap(v, EventLimits.ROUTE_TITLE_CHARS) }, Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text(stringResource(R.string.rb_name)) }, singleLine = true, shape = RoundedCornerShape(Radius.md))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Stat(Icons.Filled.Straighten, stringResource(R.string.rb_distance), Formatters.distance(sum.distanceM.toDouble()))
                Spacer(Modifier.width(16.dp))
                Stat(Icons.Filled.Schedule, stringResource(R.string.rb_time), sum.durationS?.let { Formatters.duration(it.toDouble(), lang) } ?: "–")
            }
            val saveTo = stringResource(R.string.rb_save_to)
            Column(Modifier.padding(top = 12.dp).selectableGroup().semantics { contentDescription = saveTo }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inEvent) Target(Icons.Filled.Celebration, stringResource(R.string.rb_in_event_short), eventTitle?.ifBlank { null } ?: stringResource(R.string.host_untitled),
                    stringResource(R.string.rb_in_event_note), selected = !toMine, selectable = onSaveToMine != null) { toMine = false }
                if (!inEvent || onSaveToMine != null) Target(Icons.Filled.Lock, stringResource(R.string.my_routes), stringResource(R.string.rb_this_phone),
                    stringResource(R.string.rb_personal_note), selected = toMine, selectable = inEvent) { toMine = true }
            }
            if (sum.source != null && sum.source != GeometrySource.ROUTED)
                Text(stringResource(R.string.rb_sketch_note), Modifier.padding(top = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.report)
            if (sum.openLegs > 0)
                Text(stringResource(R.string.rb_open_legs, sum.openLegs), Modifier.padding(top = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.report)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!inEvent) OutlinedButton(onClick = onShareGpx, enabled = RouteAssembly.joinedGeometry(u.route.legs).size >= 2,
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md)) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.rb_share_gpx), fontWeight = FontWeight.ExtraBold)
                }
                Button(onClick = { if (inEvent && toMine) onSaveToMine?.invoke(name.trim()) else onSave(name.trim()) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                    Text(stringResource(R.string.rb_save), fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun Target(icon: ImageVector, title: String, sub: String, note: String, selected: Boolean, selectable: Boolean, onClick: () -> Unit) {
    val c = Gimano.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (selected) c.brandMist else c.surface)
        .border(if (selected) 2.dp else 1.dp, if (selected) c.brand else c.outline, shape)
        .then(if (selectable) Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick) else Modifier).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.brandInk, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("$title · $sub", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Text(note, fontSize = 13.sp, color = c.textMuted)
        }
    }
}

private fun cap(s: String, max: Int): String = if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

// --- GPX sharing ---------------------------------------------------------------------------------------------

private const val SHARED_DIR = "shared"

/** GPX 1.1 track of the drawn geometry, through the share sheet (FileProvider, cache/shared). Asked for first. */
internal fun shareGpx(context: Context, name: String, r: BuilderRoute) {
    val pts = RouteAssembly.joinedGeometry(r.legs).map { it.geo }
    if (pts.size < 2) return
    val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
    dir.listFiles { f -> f.name.endsWith(".gpx") }?.forEach { it.delete() }
    val f = File(dir, GpxWriter.fileName(name))
    f.writeText(GpxWriter.track(name, pts))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri("", uri)
    runCatching { context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}
