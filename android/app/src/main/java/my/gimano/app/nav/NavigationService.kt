package my.gimano.app.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import my.gimano.app.GimanoApp
import my.gimano.app.MainActivity
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.nav.ManeuverFormatter
import java.time.Instant
import java.time.ZoneId

/** Keeps an active drive alive in the background and shows the next turn in a notification. */
class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as GimanoApp
        if (intent?.action == ACTION_STOP) {
            app.container.navigation.stop() // also stops this service and cancels the notification
            removeNotificationAndStop()
            return START_NOT_STICKY
        }
        if (!app.container.navigation.state.value.active) {
            // Restarted by the system with no drive in progress (process was killed): never
            // resurrect a "Navigating" notification for a drive that no longer exists.
            ensureChannel()
            runCatching { ServiceCompat.startForeground(this, NOTIF_ID, build(getString(R.string.nav_notification_title), ""), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) }
            removeNotificationAndStop()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, build(getString(R.string.nav_notification_title), ""),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Missing location permission on Android 14+: navigation still runs while visible.
            stopSelf()
            return START_NOT_STICKY
        }
        if (collecting) return START_NOT_STICKY
        collecting = true
        scope.launch {
            app.container.navigation.state
                // Only a live drive may (re)post the ongoing notification.
                .takeWhile { it.active && !it.arrived }
                .map { s ->
                    val lang = app.container.lang()
                    val next = s.progress?.nextManeuver
                    val title = next?.let { "${Formatters.navDistance(s.progress.distanceToNextM)} · ${ManeuverFormatter.instruction(it, lang)}" }
                        ?: getString(R.string.nav_notification_title)
                    val text = s.progress?.let {
                        getString(R.string.eta_prefix) + " " + Formatters.etaClock(Instant.now(), it.remainingS, ZoneId.systemDefault(), app.container.settings.current.use24h, lang) +
                            " · " + Formatters.remainingLine(it.remainingS, it.remainingM, lang)
                    } ?: (s.destination?.name ?: "")
                    title to text
                }
                .distinctUntilChanged()
                .collect { (t, x) ->
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(t, x))
                }
        }
        return START_NOT_STICKY
    }

    private var collecting = false

    private fun removeNotificationAndStop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopSelf()
    }

    /** Swiping Gimano away from Recents ends the drive: no orphaned service or notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as GimanoApp).container.navigation.stop()
        removeNotificationAndStop()
        super.onTaskRemoved(rootIntent)
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gimano)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.nav_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "navigation"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "my.gimano.app.STOP_NAV"
    }
}
