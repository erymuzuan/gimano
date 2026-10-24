package my.gimano.app.ui.events

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import my.gimano.app.BuildConfig
import my.gimano.app.R
import my.gimano.app.core.events.DraftIssue
import my.gimano.app.core.events.EventDiff
import my.gimano.app.core.events.EventLimits
import my.gimano.app.core.events.EventLinks
import my.gimano.app.core.events.EventPlan
import my.gimano.app.core.events.EventSchedule
import my.gimano.app.core.events.EventStop
import my.gimano.app.core.events.EventTransport
import my.gimano.app.core.events.HostDraft
import my.gimano.app.core.events.HostDraftRules
import my.gimano.app.core.events.HostStop
import my.gimano.app.core.events.ImportDecision
import my.gimano.app.core.events.InviteMessages
import my.gimano.app.core.events.ParentCheck
import my.gimano.app.core.events.StopChange
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.format.Lang
import my.gimano.app.data.events.EventImportUi
import my.gimano.app.data.events.HostEditor
import my.gimano.app.data.events.HostedEvent
import my.gimano.app.data.events.Publication
import my.gimano.app.data.model.SavedPlace
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
 * Host (organiser) side of Events: Acara baharu / edit (canvas EvCreate), Kongsi jemputan (EvShare), and the guest's
 * "plan changed" sheet (EvUpdate). Everything stays on this phone; nothing here makes a network call.
 */

// --- Labels -----------------------------------------------------------------------------------------------

@Composable
internal fun templateLabel(t: String): String = stringResource(when (t) {
    "kenduri" -> R.string.host_tpl_kenduri
    "dinner" -> R.string.host_tpl_dinner
    "company" -> R.string.host_tpl_company
    "ride" -> R.string.host_tpl_ride
    "convoy" -> R.string.host_tpl_convoy
    "trip" -> R.string.host_tpl_trip
    "meeting" -> R.string.host_tpl_meeting
    "seminar" -> R.string.host_tpl_seminar
    "picnic" -> R.string.host_tpl_picnic
    "hike" -> R.string.host_tpl_hike
    else -> R.string.host_tpl_other
})

@Composable
internal fun kindLabel(k: String): String = stringResource(when (k) {
    "parking" -> R.string.host_kind_parking
    "dropoff" -> R.string.host_kind_dropoff
    "entrance" -> R.string.host_kind_entrance
    "venue" -> R.string.host_kind_venue
    "meal" -> R.string.host_kind_meal
    "checkpoint" -> R.string.host_kind_checkpoint
    "fuel" -> R.string.host_kind_fuel
    "water" -> R.string.host_kind_water
    "rest" -> R.string.host_kind_rest
    "prayer" -> R.string.host_kind_prayer
    "accommodation" -> R.string.host_kind_accommodation
    "start" -> R.string.host_kind_start
    "finish" -> R.string.host_kind_finish
    else -> R.string.host_kind_other
})

