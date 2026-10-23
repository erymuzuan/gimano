package my.gimano.app.data

import kotlinx.coroutines.runBlocking
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.core.nav.NavigationTracker
import my.gimano.app.core.nav.PuckPlacement
import my.gimano.app.data.model.AvoidOptions
import my.gimano.app.data.model.LatLngDto
import my.gimano.app.data.model.RouteRequest
import my.gimano.app.data.repo.CannedRoutes
import my.gimano.app.data.repo.DemoRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guards the demo-mode bug where routes were synthetic lines through buildings. */
class CannedRoutesTest {
    private val dir = File("src/main/assets/demo-routes")
    private val docs = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map { it.readText() }
    private val canned = CannedRoutes.parse(docs)

    @Test fun assetsArePresent() {
        assertTrue("expected captured demo routes in $dir", canned.all.size >= 3)
    }

    @Test fun ss2ToMidValleyDecodesAtPrecision6AndStartsAndEndsWhereExpected() {
        val set = canned.all.single { it.id == "ss2-midvalley" }
        val pts = Polyline.decode(set.routes.first().polyline)
        // SS2 Petaling Jaya → Mid Valley Megamall. With a 1e5/1e6 mismatch these would be ~10x off.
        assertTrue("first point near SS2: ${pts.first()}", GeoMath.distanceM(pts.first(), GeoPoint(3.1200, 101.6200)) < 150)
        assertTrue("last point near Mid Valley: ${pts.last()}", GeoMath.distanceM(pts.last(), GeoPoint(3.1176, 101.6774)) < 300)
        assertEquals(3.12, pts.first().lat, 0.01)
        assertEquals(101.62, pts.first().lng, 0.01)
    }

    @Test fun everyCannedRouteIsConsistentWithTheContract() {
        canned.all.forEach { set ->
            set.routes.forEach { r ->
                val pts = Polyline.decode(r.polyline)
                assertTrue(set.id, pts.size > 10)
                assertTrue("${set.id} start", GeoMath.distanceM(pts.first(), set.origin.point) < 200)
                assertTrue("${set.id} end", GeoMath.distanceM(pts.last(), set.destination.point) < 300)
                // Every point is inside Peninsular Malaysia's Klang Valley-ish box, i.e. precision 6.
                assertTrue(set.id, pts.all { it.lat in 2.5..3.5 && it.lng in 101.3..102.0 })
                val ms = r.maneuvers
                assertEquals("depart", ms.first().type)
                assertEquals("arrive", ms.last().type)
                assertEquals(pts.lastIndex, ms.last().beginShapeIndex)
                assertTrue(set.id, ms.zipWithNext().all { (a, b) -> b.beginShapeIndex >= a.beginShapeIndex })
                assertTrue(set.id, ms.all { it.beginShapeIndex in 0..pts.lastIndex })
                // Polyline length agrees with the router's distance (within 3 %).
                val len = pts.zipWithNext().sumOf { (a, b) -> GeoMath.distanceM(a, b) }
                assertEquals(set.id, r.distanceM, len, r.distanceM * 0.03)
            }
        }
    }

    @Test fun ldpRouteUsesTheExpressway() {
        val r = canned.all.single { it.id == "ss2-sunway-ldp" }.routes.first()
        assertTrue(r.maneuvers.any { it.roadRef == "E11" })
    }

    @Test fun demoRepositoryReturnsRealRoadRoutesForNearbyDestination() = runBlocking {
        val repo = DemoRepository(cannedDocs = { docs })
        val routes = repo.routes(
            RouteRequest(LatLngDto(3.12, 101.62), LatLngDto(3.1176, 101.6774, "midvalley"), avoid = AvoidOptions()),
        )
        assertTrue(routes.isNotEmpty())
        assertEquals(1, routes.count { it.isBest })
        val captured = canned.all.single { it.id == "ss2-midvalley" }.routes.first().polyline
        assertTrue(routes.any { it.polyline == captured })
        assertTrue(routes.first().effectiveDurationS >= routes.first().durationS)
    }

    @Test fun simulatedDriveStaysOnTheRouteLine() {
        val route = canned.all.single { it.id == "ss2-klsentral" }.routes.first()
        val tracker = NavigationTracker(route)
        val g = tracker.geometry
        var along = 0.0
        var lastManeuver = -1
        var maneuversSeen = 0
        while (along < g.lengthM) {
            val (p, brg) = g.pointAt(along)
            val prog = tracker.update(p)!!
            assertTrue("off line at $along m: ${prog.distanceFromRouteM}", prog.distanceFromRouteM < 1.0)
            assertEquals(along, prog.distanceAlongM, 2.0)
            val placed = PuckPlacement.place(p, brg, 50.0, prog)
            assertTrue(placed.snapped)
            if (prog.nextManeuverIndex != lastManeuver) { maneuversSeen++; lastManeuver = prog.nextManeuverIndex }
            along += 13.9 // 50 km/h, 1 s ticks
        }
        assertTrue("turns at the maneuvers", maneuversSeen >= route.maneuvers.size - 2)
    }
}
