package my.gimano.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.update.UpdateManifest
import my.gimano.app.data.update.AppUpdater
import my.gimano.app.data.update.UpdateError
import my.gimano.app.data.update.UpdateUi
import androidx.compose.material.icons.filled.SystemUpdate
import my.gimano.app.ui.theme.Gimano

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)

@Composable
private fun DownloadProgress(u: UpdateUi) {
    val c = Gimano.colors
    if (u.progress != null || u.installing) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (u.progress != null) LinearProgressIndicator(progress = { u.progress }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)), color = c.brand, trackColor = c.surfaceAlt)
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)), color = c.brand, trackColor = c.surfaceAlt)
            Text(
                if (u.installing) stringResource(R.string.update_installing)
                else stringResource(R.string.update_downloading, mb(u.downloadedBytes), if (u.totalBytes > 0) mb(u.totalBytes) else "…"),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted,
            )
        }
    }
}

/** "Update available" (Cobalt): version, notes, size, Kemas kini sekarang / Nanti. */
@Composable
fun UpdateSheet(m: UpdateManifest, u: UpdateUi, lang: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    val c = Gimano.colors
    val busy = u.progress != null || u.installing
    ModalBottomSheet(onDismissRequest = { if (!busy) onLater() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { Icon(androidx.compose.material.icons.Icons.Filled.SystemUpdate, null, tint = c.brandInk, modifier = Modifier.size(36.dp)) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.update_available), fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.text)
                    Text(stringResource(R.string.update_version_line, m.versionName, m.sizeBytes?.let(::mb) ?: "—"), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted)
                }
            }
            m.notes(lang)?.let { Text(it, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), fontSize = 15.sp, color = c.text) }
            DownloadProgress(u)
            Button(onClick = onUpdate, enabled = !busy, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.update_now), fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            TextButton(onClick = onLater, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.update_later), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted)
            }
        }
    }
}

/** installed < minSupported: blocking, but the user can still export a backup first. */
@Composable
fun UpdateRequiredGate(m: UpdateManifest, u: UpdateUi, lang: String, onUpdate: () -> Unit, onBackup: () -> Unit) {
    val c = Gimano.colors
    Box(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.systemBars), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { Icon(androidx.compose.material.icons.Icons.Filled.SystemUpdate, null, tint = c.brandInk, modifier = Modifier.size(48.dp)) }
            Text(stringResource(R.string.update_required), fontSize = 26.sp, fontWeight = FontWeight.Black, color = c.text)
            Text(stringResource(R.string.update_required_body, m.versionName), fontSize = 16.sp, color = c.textMuted, fontWeight = FontWeight.Bold)
            m.notes(lang)?.let { Text(it, fontSize = 15.sp, color = c.text) }
            DownloadProgress(u)
            Button(onClick = onUpdate, enabled = u.progress == null && !u.installing, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.update_now), fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Text(stringResource(R.string.update_backup_first), fontWeight = FontWeight.Black, color = c.brandInk)
            }
        }
    }
}

/** Shared dialogs: "Install unknown apps" explainer (then Settings; resumes the update on return) and errors. */
@Composable
fun UpdateDialogs(updater: AppUpdater, u: UpdateUi) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var waitingForPermission by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && waitingForPermission && updater.canInstall()) {
                waitingForPermission = false
                scope.launch { updater.updateNow() }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (u.needsInstallPermission) AlertDialog(
        onDismissRequest = updater::dismissPermission,
        title = { Text(stringResource(R.string.update_permission_title), fontWeight = FontWeight.Black) },
        text = { Text(stringResource(R.string.update_permission_body)) },
        confirmButton = {
            TextButton(onClick = {
                updater.dismissPermission(); waitingForPermission = true
                runCatching { ctx.startActivity(updater.installPermissionIntent()) }
            }) { Text(stringResource(R.string.update_permission_open), fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = updater::dismissPermission) { Text(stringResource(R.string.cancel)) } },
    )
    u.error?.let { e ->
        AlertDialog(
            onDismissRequest = updater::clearError,
            title = { Text(stringResource(R.string.update_failed), fontWeight = FontWeight.Black) },
            text = {
                Text(when (e) {
                    UpdateError.Network -> stringResource(R.string.update_err_network)
                    UpdateError.HashMismatch -> stringResource(R.string.update_err_hash)
                    UpdateError.NotGimano -> stringResource(R.string.update_err_not_gimano)
                    UpdateError.SignerMismatch -> stringResource(R.string.update_err_signer)
                    is UpdateError.InstallFailed -> stringResource(R.string.update_err_install, e.message ?: "—")
                })
            },
            confirmButton = { TextButton(onClick = updater::clearError) { Text(stringResource(R.string.ok), fontWeight = FontWeight.Black) } },
        )
    }
}
