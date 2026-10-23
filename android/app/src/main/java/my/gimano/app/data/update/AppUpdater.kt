package my.gimano.app.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import my.gimano.app.BuildConfig
import my.gimano.app.MainActivity
import my.gimano.app.R
import my.gimano.app.core.identity.RequestSigning
import my.gimano.app.core.update.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** UI state of the in-app updater. */
data class UpdateUi(
    val decision: UpdateDecision = UpdateDecision.UpToDate,
    val lastCheckMs: Long = 0,
    val checking: Boolean = false,
    /** 0..1 while downloading, null otherwise. */
    val progress: Float? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val installing: Boolean = false,
    /** "Install unknown apps" is off for Gimano: show the explainer, then open Settings. */
    val needsInstallPermission: Boolean = false,
    val error: UpdateError? = null,
    /** One-time "Updated to 0.x.x" note after an update. */
    val justUpdatedTo: String? = null,
)

sealed interface UpdateError {
    data object Network : UpdateError
    data object HashMismatch : UpdateError
    data object NotGimano : UpdateError
    data object SignerMismatch : UpdateError
    data class InstallFailed(val message: String?) : UpdateError
}

/**
 * Sideload auto-update (Gimano is distributed as an APK from bukitbunga.com). The check is an anonymous
 * plain GET of version.json: no identifiers, no auth header, no analytics.
 */
