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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.gimano.app.BuildConfig
import my.gimano.app.R
import my.gimano.app.core.events.EventLinks
import my.gimano.app.core.events.EventPlan
import my.gimano.app.core.events.EventTransport
import my.gimano.app.core.events.EventWizard
import my.gimano.app.core.events.InviteMessages
import my.gimano.app.core.events.ShareMode
import my.gimano.app.data.events.HostEditor
import my.gimano.app.data.events.Publication
import my.gimano.app.ui.common.TwoToneIcon
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.io.File

/*
 * "Kongsi jemputan", file first (canvas EwShare; docs/events.md §5.2.1). Default: one share action sends the open
 * (suite-3) file plus a short text, no code. "Acara sensitif": a suite-2 file and the 8-character code, sent separately.
 * The web link stays as a secondary "Salin pautan web". Everything leaves through the host's own share sheet.
 */

private const val SHARED_DIR = "shared"

private fun copySensitive(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    // The link and code are secrets: keep them out of clipboard previews (Android 13+).
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
}

private fun shareText(context: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(i, null)) }
}

/**
 * Step 2: the file alone through the share sheet (FileProvider, MIME application/vnd.manotu.event). No EXTRA_TEXT: the
 * message went in step 1, and apps that do keep a document's text would show it twice.
 */
private fun shareFile(context: Context, plan: EventPlan, bytes: ByteArray): Boolean = runCatching {
    val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
    dir.listFiles { f -> f.name.endsWith("." + EventTransport.FILE_EXTENSION) }?.forEach { it.delete() }
    val f = File(dir, InviteMessages.fileName(plan))
    f.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).setType(EventTransport.FILE_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri("", uri)
    context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}.isSuccess

/** A numbered share step: filled when it is the next thing to do, a tick once done. */
@Composable
private fun ShareStepButton(n: Int, text: String, done: Boolean, primary: Boolean, enabled: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Gimano.colors
    val content: @Composable RowScope.() -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = if (primary) c.brandOn else c.brand, strokeWidth = 2.dp)
        else {
            if (done) Icon(Icons.Filled.Check, stringResource(R.string.ew_share_step_done), modifier = Modifier.size(20.dp))
            else Text("$n ·", fontWeight = FontWeight.Black, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn), content = content)
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.brand), content = content)
}

private fun sizeText(n: Int): String = if (n < 1024) "$n B" else if (n < 1024 * 1024) "${(n + 1023) / 1024} KB" else "%.1f MB".format(n / 1048576.0)

