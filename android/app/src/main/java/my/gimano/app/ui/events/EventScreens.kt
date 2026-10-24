package my.gimano.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.events.EventPlan
import my.gimano.app.core.events.EventSchedule
import my.gimano.app.core.events.EventStop
import my.gimano.app.core.events.ImportDecision
import my.gimano.app.core.events.ParentCheck
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.format.Lang
import my.gimano.app.data.events.EventImportUi
import my.gimano.app.data.events.EventImporter
import my.gimano.app.data.events.EventNotice
import my.gimano.app.data.events.EventStore
import my.gimano.app.data.events.SavedEvent
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- Formatting (in the event's own time zone) ----------------------------------------------------------

private fun locale(lang: Lang) = if (lang == Lang.MS) Locale.forLanguageTag("ms-MY") else Locale.UK

private fun day(t: OffsetDateTime, z: ZoneId, lang: Lang) = t.atZoneSameInstant(z).format(DateTimeFormatter.ofPattern("EEE, d MMM", locale(lang)))

private fun clock(t: OffsetDateTime, z: ZoneId, use24h: Boolean, lang: Lang) = Formatters.clock(t.atZoneSameInstant(z), use24h, lang)

/** "Sab, 15 Nov · 11:00 – 16:00" or "Sab, 21 Nov 07:00 – Ahd, 22 Nov 13:00". */
fun eventWhen(p: EventPlan, use24h: Boolean, lang: Lang): String {
    val z = EventSchedule.zone(p)
    val sameDay = p.start.atZoneSameInstant(z).toLocalDate() == p.end.atZoneSameInstant(z).toLocalDate()
    return if (sameDay) "${day(p.start, z, lang)} · ${clock(p.start, z, use24h, lang)} – ${clock(p.end, z, use24h, lang)}"
    else "${day(p.start, z, lang)} ${clock(p.start, z, use24h, lang)} – ${day(p.end, z, lang)} ${clock(p.end, z, use24h, lang)}"
}

private fun instantText(i: Instant, z: ZoneId, use24h: Boolean, lang: Lang): String {
    val t = i.atZone(z)
    return "${t.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale(lang)))} ${Formatters.clock(t, use24h, lang)}"
}

// --- Import: code entry, review sheet, notices -----------------------------------------------------------

@Composable
fun EventImportDialogs(importer: EventImporter, use24h: Boolean) {
    val c = Gimano.colors
    val lang = currentLang()
    when (val s = importer.ui.collectAsStateWithLifecycle().value) {
        EventImportUi.Idle -> Unit
        EventImportUi.Working -> AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.brand)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.event_opening), color = c.text)
                }
            },
        )
        is EventImportUi.NeedCode -> {
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = importer::dismiss,
                title = { Text(stringResource(R.string.event_code_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.event_code_hint), color = c.textMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            code, { code = it.take(80) }, Modifier.fillMaxWidth(),
                            placeholder = { Text("XXXX-XXXX-…") },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            isError = s.wrong,
                            supportingText = if (s.wrong) { { Text(stringResource(R.string.event_code_wrong)) } } else null,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                            shape = RoundedCornerShape(Radius.md),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { importer.submitCode(code) }, enabled = code.isNotBlank()) { Text(stringResource(R.string.event_open), fontWeight = FontWeight.ExtraBold) } },
                dismissButton = { TextButton(onClick = importer::dismiss) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is EventImportUi.Review -> ReviewSheet(s, use24h, lang, onSave = importer::confirm, onCancel = importer::dismiss)
        is EventImportUi.Notice -> AlertDialog(
            onDismissRequest = importer::dismiss,
            text = {
                Text(
                    when (s.kind) {
                        EventNotice.CANNOT_OPEN -> stringResource(R.string.event_cannot_open)
                        EventNotice.TOO_LONG -> stringResource(R.string.event_too_long)
                        EventNotice.NOT_A_FILE -> stringResource(R.string.event_not_a_file)
                        EventNotice.EXPIRED -> stringResource(R.string.event_expired)
                        EventNotice.DIFFERENT_ORGANIZER -> stringResource(R.string.event_different_organizer)
                        EventNotice.ALREADY_IMPORTED -> stringResource(R.string.event_already)
                        EventNotice.OLDER -> stringResource(R.string.event_older, (s.storedRev ?: 0L).toInt())
                    },
                    fontSize = 16.sp, color = c.text,
                )
            },
            confirmButton = {
                val id = s.eventId
                if (id != null) TextButton(onClick = { importer.openStored(id) }) { Text(stringResource(R.string.event_open), fontWeight = FontWeight.ExtraBold) }
                else TextButton(onClick = importer::dismiss) { Text(stringResource(R.string.close), fontWeight = FontWeight.ExtraBold) }
            },
            dismissButton = if (s.eventId != null) { { TextButton(onClick = importer::dismiss) { Text(stringResource(R.string.close)) } } } else null,
        )
    }
}