@Composable
internal fun issueText(i: DraftIssue): String {
    val n = (i.stop ?: 0) + 1
    return when (i.code) {
        DraftIssue.Code.TITLE_REQUIRED -> stringResource(R.string.host_err_title)
        DraftIssue.Code.TITLE_TOO_LONG -> stringResource(R.string.host_err_too_long_n, EventLimits.TITLE_CHARS)
        DraftIssue.Code.ALIAS_TOO_LONG -> stringResource(R.string.host_err_too_long_n, EventLimits.ALIAS_CHARS)
        DraftIssue.Code.DESCRIPTION_TOO_LONG -> stringResource(R.string.host_err_too_long_n, EventLimits.DESCRIPTION_CHARS)
        DraftIssue.Code.BAD_TIME -> stringResource(R.string.host_err_time)
        DraftIssue.Code.TOO_LONG_EVENT -> stringResource(R.string.host_err_14_days)
        DraftIssue.Code.EXPIRED -> stringResource(R.string.host_err_expired)
        DraftIssue.Code.NO_STOPS -> stringResource(R.string.host_err_no_stops)
        DraftIssue.Code.TOO_MANY_STOPS -> stringResource(R.string.host_err_too_many_stops, EventLimits.MAX_STOPS)
        DraftIssue.Code.STOP_LABEL_REQUIRED -> stringResource(R.string.host_err_stop_label, n)
        DraftIssue.Code.STOP_LABEL_TOO_LONG, DraftIssue.Code.STOP_NOTES_TOO_LONG, DraftIssue.Code.STOP_AUDIENCE_TOO_LONG ->
            stringResource(R.string.host_err_stop_too_long, n)
        DraftIssue.Code.STOP_POSITION -> stringResource(R.string.host_err_stop_position, n)
        DraftIssue.Code.INVALID -> stringResource(R.string.host_err_invalid)
        DraftIssue.Code.ROUTE_NOT_READY -> stringResource(R.string.host_err_route_pending, (i.route ?: 0) + 1)
        DraftIssue.Code.ROUTE_UNMATCHED -> stringResource(R.string.host_err_route_unmatched, (i.route ?: 0) + 1)
        DraftIssue.Code.TOO_MANY_ROUTES -> stringResource(R.string.host_err_too_many_routes, EventLimits.MAX_ROUTES)
        DraftIssue.Code.TOO_MANY_ROUTE_POINTS -> stringResource(R.string.host_err_route_points, EventLimits.MAX_POLYLINE_POINTS)
        DraftIssue.Code.INFO_TOO_LONG -> stringResource(R.string.host_err_too_long_n, EventLimits.INFO_TEXT_CHARS)
        DraftIssue.Code.TOO_MANY_INFO -> stringResource(R.string.ew_err_too_many_info, EventLimits.MAX_INFO_BLOCKS)
        DraftIssue.Code.AGENDA_INVALID -> stringResource(R.string.ew_err_agenda)
        DraftIssue.Code.STOP_TIME -> stringResource(R.string.ew_err_stop_time, n)
    }
}

internal fun chars(s: String) = s.codePointCount(0, s.length)

/** Caps typed text at [max] code points (§4 counts code points). */
internal fun cap(s: String, max: Int): String = if (chars(s) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

// --- List row ------------------------------------------------------------------------------------------

@Composable
internal fun HostedRow(h: HostedEvent, use24h: Boolean, lang: Lang, onClick: () -> Unit) {
    val c = Gimano.colors
    val plan = remember(h.draft) { runCatching { HostDraftRules.toPlan(h.draft, 1, null, Instant.EPOCH) }.getOrNull() }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.brand), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Celebration, null, tint = c.brandOn)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.draft.title.ifBlank { stringResource(R.string.host_untitled) }, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            plan?.let { Text(eventWhen(it, use24h, lang), fontSize = 14.sp, color = c.textMuted) }
            val status = when {
                h.published == null -> stringResource(R.string.host_status_draft)
                h.changed -> stringResource(R.string.host_status_changed, h.published.rev.toInt())
                else -> stringResource(R.string.host_status_published, h.published.rev.toInt())
            }
            Text(pluralStringResource(R.plurals.event_stops_n, h.draft.stops.size, h.draft.stops.size) + " · " + status, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = if (h.published == null || h.changed) c.brandInk else c.textMuted)
        }
    }
}

// --- Shared editor pieces (used by the wizard, WizardScreens.kt) -----------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickPersonalRouteSheet(store: my.gimano.app.data.routes.PersonalRouteStore, onDismiss: () -> Unit, onPick: (my.gimano.app.data.routes.PersonalRoute) -> Unit) {
    val c = Gimano.colors
    val routes by remember(store) { store.routes() }.collectAsStateWithLifecycle(initialValue = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.rb_pick_route), style = GimanoType.title, color = c.text)
            if (routes.isEmpty()) Text(stringResource(R.string.my_routes_none), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
            routes.filter { it.route.points.size >= 2 }.forEach { p ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(p) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    my.gimano.app.ui.routes.ModeIcon(p.route.mode, my.gimano.app.ui.routes.modeLabel(p.route.mode))
                    Spacer(Modifier.width(14.dp))
                    Text(p.name.ifBlank { stringResource(R.string.rb_untitled) }, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formatters.distance(my.gimano.app.core.routes.RouteAssembly.summary(p.route).distanceM.toDouble()), fontSize = 14.sp, color = c.textMuted)
                }
            }
        }
    }
}

