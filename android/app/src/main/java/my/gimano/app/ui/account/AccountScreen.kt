package my.gimano.app.ui.account

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.gimano.app.AppContainer
import my.gimano.app.R
import my.gimano.app.core.identity.Bip39
import my.gimano.app.data.model.User
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.contribute.TierBadge
import my.gimano.app.ui.theme.Gimano

/** Settings → Account: pseudonym, tier, recovery code (docs/identity-and-reputation.md §1). */
@Composable
fun AccountScreen(c: AppContainer, vm: MainViewModel, user: User?, displayName: String?) {
    val col = Gimano.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<String>?>(null) }
    var noLockNote by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val confirmTitle = stringResource(R.string.recovery_confirm)
    val confirmSub = stringResource(R.string.recovery_confirm_sub)

    fun reveal() = scope.launch {
        val w = withContext(Dispatchers.IO) { c.identity.recoveryWords() }
        val act = ctx as? FragmentActivity
        val can = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        if (act == null || can != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock / biometrics on this phone: nothing to confirm with. Show, with a note.
            noLockNote = true; words = w; return@launch
        }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { noLockNote = false; words = w }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(confirmTitle).setSubtitle(confirmSub)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build(),
        )
    }

    Column(Modifier.fillMaxSize().background(col.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = col.text) }
            Text(stringResource(R.string.account), fontSize = 22.sp, fontWeight = FontWeight.Black, color = col.text)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(col.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pseudonym), fontSize = 13.sp, fontWeight = FontWeight.Black, color = col.textMuted)
                Text(displayName ?: user?.displayName ?: "—", fontSize = 26.sp, fontWeight = FontWeight.Black, color = col.text)
                TierBadge(user?.tier ?: "baharu")
                Text(stringResource(R.string.pseudonym_info), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = col.textMuted)
            }
            Button(onClick = { reveal() }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = col.brand, contentColor = col.brandOn)) {
                Text(stringResource(R.string.show_recovery), fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            OutlinedButton(onClick = { restoring = true }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Text(stringResource(R.string.restore_recovery), fontWeight = FontWeight.Black, fontSize = 16.sp, color = col.brandInk)
            }
            // "Padam akaun Manotu saya / Delete my Manotu account" (DELETE /api/v1/me), also on the web.
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { deleting = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, col.report)) {
                Text(stringResource(R.string.delete_account), fontWeight = FontWeight.Black, fontSize = 16.sp, color = col.report)
            }
            Text(stringResource(R.string.delete_account_web, my.gimano.app.data.account.AccountDeletion.WEB_URL), fontSize = 13.sp, color = col.textMuted)
        }
    }

    if (deleting) DeleteAccountDialog(c, onClose = { deleting = false }, onDeleted = { deleting = false; vm.back() })

    words?.let { w ->
        AlertDialog(
            onDismissRequest = { words = null },
            title = { Text(stringResource(R.string.recovery_title), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.recovery_warning), fontWeight = FontWeight.Bold)
                    if (noLockNote) Text(stringResource(R.string.recovery_no_lock), fontSize = 13.sp, color = col.report)
                    w.chunked(3).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEachIndexed { i, word ->
                                Text("${r * 3 + i + 1}. $word", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { words = null }) { Text(stringResource(R.string.done), fontWeight = FontWeight.Black) } },
        )
    }

    if (restoring) RestoreDialog(c, onClose = { restoring = false }, onRestored = { name -> restoring = false; vm.onIdentityRestored(name) })
}

/**
 * What "Delete my Manotu account" deletes (the server account; public contributions are anonymised, not deleted),
 * what stays on the phone (favourites etc. unless the user ticks "also clear"), then DELETE /me. Nothing on the
 * phone changes unless the server confirmed.
 */
@Composable
private fun DeleteAccountDialog(c: AppContainer, onClose: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var alsoClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val doneMsg = stringResource(R.string.delete_account_done)
    val offlineMsg = stringResource(R.string.delete_account_offline)
    val failedMsg = stringResource(R.string.delete_account_failed)
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.delete_account_title), fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.delete_account_body))
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { alsoClear = !alsoClear }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoClear, { alsoClear = it }, enabled = !busy)
                    Text(stringResource(R.string.delete_account_also_local), fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.delete_account_web, my.gimano.app.data.account.AccountDeletion.WEB_URL), fontSize = 13.sp, color = Gimano.colors.textMuted)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = Gimano.colors.report, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    when (c.accountDeletion.run(alsoClear)) {
                        my.gimano.app.data.account.AccountDeletion.Outcome.DELETED -> {
                            android.widget.Toast.makeText(ctx, doneMsg, android.widget.Toast.LENGTH_LONG).show()
                            onDeleted()
                        }
                        my.gimano.app.data.account.AccountDeletion.Outcome.OFFLINE -> { error = offlineMsg; busy = false }
                        my.gimano.app.data.account.AccountDeletion.Outcome.FAILED -> { error = failedMsg; busy = false }
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Gimano.colors.report)) {
                Text(stringResource(R.string.delete_account_confirm), fontWeight = FontWeight.Black)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RestoreDialog(c: AppContainer, onClose: () -> Unit, onRestored: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.restore_recovery), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recovery_replace), fontWeight = FontWeight.Bold)
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text(stringResource(R.string.recovery_input)) })
                error?.let { Text(it, color = Gimano.colors.report, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                when (val r = c.identity.decodeRecovery(text)) {
                    Bip39.Result.WrongLength -> error = ctx.getString(R.string.recovery_bad_length)
                    Bip39.Result.BadChecksum -> error = ctx.getString(R.string.recovery_bad_checksum)
                    is Bip39.Result.UnknownWord -> error = ctx.getString(R.string.recovery_unknown_word, r.position, r.word)
                    is Bip39.Result.Ok -> if (r.entropy.size != 32) error = ctx.getString(R.string.recovery_bad_length) else {
                        busy = true
                        scope.launch {
                            runCatching { c.remote.restoreFromSeed(r.entropy) }
                                .onSuccess { onRestored(it) }
                                .onFailure { e ->
                                    error = ctx.getString(when ((e as? retrofit2.HttpException)?.code()) {
                                        401 -> R.string.recovery_unknown_key      // no account has this key (or it was rotated away)
                                        429 -> R.string.recovery_rate_limited
                                        null -> if (e is java.io.IOException) R.string.recovery_offline else R.string.recovery_failed
                                        else -> R.string.recovery_failed
                                    })
                                    busy = false
                                }
                        }
                    }
                }
            }) { Text(stringResource(R.string.recovery_restore), fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
