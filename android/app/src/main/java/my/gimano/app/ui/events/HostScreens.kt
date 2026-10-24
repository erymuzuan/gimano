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
import androidx.compose.ui.platform.LocalContext
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
private fun issueText(i: DraftIssue): String {
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
    }
}

private fun chars(s: String) = s.codePointCount(0, s.length)

/** Caps typed text at [max] code points (§4 counts code points). */
private fun cap(s: String, max: Int): String = if (chars(s) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

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
            Text(stringResource(R.string.event_stops_n, h.draft.stops.size) + " · " + status, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = if (h.published == null || h.changed) c.brandInk else c.textMuted)
        }
    }
}

// --- Acara baharu / edit (EvCreate) ------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(editor: HostEditor, saved: List<SavedPlace>, use24h: Boolean, vm: MainViewModel) {
    val c = Gimano.colors
    val lang = currentLang()
    val scope = rememberCoroutineScope()
    val d = editor.draft.collectAsStateWithLifecycle().value ?: return
    val issues by editor.issues.collectAsStateWithLifecycle()
    val publishing by editor.publishing.collectAsStateWithLifecycle()
    val editing by editor.editingStop.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<Int?>(null) }   // 0 start, 1 end, 2 expiry time
    var pickExpiryDate by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val published by editor.published.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(if (published) R.string.host_edit_title else R.string.host_new), style = GimanoType.title, color = c.text,
                modifier = Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(Radius.pill)).background(c.brandMist).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = c.brandInk, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.host_private), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk)
            }
            IconButton(onClick = { askDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.event_delete), tint = c.textMuted) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(d.title, { v -> editor.update { it.copy(title = cap(v, EventLimits.TITLE_CHARS)) } }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.host_title)) }, singleLine = true, shape = RoundedCornerShape(Radius.md),
                isError = issues.any { it.code == DraftIssue.Code.TITLE_REQUIRED })
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostDraft.TEMPLATES.forEach { t ->
                    FilterChip(selected = d.template == t, onClick = { editor.update { it.copy(template = t) } }, label = { Text(templateLabel(t), fontWeight = FontWeight.Bold) })
                }
            }
            val start = runCatching { d.start() }.getOrNull()
            val end = runCatching { d.end() }.getOrNull()
            val z = d.zone()
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldButton(stringResource(R.string.host_date), start?.let { day(it, z, lang) } ?: d.date, Modifier.weight(1.2f)) { pickDate = true }
                FieldButton(stringResource(R.string.host_start), start?.let { clock(it, z, use24h, lang) } ?: d.startTime, Modifier.weight(1f)) { pickTime = 0 }
                FieldButton(stringResource(R.string.host_end), end?.let { clock(it, z, use24h, lang) } ?: d.endTime, Modifier.weight(1f)) { pickTime = 1 }
            }
            if (start != null && end != null && end.toLocalDate() != start.toLocalDate())
                Text(stringResource(R.string.host_ends_next_day), fontSize = 13.sp, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(d.alias, { v -> editor.update { it.copy(alias = cap(v, EventLimits.ALIAS_CHARS)) } }, Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text(stringResource(R.string.host_alias)) }, supportingText = { Text(stringResource(R.string.host_alias_hint)) },
                singleLine = true, shape = RoundedCornerShape(Radius.md))
            OutlinedTextField(d.description, { v -> editor.update { it.copy(description = cap(v, EventLimits.DESCRIPTION_CHARS)) } },
                Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.host_description)) }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(Radius.md))

            Section(stringResource(R.string.host_stops_section))
            d.stops.forEachIndexed { i, s ->
                StopCard(s, i, d.stops.size, bad = issues.any { it.stop == i }, onClick = { editor.editStop(s.id) },
                    onUp = { editor.moveStop(s.id, -1) }, onDown = { editor.moveStop(s.id, 1) })
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).border(2.dp, c.outline, RoundedCornerShape(Radius.md))
                .clickable(enabled = d.stops.size < EventLimits.MAX_STOPS) { addOpen = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = c.brand)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.host_add_stop), fontWeight = FontWeight.ExtraBold, color = c.brand, fontSize = 16.sp)
            }

            // "Akses tamat Ahad 16 Nov, 16:00 (24 jam selepas acara). Boleh ubah."
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.brandMist).clickable { pickExpiryDate = true }.padding(14.dp)) {
                Text(stringResource(R.string.host_access_ends), style = GimanoType.label, color = c.brandInk)
                val exp = runCatching { d.expires() }.getOrNull()
                Text(exp?.let { instantText(it, z, use24h, lang) } ?: "-", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
                Text(stringResource(if (d.expiresAt == null) R.string.host_access_default else R.string.host_access_custom), fontSize = 13.sp, color = c.textMuted)
                if (d.expiresAt != null) Text(stringResource(R.string.host_access_reset), Modifier.padding(top = 6.dp).clickable { editor.update { it.copy(expiresAt = null) } },
                    fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = c.brand)
            }
            OutlinedTextField(d.organizerNotes, { v -> editor.update { it.copy(organizerNotes = cap(v, 2000)) } }, Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text(stringResource(R.string.host_organizer_notes)) }, supportingText = { Text(stringResource(R.string.host_organizer_notes_hint)) },
                minLines = 2, maxLines = 5, shape = RoundedCornerShape(Radius.md))
            issues.distinct().forEach { Warning(issueText(it)) }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().background(c.surface).padding(16.dp).navigationBarsPadding()) {
            Button(onClick = { vm.shareHostEvent() }, enabled = !publishing, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                if (publishing) CircularProgressIndicator(Modifier.size(22.dp), color = c.brandOn, strokeWidth = 2.dp)
                else { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.host_share_invite), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) }
            }
        }
    }

    if (addOpen) AddStopSheet(saved, onDismiss = { addOpen = false },
        onMap = { addOpen = false; vm.addEventStopOnMap() },
        onSearch = { addOpen = false; vm.addEventStopBySearch() },
        onSaved = { p -> addOpen = false; vm.addEventStopFromSaved(p) })
    editing?.let { id -> d.stops.firstOrNull { it.id == id } }?.let { s ->
        StopSheet(s, editor, onDismiss = { editor.editStop(null) }, onEntrance = { editor.editStop(null); vm.pickEventEntrance(s.id) })
    }
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = runCatching { d.localDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull())
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                st.selectedDateMillis?.let { ms -> editor.update { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()) } }
                pickDate = false
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } }) { DatePicker(st) }
    }
    if (pickExpiryDate) {
        val cur = runCatching { d.expires().atZone(d.zone()).toLocalDate() }.getOrNull()
        val st = rememberDatePickerState(initialSelectedDateMillis = cur?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickExpiryDate = false },
            confirmButton = { TextButton(onClick = {
                st.selectedDateMillis?.let { ms ->
                    val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    val t = runCatching { d.expires().atZone(d.zone()).toLocalTime() }.getOrDefault(LocalTime.NOON)
                    editor.update { it.copy(expiresAt = day.atTime(t).atZone(it.zone()).toInstant().toString()) }
                    pickTime = 2
                }
                pickExpiryDate = false
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickExpiryDate = false }) { Text(stringResource(R.string.cancel)) } }) { DatePicker(st) }
    }
    pickTime?.let { which ->
        val initial = when (which) {
            0 -> runCatching { d.localStart() }.getOrDefault(LocalTime.of(11, 0))
            1 -> runCatching { d.localEnd() }.getOrDefault(LocalTime.of(16, 0))
            else -> runCatching { d.expires().atZone(d.zone()).toLocalTime() }.getOrDefault(LocalTime.NOON)
        }
        val st = rememberTimePickerState(initial.hour, initial.minute, is24Hour = use24h)
        AlertDialog(onDismissRequest = { pickTime = null },
            text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = {
                val t = LocalTime.of(st.hour, st.minute)
                val hm = t.format(DateTimeFormatter.ofPattern("HH:mm"))
                when (which) {
                    0 -> editor.update { it.copy(startTime = hm) }
                    1 -> editor.update { it.copy(endTime = hm) }
                    else -> editor.update { e ->
                        val day = runCatching { e.expires().atZone(e.zone()).toLocalDate() }.getOrDefault(LocalDate.now())
                        e.copy(expiresAt = day.atTime(t).atZone(e.zone()).toInstant().toString())
                    }
                }
                pickTime = null
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        text = { Text(stringResource(R.string.host_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = { askDelete = false; scope.launch { editor.delete(); vm.open(my.gimano.app.ui.Screen.Events) } }) {
                Text(stringResource(R.string.delete), fontWeight = FontWeight.ExtraBold, color = c.report)
            }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FieldButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Gimano.colors
    Column(modifier.clip(RoundedCornerShape(Radius.md)).border(1.dp, c.outline, RoundedCornerShape(Radius.md)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StopCard(s: HostStop, i: Int, n: Int, bad: Boolean, onClick: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface)
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
private fun AddStopSheet(saved: List<SavedPlace>, onDismiss: () -> Unit, onMap: () -> Unit, onSearch: () -> Unit, onSaved: (SavedPlace) -> Unit) {
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
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit, sub: String = "") {
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
private fun StopSheet(s: HostStop, editor: HostEditor, onDismiss: () -> Unit, onEntrance: () -> Unit) {
    val c = Gimano.colors
    var kindOpen by remember { mutableStateOf(false) }
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

// --- Kongsi jemputan (EvShare) -------------------------------------------------------------------------

private const val SHARED_DIR = "shared"

private fun copySensitive(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    // The link holds the key: keep it out of clipboard previews (Android 13+).
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
}

private fun shareText(context: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(i, null)) }
}

private fun shareFile(context: Context, p: Publication, message: String) {
    val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
    dir.listFiles { f -> f.name.endsWith("." + EventTransport.FILE_EXTENSION) }?.forEach { it.delete() }
    val f = File(dir, InviteMessages.fileName(p.plan))
    f.writeBytes(p.file())
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).setType(EventTransport.FILE_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, message)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri("", uri)
    runCatching { context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}

@Composable
fun EventShareScreen(editor: HostEditor, use24h: Boolean, onBack: () -> Unit) {
    val c = Gimano.colors
    val lang = currentLang()
    val context = LocalContext.current
    val p = editor.publication.collectAsStateWithLifecycle().value ?: return
    val plan = p.plan
    val link = remember(p) { p.link(EventLinks.DEFAULT_SITE) }
    val code = remember(p) { p.secretCode }
    var copied by remember { mutableStateOf<Int?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EventTransport.FILE_MIME)) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(p.file()) } }
    }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.host_share_invite), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            val z = EventSchedule.zone(plan)
            Text(listOf(plan.title, stringResource(R.string.event_stops_n, plan.stops.size),
                stringResource(R.string.host_share_ends, day(plan.expiresAt.atOffset(ZoneOffset.UTC), z, lang))).joinToString(" · "),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
            Text(stringResource(if (p.reused) R.string.host_share_same_rev else R.string.host_share_rev, plan.rev.toInt()), fontSize = 13.sp, color = c.textMuted)

            // Link (recommended)
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(2.dp, c.brand, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.host_link_title), fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.text, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.host_recommended), Modifier.clip(RoundedCornerShape(Radius.pill)).background(c.brand).padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.brandOn)
                }
                Text(stringResource(R.string.host_link_body), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
                if (link == null) Warning(stringResource(R.string.host_link_too_long, p.fragmentLength, EventLimits.MAX_FRAGMENT_CHARS))
                else {
                    Text(link, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.surfaceAlt).padding(8.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { copySensitive(context, "Manotu", link); copied = R.string.host_copied_link }, Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(Radius.md)) {
                            Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.host_copy_link), fontWeight = FontWeight.ExtraBold)
                        }
                        Button(onClick = { shareText(context, InviteMessages.linkMessage(plan, link, BuildConfig.DOWNLOAD_URL)) }, Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                            Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.host_share), fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }

            // File + secret code (sensitive events)
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp)) {
                Text(stringResource(R.string.host_file_title), fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.text)
                Text(stringResource(R.string.host_file_body), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { saveFile.launch(InviteMessages.fileName(plan)) }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(Radius.md)) {
                        Text(stringResource(R.string.host_file_save), fontWeight = FontWeight.ExtraBold)
                    }
                    Button(onClick = { shareFile(context, p, InviteMessages.fileMessage(plan, BuildConfig.DOWNLOAD_URL)) }, Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.brandMist, contentColor = c.brandInk)) {
                        Text(stringResource(R.string.host_file_share), fontWeight = FontWeight.ExtraBold)
                    }
                }
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.brandMist).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = c.brandInk, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.host_secret_code), style = GimanoType.label, color = c.brandInk)
                    }
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(vertical = 6.dp))
                    Text(stringResource(R.string.host_secret_code_hint), fontSize = 13.sp, color = c.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = { copySensitive(context, "Manotu", code); copied = R.string.host_copied_code }) {
                            Text(stringResource(R.string.host_copy_code), fontWeight = FontWeight.ExtraBold, color = c.brand)
                        }
                        TextButton(onClick = { shareText(context, InviteMessages.codeMessage(plan, code)) }) {
                            Text(stringResource(R.string.host_share_code), fontWeight = FontWeight.ExtraBold, color = c.brand)
                        }
                    }
                }
            }
            copied?.let { Text(stringResource(it), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.brandInk, modifier = Modifier.padding(top = 8.dp)) }

            Section(stringResource(R.string.host_know_title))
            listOf(R.string.host_know_1, R.string.host_know_2, R.string.host_know_3).forEach {
                Text("• " + stringResource(it), fontSize = 14.sp, color = c.text, modifier = Modifier.padding(vertical = 3.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

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
