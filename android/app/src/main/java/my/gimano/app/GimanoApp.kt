package my.gimano.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import my.gimano.app.core.format.Lang
import my.gimano.app.data.backup.BackupCrypto
import my.gimano.app.data.backup.BackupManager
import my.gimano.app.data.backup.DriveBackup
import my.gimano.app.data.feedback.FeedbackService
import my.gimano.app.data.live.LiveHub
import my.gimano.app.data.local.GimanoDatabase
import my.gimano.app.data.local.LocalStore
import my.gimano.app.data.location.FusedLocationSource
import my.gimano.app.data.repo.DemoRepository
import my.gimano.app.data.repo.RemoteRepository
import my.gimano.app.data.repo.SwitchingRepository
import my.gimano.app.data.settings.SettingsStore
import my.gimano.app.data.settings.toBackupMap
import my.gimano.app.nav.AndroidNavServiceControl
import my.gimano.app.nav.FusedFixSource
import my.gimano.app.nav.NavigationController
import my.gimano.app.nav.VoiceGuide
import java.io.File
import java.util.Locale

/** Manual DI: one container per process. Small enough that a DI framework would be overhead. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app, scope)

    /** On-device personal data (favourites, recents, private places…): never sent to our servers. */
    val db = GimanoDatabase.build(app)
    val local = LocalStore(db)

    val remote = RemoteRepository(settings)
    val repo = SwitchingRepository(
        settings, remote,
        DemoRepository(
            cannedDocs = {
                app.assets.list("demo-routes").orEmpty().filter { it.endsWith(".json") }
                    .map { f -> app.assets.open("demo-routes/$f").bufferedReader().use { it.readText() } }
            },
            photoSink = { id, bytes ->
                val f = File(app.filesDir, "demo-photos").apply { mkdirs() }.resolve("$id.jpg")
                f.writeBytes(bytes)
                f.toURI().toString()
            },
        ),
    )
    val liveHub = LiveHub(scope, { settings.current.apiBaseUrl }, { settings.current.token })
    val locations = FusedLocationSource(app)
    val voice = VoiceGuide(app)
    val navigation = NavigationController(
        scope, repo, FusedFixSource(locations), voice, AndroidNavServiceControl(app),
        soundMode = { settings.current.soundMode }, langProvider = ::voiceLang,
    )
    val feedback = FeedbackService(db.outbox(), repo)

    val crypto by lazy { BackupCrypto(LazySodiumAndroid(SodiumAndroid())) }
    val backup by lazy {
        BackupManager(app, db, crypto, exportSettings = { settings.current.toBackupMap() }, importSettings = { settings.applyBackupMap(it) })
    }
    val drive by lazy { DriveBackup(app) }

    /** Backup passphrases are never stored (docs/privacy-and-backup.md): auto-backups are unencrypted. */
    fun backupPassphrase(): String? = null

    /** UI language (per-app locale if set, else system). */
    fun lang(): Lang {
        val l = AppCompatDelegate.getApplicationLocales()
        return Lang.from(if (!l.isEmpty) l[0]?.language else Locale.getDefault().language)
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