@Composable
private fun ReviewSheet(s: EventImportUi.Review, use24h: Boolean, lang: Lang, onSave: () -> Unit, onCancel: () -> Unit) {
    val c = Gimano.colors
    val p = s.plan
    val update = s.decision !is ImportDecision.New
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(if (update) R.string.event_update_title else R.string.event_invite_title), style = GimanoType.label, color = c.brandInk)
            Text(p.title, style = GimanoType.title, color = c.text, modifier = Modifier.padding(top = 4.dp))
            Text(p.organizerAlias?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.event_by, it) } ?: stringResource(R.string.event_by_unnamed),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
            Spacer(Modifier.height(12.dp))
            InfoLine(eventWhen(p, use24h, lang))
            InfoLine(stringResource(R.string.event_stops_n, p.stops.size) + " · " + stringResource(R.string.event_rev, p.rev.toInt()))
            InfoLine(stringResource(R.string.event_access_until, instantText(p.expiresAt, EventSchedule.zone(p), use24h, lang)))
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.brandMist).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, tint = c.brandInk, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.event_safety_code), style = GimanoType.label, color = c.brandInk)
                }
                Text(s.safetyCode, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(vertical = 6.dp))
                Text(stringResource(R.string.event_safety_hint), fontSize = 13.sp, color = c.textMuted)
            }
            val warning = when (val d = s.decision) {
                is ImportDecision.Fork -> stringResource(R.string.event_warn_fork, d.rev.toInt())
                is ImportDecision.Newer -> when (d.parent) {
                    ParentCheck.MISSED_REVISIONS -> stringResource(R.string.event_warn_missed)
                    ParentCheck.MISMATCH -> stringResource(R.string.event_warn_mismatch)
                    ParentCheck.MATCHES -> null
                }
                else -> null
            }
            warning?.let { Warning(it) }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.event_privacy_note), fontSize = 13.sp, color = c.textMuted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md)) {
                    Text(stringResource(R.string.cancel), fontWeight = FontWeight.ExtraBold, color = c.text)
                }
                Button(onClick = onSave, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                    Text(stringResource(R.string.save), fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) = Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Gimano.colors.text, modifier = Modifier.padding(vertical = 2.dp))

@Composable
private fun Warning(text: String) {
    val c = Gimano.colors
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.reportTint).padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, null, tint = c.report, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
    }
}

// --- List ("Acara" in the side menu) ---------------------------------------------------------------------

@Composable
fun EventsListScreen(store: EventStore, use24h: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Gimano.colors
    val lang = currentLang()
    LaunchedEffect(Unit) { runCatching { store.purgeExpired() } }
    val events by remember(store) { store.events() }.collectAsStateWithLifecycle(initialValue = emptyList())
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.events), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
        }
        if (events.isEmpty()) Text(stringResource(R.string.event_none), Modifier.padding(24.dp), color = c.textMuted, fontSize = 16.sp)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(events, key = { it.plan.id }) { e ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(e.plan.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.brandMist), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Celebration, null, tint = c.brandInk)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.plan.title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(eventWhen(e.plan, use24h, lang), fontSize = 14.sp, color = c.textMuted)
                        Text(stringResource(R.string.event_stops_n, e.plan.stops.size), fontSize = 13.sp, color = c.textMuted)
                    }
                }
            }
        }
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = c.brand, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.event_privacy_note), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// --- One event, over the map ---------------------------------------------------------------------------

/**
 * Header, "Seterusnya" card with "Pandu", stops, agenda and routes in a bottom panel; the map above shows the
 * stops and route lines (drawn by GimanoRoot). [selected] is the highlighted stop.
 */
