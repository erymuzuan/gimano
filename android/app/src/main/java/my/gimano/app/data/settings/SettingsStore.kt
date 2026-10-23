package my.gimano.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import my.gimano.app.BuildConfig
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "gimano_settings")

enum class SoundMode { ON, ALERTS_ONLY, OFF }

data class AppSettings(
    val token: String? = null,
    val userId: String? = null,
    val displayName: String? = null,
    val deviceId: String = "",
    val demoMode: Boolean = BuildConfig.DEMO_MODE_DEFAULT,
    val simulateDrive: Boolean = false,
    val voiceLang: String = "auto", // auto | en | ms
    val soundMode: SoundMode = SoundMode.ON,
    val vehicle: String = "car",     // car | motorcycle
    val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    val mapStyleUrl: String = BuildConfig.MAP_STYLE_URL,
    val showTraffic: Boolean = true,
    val showAlerts: Boolean = true,
    val showDrivers: Boolean = true,
    val use24h: Boolean = false,
)

/** DataStore-backed settings mirrored into a StateFlow so non-suspending code can read them. */
class SettingsStore(private val context: Context, private val scope: CoroutineScope) {
    private object K {
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("userId")
        val displayName = stringPreferencesKey("displayName")
        val deviceId = stringPreferencesKey("deviceId")
        val demoMode = booleanPreferencesKey("demoMode")
        val simulateDrive = booleanPreferencesKey("simulateDrive")
        val voiceLang = stringPreferencesKey("voiceLang")
        val soundMode = stringPreferencesKey("soundMode")
        val vehicle = stringPreferencesKey("vehicle")
        val apiBaseUrl = stringPreferencesKey("apiBaseUrl")
        val mapStyleUrl = stringPreferencesKey("mapStyleUrl")
        val showTraffic = booleanPreferencesKey("showTraffic")
        val showAlerts = booleanPreferencesKey("showAlerts")
        val showDrivers = booleanPreferencesKey("showDrivers")
        val use24h = booleanPreferencesKey("use24h")
    }

    private val _state: MutableStateFlow<AppSettings>
    val state: StateFlow<AppSettings>
    val current: AppSettings get() = _state.value

    init {
        // One small synchronous read at startup so the first frame has real settings.
        val initial = runBlocking { map(context.dataStore.data.first()) }
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        if (initial.deviceId.isBlank()) {
            scope.launch { context.dataStore.edit { it[K.deviceId] = UUID.randomUUID().toString() } }
        }
        scope.launch { context.dataStore.data.collect { _state.value = map(it) } }
    }

    private fun map(p: Preferences) = AppSettings(
        token = p[K.token],
        userId = p[K.userId],
        displayName = p[K.displayName],
        deviceId = p[K.deviceId] ?: "",
        demoMode = p[K.demoMode] ?: BuildConfig.DEMO_MODE_DEFAULT,
        simulateDrive = p[K.simulateDrive] ?: false,
        voiceLang = p[K.voiceLang] ?: "auto",
        soundMode = p[K.soundMode]?.let { runCatching { SoundMode.valueOf(it) }.getOrNull() } ?: SoundMode.ON,
        vehicle = p[K.vehicle] ?: "car",
        apiBaseUrl = p[K.apiBaseUrl]?.takeIf { it.isNotBlank() } ?: BuildConfig.API_BASE_URL,
        mapStyleUrl = p[K.mapStyleUrl]?.takeIf { it.isNotBlank() } ?: BuildConfig.MAP_STYLE_URL,
        showTraffic = p[K.showTraffic] ?: true,
        showAlerts = p[K.showAlerts] ?: true,
        showDrivers = p[K.showDrivers] ?: true,
        use24h = p[K.use24h] ?: false,
    )

    suspend fun deviceId(): String {
        current.deviceId.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { if (it[K.deviceId].isNullOrBlank()) it[K.deviceId] = id }
        return context.dataStore.data.first()[K.deviceId] ?: id
    }

    suspend fun saveAuth(token: String?, userId: String?, displayName: String?) = context.dataStore.edit {
        if (token == null) it.remove(K.token) else it[K.token] = token
        if (userId == null) it.remove(K.userId) else it[K.userId] = userId
        if (displayName == null) it.remove(K.displayName) else it[K.displayName] = displayName
    }.also { _state.value = _state.value.copy(token = token, userId = userId, displayName = displayName) }

    suspend fun setDisplayName(v: String) = context.dataStore.edit { it[K.displayName] = v }
    suspend fun setDemoMode(v: Boolean) = context.dataStore.edit { it[K.demoMode] = v }
    suspend fun setSimulateDrive(v: Boolean) = context.dataStore.edit { it[K.simulateDrive] = v }
    suspend fun setVoiceLang(v: String) = context.dataStore.edit { it[K.voiceLang] = v }
    suspend fun setSoundMode(v: SoundMode) = context.dataStore.edit { it[K.soundMode] = v.name }
    suspend fun setVehicle(v: String) = context.dataStore.edit { it[K.vehicle] = v }
    suspend fun setApiBaseUrl(v: String) = context.dataStore.edit {
        it[K.apiBaseUrl] = v.trim().let { u -> if (u.isEmpty() || u.endsWith("/")) u else "$u/" }
        // A different server means a different identity.
        it.remove(K.token)
    }
    suspend fun setMapStyleUrl(v: String) = context.dataStore.edit { it[K.mapStyleUrl] = v.trim() }
    suspend fun setLayers(traffic: Boolean, alerts: Boolean, drivers: Boolean) = context.dataStore.edit {
        it[K.showTraffic] = traffic; it[K.showAlerts] = alerts; it[K.showDrivers] = drivers
    }
    suspend fun setUse24h(v: Boolean) = context.dataStore.edit { it[K.use24h] = v }
}
