package my.gimano.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.gimano.app.core.format.Lang
import my.gimano.app.data.backup.BackupCrypto
import my.gimano.app.data.backup.BackupManager
import my.gimano.app.data.backup.DriveBackup
import my.gimano.app.data.events.transactor
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

    /** The contributor pseudonym's key (docs/identity-and-reputation.md). Seed wrapped with an Android Keystore key. */
    private val seedWrapper = my.gimano.app.data.identity.KeystoreSeedWrapper()
    val identity = my.gimano.app.data.identity.IdentityService(
        my.gimano.app.data.identity.IdentityStore({ my.gimano.app.data.backup.AndroidSqlPort(db.openHelper.writableDatabase) }, seedWrapper,
            onDropped = { e -> my.gimano.app.core.GLog.w("Identity", "restored identity unreadable here (${e.javaClass.simpleName}); new device identity") }),
        my.gimano.app.core.identity.Ed25519(LazySodiumAndroid(SodiumAndroid())),
        bip39 = { my.gimano.app.core.identity.Bip39(app.assets.open("bip39-english.txt").bufferedReader().readLines().filter { it.isNotBlank() }) },
    )
    val remote = RemoteRepository(settings, identity, acceptLanguage = { my.gimano.app.data.remote.ApiFactory.acceptLanguage(lang() == Lang.MS) })
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
    val voice = VoiceGuide(app, volume = { settings.current.voiceVolume / 100f })
    val navigation = NavigationController(
        scope, repo, FusedFixSource(locations), voice, AndroidNavServiceControl(app),
        soundMode = { settings.current.soundMode }, langProvider = ::voiceLang,
    )
    val feedback = FeedbackService(db.outbox(), repo)
    val planned = my.gimano.app.data.planned.PlannedDrives(
        app, db.plannedDrives(), db.settings(), repo,
        // No position source: background leave-by checks never carry the user's location ("where I am" drives skip them).
        lang = ::lang, use24h = { settings.current.use24h },
    )
    /** Last known position (set by the UI), for planned drives that start "where I am". */
    @Volatile var lastFix: my.gimano.app.core.geo.GeoPoint? = null
    val share by lazy { my.gimano.app.data.share.ShareSession(scope, repo, my.gimano.app.data.share.ShareCrypto(LazySodiumAndroid(SodiumAndroid())), navigation.state) }

    val crypto by lazy { BackupCrypto(LazySodiumAndroid(SodiumAndroid())) }
    val backup by lazy {
        BackupManager(
            app, db, crypto, exportSettings = { settings.current.toBackupMap() }, importSettings = { settings.applyBackupMap(it) },
            seedToPlain = { s -> if (seedWrapper.isWrapped(s)) java.util.Base64.getEncoder().encodeToString(seedWrapper.unwrap(s)) else s },
            afterRestore = {
                kotlinx.coroutines.withContext(Dispatchers.IO) { identity.reload() }
                remote.adoptRestoredIdentity()
            },
        )
    }
    val drive by lazy { DriveBackup(app) }

    /** Imported events (docs/events.md): own DB file, excluded from every backup; payloads sealed with a Keystore key. */
    private val eventsDb by lazy { my.gimano.app.data.events.EventsDatabase.build(app) }
    private val eventSodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    private suspend fun scheduleEventExpiry() = my.gimano.app.data.events.EventExpiryWorker.schedule(
        app, listOfNotNull(eventStore.nextExpiry(), hostStore.nextExpiry()).minOrNull())
    val eventStore: my.gimano.app.data.events.EventStore by lazy {
        my.gimano.app.data.events.EventStore(
            eventsDb.events(), my.gimano.app.data.events.KeystoreEventVault(), eventsDb.transactor(),
            onChanged = { scheduleEventExpiry() },
        )
    }
    /** Events this phone organises: drafts, per-event keys and published revisions, same DB file and vault. */
    val hostStore: my.gimano.app.data.events.HostStore by lazy {
        my.gimano.app.data.events.HostStore(
            eventsDb.hosted(), my.gimano.app.data.events.KeystoreEventVault(), my.gimano.app.core.events.EventPackWriter(eventSodium),
            onChanged = { scheduleEventExpiry() },
        )
    }
    val hostEditor by lazy { my.gimano.app.data.events.HostEditor(scope, hostStore) }
    val eventImporter by lazy {
        my.gimano.app.data.events.EventImporter(scope, my.gimano.app.core.events.EventPackReader(eventSodium), eventStore,
            isOwn = { id, pub -> hostStore.isOwn(id, pub) })
    }
    val integrity by lazy { my.gimano.app.data.vetting.Integrity(app, BuildConfig.CLOUD_PROJECT_NUMBER) }
    val vetting = my.gimano.app.data.vetting.VettingMemory(db.settings())
    /** Complaints v1 (hidden until the server has /issues). */
    val issues = my.gimano.app.data.issues.IssueGate(repo)
    val updater = my.gimano.app.data.update.AppUpdater(app).also { it.onAppStart() }
    /** Effective theme (set by MainActivity) for the device block of feedback / crash reports. */
    @Volatile var darkTheme: Boolean = false
    /** Accuracy of the most recent fix (set by the UI and navigation), for support info only. */
    @Volatile var lastFixAccuracyM: Int? = null
    val deviceInfo = my.gimano.app.data.feedback.DeviceInfoProvider(
        app, locale = { lang().tag }, dark = { darkTheme }, demo = { repo.isDemo || repo.fallbackActive.value },
        precision = { locations.precision().wire }, lastAccuracyM = { navigation.state.value.fix?.accuracyM?.toInt() ?: lastFixAccuracyM },
    )
    val crashes = my.gimano.app.data.crash.CrashReporter(app, db.outbox(), repo, deviceInfo)

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
        container.crashes.install()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> my.gimano.app.core.net.AppForeground.foreground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> my.gimano.app.core.net.AppForeground.foreground = false
                else -> Unit
            }
        })
        my.gimano.app.data.places.PhotoPrep.sweepCameraTemps(this)
        // Events past their expiresAt are deleted on every start (also by EventExpiryWorker at the time itself).
        container.scope.launch(Dispatchers.IO) { runCatching { container.eventStore.purgeExpired() }; runCatching { container.hostStore.purgeExpired() } }
    }
}
