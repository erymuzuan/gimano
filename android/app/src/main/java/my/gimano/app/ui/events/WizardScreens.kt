package my.gimano.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.events.DraftIssue
import my.gimano.app.core.events.EventLimits
import my.gimano.app.core.events.EventTravelMode
import my.gimano.app.core.events.EventWizard
import my.gimano.app.core.events.EventWizard.Step
import my.gimano.app.core.events.HostAgenda
import my.gimano.app.core.events.HostDraft
import my.gimano.app.core.events.HostDraftRules
import my.gimano.app.core.events.HostInfo
import my.gimano.app.core.events.HostStop
import my.gimano.app.core.events.PlaceChoice
import my.gimano.app.core.format.Formatters
import my.gimano.app.data.events.HostEditor
import my.gimano.app.data.model.SavedPlace
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.common.TwoToneIcon
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * "Acara baharu" as a wizard (canvas EwType, EwWhen, EwVenue, EwRoute, EwMore, EwAdvanced, EwReview; flow EwFlow):
 * Jenis → Bila → Di mana | Laluan → Info → Semak. It edits the same HostDraft as before (autosaved, sealed on this
 * phone only); nothing here makes a network call. Suggestions are dashed chips: nothing is added without a tap.
 */

// --- Icons and labels -------------------------------------------------------------------------------------

private val MODES = listOf("car", "motorcycle", "bicycle", "walk")

/** The template's icon: the travel-mode duotone for journeys, else a flat two-tone icon. */
@Composable
internal fun TemplateIcon(t: String, size: Dp = 24.dp, contentDescription: String? = null) = when (t) {
    "trip" -> my.gimano.app.ui.routes.ModeIcon("car", contentDescription, size)
    "ride" -> my.gimano.app.ui.routes.ModeIcon("bicycle", contentDescription, size)
    "convoy" -> my.gimano.app.ui.routes.ModeIcon("motorcycle", contentDescription, size)
    "hike" -> my.gimano.app.ui.routes.ModeIcon("walk", contentDescription, size)
    else -> TwoToneIcon(when (t) {
        "kenduri" -> "ev_kenduri"; "meeting" -> "ev_meeting"; "seminar" -> "ev_seminar"; "dinner" -> "food"
        "picnic" -> "ev_picnic"; "company" -> "ev_bus"; else -> "ev_add"
    }, contentDescription, size = size)
}

internal fun kindIconName(k: String): String = when (k) {
    "venue" -> "ev_place"; "parking" -> "parking"; "entrance" -> "ev_entrance"; "dropoff" -> "ev_dropoff"; "prayer" -> "surau"
    "meal" -> "food"; "fuel" -> "fuel"; "water" -> "ev_water"; "rest" -> "ev_rest"; "accommodation" -> "ev_bed"
    "start" -> "ev_start"; "finish" -> "ev_flag"; "checkpoint" -> "ev_checkpoint"; else -> "ev_add"
}

@Composable
internal fun KindIcon(k: String, size: Dp = 24.dp) = TwoToneIcon(kindIconName(k), null, size = size)

internal fun infoIconName(k: String): String = when (k) {
    HostInfo.AGENDA -> "ev_agenda"; "dress" -> "ev_dress"; "safety" -> "ev_safety"; "documents" -> "ev_documents"
    "bring" -> "ev_bring"; "contact" -> "ev_contact"; else -> "ev_note"
}

@Composable
internal fun infoLabel(k: String): String = stringResource(when (k) {
    HostInfo.AGENDA -> R.string.ew_info_agenda
    "dress" -> R.string.ew_info_dress
    "safety" -> R.string.ew_info_safety
    "documents" -> R.string.ew_info_documents
    "bring" -> R.string.ew_info_bring
    "contact" -> R.string.ew_info_contact
    else -> R.string.ew_info_other
})

@Composable
private fun infoHint(k: String): String = stringResource(when (k) {
    HostInfo.AGENDA -> R.string.ew_info_agenda_hint
    "dress" -> R.string.ew_info_dress_hint
    "safety" -> R.string.ew_info_safety_hint
    "documents" -> R.string.ew_info_documents_hint
    "bring" -> R.string.ew_info_bring_hint
    "contact" -> R.string.ew_info_contact_hint
    else -> R.string.ew_info_other_hint
})

/** The name prefix a template gives an empty name ("Kenduri "), empty for "Lain". */
@Composable
private fun templatePrefixes(): Map<String, String> = HostDraft.TEMPLATES.associateWith { t -> if (t == "other") "" else templateLabel(t) + " " }

@Composable
private fun presetLabel(p: PlaceChoice): String = when (p.preset) {
    "lobby" -> stringResource(R.string.ew_place_lobby)
    "registration" -> stringResource(R.string.ew_place_registration)
    "toilet" -> stringResource(R.string.ew_place_toilet)
    "gather" -> stringResource(R.string.ew_place_gather)
    "stop" -> stringResource(R.string.ew_place_stop)
    else -> kindLabel(p.kind)
}

@Composable
private fun stepLabel(s: Step, journey: Boolean): String = stringResource(when (s) {
    Step.TYPE -> R.string.ew_step_type
    Step.WHEN -> R.string.ew_step_when
    Step.PLACE -> if (journey) R.string.ew_step_route else R.string.ew_step_place
    Step.INFO -> R.string.ew_step_info
    Step.REVIEW -> R.string.ew_step_review
})