@Composable
fun EventDetailOverlay(
    event: SavedEvent?, store: EventStore, use24h: Boolean, selected: String?, onSelect: (String) -> Unit,
    onBack: () -> Unit, onDrive: (EventStop) -> Unit, onDeleted: () -> Unit, panelFraction: Float,
) {
    val c = Gimano.colors
    val lang = currentLang()
    val scope = rememberCoroutineScope()
    var askDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).size(48.dp).clip(CircleShape).background(c.surface).clickable(onClick = onBack),
            contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(panelFraction)
            .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)).background(c.surface)) {
            if (event == null) {
                Text(stringResource(R.string.event_gone), Modifier.padding(24.dp), color = c.textMuted, fontSize = 16.sp)
                return@Column
            }
            val p = event.plan
            val z = EventSchedule.zone(p)
            val next = remember(p) { EventSchedule.nextStop(p, Instant.now()) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp)) {
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = GimanoType.title, color = c.text)
                            Text(eventWhen(p, use24h, lang), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
                            Text(listOfNotNull(p.organizerAlias?.takeIf { it.isNotBlank() }, stringResource(R.string.event_rev, p.rev.toInt())).joinToString(" · "),
                                fontSize = 13.sp, color = c.textMuted)
                        }
                        IconButton(onClick = { askDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.event_delete), tint = c.textMuted) }
                    }
                    p.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp, color = c.text, modifier = Modifier.padding(top = 8.dp)) }
                    if (event.alternatives.isNotEmpty()) {
                        Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.reportTint)
                            .clickable { scope.launch { store.choose(p.id, event.alternatives.first()) } }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = c.report, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.event_other_version), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
                            Text(stringResource(R.string.event_use_version), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = c.brand)
                        }
                    }
                }
                next?.let { n ->
                    item {
                        Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.brand).clickable { onSelect(n.id) }.padding(16.dp)) {
                            Text(stringResource(R.string.event_next), color = c.brandOn.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                            Text(n.label, color = c.brandOn, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            n.arrive?.let { a -> Text("${clock(a.from, z, use24h, lang)} – ${clock(a.to, z, use24h, lang)}", color = c.brandOn, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            n.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = c.brandOn.copy(alpha = 0.9f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { onDrive(n) }, shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.surface, contentColor = c.brand)) {
                                Icon(Icons.Filled.Directions, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.event_drive), fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
                item { Section(stringResource(R.string.event_stops)) }
                items(p.stops, key = { "s" + it.id }) { s -> StopRow(s, s.id == selected, z, use24h, lang, onSelect = { onSelect(s.id) }, onDrive = { onDrive(s) }) }
                if (p.sessions.isNotEmpty()) {
                    item { Section(stringResource(R.string.event_agenda)) }
                    items(p.sessions.sortedBy { it.start }, key = { "a" + it.id }) { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("${day(s.start, z, lang)}\n${clock(s.start, z, use24h, lang)}–${clock(s.end, z, use24h, lang)}", Modifier.width(110.dp), fontSize = 13.sp, color = c.textMuted)
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
                                s.stopId?.let(p::stop)?.let { Text(it.label, fontSize = 13.sp, color = c.textMuted) }
                            }
                        }
                    }
                }
                if (p.routes.isNotEmpty()) {
                    item { Section(stringResource(R.string.event_routes)) }
                    items(p.routes, key = { "r" + it.id }) { r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(listOfNotNull(r.title, r.day).joinToString(" · ").ifBlank { r.mode.wire }, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
                            Text(listOfNotNull(Formatters.distance(r.distanceM.toDouble()), r.durationS?.let { Formatters.duration(it.toDouble(), lang) }).joinToString(" · "),
                                fontSize = 14.sp, color = c.textMuted)
                            if (r.ascentM != null && r.descentM != null) Text(stringResource(R.string.event_ascent, r.ascentM.toInt(), r.descentM.toInt()), fontSize = 13.sp, color = c.textMuted)
                            if (!r.source.navigable) Text(stringResource(R.string.event_route_sketch), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.report)
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.event_access_until, instantText(p.expiresAt, z, use24h, lang)) + "\n" + stringResource(R.string.event_privacy_note),
                        fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
    if (askDelete && event != null) AlertDialog(
        onDismissRequest = { askDelete = false },
        text = { Text(stringResource(R.string.event_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = { askDelete = false; scope.launch { store.delete(event.plan.id); onDeleted() } }) {
                Text(stringResource(R.string.delete), fontWeight = FontWeight.ExtraBold, color = c.report)
            }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(title: String) =
    Text(title, style = GimanoType.label, color = Gimano.colors.brandInk, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))

@Composable
private fun StopRow(s: EventStop, selected: Boolean, z: ZoneId, use24h: Boolean, lang: Lang, onSelect: () -> Unit, onDrive: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(if (selected) c.brandMist else c.surface).clickable(onClick = onSelect)
        .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
            val meta = listOfNotNull(
                s.arrive?.let { "${clock(it.from, z, use24h, lang)} – ${clock(it.to, z, use24h, lang)}" },
                s.dwellMin?.let { stringResource(R.string.event_dwell, it.toInt()) },
                s.audience?.takeIf { it.isNotBlank() },
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 13.sp, color = c.textMuted)
            s.notes?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = c.text, modifier = Modifier.padding(top = 2.dp)) }
            if (s.entrance != null) Text(stringResource(R.string.event_entrance), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.brandInk)
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onDrive, shape = RoundedCornerShape(Radius.md),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.brandMist, contentColor = c.brandInk)) {
            Text(stringResource(R.string.event_drive), fontWeight = FontWeight.ExtraBold)
        }
    }
}
