package my.gimano.app.ui.events

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import my.gimano.app.ui.common.TwoToneIcon
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import kotlinx.coroutines.launch
import java.io.File

/*
 * "Kongsi jemputan", link first (owner decision 24 Oct 2026, after device testing: files were too much work for
 * guests). One share action sends the invitation text with the short link (§5.6, uploaded on the first tap, PUT after a
 * republish); "Salin pautan" copies the same link. When the short link can't be made: the full link (§5.1), or when
 * that is too long the stops-only link (§5.5) after a confirm. Files (suites 2 and 3) stay supported by the
 * codec and guests still open them; the host UI no longer offers them, except as the last resort when no link fits.
 */

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

/** Last resort when no link fits (§5.1): the open file through the share sheet, with the text as EXTRA_TEXT. */
private fun shareFile(context: Context, plan: EventPlan, bytes: ByteArray, text: String): Boolean = runCatching {
    val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
    dir.listFiles { f -> f.name.endsWith("." + EventTransport.FILE_EXTENSION) }?.forEach { it.delete() }
    val f = File(dir, InviteMessages.fileName(plan))
    f.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).setType(EventTransport.FILE_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = ClipData.newRawUri("", uri)
    context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}.isSuccess

/** The link as shown in the preview: its first 32 characters and an ellipsis (the real message carries all of it). */
internal fun shortLink(link: String): String = if (link.length <= 33) link else link.take(32) + "…"

@Composable
fun EventShareScreen(editor: HostEditor, use24h: Boolean, onBack: () -> Unit) {
    val c = Gimano.colors
    val context = LocalContext.current
    val p = editor.publication.collectAsStateWithLifecycle().value ?: return
    val d = editor.draft.collectAsStateWithLifecycle().value
    val plan = p.plan
    val full = remember(p) { p.link(EventLinks.DEFAULT_SITE) }
    val stopsOnly = remember(p) { if (full == null) p.stopsOnlyLink(EventLinks.DEFAULT_SITE) else null }
    var stopsOnlyOk by remember(p) { mutableStateOf(false) }
    var askStopsOnly by remember(p) { mutableStateOf(false) }
    // The short link (§5.6) is made on the first tap of "Kongsi jemputan" / "Salin pautan"; when it can't be made, the
    // full link (or the stops-only link after a confirm, or the file) as before, with a one-line note.
    val short by editor.shortLink.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val shortFailed = short is HostEditor.ShortLink.Failed
    val working = short == HostEditor.ShortLink.Working
    val fallback = full ?: stopsOnly?.takeIf { stopsOnlyOk }
    val link = (short as? HostEditor.ShortLink.Ready)?.link ?: fallback
    val canShare = !working && (!shortFailed || fallback != null)
    var copied by remember { mutableStateOf<Int?>(null) }
    /** The short link if it can be made, else the fallback link (null: nothing fits, see the file below). */
    fun withLink(use: (String) -> Unit) {
        scope.launch { (editor.shortLinkForShare() ?: fallback)?.let(use) }
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
            // A chat-style preview of the message guests receive (the link shortened for display only).
            val previewLink = (short as? HostEditor.ShortLink.Ready)?.link
                ?: (if (shortFailed) link ?: full ?: stopsOnly else null) ?: EventLinks.DEFAULT_SITE + "/j/#…"
            val preview = InviteMessages.linkMessage(plan, shortLink(previewLink), BuildConfig.DOWNLOAD_URL)
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.surfaceAlt).padding(16.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface).padding(12.dp)) {
                    val url = BuildConfig.DOWNLOAD_URL
                    preview.lines().forEach { line ->
                        Text(if (line.contains(url)) line.replace(url, url.removePrefix("https://").trimEnd('/')) else line, fontSize = 15.sp, color = c.text)
                    }
                }
            }
            if (working) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = c.brand, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.host_short_link_working), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted)
            }
            if (shortFailed) Text(stringResource(R.string.host_short_link_failed), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted,
                modifier = Modifier.padding(top = 12.dp))
            if (short is HostEditor.ShortLink.Ready) Text(stringResource(R.string.host_short_link_on), fontSize = 13.sp, color = c.textMuted,
                modifier = Modifier.padding(top = 12.dp))
            // No short link and too long for a full link: the stops-only link after a confirm, or (last resort) the file.
            if (full == null && shortFailed) {
                Warning(stringResource(R.string.host_link_too_long, p.fragmentLength, my.gimano.app.core.events.EventLimits.MAX_FRAGMENT_CHARS))
                when {
                    p.mode == ShareMode.STOPS_ONLY_OR_FILE && stopsOnly != null && !stopsOnlyOk -> {
                        Text(stringResource(R.string.host_stops_only_body), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
                        OutlinedButton(onClick = { askStopsOnly = true }, Modifier.padding(top = 8.dp).fillMaxWidth().height(50.dp), shape = RoundedCornerShape(Radius.md)) {
                            Text(stringResource(R.string.host_stops_only_show), fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    p.mode == ShareMode.FILE_ONLY -> {
                        Text(stringResource(R.string.ew_share_file_only), fontSize = 14.sp, color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
                        val file = remember(p) { runCatching { p.file() }.getOrNull() }
                        if (file != null) OutlinedButton(onClick = { shareFile(context, plan, file, InviteMessages.openFileMessage(plan, BuildConfig.DOWNLOAD_URL)) },
                            Modifier.padding(top = 8.dp).fillMaxWidth().height(50.dp), shape = RoundedCornerShape(Radius.md)) {
                            Text(stringResource(R.string.ew_share_send_file), fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
            copied?.let { Text(stringResource(it), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.brandInk, modifier = Modifier.padding(top = 8.dp)) }
            Text(stringResource(if (p.reused) R.string.host_share_same_rev else R.string.host_share_rev, plan.rev.toInt()), fontSize = 13.sp, color = c.textMuted,
                modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().background(c.surface).padding(16.dp).navigationBarsPadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { withLink { copySensitive(context, "Manotu", it); copied = R.string.host_copied_link } }, enabled = canShare,
                    modifier = Modifier.height(56.dp), shape = RoundedCornerShape(Radius.md)) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ew_share_copy_link), fontWeight = FontWeight.ExtraBold, color = c.brand)
                }
                Button(onClick = { withLink { shareText(context, InviteMessages.linkMessage(plan, it, BuildConfig.DOWNLOAD_URL)) } }, enabled = canShare,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(Radius.md), colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                    if (working) CircularProgressIndicator(Modifier.size(22.dp), color = c.brandOn, strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.host_share_invite), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    }
                }
            }
            Text(stringResource(R.string.ew_share_link_hint), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (askStopsOnly) AlertDialog(
        onDismissRequest = { askStopsOnly = false },
        text = { Text(stringResource(R.string.host_stops_only_confirm), fontSize = 16.sp, color = c.text) },
        confirmButton = { TextButton(onClick = { askStopsOnly = false; stopsOnlyOk = true }) { Text(stringResource(R.string.host_stops_only_title), fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = { askStopsOnly = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
