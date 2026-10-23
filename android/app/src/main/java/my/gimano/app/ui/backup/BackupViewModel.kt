package my.gimano.app.ui.backup

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.gimano.app.AppContainer
import my.gimano.app.R
import my.gimano.app.data.backup.BackupManifest
import my.gimano.app.data.backup.BackupTooNewException
import my.gimano.app.data.backup.BackupWorker
import my.gimano.app.data.backup.DriveAuth
import my.gimano.app.data.backup.DriveFile
import my.gimano.app.data.backup.RestoreMode
import my.gimano.app.data.backup.WrongPassphraseException
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Where a pending restore's bytes come from. */
sealed interface RestoreSource {
    data class FileUri(val uri: Uri) : RestoreSource
    data class Drive(val file: DriveFile) : RestoreSource
}

data class BackupUi(
    val busy: Boolean = false,
    val usePassphrase: Boolean = false,
    /** Asks for a passphrase: for a new backup (confirm twice) or to open an encrypted one. */
    val passphrasePrompt: PassphrasePrompt? = null,
    val pendingRestore: PendingRestore? = null,
    val driveFiles: List<DriveFile>? = null,
)

data class PendingRestore(val source: RestoreSource, val manifest: BackupManifest, val passphrase: String? = null)

enum class PassphrasePurpose { BACKUP_DRIVE, BACKUP_FILE, BACKUP_SHARE, RESTORE }

data class PassphrasePrompt(val purpose: PassphrasePurpose, val error: Int? = null)

