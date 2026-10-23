package my.gimano.app.issues

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.issues.DeviationPromptGate
import my.gimano.app.core.issues.DeviationTracker
import my.gimano.app.core.nav.RouteGeometry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DeviationTest {
    // Straight north for 2 km, then east for 2 km (the decision point is the corner at 2 km).
    private val start = GeoPoint(3.10, 101.60)
    private val corner = GeoMath.offset(start, 0.0, 2_000.0)
    private val end = GeoMath.offset(corner, 90.0, 2_000.0)
    private val route = RouteGeometry((0..20).map { GeoMath.offset(start, 0.0, it * 100.0) } + (1..20).map { GeoMath.offset(corner, 90.0, it * 100.0) })

    @Test fun leftAtDecisionPointAndNeverRejoinedIsConfirmed() {
        val t = DeviationTracker()
        t.onLeftRoute(alongM = 1_900.0, distanceToNextManeuverM = 100.0, road = "Jalan Ditutup", route = route)
        // Driver carries on north past the corner instead of turning east.
        assertNull(t.onFix(GeoMath.offset(corner, 0.0, 200.0)))
        val d = t.onFix(GeoMath.offset(corner, 0.0, 700.0))
        assertNotNull(d)
        // The reported segment is the one the route wanted: just east of the corner, heading east.
        assertTrue(GeoMath.distanceM(d!!.point, GeoMath.offset(corner, 90.0, 30.0)) < 5)
        assertEquals(90.0, d.heading, 2.0)
        assertEquals("Jalan Ditutup", d.road)
        assertNull("only once", t.onFix(GeoMath.offset(corner, 0.0, 900.0)))
        assertEquals(d, t.confirmed)
    }

    @Test fun rejoiningTheOldRouteAheadForgetsIt() {
        val t = DeviationTracker()
        t.onLeftRoute(1_900.0, 100.0, "Jalan A", route)
        assertNull(t.onFix(GeoMath.offset(corner, 45.0, 150.0)))            // cut the corner…
        assertNull(t.onFix(GeoMath.offset(corner, 90.0, 400.0)))            // …and back on the east leg
        assertNull(t.onFix(GeoMath.offset(corner, 90.0, 2_500.0).let { GeoMath.offset(it, 0.0, 600.0) }))
        assertNull(t.confirmed)
    }

    @Test fun leavingFarFromAnyManeuverIsNotADeviationQuestion() {
        val t = DeviationTracker()
        t.onLeftRoute(500.0, 1_500.0, "Jalan B", route)
        assertNull(t.onFix(GeoMath.offset(start, 270.0, 900.0)))
        assertNull(t.confirmed)
    }

    @Test fun gateOncePerDayNeverWhileDrivingAndDontAskAgain() {
        val today = LocalDate.of(2026, 10, 23)
        assertTrue(DeviationPromptGate.mayAsk(false, null, today, 0))
        assertTrue(DeviationPromptGate.mayAsk(false, "2026-10-22", today, 3))
        assertFalse(DeviationPromptGate.mayAsk(false, "2026-10-23", today, 0))
        assertFalse(DeviationPromptGate.mayAsk(true, null, today, 0))
        assertFalse(DeviationPromptGate.mayAsk(false, null, today, 40))
    }
}
