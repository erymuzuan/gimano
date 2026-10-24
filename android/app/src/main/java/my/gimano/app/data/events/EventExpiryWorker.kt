package my.gimano.app.data.events

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import my.gimano.app.GimanoApp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Deletes events at their `expiresAt` (docs/events.md §7.5); reschedules itself for the next one. */
class EventExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GimanoApp).container
        c.eventStore.purgeExpired()   // reschedules through onChanged
        c.hostStore.purgeExpired()
        return Result.success()
    }

    companion object {
        private const val NAME = "manotu-event-expiry"

        fun schedule(context: Context, next: Instant?) {
            val wm = WorkManager.getInstance(context)
            if (next == null) { wm.cancelUniqueWork(NAME); return }
            val delay = Duration.between(Instant.now(), next).toMillis().coerceAtLeast(0) + 1_000
            val req = OneTimeWorkRequestBuilder<EventExpiryWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
            wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
