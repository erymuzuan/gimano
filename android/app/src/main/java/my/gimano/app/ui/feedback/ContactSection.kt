package my.gimano.app.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.feedback.ContactRules
import my.gimano.app.ui.theme.Gimano

/**
 * "Boleh kami hubungi anda tentang laporan ini?" (optional, collapsed). Never prefilled; consent is explicit.
 */
@Composable
fun ContactSection(email: String, whatsapp: String, consent: Boolean, onChange: (email: String, whatsapp: String, consent: Boolean) -> Unit) {
    val c = Gimano.colors
    var open by remember { mutableStateOf(email.isNotBlank()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceAlt).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.contact_question), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Black, color = c.text)
            Text(if (open) "−" else "+", fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.brandInk)
        }
        if (open) {
            OutlinedTextField(email, { onChange(it.take(254), whatsapp, consent) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.contact_email)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(whatsapp, { onChange(email, it.take(24), consent) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.contact_whatsapp)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            Row(Modifier.fillMaxWidth().clickable { onChange(email, whatsapp, !consent) }, verticalAlignment = Alignment.Top) {
                Checkbox(consent, { onChange(email, whatsapp, it) })
                Text(stringResource(R.string.contact_consent), Modifier.padding(top = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
            }
            when (ContactRules.state(email, whatsapp, consent)) {
                ContactRules.State.BadEmail -> Text(stringResource(R.string.contact_bad_email), color = c.report, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                ContactRules.State.BadPhone -> Text(stringResource(R.string.contact_bad_phone), color = c.report, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                ContactRules.State.NeedsConsent -> Text(stringResource(R.string.contact_needs_consent), color = c.report, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                else -> Unit
            }
        }
    }
}

/** "Lihat apa yang dihantar" / "Lihat butiran": the exact JSON. */
@Composable
fun JsonPreviewDialog(title: String, json: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = { Text(json, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ok), fontWeight = FontWeight.Black) } },
    )
}
