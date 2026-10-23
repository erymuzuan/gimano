package my.gimano.app.ui.contribute

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.ui.theme.Gimano

/** "Belum disahkan · 1 daripada 3" (canvas "Nearby driver: verify prompt"). */
@Composable
fun UnverifiedChip(confirmed: Double?, required: Double?, modifier: Modifier = Modifier) {
    val text = if (confirmed != null && required != null) stringResource(R.string.vet_unverified_n, fmtWeight(confirmed), fmtWeight(required))
    else stringResource(R.string.unverified)
    Text(text, modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFFFF1CC)).padding(horizontal = 10.dp, vertical = 3.dp),
        color = Color(0xFF7A5200), fontSize = 12.sp, fontWeight = FontWeight.Black)
}

/** "Emas": the contributor is in the Gold tier, so the place was published immediately. */
@Composable
fun GoldChip(modifier: Modifier = Modifier) {
    Text("★ " + stringResource(R.string.tier_emas), modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFFFF3C4)).padding(horizontal = 10.dp, vertical = 3.dp),
        color = Color(0xFF6B4E00), fontSize = 12.sp, fontWeight = FontWeight.Black)
}

/**
 * "Is this place here?" + optional "name right" / "location right" chips + Ya, ada / Tiada / Tak pasti.
 * Used by the nearby prompt and by the place card of a pending place.
 */
@Composable
fun VerifyPanel(onAnswer: (answer: String, nameCorrect: Boolean?, locationCorrect: Boolean?) -> Unit, enabled: Boolean = true) {
    val c = Gimano.colors
    var nameOk by remember { mutableStateOf<Boolean?>(null) }
    var locOk by remember { mutableStateOf<Boolean?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.vet_question), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.vet_name_ok), nameOk == true) { nameOk = if (nameOk == true) null else true }
            ToggleChip(stringResource(R.string.vet_loc_ok), locOk == true) { locOk = if (locOk == true) null else true }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnswerButton(stringResource(R.string.vet_yes), Color(0xFF0E8A5F), Color.White, Modifier.weight(1f), enabled) { onAnswer("yes", nameOk, locOk) }
            AnswerButton(stringResource(R.string.vet_no), Color(0xFFFFE2E2), Color(0xFF8B1A1A), Modifier.weight(1f), enabled) { onAnswer("no", nameOk, locOk) }
            AnswerButton(stringResource(R.string.vet_unsure), c.surfaceAlt, c.textMuted, Modifier.weight(1f), enabled) { onAnswer("unsure", nameOk, locOk) }
        }
        Text(stringResource(R.string.vet_footer), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textMuted, lineHeight = 17.sp)
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Gimano.colors
    Text((if (on) "✓ " else "") + label,
        Modifier.height(36.dp).clip(RoundedCornerShape(999.dp)).background(if (on) c.brandMist else c.surfaceAlt)
            .semantics { role = Role.Checkbox; selected = on }.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Black, color = if (on) c.brandInk else c.textMuted)
}

@Composable
private fun AnswerButton(label: String, bg: Color, fg: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier.height(64.dp).clip(RoundedCornerShape(18.dp)).background(if (enabled) bg else bg.copy(alpha = 0.5f))
        .semantics { role = Role.Button }.clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    }
}

/** Tier colours from the canvas "Trust tiers" board. */
fun tierColor(tier: String): Color = when (tier) {
    "emas" -> Color(0xFFE0A800)
    "perak" -> Color(0xFF6B7A94)
    else -> Color(0xFF8FB0E8)
}

@Composable
fun tierName(tier: String): String = stringResource(
    when (tier) { "emas" -> R.string.tier_emas; "perak" -> R.string.tier_perak; else -> R.string.tier_baharu },
)

@Composable
fun TierBadge(tier: String) {
    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(tierColor(tier)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("★ " + tierName(tier), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