@Composable
fun EventShareScreen(editor: HostEditor, use24h: Boolean, onBack: () -> Unit) {
    val c = Gimano.colors
    val context = LocalContext.current
    val p = editor.publication.collectAsStateWithLifecycle().value ?: return
    val d = editor.draft.collectAsStateWithLifecycle().value
    val sealing by editor.sealingCodeFile.collectAsStateWithLifecycle()
    val sensitive = d?.sensitive == true
    val plan = p.plan
    val openFile = remember(p) { runCatching { p.file() }.getOrNull() }
    val codeFile = p.codeFile()
    val file = if (sensitive) codeFile else openFile
    val message = if (sensitive) InviteMessages.fileMessage(plan, BuildConfig.DOWNLOAD_URL) else InviteMessages.openFileMessage(plan, BuildConfig.DOWNLOAD_URL)
    val link = remember(p) { p.link(EventLinks.DEFAULT_SITE) }
    var copied by remember { mutableStateOf<Int?>(null) }
    // Step 1 is done once the share sheet for the message returns (the message changes with "Acara sensitif").
    var messageSent by remember(p, sensitive) { mutableStateOf(false) }
    val sendMessage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { messageSent = true }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EventTransport.FILE_MIME)) { uri ->
        val bytes = file
        if (uri != null && bytes != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
    }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.host_share_invite), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
            PrivateChip()
            Spacer(Modifier.width(12.dp))
        }
        WizardProgress(EventWizard.Step.REVIEW, EventWizard.isJourney(d?.template ?: ""), reachable = { false }, onStep = null)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TwoToneIcon("ev_eye", null, size = 20.dp); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ew_share_guests_get), style = GimanoType.label, color = c.textMuted)
            }
            // A chat-style preview of what lands in the group: the file and the text.
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.surfaceAlt).padding(16.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface).padding(10.dp)) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.brandMist).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TwoToneIcon("ev_file", null, size = 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(InviteMessages.fileName(plan), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(file?.size?.let(::sizeText), "Manotu").joinToString(" · "), fontSize = 13.sp, color = c.textMuted)
                        }
                        if (sensitive && sealing) CircularProgressIndicator(Modifier.size(20.dp), color = c.brand, strokeWidth = 2.dp)
                    }
                    val lines = message.lines()
                    lines.forEachIndexed { i, line ->
                        val url = BuildConfig.DOWNLOAD_URL
                        if (line.contains(url)) Text(line.replace(url, url.removePrefix("https://").trimEnd('/')), fontSize = 15.sp, color = c.text,
                            textDecoration = TextDecoration.None, modifier = Modifier.padding(top = if (i == 0) 8.dp else 0.dp))
                        else Text(line, fontSize = 15.sp, color = c.text, modifier = Modifier.padding(top = if (i == 0) 8.dp else 0.dp))
                    }
                }
            }
            if (!sensitive) {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TwoToneIcon("ev_safety", null, size = 20.dp); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ew_share_no_code), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
                }
            }
            // "Acara sensitif"
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.surface)
                .border(1.dp, if (sensitive) c.brand else c.outline, RoundedCornerShape(Radius.lg))
                .clickable(role = Role.Switch, enabled = !sealing) { editor.setSensitive(!sensitive) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { TwoToneIcon("ev_lock", null, size = 26.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ew_share_sensitive), fontSize = 17.sp, fontWeight = FontWeight.Black, color = c.text)
                    Text(stringResource(R.string.ew_share_sensitive_sub), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
                }
                Switch(checked = sensitive, enabled = !sealing, onCheckedChange = { editor.setSensitive(it) })
            }
            if (sensitive) {
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.brandMist).padding(12.dp)) {
                    Text(stringResource(R.string.host_secret_code), style = GimanoType.label, color = c.brandInk)
                    Text(p.secretCode, fontFamily = FontFamily.Monospace, fontSize = 26.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, color = c.text,
                        modifier = Modifier.padding(vertical = 6.dp))
                    Text(stringResource(R.string.host_secret_code_hint), fontSize = 13.sp, color = c.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = { copySensitive(context, "Manotu", p.secretCode); copied = R.string.host_copied_code }) {
                            Text(stringResource(R.string.host_copy_code), fontWeight = FontWeight.ExtraBold, color = c.brand)
                        }
                        TextButton(onClick = { shareText(context, InviteMessages.codeMessage(plan, p.secretCode)) }) {
                            Text(stringResource(R.string.host_share_code), fontWeight = FontWeight.ExtraBold, color = c.brand)
                        }
                    }
                }
            }
            // Secondary: the web link (web viewer, older apps), or the stops-only link when the full one is too long.
            if (link != null) TextButton(onClick = { copySensitive(context, "Manotu", link); copied = R.string.host_copied_link }, Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp), tint = c.brand); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ew_share_copy_web_link), fontWeight = FontWeight.ExtraBold, color = c.brand)
            } else {
                val stopsOnly = remember(p) { p.stopsOnlyLink(EventLinks.DEFAULT_SITE) }
                if (p.mode == ShareMode.STOPS_ONLY_OR_FILE && stopsOnly != null) TextButton(onClick = { copySensitive(context, "Manotu", stopsOnly); copied = R.string.host_copied_link },
                    Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp), tint = c.brand); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ew_share_copy_stops_only), fontWeight = FontWeight.ExtraBold, color = c.brand)
                }
                Text(stringResource(R.string.ew_share_link_too_long), fontSize = 13.sp, color = c.textMuted)
            }
            copied?.let { Text(stringResource(it), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.brandInk, modifier = Modifier.padding(top = 4.dp)) }
            Text(stringResource(if (p.reused) R.string.host_share_same_rev else R.string.host_share_rev, plan.rev.toInt()), fontSize = 13.sp, color = c.textMuted,
                modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(16.dp))
        }
        // Two sends to the same chat (WhatsApp drops the text of a shared document): 1 · the message, 2 · the file.
        Column(Modifier.fillMaxWidth().background(c.surface).padding(16.dp).navigationBarsPadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShareStepButton(1, stringResource(R.string.ew_share_send_message), done = messageSent, primary = !messageSent, enabled = true,
                    busy = false, modifier = Modifier.weight(1f)) {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                    runCatching { sendMessage.launch(Intent.createChooser(i, null)) }
                }
                ShareStepButton(2, stringResource(R.string.ew_share_send_file), done = false, primary = messageSent, enabled = file != null,
                    busy = file == null && sealing, modifier = Modifier.weight(1f)) {
                    file?.let { shareFile(context, plan, it) }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ew_share_two_steps_hint), Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted)
                TextButton(onClick = { saveFile.launch(InviteMessages.fileName(plan)) }, enabled = file != null) {
                    Text(stringResource(R.string.ew_share_save), fontWeight = FontWeight.ExtraBold, color = c.brand)
                }
            }
        }
    }
}
