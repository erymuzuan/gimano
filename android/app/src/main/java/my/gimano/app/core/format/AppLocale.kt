package my.gimano.app.core.format

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Services, receivers and notifications must use the in-app language ("Bahasa": Ikut telefon / English /
 * Bahasa Melayu) too. Android 13+ applies per-app locales everywhere; older versions only to AppCompat
 * activities, so wrap other contexts explicitly.
 */
object AppLocale {
    fun wrap(base: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locales[0])
        return base.createConfigurationContext(cfg)
    }
}

object ServerLabel {
    /** Release builds talking to their built-in server show the app's name instead of a host name. */
    fun isPublic(current: String, builtIn: String, debug: Boolean): Boolean =
        !debug && current.trimEnd('/') == builtIn.trimEnd('/')
}
