package my.gimano.app.core

import my.gimano.app.core.nav.OffRouteDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffRouteDetectorTest {
    @Test fun singleNoisyFixDoesNotTrigger() {
        val d = OffRouteDetector()
        assertFalse(d.update(120.0, 10.0, 0))
        assertFalse(d.update(10.0, 10.0, 1_000))
        assertFalse(d.update(120.0, 10.0, 2_000))
        assertFalse(d.update(5.0, 10.0, 3_000))
    }

    @Test fun threeConsecutiveFixesTriggerOnce() {
        val d = OffRouteDetector()
        assertFalse(d.update(80.0, 8.0, 0))
        assertFalse(d.update(90.0, 8.0, 1_000))
        assertTrue(d.update(100.0, 8.0, 2_000))
        // Counter resets after triggering.
        assertFalse(d.update(110.0, 8.0, 3_000))
    }

    @Test fun cooldownPreventsRerouteStorm() {
        val d = OffRouteDetector(cooldownMs = 15_000)
        repeat(2) { d.update(100.0, 5.0, it * 1_000L) }
        assertTrue(d.update(100.0, 5.0, 2_000))
        // Still off route, but within cooldown.
        repeat(5) { assertFalse(d.update(100.0, 5.0, 3_000L + it * 1_000L)) }
        // After cooldown a reroute is allowed again (counter accumulated meanwhile).
        assertTrue(d.update(100.0, 5.0, 18_000))
    }

    @Test fun poorAccuracyWidensThreshold() {
        val d = OffRouteDetector()
        assertEquals(50.0, d.thresholdFor(10.0), 0.0)
        assertEquals(90.0, d.thresholdFor(60.0), 0.0)
        // 70 m off with 60 m accuracy is within noise.
        repeat(5) { assertFalse(d.update(70.0, 60.0, it * 1_000L)) }
    }

    @Test fun uselessFixesAreIgnored() {
        val d = OffRouteDetector()
        repeat(5) { assertFalse(d.update(500.0, 250.0, it * 1_000L)) }
        assertFalse(d.isOffRoute)
    }

    @Test fun drivingTheWrongWayCountsAsOffRoute() {
        val d = OffRouteDetector()
        assertFalse(d.update(30.0, 5.0, 0, headingDiffDeg = 175.0, speedKmh = 40.0))
        assertFalse(d.update(30.0, 5.0, 1_000, headingDiffDeg = 178.0, speedKmh = 40.0))
        assertTrue(d.update(30.0, 5.0, 2_000, headingDiffDeg = -170.0, speedKmh = 40.0))
    }

    @Test fun wrongHeadingWhileCrawlingIsIgnored() {
        val d = OffRouteDetector()
        repeat(5) { assertFalse(d.update(30.0, 5.0, it * 1_000L, headingDiffDeg = 180.0, speedKmh = 5.0)) }
    }
}
