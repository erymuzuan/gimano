package my.gimano.app.core

import my.gimano.app.core.format.Formatters
import my.gimano.app.core.format.Lang
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FormattersTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")

    @Test fun distanceForLists() {
        assertEquals("0 m", Formatters.distance(0.0))
        assertEquals("910 m", Formatters.distance(908.0))
        assertEquals("990 m", Formatters.distance(994.0))
        assertEquals("1.0 km", Formatters.distance(996.0))
        assertEquals("5.1 km", Formatters.distance(5_080.0))
        assertEquals("9.9 km", Formatters.distance(9_940.0))
        assertEquals("10 km", Formatters.distance(9_960.0))
        assertEquals("260 km", Formatters.distance(260_400.0))
        assertEquals("0 m", Formatters.distance(-5.0))
    }

    @Test fun navBannerDistanceUsesCoarserSteps() {
        assertEquals("40 m", Formatters.navDistance(43.0))
        assertEquals("250 m", Formatters.navDistance(240.0))
        assertEquals("700 m", Formatters.navDistance(680.0))
        assertEquals("1.2 km", Formatters.navDistance(1_234.0))
    }

    @Test fun durationEnglishAndMalay() {
        assertEquals("1 min", Formatters.duration(10.0))
        assertEquals("10 min", Formatters.duration(600.0))
        assertEquals("1 h", Formatters.duration(3_600.0))
        assertEquals("1 h 5 min", Formatters.duration(3_900.0))
        assertEquals("1 j 5 min", Formatters.duration(3_900.0, Lang.MS))
        assertEquals("2 j", Formatters.duration(7_200.0, Lang.MS))
    }

    @Test fun etaClock12hAnd24h() {
        val now = Instant.parse("2026-10-23T00:03:00Z") // 08:03 in KL
        assertEquals("8:13 AM", Formatters.etaClock(now, 600.0, kl, use24h = false))
        assertEquals("08:13", Formatters.etaClock(now, 600.0, kl, use24h = true))
        assertEquals("8:13 PG", Formatters.etaClock(now, 600.0, kl, use24h = false, lang = Lang.MS))
        val evening = Instant.parse("2026-10-23T11:50:00Z") // 19:50 KL
        assertEquals("8:05 PM", Formatters.etaClock(evening, 900.0, kl, use24h = false))
        val midnight = Instant.parse("2026-10-23T15:55:00Z") // 23:55 KL
        assertEquals("12:05 AM", Formatters.etaClock(midnight, 600.0, kl, use24h = false))
    }

    @Test fun remainingLine() {
        assertEquals("10 min • 3.7 km", Formatters.remainingLine(600.0, 3_700.0))
    }

    @Test fun reportAge() {
        val now = Instant.parse("2026-10-23T10:00:00Z")
        assertEquals("Just now", Formatters.reportAge(now.minusSeconds(20), now))
        assertEquals("5 min ago", Formatters.reportAge(now.minusSeconds(5 * 60), now))
        assertEquals("2 h ago", Formatters.reportAge(now.minusSeconds(2 * 3600 + 100), now))
        assertEquals("1 day ago", Formatters.reportAge(now.minusSeconds(26 * 3600), now))
        assertEquals("5 min lalu", Formatters.reportAge(now.minusSeconds(5 * 60), now, Lang.MS))
        assertEquals("Baru sahaja", Formatters.reportAge(now.plusSeconds(30), now, Lang.MS)) // clock skew
    }

    @Test fun reportTimeLeftDisplay() {
        val now = Instant.parse("2026-10-23T10:00:00Z")
        assertEquals("Expires in 25 min", Formatters.reportTimeLeft(now.plusSeconds(25 * 60), now))
        assertEquals("Expires in 2 h 5 min", Formatters.reportTimeLeft(now.plusSeconds(125 * 60), now))
        assertEquals("Expires in < 1 min", Formatters.reportTimeLeft(now.plusSeconds(30), now))
        assertEquals("Expired", Formatters.reportTimeLeft(now.minusSeconds(1), now))
        assertEquals("Tamat dalam 25 min", Formatters.reportTimeLeft(now.plusSeconds(25 * 60), now, Lang.MS))
        assertEquals("Tamat tempoh", Formatters.reportTimeLeft(now, now, Lang.MS))
    }

    @Test fun reportTtlFraction() {
        val created = Instant.parse("2026-10-23T10:00:00Z")
        val expires = created.plusSeconds(30 * 60) // police: 30 min TTL
        assertEquals(1f, Formatters.reportTtlFraction(created, expires, created), 1e-6f)
        assertEquals(0.5f, Formatters.reportTtlFraction(created, expires, created.plusSeconds(15 * 60)), 1e-6f)
        assertEquals(0f, Formatters.reportTtlFraction(created, expires, expires.plusSeconds(60)), 1e-6f)
        assertEquals(0f, Formatters.reportTtlFraction(created, created, created), 1e-6f)
    }

    @Test fun parseInstantAcceptsOffsetsAndRejectsGarbage() {
        assertEquals(Instant.parse("2026-10-23T02:00:00Z"), Formatters.parseInstant("2026-10-23T10:00:00+08:00"))
        assertEquals(null, Formatters.parseInstant("not a date"))
        assertEquals(null, Formatters.parseInstant(null))
    }

    @Test fun langFromTag() {
        assertEquals(Lang.MS, Lang.from("ms-MY"))
        assertEquals(Lang.EN, Lang.from("en"))
        assertEquals(Lang.EN, Lang.from(null))
    }
}