@Composable
internal fun FieldButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Gimano.colors
    Column(modifier.clip(RoundedCornerShape(Radius.md)).border(1.dp, c.outline, RoundedCornerShape(Radius.md)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun StopCard(s: HostStop, i: Int, n: Int, bad: Boolean, onClick: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, modifier: Modifier = Modifier) {
    val c = Gimano.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface)
        .then(if (bad) Modifier.border(2.dp, c.report, RoundedCornerShape(Radius.md)) else Modifier)
        .clickable(onClick = onClick).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.brandMist), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Place, null, tint = c.brandInk)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label.ifBlank { stringResource(R.string.host_stop_unnamed) }, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = if (s.label.isBlank()) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(kindLabel(s.kind), s.notes.takeIf { it.isNotBlank() }).joinToString(" · ")
            Text(meta, fontSize = 13.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.audience.isNotBlank()) Text(s.audience, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.brandInk)
        }
        Column {
            IconButton(onClick = onUp, enabled = i > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.host_move_up), tint = c.textMuted) }
            IconButton(onClick = onDown, enabled = i < n - 1, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.host_move_down), tint = c.textMuted) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddStopSheet(saved: List<SavedPlace>, onDismiss: () -> Unit, onMap: () -> Unit, onSearch: () -> Unit, onSaved: (SavedPlace) -> Unit) {
    val c = Gimano.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.host_add_stop), style = GimanoType.title, color = c.text)
            SheetRow(Icons.Filled.Map, stringResource(R.string.host_add_on_map), onMap)
            SheetRow(Icons.Filled.Search, stringResource(R.string.host_add_search), onSearch)
            if (saved.isNotEmpty()) {
                Section(stringResource(R.string.host_add_saved))
                saved.forEach { p -> SheetRow(Icons.Filled.Star, p.name, { onSaved(p) }, p.address) }
            }
            Text(stringResource(R.string.host_add_note), fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
internal fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit, sub: String = "") {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.brand, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StopSheet(s: HostStop, editor: HostEditor, onDismiss: () -> Unit, onEntrance: () -> Unit, journey: Boolean = false, days: Int = 1,
    use24h: Boolean = true) {
    val c = Gimano.colors
    var kindOpen by remember { mutableStateOf(false) }
    var pickArrive by remember { mutableStateOf(false) }
    if (pickArrive) {
        val init = my.gimano.app.core.events.HostAgenda.time(s.arrive) ?: LocalTime.of(9, 0)
        val st = rememberTimePickerState(init.hour, init.minute, is24Hour = use24h)
        AlertDialog(onDismissRequest = { pickArrive = false }, text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = {
                editor.updateStop(s.id) { it.copy(arrive = "%02d:%02d".format(st.hour, st.minute)) }; pickArrive = false
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickArrive = false }) { Text(stringResource(R.string.cancel)) } })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            Text(stringResource(R.string.host_stop_title), style = GimanoType.label, color = c.brandInk)
            OutlinedTextField(s.label, { v -> editor.updateStop(s.id) { it.copy(label = cap(v, EventLimits.STOP_LABEL_CHARS)) } }, Modifier.fillMaxWidth().padding(top = 6.dp),
                label = { Text(stringResource(R.string.host_stop_label)) }, singleLine = true, shape = RoundedCornerShape(Radius.md))
            Box(Modifier.padding(top = 10.dp)) {
                FieldButton(stringResource(R.string.host_stop_kind), kindLabel(s.kind), Modifier.fillMaxWidth()) { kindOpen = true }
                DropdownMenu(kindOpen, onDismissRequest = { kindOpen = false }) {
                    HostDraft.KINDS.forEach { k ->
                        DropdownMenuItem(text = { Text(kindLabel(k), fontWeight = FontWeight.Bold) }, onClick = { kindOpen = false; editor.updateStop(s.id) { it.copy(kind = k) } })
                    }
                }
            }
            if (journey) {
                // Timeline: arrival time, minutes there, and the day of a multi-day trip.
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldButton(stringResource(R.string.ew_stop_arrive), s.arrive.ifBlank { "–" }, Modifier.weight(1f)) { pickArrive = true }
                    OutlinedTextField(s.dwellMin?.toString().orEmpty(), { v ->
                        val n = v.filter(Char::isDigit).take(5).toIntOrNull()?.coerceAtMost(EventLimits.MAX_DWELL_MIN.toInt())
                        editor.updateStop(s.id) { it.copy(dwellMin = n?.takeIf { m -> m > 0 }) }
                    }, Modifier.weight(1f), label = { Text(stringResource(R.string.ew_stop_dwell)) }, singleLine = true, shape = RoundedCornerShape(Radius.md),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
                if (s.arrive.isNotBlank()) TextButton(onClick = { editor.updateStop(s.id) { it.copy(arrive = "") } }) {
                    Text(stringResource(R.string.ew_stop_arrive_clear), fontWeight = FontWeight.ExtraBold, color = c.report)
                }
                if (days > 1) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0 until days).forEach { i ->
                        FilterChip(selected = s.day == i, onClick = { editor.updateStop(s.id) { it.copy(day = i) } },
                            label = { Text(stringResource(R.string.ew_day_n, i + 1), fontWeight = FontWeight.Bold) })
                    }
                }
            }
            OutlinedTextField(s.notes, { v -> editor.updateStop(s.id) { it.copy(notes = cap(v, EventLimits.STOP_NOTES_CHARS)) } }, Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text(stringResource(R.string.host_stop_notes)) }, minLines = 2, maxLines = 4, shape = RoundedCornerShape(Radius.md),
                supportingText = { Text("${chars(s.notes)}/${EventLimits.STOP_NOTES_CHARS}") })
            OutlinedTextField(s.audience, { v -> editor.updateStop(s.id) { it.copy(audience = cap(v, EventLimits.AUDIENCE_CHARS)) } }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.host_stop_audience)) }, singleLine = true, shape = RoundedCornerShape(Radius.md))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(stringResource(R.string.host_audience_all), stringResource(R.string.host_audience_family)).forEach { a ->
                    AssistChip(onClick = { editor.updateStop(s.id) { it.copy(audience = a) } }, label = { Text(a) })
                }
            }
            Section(stringResource(R.string.host_stop_entrance))
            Text(stringResource(if (s.entrance == null) R.string.host_stop_entrance_none else R.string.host_stop_entrance_set), fontSize = 14.sp, color = c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = onEntrance, shape = RoundedCornerShape(Radius.md)) {
                    Icon(Icons.Filled.Directions, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.host_stop_entrance_pick), fontWeight = FontWeight.ExtraBold)
                }
                if (s.entrance != null) TextButton(onClick = { editor.updateStop(s.id) { it.copy(entranceLat = null, entranceLng = null) } }) {
                    Text(stringResource(R.string.host_stop_entrance_clear), fontWeight = FontWeight.ExtraBold, color = c.report)
                }
            }
            OutlinedTextField(s.organizerNote, { v -> editor.updateStop(s.id) { it.copy(organizerNote = cap(v, 500)) } }, Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(stringResource(R.string.host_organizer_notes)) }, supportingText = { Text(stringResource(R.string.host_organizer_notes_hint)) },
                minLines = 1, maxLines = 3, shape = RoundedCornerShape(Radius.md))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { editor.removeStop(s.id) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md)) {
                    Text(stringResource(R.string.host_stop_remove), fontWeight = FontWeight.ExtraBold, color = c.report)
                }
                Button(onClick = onDismiss, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                    Text(stringResource(R.string.done), fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

// --- Kongsi jemputan: see ShareScreen.kt (EwShare) ---------------------------------------------------

// --- Guest: the host changed the plan (EvUpdate) -------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSheet(
    s: EventImportUi.Review, previous: EventPlan, use24h: Boolean, lang: Lang, navigatingTo: my.gimano.app.nav.NavDestination?,
    onKeep: () -> Unit, onUse: () -> Unit, onUseAndDrive: (EventStop) -> Unit,
) {
    val c = Gimano.colors
    val p = s.plan
    val diff = remember(previous, p) { EventDiff.diff(previous, p) }
    val z = EventSchedule.zone(p)
    val driving = navigatingTo?.takeIf { it.eventId == p.id }?.stopId?.let { diff.targetChanged(it) }
    ModalBottomSheet(onDismissRequest = onKeep, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.event_changed_title), style = GimanoType.title, color = c.text)
            Text(stringResource(R.string.event_changed_sub, p.rev.toInt(), instantText(p.publishedAt, z, use24h, lang)), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
            Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text, modifier = Modifier.padding(top = 6.dp))
            if (diff.timeChanged) BeforeAfter(stringResource(R.string.event_changed_time), eventWhen(previous, use24h, lang), null, eventWhen(p, use24h, lang), null)
            diff.stops.forEach { ch -> StopChangeCard(ch) }
            if (diff.isEmpty || (diff.infoChanged && diff.stops.isEmpty() && !diff.timeChanged))
                Text(stringResource(R.string.event_changed_details), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
            when (val d = s.decision) {
                is ImportDecision.Fork -> Warning(stringResource(R.string.event_warn_fork, d.rev.toInt()))
                is ImportDecision.Newer -> when (d.parent) {
                    ParentCheck.MISSED_REVISIONS -> Warning(stringResource(R.string.event_warn_missed))
                    ParentCheck.MISMATCH -> Warning(stringResource(R.string.event_warn_mismatch))
                    ParentCheck.MATCHES -> Unit
                }
                else -> Unit
            }
            val newTarget = driving?.before?.let { b -> p.stop(b.id) }
            if (driving != null) {
                Warning(stringResource(R.string.event_changed_driving, driving.before?.label ?: ""))
                if (newTarget != null) TextButton(onClick = { onUseAndDrive(newTarget) }, Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.event_changed_drive_new), fontWeight = FontWeight.ExtraBold, color = c.brand)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onKeep, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md)) {
                    Text(stringResource(R.string.event_keep), fontWeight = FontWeight.ExtraBold, color = c.text)
                }
                Button(onClick = onUse, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                    Text(stringResource(R.string.event_use_new), fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun StopChangeCard(ch: StopChange) {
    val title = when {
        ch.added -> stringResource(R.string.event_changed_added)
        ch.removed -> stringResource(R.string.event_changed_removed)
        ch.moved -> stringResource(R.string.event_changed_moved, ch.after?.label ?: "")
        ch.renamed -> stringResource(R.string.event_changed_renamed)
        else -> stringResource(R.string.event_changed_stop, ch.after?.label ?: "")
    }
    val moved = if (ch.moved) stringResource(R.string.event_changed_moved_m, Formatters.distance(ch.movedM)) else null
    BeforeAfter(title, ch.before?.label, ch.before?.notes, ch.after?.label, listOfNotNull(ch.after?.notes, moved).joinToString(" · ").ifBlank { null })
}

@Composable
private fun BeforeAfter(title: String, before: String?, beforeSub: String?, after: String?, afterSub: String?) {
    val c = Gimano.colors
    Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Black, color = c.text)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Side(stringResource(R.string.event_before), before, beforeSub, Modifier.weight(1f), c.surfaceAlt)
            Side(stringResource(R.string.event_now), after, afterSub, Modifier.weight(1f), c.brandMist)
        }
    }
}

@Composable
private fun Side(label: String, text: String?, sub: String?, modifier: Modifier, bg: androidx.compose.ui.graphics.Color) {
    val c = Gimano.colors
    Column(modifier.clip(RoundedCornerShape(Radius.md)).background(bg).padding(10.dp)) {
        Text(label, style = GimanoType.label, color = c.textMuted)
        Text(text ?: "-", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (text == null) c.textMuted else c.text)
        sub?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = c.textMuted) }
    }
}
