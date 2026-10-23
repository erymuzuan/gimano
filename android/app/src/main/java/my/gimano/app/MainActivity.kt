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
    private fun applyDebugExtras(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        if (i.hasExtra("gimano.simulateDrive")) vm.setSimulate(i.getBooleanExtra("gimano.simulateDrive", false))
        if (i.hasExtra("gimano.demoMode")) vm.setDemoMode(i.getBooleanExtra("gimano.demoMode", false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDebugExtras(intent)
    }

    private val vm: MainViewModel by viewModels { MainViewModel.Factory((application as GimanoApp).container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyDebugExtras(intent)
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
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            GimanoTheme(dark = dark) {
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                    vm.onLocationPermission(res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
                }
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    if (!text.isNullOrBlank()) vm.openSearch(SearchPurpose.NAVIGATE, text)
                }
                LaunchedEffect(Unit) {
                    val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val notif = Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (fine) vm.onLocationPermission(true)
                    if (!fine || !notif) {
                        val perms = buildList {
                            if (!fine) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
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
                    onExit = {
                        (application as GimanoApp).container.navigation.stop()
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }
}
