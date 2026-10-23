package my.gimano.app.ui.planned

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.trip.PlannedSchedule
import my.gimano.app.data.planned.PlannedDrive
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.home.IconTile
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val DAY_LABELS = mapOf(
    "mo" to R.string.day_mo, "tu" to R.string.day_tu, "we" to R.string.day_we, "th" to R.string.day_th,
    "fr" to R.string.day_fr, "sa" to R.string.day_sa, "su" to R.string.day_su,
)

@Composable
fun scheduleText(s: PlannedSchedule): String {
    val days = if (s.repeating) {
        if (s.days == PlannedSchedule.WEEKDAYS) stringResource(R.string.weekdays) else s.days.map { stringResource(DAY_LABELS.getValue(it)) }.joinToString(" ")
    } else s.date ?: ""
    return stringResource(R.string.arrive_by_on, s.arriveBy, days)
}

@Composable
fun PlannedDrivesScreen(pvm: PlannedViewModel, use24h: Boolean, onBack: () -> Unit, onAdd: () -> Unit, onGo: (PlannedDrive) -> Unit) {
    val c = Gimano.colors
    val context = LocalContext.current
    val drives by pvm.drives.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.planned_drives), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, stringResource(R.string.plan_a_drive), tint = c.brand) }
        }
        if (!pvm.canExact && Build.VERSION.SDK_INT >= 31) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.brandMist).clickable {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Alarm, null, tint = c.brand); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.exact_alarm_prompt), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (drives.isEmpty()) Text(stringResource(R.string.no_planned), Modifier.padding(24.dp), color = c.textMuted)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(drives, key = { it.id }) { d ->
                Row(Modifier.fillMaxWidth().clickable { pvm.edit(d) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile { GIcon("recent", null, size = 26.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name.ifBlank { d.destination.name }, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(scheduleText(d.schedule), fontSize = 14.sp, color = c.textMuted)
                    }
                    Button(onClick = { onGo(d) }, shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                        Text(stringResource(R.string.go), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
    pvm.editor.collectAsStateWithLifecycle().value?.let { PlanEditorSheet(it, pvm, use24h) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEditorSheet(e: PlanEditor, pvm: PlannedViewModel, use24h: Boolean) {
    val c = Gimano.colors
    val lang = currentLang()
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val d = e.drive
    ModalBottomSheet(onDismissRequest = pvm::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            OutlinedTextField(d.name, { v -> pvm.update { it.copy(name = v.take(40)) } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.plan_name)) }, shape = RoundedCornerShape(Radius.md))
            Text(d.destination.address.ifBlank { d.destination.name }, fontSize = 13.sp, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.arrive_by), fontWeight = FontWeight.ExtraBold, color = c.text, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { pickTime = true }, shape = RoundedCornerShape(Radius.md)) { Text(d.schedule.arriveBy, fontSize = 20.sp, fontWeight = FontWeight.Black) }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlannedSchedule.WEEK.forEach { code ->
                    FilterChip(selected = code in d.schedule.days, onClick = { pvm.toggleDay(code) }, label = { Text(stringResource(DAY_LABELS.getValue(code)), fontWeight = FontWeight.ExtraBold) },
                        shape = RoundedCornerShape(Radius.pill), modifier = Modifier.height(44.dp))
                }
            }
            if (!d.schedule.repeating) {
                TextButton(onClick = { pickDate = true }) { Text(stringResource(R.string.one_off_on, d.schedule.date ?: ""), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.brandMist).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, null, tint = c.brand)
                Spacer(Modifier.width(10.dp))
                val z = ZoneId.systemDefault()
                Text(
                    when {
                        e.computing -> stringResource(R.string.computing)
                        e.leaveBy != null -> stringResource(R.string.leave_by, Formatters.clock(e.leaveBy.atZone(z), use24h, lang), e.arrive?.atZone(z)?.toLocalDate()?.toString() ?: "")
                        else -> stringResource(R.string.no_next_occurrence)
                    },
                    fontWeight = FontWeight.ExtraBold, color = c.text,
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = pvm::save, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) { Text(stringResource(R.string.save), style = GimanoType.button) }
            if (!e.isNew) TextButton(onClick = { pvm.delete(d.id) }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.delete), color = c.report, fontWeight = FontWeight.ExtraBold) }
        }
    }
    if (pickTime) {
        val t = LocalTime.parse(d.schedule.arriveBy)
        val st = rememberTimePickerState(t.hour, t.minute, is24Hour = use24h)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { pickTime = false; pvm.update { it.copy(schedule = it.schedule.copy(arriveBy = "%02d:%02d".format(st.hour, st.minute))) } }) { Text(stringResource(R.string.ok)) } },
            text = { TimePicker(st) },
        )
    }
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (d.schedule.date?.let(LocalDate::parse) ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                pickDate = false
                st.selectedDateMillis?.let { pvm.setOneOff(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text(stringResource(R.string.ok)) } },
        ) { DatePicker(st) }
    }
}
