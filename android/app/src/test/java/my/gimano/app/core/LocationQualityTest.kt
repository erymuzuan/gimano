package my.gimano.app.core

import my.gimano.app.core.location.FixRules
import my.gimano.app.core.location.GpsQuality
import my.gimano.app.core.location.LocationPrecision
import org.junit.Assert.*
import org.junit.Test

class LocationQualityTest {
    @Test fun precision() {
        assertEquals(LocationPrecision.PRECISE, LocationPrecision.of(fine = true, coarse = true))
        assertEquals(LocationPrecision.APPROXIMATE, LocationPrecision.of(fine = false, coarse = true))
        assertEquals(LocationPrecision.NONE, LocationPrecision.of(fine = false, coarse = false))
        assertEquals("approximate", LocationPrecision.APPROXIMATE.wire)
    }

    @Test fun fixesWorseThan100mAreNotUsable() {
        assertTrue(FixRules.usable(8.0)); assertTrue(FixRules.usable(100.0)); assertTrue(FixRules.usable(null))
        assertFalse(FixRules.usable(101.0)); assertFalse("approximate-only fixes (~km)", FixRules.usable(2000.0))
        assertTrue("simulated fixes always count", FixRules.usable(5000.0, simulated = true))
    }

    @Test fun weakGpsChipAfter10sOver50m() {
        val g = GpsQuality()
        assertNull(g.onFix(80.0, 0)); assertNull(g.onFix(80.0, 9_000))
        assertEquals(75, g.onFix(75.0, 10_000))
        assertNull("good fix clears it", g.onFix(12.0, 11_000))
        assertNull(g.onFix(60.0, 12_000)); assertNull(g.onFix(60.0, 20_000)); assertEquals(60, g.onFix(60.0, 22_000))
        assertNull("unknown accuracy is not 'weak'", g.onFix(null, 40_000))
    }

    @Test fun followResumesTenSecondsAfterAPan() {
        assertFalse(FixRules.shouldResumeFollow(1_000, 10_999, navigating = true))
        assertTrue(FixRules.shouldResumeFollow(1_000, 11_000, navigating = true))
        assertFalse("only while navigating", FixRules.shouldResumeFollow(1_000, 60_000, navigating = false))
        assertFalse(FixRules.shouldResumeFollow(null, 60_000, navigating = true))
    }
}
