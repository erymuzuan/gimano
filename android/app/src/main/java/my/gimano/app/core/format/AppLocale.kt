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
