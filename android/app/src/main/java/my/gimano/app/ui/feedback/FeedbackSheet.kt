package my.gimano.app.ui.feedback

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius

/** Feedback categories: id, label, Gimano icon (or a Material fallback for bug/idea/route). */
val FEEDBACK_CATEGORIES = listOf(
    Triple("bug", R.string.fb_bug, null),
    Triple("idea", R.string.fb_idea, null),
    Triple("map_data", R.string.fb_map, "map"),
    Triple("route", R.string.fb_route, null),
    Triple("search", R.string.fb_search, "search"),
    Triple("other", R.string.fb_other, "feedback"),
)

@Composable
fun FeedbackCategoryIcon(cat: String, size: androidx.compose.ui.unit.Dp) {
    val c = Gimano.colors
    when (cat) {
        "bug" -> Icon(Icons.Filled.BugReport, null, tint = c.brandInk, modifier = Modifier.size(size))
        "idea" -> Icon(Icons.Filled.Lightbulb, null, tint = c.brandInk, modifier = Modifier.size(size))
        "route" -> Icon(Icons.Filled.AltRoute, null, tint = c.brandInk, modifier = Modifier.size(size))
        else -> GIcon(FEEDBACK_CATEGORIES.firstOrNull { it.first == cat }?.third ?: "feedback", null, size = size)
    }
}

@Composable
fun FeedbackSheet(vm: FeedbackViewModel, d: FeedbackDraft, onSend: () -> Unit, here: () -> Pair<Double, Double>? = { null }) {
    val c = Gimano.colors
    var preview by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    preview?.let { JsonPreviewDialog(stringResource(R.string.fb_what_is_sent), it) { preview = null } }
    ModalBottomSheet(
        onDismissRequest = vm::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding()) {
            // Big icons, little text: 3×2 category grid.
            FEEDBACK_CATEGORIES.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (id, label, _) ->
                        val sel = d.category == id
                        val name = stringResource(label)
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(Radius.tile))
                                .background(if (sel) c.brandMist else c.surfaceAlt)
                                .then(if (sel) Modifier.border(2.dp, c.brand, RoundedCornerShape(Radius.tile)) else Modifier)
                                .clickable { vm.update { it.copy(category = id) } }
                                .semantics { contentDescription = name }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            FeedbackCategoryIcon(id, 40.dp)
                            Text(name, style = GimanoType.label, color = c.text, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                    }
                }
            }
            OutlinedTextField(
                d.message, { m -> vm.update { it.copy(message = m.take(2000)) } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3, maxLines = 6,
                placeholder = { Text(stringResource(R.string.fb_message_hint)) }, shape = RoundedCornerShape(Radius.md),
            )
            // Optional 1–5 rating.
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                (1..5).forEach { n ->
                    val on = (d.rating ?: 0) >= n
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).clickable { vm.update { it.copy(rating = if (it.rating == n) null else n) } }
                            .semantics { contentDescription = "$n" },
                        contentAlignment = Alignment.Center,
                    ) { GIcon("star", null, size = 34.dp, stroke = if (on) c.report else c.outline, fill = if (on) c.report else c.surface) }
                }
            }
            ToggleRow(stringResource(R.string.fb_include_screenshot), d.includeScreenshot, enabled = d.screenshot != null) { v -> vm.update { it.copy(includeScreenshot = v) } }
            if (d.includeScreenshot && d.screenshot != null) {
                val bmp = remember(d.screenshot) { BitmapFactory.decodeByteArray(d.screenshot, 0, d.screenshot.size)?.asImageBitmap() }
                if (bmp != null) Image(bmp, stringResource(R.string.fb_include_screenshot), Modifier.height(140.dp).clip(RoundedCornerShape(Radius.sm)), contentScale = ContentScale.Fit)
            }
            ToggleRow(stringResource(R.string.fb_include_device), d.includeDevice) { v -> vm.update { it.copy(includeDevice = v) } }
            Text(stringResource(R.string.fb_device_note), fontSize = 12.sp, color = c.textMuted)
            ToggleRow(stringResource(R.string.fb_include_location), d.includeLocation) { v -> vm.update { it.copy(includeLocation = v) } }
            Text(stringResource(R.string.fb_location_note), fontSize = 12.sp, color = c.textMuted)
            if (d.includeDevice || d.includeLocation) TextButton(onClick = { val h = here(); preview = vm.previewJson(h?.first, h?.second) }) {
                Text(stringResource(R.string.fb_what_is_sent), fontWeight = FontWeight.ExtraBold, color = c.brandInk)
            }
            ContactSection(d.email, d.whatsapp, d.consent) { e, w, k -> vm.update { it.copy(email = e, whatsapp = w, consent = k) } }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onSend, enabled = d.canSend, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn),
            ) { Text(stringResource(R.string.send), style = GimanoType.button) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (enabled) Gimano.colors.text else Gimano.colors.textMuted)
        Switch(checked && enabled, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Gimano.colors.brand))
    }
}
