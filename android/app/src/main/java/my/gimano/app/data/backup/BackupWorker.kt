package my.gimano.app.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import my.gimano.app.BuildConfig
import my.gimano.app.GimanoApp
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Optional daily automatic backup to the user's Google Drive: Wi-Fi + charging only, and only
 * when the data changed since the last backup. Never prompts: if Drive needs consent again it skips.
 * The Play build has no Drive ([BackupDestinations.autoBackup] is null, `container.drive` is null): it never runs.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GimanoApp).container
        val s = c.settings.current
        if (BackupDestinations.autoBackup(BuildConfig.DRIVE_BACKUP) != BackupDestination.DRIVE) return Result.success()
        val drive = c.drive ?: return Result.success()
        if (!s.autoBackup || !drive.configured) return Result.success()
        val fp = c.backup.fingerprint()
        if (fp == s.lastBackupFingerprint) return Result.success()
        val auth = runCatching { drive.authorize() }.getOrNull() as? DriveAuth.Token ?: return Result.success()
        return try {
            val bytes = ByteArrayOutputStream().also { c.backup.createBackup(it, c.backupPassphrase()) }.toByteArray()
            drive.upload(auth.accessToken, "manotu-${LocalDate.now()}.manotu", bytes)
            runCatching { drive.prune(auth.accessToken) }
            c.settings.setLastBackup(java.time.Instant.now().toString(), bytes.size.toLong(), fp, "drive")
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "gimano-auto-backup"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled || BackupDestinations.autoBackup(BuildConfig.DRIVE_BACKUP) == null) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
