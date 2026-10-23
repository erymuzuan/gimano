package my.gimano.app.trip

import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.trip.RouteOrigin
import my.gimano.app.core.trip.RouteOrigin.Gps
import org.junit.Assert.*
import org.junit.Test

class RouteOriginTest {
    private val klcc = GeoPoint(3.157938, 101.712313)
    private val now = 1_800_000_000_000L

    @Test fun noFixIsNone() {
        assertEquals(Gps.None, RouteOrigin.classify(null, null, now))
        assertEquals(Gps.None, RouteOrigin.classify(klcc, null, now))
        assertEquals(Gps.None, RouteOrigin.classify(klcc, 0L, now))
    }

    @Test fun recentFixIsFresh() {
        assertEquals(Gps.Fresh(klcc), RouteOrigin.classify(klcc, now - 5_000, now))
        assertEquals(Gps.Fresh(klcc), RouteOrigin.classify(klcc, now - 120_000, now))
    }

    @Test fun oldFixIsStale() {
        val g = RouteOrigin.classify(klcc, now - 121_000, now)
        assertTrue(g is Gps.Stale)
        assertEquals(121_000, (g as Gps.Stale).ageMs)
        assertTrue(RouteOrigin.classify(klcc, now - 3_600_000, now) is Gps.Stale)
    }

    @Test fun fixFromTheFutureBeyondClockSkewIsNotTrusted() {
        assertTrue(RouteOrigin.classify(klcc, now + 10 * 60_000, now) is Gps.Stale)
    }

    @Test fun sameSpotUnder50m() {
        val suria40m = GeoPoint(3.158298, 101.712313)    // ~40 m north
        val far = GeoPoint(3.158938, 101.712313)        // ~111 m north
        assertTrue(RouteOrigin.sameSpot(klcc, klcc))
        assertTrue(RouteOrigin.sameSpot(klcc, suria40m))
        assertFalse(RouteOrigin.sameSpot(klcc, far))
    }
}
