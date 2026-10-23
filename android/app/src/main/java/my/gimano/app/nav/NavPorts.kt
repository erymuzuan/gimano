package my.gimano.app.nav

import android.content.Context
import android.content.Intent
import android.os.Build
import my.gimano.app.core.GLog
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.Flow
import my.gimano.app.core.format.Lang
import my.gimano.app.data.location.FusedLocationSource
import my.gimano.app.data.location.Fix

/** Speaks prompts. [VoiceGuide] in the app, a recorder in tests. */
interface Speaker {
    fun speak(text: String, lang: Lang, flush: Boolean = false)
    fun stop()
}

/** Starts/stops the foreground service and owns its ongoing notification. */
interface NavServiceControl {
    fun start()
    /** Stops the service AND removes the ongoing notification, even if the service already died. */
    fun stop()
}

/** Live GPS fixes. */
fun interface FixSource {
    fun updates(): Flow<Fix>
}

class AndroidNavServiceControl(private val context: Context) : NavServiceControl {
    override fun start() {
        val i = Intent(context, NavigationService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        } catch (e: Exception) {
            GLog.w("Nav", "Could not start foreground service: ${e.message}")
        }
    }

    override fun stop() {
        context.stopService(Intent(context, NavigationService::class.java))
        // Belt and braces: a notification re-posted by a late collector, or left behind by a
        // killed process, must never outlive the drive.
        NotificationManagerCompat.from(context).cancel(NavigationService.NOTIF_ID)
    }
}

class FusedFixSource(private val locations: FusedLocationSource) : FixSource {
    override fun updates(): Flow<Fix> = locations.updates()
}
