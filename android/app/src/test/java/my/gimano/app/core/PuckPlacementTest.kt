package my.gimano.app.core

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.nav.NavProgress
import my.gimano.app.core.nav.PuckPlacement
import org.junit.Assert.*
import org.junit.Test

class PuckPlacementTest {
    private val onRoute = GeoPoint(3.12, 101.62)
    private fun progress(distFromRoute: Double, bearing: Double = 90.0) = NavProgress(
        snapped = onRoute, bearing = bearing, distanceAlongM = 0.0, distanceFromRouteM = distFromRoute,
        remainingM = 1000.0, remainingS = 100.0, nextManeuverIndex = 1, nextManeuver = null,
        distanceToNextM = 100.0, thenManeuver = null, arrived = false,
    )

    @Test fun closeAndSameDirectionSnapsToRoute() {
        val raw = GeoMath.offset(onRoute, 0.0, 20.0)
        val p = PuckPlacement.place(raw, 95.0, 50.0, progress(20.0))
        assertTrue(p.snapped)
        assertEquals(onRoute, p.point)
        assertEquals(90.0, p.bearing, 0.0)
    }

    @Test fun beyond30mShowsRawFix() {
        val raw = GeoMath.offset(onRoute, 0.0, 45.0)
        val p = PuckPlacement.place(raw, 90.0, 50.0, progress(45.0))
        assertFalse(p.snapped)
        assertEquals(raw, p.point)
    }

    @Test fun oppositeHeadingShowsRawFix() {
        val raw = GeoMath.offset(onRoute, 0.0, 10.0)
        assertFalse(PuckPlacement.place(raw, 270.0, 50.0, progress(10.0)).snapped)
    }

    @Test fun headingIgnoredWhenCrawling() {
        val raw = GeoMath.offset(onRoute, 0.0, 10.0)
        assertTrue(PuckPlacement.place(raw, 270.0, 4.0, progress(10.0)).snapped)
    }

    @Test fun noProgressMeansRaw() {
        val p = PuckPlacement.place(onRoute, null, 0.0, null)
        assertFalse(p.snapped)
        assertEquals(0.0, p.bearing, 0.0)
    }
}
