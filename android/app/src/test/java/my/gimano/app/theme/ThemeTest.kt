package my.gimano.app.theme

import my.gimano.app.core.theme.SunTimes
import my.gimano.app.core.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ThemeTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")

    private fun assertNear(expectedLocal: String, actual: Instant?, date: LocalDate, minutes: Long = 6) {
        val expected = date.atTime(java.time.LocalTime.parse(expectedLocal)).atZone(kl).toInstant()
        val diff = Duration.between(expected, actual!!).abs().toMinutes()
        assertTrue("expected ~$expectedLocal, got ${actual.atZone(kl).toLocalTime()}", diff <= minutes)
    }

    @Test fun kualaLumpurSunriseSunset() {
        // Independent check: KL mean solar noon = 13:13 MYT (101.69°E in UTC+8). On 23 Oct the equation
        // of time is +15.6 min → solar noon ≈ 12:58, day ≈ 12 h 03 → sunrise ≈ 06:56, sunset ≈ 18:59.
        // 21 Jun: EoT ≈ −2 min → noon ≈ 13:15, day ≈ 12 h 16 → 07:07 / 19:23.
        val oct = LocalDate.of(2026, 10, 23)
        SunTimes.of(oct, 3.139, 101.687).let { assertNear("06:56", it.sunrise, oct, 4); assertNear("18:59", it.sunset, oct, 4) }
        val jun = LocalDate.of(2026, 6, 21)
        SunTimes.of(jun, 3.139, 101.687).let { assertNear("07:07", it.sunrise, jun, 4); assertNear("19:23", it.sunset, jun, 4) }
    }

    @Test fun kotaKinabaluIsEarlierThanKl() {
        val d = LocalDate.of(2026, 10, 23)
        val kk = SunTimes.of(d, 5.98, 116.07).sunrise!!
        val klr = SunTimes.of(d, 3.139, 101.687).sunrise!!
        assertTrue(Duration.between(kk, klr).toMinutes() in 50..70)  // ~1 h earlier (same time zone)
    }

    @Test fun autoSwitchesAtSunsetAndSunrise() {
        fun at(t: String) = java.time.LocalDateTime.parse(t).atZone(kl).toInstant()
        assertFalse(ThemeMode.isDark(ThemeMode.AUTO, systemDark = true, now = at("2026-10-23T12:00"), lat = 3.139, lng = 101.687))
        assertTrue(ThemeMode.isDark(ThemeMode.AUTO, systemDark = false, now = at("2026-10-23T21:00"), lat = 3.139, lng = 101.687))
        assertTrue(ThemeMode.isDark(ThemeMode.AUTO, systemDark = false, now = at("2026-10-23T05:30"), lat = 3.139, lng = 101.687))
        assertFalse(ThemeMode.isDark(ThemeMode.AUTO, systemDark = false, now = at("2026-10-23T07:30"), lat = null, lng = null))
    }

    @Test fun lightIsTheDefaultEvenOnADarkPhone() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.from(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.from("bogus"))
        assertFalse(ThemeMode.isDark(ThemeMode.LIGHT, systemDark = true, now = Instant.now(), lat = null, lng = null))
        assertTrue(ThemeMode.isDark(ThemeMode.SYSTEM, systemDark = true, now = Instant.now(), lat = null, lng = null))
        assertTrue(ThemeMode.isDark(ThemeMode.DARK, systemDark = false, now = Instant.now(), lat = null, lng = null))
    }

    @Test fun polarNightHasNoSunrise() {
        assertNull(SunTimes.of(LocalDate.of(2026, 12, 21), 78.2, 15.6).sunrise)
    }
}