class AppUpdater(private val context: Context) {
    companion object {
        const val MANIFEST_URL = "https://www.bukitbunga.com/gimano/download/version.json"
        private const val PREFS = "gimano.updater"
        private const val CHANNEL = "updates"
        @Volatile var instance: AppUpdater? = null
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val _ui = MutableStateFlow(UpdateUi(lastCheckMs = prefs.getLong("lastCheck", 0)))
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()
    private val installed = BuildConfig.VERSION_CODE

    init {
        instance = this
        // Re-derive the decision from the last manifest seen (e.g. a required update stays enforced offline).
        prefs.getString("manifest", null)?.let(UpdateManifest::parse)?.let { m -> _ui.update { it.copy(decision = decide(m)) } }
    }

    /** Called once per app start (not from receivers): detects "just updated" for the one-time note. */
    fun onAppStart() {
        val lastRun = prefs.getInt("lastRunVersion", 0)
        if (lastRun in 1 until installed) _ui.update { it.copy(justUpdatedTo = BuildConfig.VERSION_NAME) }
        prefs.edit().putInt("lastRunVersion", installed).apply()
        File(context.cacheDir, "updates").deleteRecursively()   // the installed APK copy is no longer needed
    }

    private fun decide(m: UpdateManifest?) = UpdatePolicy.decide(installed, m, prefs.getLong("snoozedUntil", 0), System.currentTimeMillis())

    /** On launch (at most every 6 h) and from Settings → "Check now" ([force]). */
    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldCheck(prefs.getLong("lastCheck", 0), now, force) || _ui.value.checking) return
        _ui.update { it.copy(checking = true) }
        val text = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(MANIFEST_URL).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
        }
        val m = text?.let(UpdateManifest::parse)
        if (m != null) prefs.edit().putString("manifest", text).putLong("lastCheck", now).apply()
        _ui.update { it.copy(checking = false, lastCheckMs = if (m != null) now else it.lastCheckMs, decision = if (m != null) decide(m) else it.decision) }
    }

    fun later() {
        prefs.edit().putLong("snoozedUntil", System.currentTimeMillis() + UpdatePolicy.SNOOZE_MS).apply()
        _ui.update { it.copy(decision = decide(prefs.getString("manifest", null)?.let(UpdateManifest::parse))) }
    }

    fun dismissUpdatedNote() = _ui.update { it.copy(justUpdatedTo = null) }
    fun clearError() = _ui.update { it.copy(error = null) }
    fun dismissPermission() = _ui.update { it.copy(needsInstallPermission = false) }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Settings → "Install unknown apps" for Gimano. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    private fun manifest(): UpdateManifest? = when (val d = _ui.value.decision) {
        is UpdateDecision.Available -> d.manifest
        is UpdateDecision.Required -> d.manifest
        UpdateDecision.UpToDate -> null
    }

    /** "Update now": permission → download (progress) → verify (hash, package, version, signer) → PackageInstaller. */
    suspend fun updateNow() {
        val m = manifest() ?: return
        if (!canInstall()) { _ui.update { it.copy(needsInstallPermission = true) }; return }
        _ui.update { it.copy(needsInstallPermission = false, error = null, progress = 0f, downloadedBytes = 0, totalBytes = m.sizeBytes ?: 0) }
        val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, "gimano-${m.versionCode}.apk")
        val sha = withContext(Dispatchers.IO) { runCatching { download(m, file) }.getOrNull() }
        if (sha == null) { _ui.update { it.copy(progress = null, error = UpdateError.Network) }; return }
        val outcome = withContext(Dispatchers.IO) {
            UpdateVerifier.verify(sha, m, archiveInfo(file), context.packageName, installedSigners())
        }
        if (outcome != VerifyOutcome.Ok) {
            file.delete()
            _ui.update {
                it.copy(progress = null, error = when (outcome) {
                    is VerifyOutcome.HashMismatch -> UpdateError.HashMismatch
                    VerifyOutcome.SignerMismatch -> UpdateError.SignerMismatch
                    else -> UpdateError.NotGimano
                })
            }
            return
        }
        _ui.update { it.copy(progress = null, installing = true) }
        withContext(Dispatchers.IO) { runCatching { install(file) }.onFailure { e -> _ui.update { it.copy(installing = false, error = UpdateError.InstallFailed(e.message)) } } }
    }

    /** Streams to the cache dir, hashing as it goes. Returns the hex SHA-256. */
    private fun download(m: UpdateManifest, out: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(m.apkUrl).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: m.sizeBytes ?: 0
            var done = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (done - lastEmit > 256 * 1024 || done == total) {
                            lastEmit = done
                            _ui.update { it.copy(progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null, downloadedBytes = done, totalBytes = total) }
                        }
                    }
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo?): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info?.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else info?.signatures
        return certs.orEmpty().map { RequestSigning.sha256Hex(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private val sigFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun installedSigners(): Set<String> = signersOf(context.packageManager.getPackageInfo(context.packageName, sigFlag)).let { all ->
        // For a rotated key the history holds old + current; compare against the current signer only.
        if (Build.VERSION.SDK_INT >= 28) {
            val si = context.packageManager.getPackageInfo(context.packageName, sigFlag).signingInfo
            if (si != null && !si.hasMultipleSigners()) setOfNotNull(si.signingCertificateHistory.lastOrNull()?.let { RequestSigning.sha256Hex(it.toByteArray()) }) else all
        } else all
    }

    private fun archiveInfo(f: File): ArchiveInfo? {
        val pi = context.packageManager.getPackageArchiveInfo(f.path, sigFlag) ?: return null
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            if (si != null && !si.hasMultipleSigners()) setOfNotNull(si.signingCertificateHistory.lastOrNull()?.let { RequestSigning.sha256Hex(it.toByteArray()) })
            else signersOf(pi)
        } else signersOf(pi)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return ArchiveInfo(pi.packageName, code, signers)
    }

    private fun install(apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("gimano.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags)
            s.commit(pending.intentSender)
        }
    }

    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> _ui.update { it.copy(installing = false) }   // the process is replaced right after
            PackageInstaller.STATUS_FAILURE_ABORTED -> _ui.update { it.copy(installing = false) }   // user cancelled the system prompt
            else -> _ui.update { it.copy(installing = false, error = UpdateError.InstallFailed(message)) }
        }
    }

    /** After the package was replaced: the app doesn't restart by itself, so offer a tap to reopen. */
    fun notifyUpdated() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gimano)
            .setContentTitle(context.getString(R.string.updated_to, BuildConfig.VERSION_NAME))
            .setContentText(context.getString(R.string.updated_tap_to_open))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(7001, n) }
    }
}

/** PackageInstaller session results (exported=false). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        val u = AppUpdater.instance ?: AppUpdater(context.applicationContext)
        u.onInstallStatus(status, msg, confirm)
    }
}

/** ACTION_MY_PACKAGE_REPLACED: tell the user the update finished (a tap reopens Gimano with its data). */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) (AppUpdater.instance ?: AppUpdater(context.applicationContext)).notifyUpdated()
    }
}