/** Dashed rounded outline (suggestion chips, hints). */
internal fun Modifier.dashed(color: Color, radius: Dp = Radius.pill): Modifier = drawBehind {
    val r = minOf(radius.toPx(), size.minDimension / 2)
    drawRoundRect(color, cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
}

/** A dashed suggestion chip: icon + label; adds nothing until tapped. */
@Composable
internal fun DashedChip(text: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.clip(RoundedCornerShape(Radius.pill)).dashed(c.brandTint).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) =
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
internal fun PrivateChip() {
    val c = Gimano.colors
    Row(Modifier.clip(RoundedCornerShape(Radius.pill)).background(c.brandMist).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, null, tint = c.brandInk, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.host_private), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk)
    }
}

/** The 5-segment progress header with labels; [onStep] (when given) opens a step already reached. */
@Composable
internal fun WizardProgress(current: Step, journey: Boolean, reachable: (Step) -> Boolean, onStep: ((Step) -> Unit)?) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Step.entries.forEach { s ->
            val done = s.ordinal <= current.ordinal
            val label = stepLabel(s, journey)
            val tap = onStep != null && s != current && reachable(s)
            Column(Modifier.weight(1f).then(if (tap) Modifier.clickable(role = Role.Tab) { onStep!!(s) } else Modifier)
                .semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(Radius.pill)).background(if (done) c.brand else c.brandTint.copy(alpha = 0.45f)))
                Text(label, fontSize = 11.sp, fontWeight = if (s == current) FontWeight.Black else FontWeight.Bold,
                    color = if (s == current) c.text else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

// --- The wizard -------------------------------------------------------------------------------------------

@Composable
fun EventWizardScreen(editor: HostEditor, saved: List<SavedPlace>, use24h: Boolean, vm: MainViewModel,
    personalRoutes: my.gimano.app.data.routes.PersonalRouteStore? = null) {
    val c = Gimano.colors
    val scope = rememberCoroutineScope()
    val d = editor.draft.collectAsStateWithLifecycle().value ?: return
    val step by editor.step.collectAsStateWithLifecycle()
    val issues by editor.issues.collectAsStateWithLifecycle()
    val publishing by editor.publishing.collectAsStateWithLifecycle()
    val editing by editor.editingStop.collectAsStateWithLifecycle()
    val published by editor.published.collectAsStateWithLifecycle()
    val journey = EventWizard.isJourney(d.template)
    var addOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var pickRoute by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var maxStep by remember(d.id) { mutableStateOf(if (published) Step.REVIEW else step) }
    LaunchedEffect(step) { if (step.ordinal > maxStep.ordinal) maxStep = step }
    val blocked = EventWizard.blocking(d, step)?.let { issueText(DraftIssue(it)) }
    // "Kongsi jemputan" blocked: open the step of the first problem and say what it is.
    var blockedTap by remember { mutableIntStateOf(0) }
    val firstIssue = issues.firstOrNull()
    val firstIssueText = firstIssue?.let { issueText(it) }
    LaunchedEffect(blockedTap) {
        if (blockedTap == 0 || firstIssue == null) return@LaunchedEffect
        firstIssueText?.let(vm::toastText)
        if (EventWizard.stepFor(firstIssue) != Step.REVIEW) editor.showIssue(firstIssue)
    }

    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::eventWizardBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(if (published) R.string.host_edit_title else R.string.host_new), style = GimanoType.title, color = c.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            PrivateChip()
            if (step == Step.REVIEW) IconButton(onClick = { askDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.event_delete), tint = c.textMuted) }
            else Spacer(Modifier.width(12.dp))
        }
        WizardProgress(step, journey, reachable = { it.ordinal <= maxStep.ordinal }, onStep = { editor.goTo(it) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState(), reverseScrolling = false).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp)) {
            when (step) {
                Step.TYPE -> TypeStep(d, editor)
                Step.WHEN -> WhenStep(d, editor, use24h)
                Step.PLACE -> if (journey) RouteStep(d, editor, vm, use24h, canPick = personalRoutes != null, onAdd = { addOpen = true }, onPick = { pickRoute = true })
                    else VenueStep(d, editor, onAdd = { addOpen = true })
                Step.INFO -> InfoStep(d, editor, onAdvanced = { advancedOpen = true })
                Step.REVIEW -> ReviewStep(d, editor, use24h, issues)
            }
        }
        // Bottom bar
        Row(Modifier.fillMaxWidth().background(c.surface).padding(16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            when (step) {
                Step.REVIEW -> {
                    OutlinedButton(onClick = vm::saveEventDraft, Modifier.height(56.dp), shape = RoundedCornerShape(Radius.md)) {
                        Text(stringResource(R.string.ew_draft), fontWeight = FontWeight.ExtraBold, color = c.brand)
                    }
                    Button(onClick = { vm.shareHostEvent { blockedTap++ } }, enabled = !publishing, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                        if (publishing) CircularProgressIndicator(Modifier.size(22.dp), color = c.brandOn, strokeWidth = 2.dp)
                        else { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.host_share_invite), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) }
                    }
                }
                else -> {
                    if (step == Step.INFO) TextButton(onClick = editor::next, Modifier.height(56.dp)) {
                        Text(stringResource(R.string.ew_skip), fontWeight = FontWeight.ExtraBold, color = c.brand, fontSize = 16.sp)
                    }
                    Button(onClick = { if (blocked != null) vm.toastText(blocked) else editor.next() }, Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                        Text(stringResource(R.string.ew_next), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    }
                }
            }
        }
    }

    if (pickRoute && personalRoutes != null) PickPersonalRouteSheet(personalRoutes, onDismiss = { pickRoute = false }) { p -> pickRoute = false; vm.copyPersonalRouteToEvent(p) }
    if (addOpen) AddStopSheet(saved, onDismiss = { addOpen = false; editor.addKind = null; editor.addLabel = null; editor.relocate = null },
        onMap = { addOpen = false; vm.addEventStopOnMap() },
        onSearch = { addOpen = false; vm.addEventStopBySearch() },
        onSaved = { p -> addOpen = false; vm.addEventStopFromSaved(p) })
    editing?.let { id -> d.stops.firstOrNull { it.id == id } }?.let { s ->
        StopSheet(s, editor, onDismiss = { editor.editStop(null) }, onEntrance = { editor.editStop(null); vm.pickEventEntrance(s.id) },
            journey = journey, days = d.dayCount(), use24h = use24h)
    }
    if (advancedOpen) AdvancedSheet(d, editor, use24h, onDismiss = { advancedOpen = false })
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
private fun StepTitle(title: String, sub: String? = null) {
    val c = Gimano.colors
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Black, color = c.text)
    sub?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) }
}

