package my.gimano.app.ui.backup

import androidx.compose.ui.graphics.Color

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.gimano.app.R
import my.gimano.app.data.backup.RestoreMode
import my.gimano.app.ui.common.PrivacyCard
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius

@Composable
fun BackupScreen(vm: BackupViewModel, onBack: () -> Unit) {
    val c = Gimano.colors
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) vm.writeTo(uri) }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restoreFromUri(uri) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> vm.onDriveConsent(r.data) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is BackupEvent.Message -> Toast.makeText(context, context.getString(e.res, e.arg ?: ""), Toast.LENGTH_LONG).show()
                is BackupEvent.CreateFile -> createDoc.launch(e.suggestedName)
                is BackupEvent.Share -> context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, e.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                is BackupEvent.DriveConsent -> consent.launch(IntentSenderRequest.Builder(e.intent.intentSender).build())
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.backup_restore), style = GimanoType.title, color = c.text)
        }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = c.brand, trackColor = c.surfaceAlt)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            PrivacyCard(onBackup = null)
            Spacer(Modifier.height(14.dp))
            val last = s.lastBackupAt
            Text(
                if (last != null) stringResource(R.string.last_backup, my.gimano.app.core.format.Formatters.localStamp(last), "%.1f KB".format(s.lastBackupBytes / 1024.0),
                    when (s.lastBackupDest) { "drive" -> "Google Drive"; "file" -> stringResource(R.string.backup_dest_file); else -> s.lastBackupDest ?: "" })
                else stringResource(R.string.no_backup_yet),
                color = c.textMuted, fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            SwitchLine(Icons.Filled.Lock, stringResource(R.string.use_passphrase), stringResource(R.string.use_passphrase_desc), ui.usePassphrase, vm::setUsePassphrase)
            // The backup carries the contributor key (identity table); say so when it isn't passphrase-locked.
            if (!ui.usePassphrase) Text(
                stringResource(R.string.backup_key_warning),
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFF1CC)).padding(12.dp),
                color = Color(0xFF7A5200), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(8.dp))
            BigAction(Icons.Filled.CloudUpload, if (vm.driveConfigured) stringResource(R.string.backup_to_drive) else stringResource(R.string.drive_not_configured), enabled = vm.driveConfigured && !ui.busy) { vm.backup(PassphrasePurpose.BACKUP_DRIVE) }
            BigAction(Icons.Filled.FileDownload, stringResource(R.string.backup_save_file), enabled = !ui.busy) { vm.backup(PassphrasePurpose.BACKUP_FILE) }
            BigAction(Icons.Filled.Share, stringResource(R.string.backup_share), enabled = !ui.busy) { vm.backup(PassphrasePurpose.BACKUP_SHARE) }
            Spacer(Modifier.height(8.dp))
            BigAction(Icons.Filled.Restore, stringResource(R.string.restore_from_file), enabled = !ui.busy) { openDoc.launch(arrayOf("*/*")) }
            if (vm.driveConfigured) BigAction(Icons.Filled.Restore, stringResource(R.string.restore_from_drive), enabled = !ui.busy) { vm.listDrive() }
            Spacer(Modifier.height(8.dp))
            SwitchLine(
                Icons.Filled.CloudUpload, stringResource(R.string.auto_backup),
                stringResource(if (!vm.driveConfigured) R.string.auto_backup_needs_drive else R.string.auto_backup_desc),
                s.autoBackup && vm.driveConfigured, vm::setAutoBackup, enabled = vm.driveConfigured,
            )
            Text(stringResource(R.string.android_auto_backup_note), fontSize = 13.sp, color = c.textMuted, modifier = Modifier.padding(top = 12.dp))
        }
    }

    ui.passphrasePrompt?.let { p -> PassphraseDialog(p, vm) }
    ui.pendingRestore?.takeIf { ui.passphrasePrompt == null }?.let { pr ->
        val n = pr.manifest.counts
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text(stringResource(R.string.restore_title), style = GimanoType.title) },
            text = { Text(stringResource(R.string.restore_body, my.gimano.app.core.format.Formatters.localStamp(pr.manifest.createdAt), pr.manifest.platform, n.saved_places, n.recents, n.private_places)) },
            confirmButton = { Button(onClick = { vm.confirmRestore(RestoreMode.MERGE) }) { Text(stringResource(R.string.restore_merge)) } },
            dismissButton = { OutlinedButton(onClick = { vm.confirmRestore(RestoreMode.REPLACE) }) { Text(stringResource(R.string.restore_replace)) } },
        )
    }
    ui.driveFiles?.let { files ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text(stringResource(R.string.restore_from_drive)) },
            text = {
                Column {
                    if (files.isEmpty()) Text(stringResource(R.string.no_backup_yet))
                    files.forEach { f -> Text(my.gimano.app.core.format.Formatters.localStamp(f.createdTime) + "  " + f.name +
                        (if (f.installId != null && f.installId == vm.driveInstallId) " · " + stringResource(R.string.backup_this_phone) else ""), Modifier.fillMaxWidth().clickable { vm.restoreFromDrive(f) }.padding(vertical = 12.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = vm::cancelRestore) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PassphraseDialog(p: PassphrasePrompt, vm: BackupViewModel) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val restoring = p.purpose == PassphrasePurpose.RESTORE
    AlertDialog(
        onDismissRequest = vm::cancelPrompt,
        title = { Text(stringResource(if (restoring) R.string.enter_passphrase else R.string.set_passphrase)) },
        text = {
            Column {
                if (!restoring) Text(stringResource(R.string.passphrase_warning), fontSize = 13.sp, color = Gimano.colors.report)
                OutlinedTextField(pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), label = { Text(stringResource(R.string.passphrase)) })
                if (!restoring) OutlinedTextField(confirm, { confirm = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), label = { Text(stringResource(R.string.passphrase_confirm)) })
                p.error?.let { Text(stringResource(it), color = Gimano.colors.report, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = { Button(onClick = { vm.submitPassphrase(pass, if (restoring) null else confirm) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = vm::cancelPrompt) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BigAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(Radius.md)).background(c.surface).clickable(enabled = enabled, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.brandMist), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (enabled) c.brand else c.textMuted, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = if (enabled) c.text else c.textMuted)
    }
}

@Composable
private fun SwitchLine(icon: ImageVector, title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.brand, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
            Text(desc, fontSize = 13.sp, color = c.textMuted)
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.brand))
    }
}
