package my.gimano.app.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import my.gimano.app.GimanoApp
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Optional daily automatic backup to the user's Google Drive: Wi-Fi + charging only, and only
 * when the data changed since the last backup. Never prompts: if Drive needs consent again it skips.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GimanoApp).container
        val s = c.settings.current
        if (!s.autoBackup || !c.drive.configured) return Result.success()
        val fp = c.backup.fingerprint()
        if (fp == s.lastBackupFingerprint) return Result.success()
        val auth = runCatching { c.drive.authorize() }.getOrNull() as? DriveAuth.Token ?: return Result.success()
        return try {
            val bytes = ByteArrayOutputStream().also { c.backup.createBackup(it, c.backupPassphrase()) }.toByteArray()
            c.drive.upload(auth.accessToken, "manotu-${LocalDate.now()}.manotu", bytes)
            c.drive.prune(auth.accessToken)
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
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