// --- 1 · Jenis (EwType) -------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeStep(d: HostDraft, editor: HostEditor) {
    val prefixes = templatePrefixes()
    fun choose(t: String) = editor.update { cur ->
        val untouched = EventWizard.defaults(cur.template).times.any { EventWizard.hm(it.start) == cur.startTime && EventWizard.hm(it.end) == cur.endTime }
        EventWizard.chooseTemplate(cur, t, { prefixes[it].orEmpty() }, untouched)
    }
    // The pre-selected template names a new event too, not only a tapped one.
    LaunchedEffect(d.id) {
        if (d.title.isBlank()) editor.update { cur -> if (cur.title.isBlank()) cur.copy(title = prefixes[cur.template].orEmpty()) else cur }
    }
    StepTitle(stringResource(R.string.ew_type_title), stringResource(R.string.ew_type_sub))
    TypeCard(selected = !EventWizard.isJourney(d.template), icon = "ev_place", title = stringResource(R.string.ew_type_place),
        sub = stringResource(R.string.ew_type_place_sub), foot = stringResource(R.string.ew_type_place_foot),
        templates = EventWizard.PLACE_TEMPLATES, current = d.template, onCard = { if (EventWizard.isJourney(d.template)) choose("kenduri") }, onTemplate = ::choose)
    Spacer(Modifier.height(12.dp))
    TypeCard(selected = EventWizard.isJourney(d.template), icon = "ev_journey", title = stringResource(R.string.ew_type_journey),
        sub = stringResource(R.string.ew_type_journey_sub), foot = stringResource(R.string.ew_type_journey_foot),
        templates = EventWizard.JOURNEY_TEMPLATES, current = d.template, onCard = { if (!EventWizard.isJourney(d.template)) choose("trip") }, onTemplate = ::choose)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeCard(selected: Boolean, icon: String, title: String, sub: String, foot: String, templates: List<String>, current: String,
    onCard: () -> Unit, onTemplate: (String) -> Unit) {
    val c = Gimano.colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(Modifier.fillMaxWidth().clip(shape).background(if (selected) c.surface else c.surfaceAlt)
        .border(if (selected) 2.dp else 1.dp, if (selected) c.brand else c.outline, shape).padding(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onCard), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { TwoToneIcon(icon, null, size = 32.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Black, color = c.text)
                Text(sub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted)
            }
            Box(Modifier.size(28.dp).clip(CircleShape).then(if (selected) Modifier.background(c.brand) else Modifier.border(2.dp, c.outline, CircleShape)),
                contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Filled.Check, null, tint = c.brandOn, modifier = Modifier.size(18.dp)) }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            templates.forEach { t ->
                val on = t == current
                Row(Modifier.clip(RoundedCornerShape(Radius.pill)).background(if (on) c.brandMist else c.surface)
                    .border(if (on) 2.dp else 1.dp, if (on) c.brand else c.outline, RoundedCornerShape(Radius.pill))
                    .clickable(role = Role.RadioButton) { onTemplate(t) }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TemplateIcon(t, 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(templateLabel(t), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
                }
            }
        }
        Text(foot, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
    }
}

// --- 2 · Bila (EwWhen) --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WhenStep(d: HostDraft, editor: HostEditor, use24h: Boolean) {
    val c = Gimano.colors
    val lang = currentLang()
    var pickDate by remember { mutableStateOf<Int?>(null) }   // 0 first day, 1 last day
    var pickTime by remember { mutableStateOf<Int?>(null) }   // 0 start, 1 end (then 1 follows 0 for "Pilih masa")
    var chain by remember { mutableStateOf(false) }
    val def = EventWizard.defaults(d.template)
    StepTitle(stringResource(R.string.ew_when_title))
    Text(stringResource(R.string.ew_name), style = GimanoType.label, color = c.textMuted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    OutlinedTextField(d.title, { v -> editor.update { it.copy(title = cap(v, EventLimits.TITLE_CHARS)) } }, Modifier.fillMaxWidth(),
        leadingIcon = { TemplateIcon(d.template, 24.dp) }, singleLine = true, shape = RoundedCornerShape(Radius.md),
        textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold))
    val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", locale(lang))
    Text(stringResource(if (d.multiDay) R.string.ew_date_from else R.string.ew_date), style = GimanoType.label, color = c.textMuted,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    IconField(runCatching { d.localDate().format(dayFmt) }.getOrDefault(d.date), "ev_calendar") { pickDate = 0 }
    if (d.multiDay) {
        Text(stringResource(R.string.ew_date_to), style = GimanoType.label, color = c.textMuted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        IconField(runCatching { LocalDate.parse(d.endDate).format(dayFmt) }.getOrDefault(d.endDate.orEmpty()), "ev_calendar") { pickDate = 1 }
    }
    Text(stringResource(R.string.ew_time), style = GimanoType.label, color = c.textMuted, modifier = Modifier.padding(top = 14.dp))
    val fmt = { t: LocalTime -> Formatters.clock(t.atDate(LocalDate.of(2026, 1, 1)).atZone(java.time.ZoneId.of("UTC")), use24h, lang) }
    ChipFlow {
        def.times.forEach { tc ->
            val on = EventWizard.hm(tc.start) == d.startTime && EventWizard.hm(tc.end) == d.endTime
            TimeChip("${fmt(tc.start)} – ${fmt(tc.end)}", on) { editor.update { it.copy(startTime = EventWizard.hm(tc.start), endTime = EventWizard.hm(tc.end)) } }
        }
        val custom = def.times.none { EventWizard.hm(it.start) == d.startTime && EventWizard.hm(it.end) == d.endTime }
        val customText = if (custom) runCatching { "${fmt(d.localStart())} – ${fmt(d.localEnd())}" }.getOrDefault(stringResource(R.string.ew_pick_time))
            else stringResource(R.string.ew_pick_time)
        TimeChip(customText, custom) { chain = true; pickTime = 0 }
    }
    // "Lebih dari sehari"
    Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface)
        .clickable(role = Role.Switch) { editor.setDays(if (d.multiDay) 1 else 2) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TwoToneIcon("ev_calendar", null, size = 26.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.ew_multi_day), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
        Switch(checked = d.multiDay, onCheckedChange = { editor.setDays(if (it) 2 else 1) })
    }
    if (d.multiDay) Text(pluralStringResource(R.plurals.ew_days_n, d.dayCount(), d.dayCount()), fontSize = 13.sp, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
    val end = runCatching { d.end() }.getOrNull()
    val start = runCatching { d.start() }.getOrNull()
    if (!d.multiDay && start != null && end != null && end.toLocalDate() != start.toLocalDate())
        Text(stringResource(R.string.host_ends_next_day), fontSize = 13.sp, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
    // Hint
    Row(Modifier.padding(top = 14.dp).fillMaxWidth().dashed(c.outline, Radius.md).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TemplateIcon(d.template, 18.dp)
        Spacer(Modifier.width(8.dp))
        val tc = def.times.first()
        Text(stringResource(if (def.days) R.string.ew_when_hint_days else R.string.ew_when_hint, templateLabel(d.template), "${fmt(tc.start)} – ${fmt(tc.end)}"),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted)
    }

    pickDate?.let { which ->
        val cur = if (which == 0) runCatching { d.localDate() }.getOrNull() else d.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val st = rememberDatePickerState(initialSelectedDateMillis = cur?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = null },
            confirmButton = { TextButton(onClick = {
                st.selectedDateMillis?.let { ms ->
                    val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    if (which == 0) editor.update { e ->
                        // Moving the first day keeps the number of days.
                        val n = e.dayCount()
                        e.copy(date = day.toString(), endDate = if (n > 1) day.plusDays((n - 1).toLong()).toString() else null)
                    } else {
                        val n = (java.time.temporal.ChronoUnit.DAYS.between(d.localDate(), day) + 1).toInt()
                        editor.setDays(n.coerceAtLeast(1))
                    }
                }
                pickDate = null
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickDate = null }) { Text(stringResource(R.string.cancel)) } }) { DatePicker(st) }
    }
    pickTime?.let { which ->
        val initial = if (which == 0) runCatching { d.localStart() }.getOrDefault(LocalTime.of(11, 0)) else runCatching { d.localEnd() }.getOrDefault(LocalTime.of(16, 0))
        val st = rememberTimePickerState(initial.hour, initial.minute, is24Hour = use24h)
        AlertDialog(onDismissRequest = { pickTime = null; chain = false },
            title = { Text(stringResource(if (which == 0) R.string.host_start else R.string.host_end)) },
            text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = {
                val hm = "%02d:%02d".format(st.hour, st.minute)
                editor.update { if (which == 0) it.copy(startTime = hm) else it.copy(endTime = hm) }
                pickTime = if (which == 0 && chain) 1 else null
                if (which == 1) chain = false
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickTime = null; chain = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun IconField(text: String, icon: String, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface).border(1.dp, c.outline, RoundedCornerShape(Radius.md))
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        TwoToneIcon(icon, null, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TimeChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.clip(RoundedCornerShape(Radius.pill)).background(if (on) c.brandMist else c.surface)
        .border(if (on) 2.dp else 1.dp, if (on) c.brand else c.outline, RoundedCornerShape(Radius.pill))
        .clickable(role = Role.RadioButton, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TwoToneIcon("ev_clock", null, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
    }
}

// --- 3A · Di mana (EwVenue) ---------------------------------------------------------------------------------

/** The main venue: the first `venue` stop, else the first stop of an older event. */
private fun mainVenue(d: HostDraft): HostStop? = d.stops.firstOrNull { it.kind == "venue" } ?: d.stops.firstOrNull()

@Composable
private fun VenueStep(d: HostDraft, editor: HostEditor, onAdd: () -> Unit) {
    val c = Gimano.colors
    StepTitle(stringResource(R.string.ew_place_title), stringResource(R.string.ew_place_sub))
    val venue = mainVenue(d)
    if (venue == null) {
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).dashed(c.brandTint, Radius.lg)
            .clickable(role = Role.Button) { editor.addKind = "venue"; editor.addLabel = null; editor.relocate = null; onAdd() }.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            TwoToneIcon("ev_place", null, size = 40.dp)
            Text(stringResource(R.string.ew_place_pick), fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.brand, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.host_add_note), fontSize = 13.sp, color = c.textMuted)
        }
    } else {
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.surface)
            .border(2.dp, c.brandTint, RoundedCornerShape(Radius.lg)).clickable { editor.editStop(venue.id) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { KindIcon("venue", 30.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(venue.label.ifBlank { stringResource(R.string.host_stop_unnamed) }, fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(venue.notes.ifBlank { "%.5f, %.5f".format(venue.lat, venue.lng) }, fontSize = 13.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { editor.relocate = venue.id; editor.addKind = null; onAdd() }) {
                Text(stringResource(R.string.ew_change), fontWeight = FontWeight.ExtraBold, color = c.brand)
            }
        }
    }
    val extras = d.stops.filter { it.id != venue?.id }
    Text(stringResource(R.string.ew_place_extra), style = GimanoType.label, color = c.textMuted, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    extras.forEach { s -> StopRowCard(s) { editor.editStop(s.id) }; Spacer(Modifier.height(8.dp)) }
    ChipFlow {
        EventWizard.VENUE_CHIPS.forEach { p ->
            DashedChip(presetLabel(p), { KindIcon(p.kind, 22.dp) }) {
                editor.addKind = if (venue == null) "venue" else p.kind
                editor.addLabel = null; editor.relocate = null; editor.addDay = 0
                onAdd()
            }
        }
    }
    // The template's own suggestions with a preset name (Lobi, Pendaftaran, Tandas…).
    val presets = EventWizard.defaults(d.template).places.filter { it.preset != null }
    if (presets.isNotEmpty()) ChipFlow {
        presets.forEach { p ->
            val label = presetLabel(p)
            DashedChip(label, { KindIcon(p.kind, 22.dp) }) { editor.addKind = p.kind; editor.addLabel = label; editor.relocate = null; onAdd() }
        }
    }
}

@Composable
private fun StopRowCard(s: HostStop, trailing: String? = null, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.background), contentAlignment = Alignment.Center) { KindIcon(s.kind, 28.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label.ifBlank { stringResource(R.string.host_stop_unnamed) }, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = if (s.label.isBlank()) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(kindLabel(s.kind), s.dwellMin?.let { stringResource(R.string.event_dwell, it) }, s.notes.takeIf { it.isNotBlank() }).joinToString(" · ")
            Text(meta, fontSize = 13.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.audience.isNotBlank()) Text(s.audience, Modifier.padding(start = 6.dp).clip(RoundedCornerShape(Radius.pill)).background(c.brandMist)
            .padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk)
        trailing?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Black, color = c.brand, modifier = Modifier.padding(start = 8.dp)) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textMuted)
    }
}

// --- 3B · Laluan (EwRoute) ----------------------------------------------------------------------------------

@Composable
private fun RouteStep(d: HostDraft, editor: HostEditor, vm: MainViewModel, use24h: Boolean, canPick: Boolean, onAdd: () -> Unit, onPick: () -> Unit) {
    val c = Gimano.colors
    val lang = currentLang()
    var daySel by remember(d.id) { mutableIntStateOf(0) }
    val days = d.dayCount()
    val day = daySel.coerceIn(0, days - 1)
    val dayDate = if (d.multiDay) runCatching { d.dayDate(day).toString() }.getOrNull() else null
    StepTitle(stringResource(R.string.ew_route_title), stringResource(R.string.ew_route_sub))
    // Travel mode
    val mode = d.routes.firstOrNull { dayDate == null || it.day == dayDate }?.mode ?: EventWizard.mode(d)
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface).padding(4.dp)) {
        MODES.forEach { m ->
            val on = m == mode
            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(Radius.md)).background(if (on) c.brand else Color.Transparent)
                .clickable(role = Role.RadioButton) { editor.setMode(m, dayDate) },
                contentAlignment = Alignment.Center) {
                my.gimano.app.ui.routes.ModeIcon(m, my.gimano.app.ui.routes.modeLabel(m), 28.dp, onBrand = on)
            }
        }
    }
    // Days
    if (d.multiDay) Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        (0 until days).forEach { i ->
            val on = i == day
            Text(stringResource(R.string.ew_day_n, i + 1), Modifier.clip(RoundedCornerShape(Radius.pill)).background(if (on) c.text else c.surface)
                .clickable(role = Role.Tab) { daySel = i }.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 9.dp),
                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (on) c.background else c.text)
        }
        if (days < EventLimits.MAX_EVENT_DAYS) {
            val addDay = stringResource(R.string.ew_day_add)
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).clickable(role = Role.Button) { editor.setDays(days + 1) }
                .semantics { contentDescription = addDay }, contentAlignment = Alignment.Center) {
                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.text)
            }
        }
    }
    // Timeline: Mula → stops → Tamat
    val stops = d.stops.filter { !d.multiDay || it.day == day }
    Column(Modifier.padding(top = 12.dp)) {
        if (stops.none { it.kind == "start" }) SetEndpoint(stringResource(R.string.ew_route_set_start), "start") {
            editor.addKind = "start"; editor.addDay = day; editor.addLabel = null; editor.relocate = null; onAdd()
        }
        stops.forEachIndexed { i, s ->
            TimelineRow(s, first = i == 0, last = i == stops.lastIndex, use24h = use24h, lang = lang) { editor.editStop(s.id) }
        }
        if (stops.none { it.kind == "finish" } && stops.isNotEmpty()) SetEndpoint(stringResource(R.string.ew_route_set_finish), "finish") {
            editor.addKind = "finish"; editor.addDay = day; editor.addLabel = null; editor.relocate = null; onAdd()
        }
    }
    ChipFlow {
        EventWizard.routeChips(d.template).forEach { p ->
            val label = presetLabel(p)
            DashedChip(label, { KindIcon(p.kind, 22.dp) }) {
                editor.addKind = p.kind; editor.addDay = day; editor.addLabel = label; editor.relocate = null; onAdd()
            }
        }
    }
    // "Bina laluan di peta"
    val route = d.routes.firstOrNull { dayDate == null || it.day == dayDate }
    val sum = route?.let { remember(it) { my.gimano.app.core.routes.RouteAssembly.summary(it) } }
    Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.brandMist)
        .clickable(role = Role.Button) { vm.openEventRouteForDay(day) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface), contentAlignment = Alignment.Center) { TwoToneIcon("route", null, size = 30.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ew_route_build), fontSize = 17.sp, fontWeight = FontWeight.Black, color = c.text)
            val status = when {
                route == null || route.points.size < 2 -> stringResource(R.string.ew_route_build_sub)
                sum!!.openLegs > 0 -> stringResource(R.string.rb_open_legs, sum.openLegs)
                sum.unmatchedLegs > 0 -> stringResource(R.string.rb_unmatched)
                else -> listOfNotNull(Formatters.distance(sum.distanceM.toDouble()), sum.durationS?.let { Formatters.duration(it.toDouble(), lang) },
                    stringResource(if (sum.navigable) R.string.ew_route_follows_roads else R.string.rb_sketch)).joinToString(" · ")
            }
            Text(status, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textMuted)
    }
    // Other routes of the event (e.g. several per day, or routes copied from "Laluan saya").
    d.routes.filter { it.id != route?.id && (dayDate == null || it.day == null || it.day == dayDate) }.forEach { r ->
        val s = remember(r) { my.gimano.app.core.routes.RouteAssembly.summary(r) }
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface).clickable { vm.openEventRoute(r.id) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            my.gimano.app.ui.routes.ModeIcon(r.mode, my.gimano.app.ui.routes.modeLabel(r.mode))
            Spacer(Modifier.width(10.dp))
            Text(listOfNotNull(r.title.ifBlank { null }, r.day).joinToString(" · ").ifBlank { stringResource(R.string.host_routes_section) },
                Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Formatters.distance(s.distanceM.toDouble()), fontSize = 13.sp, color = c.textMuted)
        }
    }
    if (canPick && d.routes.size < EventLimits.MAX_ROUTES) TextButton(onClick = onPick, Modifier.padding(top = 4.dp)) {
        Text(stringResource(R.string.host_route_from_mine), fontWeight = FontWeight.ExtraBold, color = c.brand)
    }
}

