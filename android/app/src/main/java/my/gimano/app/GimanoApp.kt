package my.gimano.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import my.gimano.app.core.format.Lang
import my.gimano.app.data.live.LiveHub
import my.gimano.app.data.location.FusedLocationSource
import my.gimano.app.data.repo.DemoRepository
import my.gimano.app.data.repo.RemoteRepository
import my.gimano.app.data.repo.SwitchingRepository
import my.gimano.app.data.settings.SettingsStore
import my.gimano.app.nav.NavigationController
import my.gimano.app.nav.VoiceGuide
import java.util.Locale

/** Manual DI: one container per process. Small enough that a DI framework would be overhead. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app, scope)
    val remote = RemoteRepository(settings)
    val repo = SwitchingRepository(settings, remote, DemoRepository {
        app.assets.list("demo-routes").orEmpty().filter { it.endsWith(".json") }
            .map { f -> app.assets.open("demo-routes/$f").bufferedReader().use { it.readText() } }
    })
    val liveHub = LiveHub(scope, { settings.current.apiBaseUrl }, { settings.current.token })
    val locations = FusedLocationSource(app)
    val voice = VoiceGuide(app)
    val navigation = NavigationController(
        scope, repo, my.gimano.app.nav.FusedFixSource(locations), voice, my.gimano.app.nav.AndroidNavServiceControl(app),
        soundMode = { settings.current.soundMode }, langProvider = ::voiceLang,
    )

    /** UI language (per-app locale if set, else system). */
    fun lang(): Lang {
        val app = AppCompatDelegate.getApplicationLocales()
        return Lang.from(if (!app.isEmpty) app[0]?.language else Locale.getDefault().language)
    }

    fun voiceLang(): Lang = when (settings.current.voiceLang) {
        "en" -> Lang.EN
        "ms" -> Lang.MS
        else -> lang()
    }
}

class GimanoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
