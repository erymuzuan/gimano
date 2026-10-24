package my.gimano.app.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Sound selector rules (canvas NavBunyi / NavBunyiKeadaan), free of Android so they are unit-tested:
 * tap the FAB → choose Full / Alerts only / Mute; long-press → quick mute, or back to the last non-mute mode.
 */
object SoundModes {
    /** Long-press on the sound FAB. */
    fun longPress(current: SoundMode, lastNonMute: SoundMode): SoundMode =
        if (current != SoundMode.OFF) SoundMode.OFF else lastNonMute.takeIf { it != SoundMode.OFF } ?: SoundMode.ON

    /** The mode a later "unmute" returns to, after [picked] was chosen. */
    fun lastNonMuteAfter(picked: SoundMode, previous: SoundMode): SoundMode = if (picked != SoundMode.OFF) picked else previous
}

/** Sound settings in DataStore (on this phone only; the mode also travels in a user backup as before). */
object SoundPrefs {
    val mode = stringPreferencesKey("soundMode")
    val lastNonMute = stringPreferencesKey("lastSoundMode")
    /** App voice volume for spoken directions, 0–100 %, independent of the phone's media volume. */
    val voiceVolume = intPreferencesKey("voiceVolume")
    const val DEFAULT_VOLUME = 100

    private fun parse(s: String?): SoundMode? = s?.let { runCatching { SoundMode.valueOf(it) }.getOrNull() }

    fun mode(p: Preferences): SoundMode = parse(p[mode]) ?: SoundMode.ON
    fun lastNonMute(p: Preferences): SoundMode = parse(p[lastNonMute])?.takeIf { it != SoundMode.OFF } ?: SoundMode.ON
    fun volume(p: Preferences): Int = (p[voiceVolume] ?: DEFAULT_VOLUME).coerceIn(0, 100)

    fun setMode(p: MutablePreferences, picked: SoundMode) {
        p[lastNonMute] = SoundModes.lastNonMuteAfter(picked, lastNonMute(p)).name
        p[mode] = picked.name
    }

    fun setVolume(p: MutablePreferences, percent: Int) { p[voiceVolume] = percent.coerceIn(0, 100) }
}
