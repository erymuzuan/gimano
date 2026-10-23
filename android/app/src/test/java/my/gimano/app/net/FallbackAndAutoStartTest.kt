package my.gimano.app.net

import my.gimano.app.core.net.FallbackPolicy
import my.gimano.app.core.trip.AutoStart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackAndAutoStartTest {
    @Test fun backgroundFailuresNeverSwitchToDemo() {
        val p = FallbackPolicy(threshold = 3, windowMs = 60_000)
        repeat(10) { assertFalse(p.onFailure(foreground = false, nowMs = it * 1_000L)) }
    }

    @Test fun threeForegroundFailuresInAMinuteDo() {
        val p = FallbackPolicy(threshold = 3, windowMs = 60_000)
        assertFalse(p.onFailure(true, 0)); assertFalse(p.onFailure(true, 10_000)); assertTrue(p.onFailure(true, 20_000))
    }

    @Test fun successOrTimeResetsTheCount() {
        val p = FallbackPolicy(threshold = 3, windowMs = 60_000)
        p.onFailure(true, 0); p.onFailure(true, 1_000); p.onSuccess()
        assertFalse(p.onFailure(true, 2_000))
        val q = FallbackPolicy(threshold = 3, windowMs = 60_000)
        q.onFailure(true, 0); q.onFailure(true, 1_000)
        assertFalse("old failures expire", q.onFailure(true, 120_000))
    }

    @Test fun autoStartIsOptInAndOnlyForOneTapDestinations() {
        assertFalse("off by default", AutoStart.shouldCountdown(false, true, true, false, false, true))
        assertTrue(AutoStart.shouldCountdown(true, true, true, false, false, true))
        assertFalse("never after a search", AutoStart.shouldCountdown(true, false, true, false, false, true))
        assertFalse("chosen start", AutoStart.shouldCountdown(true, true, false, false, false, true))
        assertFalse("stops", AutoStart.shouldCountdown(true, true, true, true, false, true))
        assertFalse("leave later", AutoStart.shouldCountdown(true, true, true, false, true, true))
        assertFalse("backgrounded", AutoStart.shouldCountdown(true, true, true, false, false, false))
    }
}
