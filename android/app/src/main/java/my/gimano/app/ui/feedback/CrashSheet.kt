package my.gimano.app.ui.feedback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.feedback.ContactRules
import my.gimano.app.data.crash.CrashMode
import my.gimano.app.data.crash.CrashRecord
import my.gimano.app.data.crash.CrashReporter
import my.gimano.app.data.model.ContactDetails
import my.gimano.app.ui.theme.Gimano

/** "Manotu terhenti tadi. Hantar laporan?" on the launch after a crash (ask mode). */
@Composable
fun CrashSheet(crashes: CrashReporter, records: List<CrashRecord>) {
    val c = Gimano.colors
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var wa by remember { mutableStateOf("+60 ") }
    var consent by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(crashes.mode) }
    var details by remember { mutableStateOf<String?>(null) }
    // Device info is opt-in for crash reports too (starts unticked; shown in "See details").
    var includeDevice by remember { mutableStateOf(FeedbackConsent.DEFAULT_DEVICE) }
    val r = records.last()
    val contactState = ContactRules.state(email, wa, consent)
    val contact = (contactState as? ContactRules.State.Ok)?.let { ContactDetails(it.email, it.whatsapp, true) }
    details?.let { JsonPreviewDialog(stringResource(R.string.crash_details), it) { details = null } }

    fun finish(send: Boolean) = scope.launch {
        crashes.mode = mode
        records.forEach { if (send) crashes.send(it, contact, includeDevice) else crashes.discard(it) }
    }

    ModalBottomSheet(onDismissRequest = { finish(send = false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 26.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.crash_title), fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.text)
            Text(stringResource(R.string.crash_body), fontSize = 15.sp, color = c.textMuted, fontWeight = FontWeight.Bold)
            TextButton(onClick = { details = crashes.previewJson(r, contact, includeDevice) }) { Text(stringResource(R.string.crash_details), fontWeight = FontWeight.ExtraBold, color = c.brandInk) }
            Row(Modifier.fillMaxWidth().clickable { includeDevice = !includeDevice }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeDevice, { includeDevice = it })
                Text(stringResource(R.string.fb_include_device), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.text)
            }
            ContactSection(email, wa, consent) { e, w, k -> email = e; wa = w; consent = k }
            Text(stringResource(R.string.crash_remember), fontSize = 14.sp, fontWeight = FontWeight.Black, color = c.text)
            CrashModeChoice(mode) { mode = it }
            Button(onClick = { finish(send = true) }, enabled = contactState is ContactRules.State.None || contactState is ContactRules.State.Ok,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.crash_send), fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
            TextButton(onClick = { finish(send = false) }, Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.crash_dont_send), fontWeight = FontWeight.ExtraBold, color = c.textMuted)
            }
        }
    }
}

@Composable
fun CrashModeChoice(mode: CrashMode, onPick: (CrashMode) -> Unit) {
    listOf(CrashMode.ASK to R.string.crash_mode_ask, CrashMode.ALWAYS to R.string.crash_mode_always, CrashMode.NEVER to R.string.crash_mode_never).forEach { (m, l) ->
        Row(Modifier.fillMaxWidth().clickable { onPick(m) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == m, { onPick(m) })
            Text(stringResource(l), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Gimano.colors.text)
        }
    }
}
