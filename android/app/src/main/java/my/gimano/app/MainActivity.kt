package my.gimano.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import my.gimano.app.core.theme.ThemeMode
import java.time.Instant
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import my.gimano.app.ui.GimanoRoot
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.SearchPurpose
import my.gimano.app.ui.theme.GimanoTheme

class MainActivity : AppCompatActivity() {
    /** Debug builds only: `adb shell am start -n my.gimano.app/.MainActivity --ez gimano.simulateDrive true`. */
    /** "Leave by" reminder tapped → route preview for that planned drive. */
    private fun handlePlannedIntent(i: Intent?) {
        i?.getStringExtra(my.gimano.app.data.planned.PlannedDrives.EXTRA_ID)?.let { vm.openPlannedDrive(it); i.removeExtra(my.gimano.app.data.planned.PlannedDrives.EXTRA_ID) }
    }

    private fun applyDebugExtras(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        if (i.hasExtra("gimano.simulateDrive")) vm.setSimulate(i.getBooleanExtra("gimano.simulateDrive", false))
        if (i.hasExtra("gimano.demoMode")) vm.setDemoMode(i.getBooleanExtra("gimano.demoMode", false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDebugExtras(intent)
        handlePlannedIntent(intent)
    }

    private val vm: MainViewModel by viewModels { MainViewModel.Factory((application as GimanoApp).container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyDebugExtras(intent)
        handlePlannedIntent(intent)
        setContent {
            // Theme: Light by default (even on a dark-mode phone), Dark, Follow phone, or Auto by sunset.
            val st by vm.settingsState.collectAsStateWithLifecycle()
            val uiState by vm.ui.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            var now by remember { mutableStateOf(Instant.now()) }
            LaunchedEffect(Unit) { while (true) { delay(60_000); now = Instant.now() } }
            val fix = uiState.myLocation?.point
            val dark = ThemeMode.isDark(ThemeMode.from(st.theme), systemDark, now, fix?.lat, fix?.lng)
            LaunchedEffect(dark) {
                (application as GimanoApp).container.darkTheme = dark
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            GimanoTheme(dark = dark) {
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                    vm.onLocationPermission(res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
                }
                // "Navigasi perlukan lokasi tepat" → ask again; Android 12+ shows "Change to precise location".
                val preciseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                    vm.onLocationPermission(res.values.any { it } || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                    vm.onPreciseAnswered()
                }
                // Route preview "Benarkan lokasi": ask again; if Android won't show the dialog any more, open app settings.
                val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                    val granted = res.values.any { it }
                    vm.onLocationPermission(granted)
                    if (granted) vm.retryOrigin()
                    else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION))
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) }
                }
                val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(owner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshPrecision() }
                    owner.lifecycle.addObserver(obs)
                    onDispose { owner.lifecycle.removeObserver(obs) }
                }
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    if (!text.isNullOrBlank()) vm.openSearch(SearchPurpose.NAVIGATE, text)
                }
                LaunchedEffect(Unit) {
                    val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val notif = Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (fine || coarse) vm.onLocationPermission(true)
                    // Approximate-only users get our explainer (refreshPrecision) instead of the raw system dialog each launch.
                    if (!coarse || !notif) {
                        val perms = buildList {
                            if (!coarse) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
                            if (!notif && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permLauncher.launch(perms.toTypedArray())
                    }
                }
                GimanoRoot(
                    vm,
                    onMic = {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, (application as GimanoApp).container.lang().tag)
                        runCatching { micLauncher.launch(i) }
                    },
                    onRequestPrecise = { preciseLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    onRequestLocation = { locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    onExit = {
                        (application as GimanoApp).container.navigation.stop()
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }
}
