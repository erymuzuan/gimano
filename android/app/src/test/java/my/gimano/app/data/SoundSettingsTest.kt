package my.gimano.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import my.gimano.app.data.settings.SoundMode
import my.gimano.app.data.settings.SoundModes
import my.gimano.app.data.settings.SoundPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundSettingsTest {
    @Test fun longPressMutesThenReturnsToTheLastNonMuteMode() {
        assertEquals(SoundMode.OFF, SoundModes.longPress(SoundMode.ON, SoundMode.ON))
        assertEquals(SoundMode.OFF, SoundModes.longPress(SoundMode.ALERTS_ONLY, SoundMode.ON))
        assertEquals(SoundMode.ALERTS_ONLY, SoundModes.longPress(SoundMode.OFF, SoundMode.ALERTS_ONLY))
        assertEquals(SoundMode.ON, SoundModes.longPress(SoundMode.OFF, SoundMode.ON))
        // A corrupt "last" of OFF never leaves you stuck muted.
        assertEquals(SoundMode.ON, SoundModes.longPress(SoundMode.OFF, SoundMode.OFF))
    }

    @Test fun pickingAModeRemembersItAndMuteKeepsThePreviousOne() {
        assertEquals(SoundMode.ALERTS_ONLY, SoundModes.lastNonMuteAfter(SoundMode.ALERTS_ONLY, SoundMode.ON))
        assertEquals(SoundMode.ALERTS_ONLY, SoundModes.lastNonMuteAfter(SoundMode.OFF, SoundMode.ALERTS_ONLY))
    }

    @Test fun modeAndUnmuteTargetPersistAcrossDrives() {
        val p = mutablePreferencesOf()
        assertEquals(SoundMode.ON, SoundPrefs.mode(p))                  // default: full sound
        SoundPrefs.setMode(p, SoundMode.ALERTS_ONLY)
        SoundPrefs.setMode(p, SoundMode.OFF)                              // long-press mute
        assertEquals(SoundMode.OFF, SoundPrefs.mode(p))
        assertEquals(SoundMode.ALERTS_ONLY, SoundPrefs.lastNonMute(p))
        // Next drive (a fresh read of the same store): long-press brings Alerts only back.
        val restored = p.toPreferences()
        assertEquals(SoundMode.ALERTS_ONLY, SoundModes.longPress(SoundPrefs.mode(restored), SoundPrefs.lastNonMute(restored)))
        // Garbage in the store reads as the defaults.
        p[SoundPrefs.mode] = "LOUD"; p[SoundPrefs.lastNonMute] = "OFF"
        assertEquals(SoundMode.ON, SoundPrefs.mode(p))
        assertEquals(SoundMode.ON, SoundPrefs.lastNonMute(p))
    }

    @Test fun voiceVolumePersistsClampedToZeroToHundred() {
        val p = mutablePreferencesOf()
        assertEquals(SoundPrefs.DEFAULT_VOLUME, SoundPrefs.volume(p))
        SoundPrefs.setVolume(p, 70)
        assertEquals(70, SoundPrefs.volume(p.toPreferences()))
        SoundPrefs.setVolume(p, 140); assertEquals(100, SoundPrefs.volume(p))
        SoundPrefs.setVolume(p, -5); assertEquals(0, SoundPrefs.volume(p))
        p[SoundPrefs.voiceVolume] = 900                                   // written by something else
        assertEquals(100, SoundPrefs.volume(p))
    }
}