sealed interface BackupEvent {
    data class Message(val res: Int, val arg: String? = null) : BackupEvent
    data class CreateFile(val suggestedName: String) : BackupEvent
    data class Share(val uri: Uri) : BackupEvent
    data class DriveConsent(val intent: PendingIntent) : BackupEvent
}

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BackupEvent> = _events
    val settings = c.settings.state
    val driveConfigured: Boolean get() = c.drive.configured

    private var sessionPassphrase: String? = null
    private var afterConsent: (suspend (String) -> Unit)? = null

    fun fileName() = "manotu-${LocalDate.now()}.manotu"

    fun setUsePassphrase(v: Boolean) = _ui.update { it.copy(usePassphrase = v) }

    /** Entry point for every "back up" button: asks for the passphrase first when enabled. */
    fun backup(purpose: PassphrasePurpose) {
        if (_ui.value.usePassphrase) _ui.update { it.copy(passphrasePrompt = PassphrasePrompt(purpose)) } else runBackup(purpose, null)
    }

    fun submitPassphrase(pass: String, confirm: String?) {
        val prompt = _ui.value.passphrasePrompt ?: return
        if (prompt.purpose != PassphrasePurpose.RESTORE) {
            if (pass.length < 8) { _ui.update { it.copy(passphrasePrompt = prompt.copy(error = R.string.pass_too_short)) }; return }
            if (pass != confirm) { _ui.update { it.copy(passphrasePrompt = prompt.copy(error = R.string.pass_mismatch)) }; return }
            _ui.update { it.copy(passphrasePrompt = null) }
            runBackup(prompt.purpose, pass)
        } else {
            val pr = _ui.value.pendingRestore ?: return
            _ui.update { it.copy(passphrasePrompt = null, pendingRestore = pr.copy(passphrase = pass)) }
        }
    }

    fun cancelPrompt() = _ui.update { it.copy(passphrasePrompt = null, pendingRestore = if (it.passphrasePrompt?.purpose == PassphrasePurpose.RESTORE) null else it.pendingRestore) }

    private fun runBackup(purpose: PassphrasePurpose, pass: String?) {
        sessionPassphrase = pass
        when (purpose) {
            PassphrasePurpose.BACKUP_FILE -> _events.tryEmit(BackupEvent.CreateFile(fileName()))
            PassphrasePurpose.BACKUP_SHARE -> shareBackup()
            PassphrasePurpose.BACKUP_DRIVE -> withDrive { token -> uploadToDrive(token) }
            PassphrasePurpose.RESTORE -> Unit
        }
    }

    /** SAF "Save to file" result. */
    fun writeTo(uri: Uri) = work {
        val bytes = backupBytes()
        withContext(Dispatchers.IO) { c.app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        record(bytes.size.toLong(), "file")
        _events.tryEmit(BackupEvent.Message(R.string.backup_saved))
    }

    private fun shareBackup() = work {
        val bytes = backupBytes()
        val f = File(c.app.cacheDir, "shared").apply { mkdirs() }.resolve(fileName())
        withContext(Dispatchers.IO) { f.writeBytes(bytes) }
        record(bytes.size.toLong(), "file")
        _events.tryEmit(BackupEvent.Share(FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", f)))
    }

    private suspend fun backupBytes(): ByteArray = ByteArrayOutputStream().also { c.backup.createBackup(it, sessionPassphrase) }.toByteArray().also { sessionPassphrase = null }

    private suspend fun record(bytes: Long, dest: String) =
        c.settings.setLastBackup(Instant.now().toString(), bytes, c.backup.fingerprint(), dest)

    private suspend fun uploadToDrive(token: String) {
        val bytes = backupBytes()
        c.drive.upload(token, fileName(), bytes)
        c.drive.prune(token)
        record(bytes.size.toLong(), "drive")
        _events.tryEmit(BackupEvent.Message(R.string.backup_drive_done))
    }

    // --- Restore ---

    fun restoreFromUri(uri: Uri) = work {
        val m = withContext(Dispatchers.IO) { c.app.contentResolver.openInputStream(uri)!!.use { c.backup.readManifest(it) } }
        beginRestore(RestoreSource.FileUri(uri), m)
    }

    fun listDrive() = withDrive { token -> _ui.update { it.copy(driveFiles = c.drive.list(token)) } }

    fun restoreFromDrive(f: DriveFile) = withDrive { token ->
        val m = c.drive.download(token, f.id).use { c.backup.readManifest(it) }
        _ui.update { it.copy(driveFiles = null) }
        beginRestore(RestoreSource.Drive(f), m)
    }

    private fun beginRestore(source: RestoreSource, m: BackupManifest) {
        _ui.update { it.copy(pendingRestore = PendingRestore(source, m)) }
        if (m.encryption != BackupManifest.ENCRYPTION_NONE) _ui.update { it.copy(passphrasePrompt = PassphrasePrompt(PassphrasePurpose.RESTORE)) }
    }

    fun confirmRestore(mode: RestoreMode) {
        val pr = _ui.value.pendingRestore ?: return
        _ui.update { it.copy(pendingRestore = null) }
        work {
            try {
                val input = when (val s = pr.source) {
                    is RestoreSource.FileUri -> withContext(Dispatchers.IO) { c.app.contentResolver.openInputStream(s.uri)!! }
                    is RestoreSource.Drive -> {
                        val t = (c.drive.authorize() as? DriveAuth.Token)?.accessToken ?: error("Drive not authorised")
                        c.drive.download(t, s.file.id)
                    }
                }
                val r = input.use { c.backup.restore(it, pr.passphrase, mode) }
                val n = r.manifest.counts
                _events.tryEmit(BackupEvent.Message(R.string.restore_done, "${n.saved_places} · ${n.recents} · ${n.private_places}"))
            } catch (e: WrongPassphraseException) {
                _ui.update { it.copy(pendingRestore = pr.copy(passphrase = null), passphrasePrompt = PassphrasePrompt(PassphrasePurpose.RESTORE, R.string.pass_wrong)) }
            } catch (e: BackupTooNewException) {
                _events.tryEmit(BackupEvent.Message(R.string.restore_too_new))
            }
        }
    }

    fun cancelRestore() = _ui.update { it.copy(pendingRestore = null, driveFiles = null) }

    fun setAutoBackup(v: Boolean) = viewModelScope.launch {
        c.settings.setAutoBackup(v)
        BackupWorker.schedule(c.app, v)
    }

    // --- Drive auth ---

    private fun withDrive(block: suspend (String) -> Unit) = work {
        if (!c.drive.configured) { _events.tryEmit(BackupEvent.Message(R.string.drive_not_configured)); return@work }
        when (val a = c.drive.authorize()) {
            is DriveAuth.Token -> block(a.accessToken)
            is DriveAuth.NeedsConsent -> { afterConsent = block; _events.tryEmit(BackupEvent.DriveConsent(a.intent)) }
        }
    }

    fun onDriveConsent(data: Intent?) {
        val token = c.drive.tokenFromConsent(data)
        val next = afterConsent.also { afterConsent = null }
        if (token != null && next != null) work { next(token) } else _events.tryEmit(BackupEvent.Message(R.string.drive_denied))
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: Exception) {
            _events.tryEmit(BackupEvent.Message(R.string.backup_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(c) as T
    }
}