@Composable
private fun SetEndpoint(text: String, kind: String, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).dashed(c.brandTint, Radius.md)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        KindIcon(kind, 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.brand)
    }
}

@Composable
private fun TimelineRow(s: HostStop, first: Boolean, last: Boolean, use24h: Boolean, lang: my.gimano.app.core.format.Lang, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(3.dp).weight(1f).background(if (first) Color.Transparent else c.brandTint))
                Box(Modifier.width(3.dp).weight(1f).background(if (last) Color.Transparent else c.brandTint))
            }
            Box(Modifier.size(42.dp).clip(CircleShape).background(c.brandMist), contentAlignment = Alignment.Center) { KindIcon(s.kind, 26.dp) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(s.label.ifBlank { stringResource(R.string.host_stop_unnamed) }, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = if (s.label.isBlank()) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(kindLabel(s.kind), s.dwellMin?.let { stringResource(R.string.event_dwell, it) }, s.notes.takeIf { it.isNotBlank() }).joinToString(" · ")
            Text(meta, fontSize = 13.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HostAgenda.time(s.arrive)?.let { t ->
            Text(Formatters.clock(t.atDate(LocalDate.of(2026, 1, 1)).atZone(java.time.ZoneId.of("UTC")), use24h, lang), fontSize = 16.sp,
                fontWeight = FontWeight.Black, color = c.brand, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// --- 4 · Info (EwMore) --------------------------------------------------------------------------------------

@Composable
private fun InfoStep(d: HostDraft, editor: HostEditor, onAdvanced: () -> Unit) {
    val c = Gimano.colors
    StepTitle(stringResource(R.string.ew_info_title), stringResource(R.string.ew_info_sub))
    Text(stringResource(R.string.ew_message), style = GimanoType.label, color = c.textMuted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    OutlinedTextField(d.description, { v -> editor.update { it.copy(description = cap(v, EventLimits.DESCRIPTION_CHARS)) } }, Modifier.fillMaxWidth(),
        leadingIcon = { TwoToneIcon("ev_note", null, size = 22.dp) }, placeholder = { Text(stringResource(R.string.ew_message_hint)) },
        minLines = 1, maxLines = 6, shape = RoundedCornerShape(Radius.md))
    d.info.forEach { b -> InfoCard(b, editor) }
    val present = d.info.map { it.kind }.toSet()
    Text(stringResource(R.string.ew_add), style = GimanoType.label, color = c.textMuted, modifier = Modifier.padding(top = 16.dp))
    ChipFlow {
        EventWizard.infoChips(d.template, present).forEach { k ->
            DashedChip(infoLabel(k), { TwoToneIcon(infoIconName(k), null, size = 22.dp) }) { editor.addInfo(k) }
        }
    }
    Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface)
        .clickable(role = Role.Button, onClick = onAdvanced).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TwoToneIcon("ev_sliders", null, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ew_advanced), fontSize = 17.sp, fontWeight = FontWeight.Black, color = c.text)
            Text(stringResource(R.string.ew_advanced_sub), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textMuted)
    }
}

@Composable
private fun InfoCard(b: HostInfo, editor: HostEditor) {
    val c = Gimano.colors
    val agenda = b.kind == HostInfo.AGENDA
    val label = infoLabel(b.kind)
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.surface)
        .border(1.dp, c.outline, RoundedCornerShape(Radius.lg)).padding(start = 14.dp, top = 6.dp, bottom = 10.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TwoToneIcon(infoIconName(b.kind), null, size = 24.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Black, color = c.text)
            IconButton(onClick = { editor.removeInfo(b.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.ew_remove_n, label), tint = c.textMuted) }
        }
        val max = if (agenda) 4000 else EventLimits.INFO_TEXT_CHARS
        OutlinedTextField(b.text, { v -> editor.updateInfo(b.id, cap(v, max)) }, Modifier.fillMaxWidth().padding(end = 10.dp),
            placeholder = { Text(infoHint(b.kind)) }, minLines = 2, maxLines = 10, shape = RoundedCornerShape(Radius.md),
            supportingText = if (!agenda) { { Text("${chars(b.text)}/${EventLimits.INFO_TEXT_CHARS}") } } else null)
        if (agenda) {
            val bad = remember(b.text) { HostDraftRules.agendaBadLines(b.text) }
            if (bad.isNotEmpty()) Text(stringResource(R.string.ew_agenda_bad, bad.first()), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.report,
                modifier = Modifier.padding(top = 4.dp, end = 10.dp))
            else Text(stringResource(R.string.ew_agenda_help), fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(top = 4.dp, end = 10.dp))
        }
    }
}

// --- 4+ · Lanjutan (EwAdvanced) ----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSheet(d: HostDraft, editor: HostEditor, use24h: Boolean, onDismiss: () -> Unit) {
    val c = Gimano.colors
    val lang = currentLang()
    var pickExpiryDate by remember { mutableStateOf(false) }
    var pickExpiryTime by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TwoToneIcon("ev_sliders", null, size = 26.dp); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ew_advanced), style = GimanoType.title, color = c.text)
            }
            AdvRow("ev_person", stringResource(R.string.ew_adv_organizer), stringResource(R.string.host_alias_hint)) {
                OutlinedTextField(d.alias, { v -> editor.update { it.copy(alias = cap(v, EventLimits.ALIAS_CHARS)) } }, Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(Radius.md), placeholder = { Text(stringResource(R.string.ew_adv_organizer_hint)) })
            }
            val z = d.zone()
            val exp = runCatching { d.expires() }.getOrNull()
            AdvRow("ev_key", stringResource(R.string.host_access_ends),
                (exp?.let { instantText(it, z, use24h, lang) } ?: "-") + " · " + stringResource(if (d.expiresAt == null) R.string.host_access_default else R.string.host_access_custom),
                onClick = { pickExpiryDate = true }) {
                if (d.expiresAt != null) TextButton(onClick = { editor.update { it.copy(expiresAt = null) } }) {
                    Text(stringResource(R.string.host_access_reset), fontWeight = FontWeight.ExtraBold, color = c.brand)
                }
            }
            AdvRow("ev_note", stringResource(R.string.ew_adv_notes), stringResource(R.string.host_organizer_notes_hint)) {
                OutlinedTextField(d.organizerNotes, { v -> editor.update { it.copy(organizerNotes = cap(v, 2000)) } }, Modifier.fillMaxWidth(),
                    minLines = 1, maxLines = 4, shape = RoundedCornerShape(Radius.md))
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AdvIcon("ev_globe")
                Text(stringResource(R.string.ew_adv_lang), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
                Row(Modifier.clip(RoundedCornerShape(Radius.pill)).background(c.background).padding(3.dp)) {
                    listOf("ms" to "BM", "en" to "EN").forEach { (code, label) ->
                        val on = d.lang == code
                        Text(label, Modifier.clip(RoundedCornerShape(Radius.pill)).background(if (on) c.brand else Color.Transparent)
                            .clickable(role = Role.RadioButton) { editor.update { it.copy(lang = code) } }.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 7.dp),
                            fontSize = 15.sp, fontWeight = FontWeight.Black, color = if (on) c.brandOn else c.text)
                    }
                }
            }
            // "Hantar sebagai fail + kod" is no longer offered (link-first sharing, 24 Oct 2026); the codec keeps suite 2.
            Button(onClick = onDismiss, Modifier.padding(top = 20.dp).fillMaxWidth().height(56.dp), shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.done), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
        }
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
                    pickExpiryTime = true
                }
                pickExpiryDate = false
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickExpiryDate = false }) { Text(stringResource(R.string.cancel)) } }) { DatePicker(st) }
    }
    if (pickExpiryTime) {
        val initial = runCatching { d.expires().atZone(d.zone()).toLocalTime() }.getOrDefault(LocalTime.NOON)
        val st = rememberTimePickerState(initial.hour, initial.minute, is24Hour = use24h)
        AlertDialog(onDismissRequest = { pickExpiryTime = false }, text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = {
                val t = LocalTime.of(st.hour, st.minute)
                editor.update { e ->
                    val day = runCatching { e.expires().atZone(e.zone()).toLocalDate() }.getOrDefault(LocalDate.now())
                    e.copy(expiresAt = day.atTime(t).atZone(e.zone()).toInstant().toString())
                }
                pickExpiryTime = false
            }) { Text(stringResource(R.string.ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { pickExpiryTime = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun AdvIcon(name: String) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Gimano.colors.brandMist), contentAlignment = Alignment.Center) { TwoToneIcon(name, null, size = 26.dp) }
    Spacer(Modifier.width(12.dp))
}

@Composable
private fun AdvRow(icon: String, title: String, sub: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Gimano.colors
    Column(Modifier.padding(top = 14.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically) {
            AdvIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
                Text(sub, fontSize = 13.sp, color = c.textMuted)
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textMuted)
        }
        Box(Modifier.padding(start = 56.dp, top = 6.dp)) { content() }
    }
}

// --- 5 · Semak (EwReview) -----------------------------------------------------------------------------------

@Composable
private fun ReviewStep(d: HostDraft, editor: HostEditor, use24h: Boolean, issues: List<DraftIssue>) {
    val c = Gimano.colors
    val lang = currentLang()
    val journey = EventWizard.isJourney(d.template)
    var safety by remember(d.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(d.id) { safety = editor.safetyCode() }
    StepTitle(stringResource(R.string.ew_review_title))
    val edit = stringResource(R.string.ew_edit)
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TwoToneIcon("ev_eye", null, size = 20.dp); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ew_guests_see), style = GimanoType.label, color = c.textMuted)
        }
        val plan = remember(d) { runCatching { HostDraftRules.toPlan(d, 1, null, Instant.EPOCH) }.getOrNull() }
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { editor.goTo(Step.WHEN, fromReview = true) }.padding(vertical = 6.dp)
            .semantics { contentDescription = edit }) {
            Text(d.title.ifBlank { stringResource(R.string.host_untitled) }, fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.text)
            plan?.let { Text(eventWhen(it, use24h, lang), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.textMuted) }
        }
        HorizontalDivider(color = c.outline)
        if (journey) {
            val start = d.stops.firstOrNull { it.kind == "start" } ?: d.stops.firstOrNull()
            val finish = d.stops.lastOrNull { it.kind == "finish" }
            ReviewRow("route", listOfNotNull(start?.label?.ifBlank { null }, finish?.label?.ifBlank { null }).joinToString(" → ")
                .ifBlank { stringResource(R.string.ew_route_title) } + " · " + pluralStringResource(R.plurals.event_stops_n, d.stops.size, d.stops.size)) {
                editor.goTo(Step.PLACE, fromReview = true)
            }
            if (d.routes.isNotEmpty()) ReviewRow(null, pluralStringResource(R.plurals.ew_routes_n, d.routes.size, d.routes.size), modeIcon = EventWizard.mode(d)) {
                editor.goTo(Step.PLACE, fromReview = true)
            }
        } else {
            val venue = mainVenue(d)
            ReviewRow("ev_place", venue?.label?.ifBlank { null } ?: stringResource(R.string.ew_place_pick)) { editor.goTo(Step.PLACE, fromReview = true) }
            val extras = d.stops.filter { it.id != venue?.id }
            if (extras.isNotEmpty()) {
                val parking = extras.count { it.kind == "parking" }
                val entrances = d.stops.count { it.entrance != null } + extras.count { it.kind == "entrance" }
                val others = extras.size - parking - extras.count { it.kind == "entrance" }
                val parts = listOfNotNull(
                    parking.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ew_parking_n, it, it) },
                    entrances.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ew_entrance_n, it, it) },
                    others.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ew_other_places_n, it, it) })
                ReviewRow("parking", parts.joinToString(" · ")) { editor.goTo(Step.PLACE, fromReview = true) }
            }
        }
        if (d.description.isNotBlank()) ReviewRow("ev_note", d.description.trim().lines().first()) { editor.goTo(Step.INFO, fromReview = true) }
        d.info.filter { it.text.isNotBlank() }.forEach { b ->
            val text = if (b.kind == HostInfo.AGENDA) infoLabel(b.kind) + " · " + HostAgenda.parse(b.text).size else infoLabel(b.kind)
            ReviewRow(infoIconName(b.kind), text) { editor.goTo(Step.INFO, fromReview = true) }
        }
    }
    issues.distinct().forEach { Warning(issueText(it)) }
    // The host's safety code, small: guests can check it behind "Penganjur disahkan".
    safety?.let { code ->
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).border(1.dp, c.outline, RoundedCornerShape(Radius.lg)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TwoToneIcon("ev_safety", null, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.ew_your_safety_code), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
            Text(code, Modifier.clip(RoundedCornerShape(Radius.sm)).background(c.brandMist).padding(horizontal = 10.dp, vertical = 4.dp),
                fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = c.brandInk)
        }
    }
    val exp = runCatching { d.expires() }.getOrNull()
    if (exp != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TwoToneIcon("ev_key", null, size = 18.dp); Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.ew_access_ends, instantText(exp, d.zone(), use24h, lang)), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
    }
}

@Composable
private fun ReviewRow(icon: String?, text: String, modeIcon: String? = null, onClick: () -> Unit) {
    val c = Gimano.colors
    val edit = stringResource(R.string.ew_edit)
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (modeIcon != null) my.gimano.app.ui.routes.ModeIcon(modeIcon, null, 24.dp) else if (icon != null) TwoToneIcon(icon, null, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TwoToneIcon("edit", edit, size = 20.dp)
    }
    HorizontalDivider(color = c.outline.copy(alpha = 0.6f))
}
