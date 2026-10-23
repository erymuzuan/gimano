package my.gimano.app.data.feedback

import android.content.Context
import android.os.Build
import android.view.WindowManager
import my.gimano.app.BuildConfig
import my.gimano.app.data.model.DeviceInfo

/** The exact `device` block for feedback (with consent, previewed first) and crash reports. No identifiers. */
class DeviceInfoProvider(
    private val context: Context,
    private val locale: () -> String,
    private val dark: () -> Boolean,
    private val demo: () -> Boolean,
) {
    fun current(): DeviceInfo {
        val dm = context.resources.displayMetrics
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        } else dm.widthPixels to dm.heightPixels
        return DeviceInfo(
            appVersion = BuildConfig.VERSION_NAME,
            appBuild = BuildConfig.VERSION_CODE,
            osVersion = Build.VERSION.RELEASE ?: "",
            apiLevel = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            screen = DeviceInfo.ScreenInfo(w, h, dm.density, (w / dm.density).toInt(), (h / dm.density).toInt()),
            locale = locale(),
            theme = if (dark()) "dark" else "light",
            dataMode = if (demo()) "demo" else "live",
        )
    }
}
