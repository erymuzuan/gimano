package my.gimano.app.data

import kotlinx.coroutines.runBlocking
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.core.nav.RouteGeometry
import my.gimano.app.data.model.AvoidOptions
import my.gimano.app.data.model.LatLngDto
import my.gimano.app.data.model.RouteRequest
import my.gimano.app.data.repo.CannedRoutes
import my.gimano.app.data.repo.DemoRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Demo jams, alerts and nearby drivers must sit on real road geometry (the captured OSM
 * route polylines), never on synthetic straight segments drawn across the map.
 */
class DemoLiveGeometryTest {
    private val docs = File("src/main/assets/demo-routes").listFiles { f -> f.extension == "json" }!!.map { it.readText() }
    private val roads = CannedRoutes.parse(docs).all.flatMap { it.routes }.map { RouteGeometry(Polyline.decode(it.polyline)) }

    private fun distanceToRoads(p: GeoPoint): Double = roads.minOf { it.snap(p)!!.distanceFromRouteM }

    private fun assertOnRoad(what: String, p: GeoPoint) {
        val d = distanceToRoads(p)
        assertTrue("$what at $p is ${"%.1f".format(d)} m from any captured road", d <= 15.0)
    }

    private fun checkViewport(center: GeoPoint) = runBlocking {
        val repo = DemoRepository(cannedDocs = { docs })
        val r = repo.live(center.lng - 0.05, center.lat - 0.05, center.lng + 0.05, center.lat + 0.05)
        assertTrue("alerts seeded", r.alerts.isNotEmpty())
        assertTrue("jams seeded", r.jams.isNotEmpty())
        assertTrue("drivers seeded", r.drivers.isNotEmpty())
        r.alerts.forEach { assertOnRoad("alert ${it.id}", GeoPoint(it.lat, it.lng)) }
        r.drivers.forEach { assertOnRoad("driver", GeoPoint(it.lat, it.lng)) }
        r.jams.forEach { j ->
            val pts = Polyline.decode(j.polyline)
            assertTrue("jam ${j.id} has geometry", pts.size >= 2)
            pts.forEach { assertOnRoad("jam ${j.id} vertex", it) }
        }
    }

    @Test fun liveDataNearSs2IsOnRoads() = checkViewport(GeoPoint(3.1200, 101.6200))

    @Test fun liveDataNearKlccIsOnRoads() = checkViewport(GeoPoint(3.1579, 101.7123))

    @Test fun liveDataFarFromCapturedRoadsIsStillOnRoads() = checkViewport(GeoPoint(5.3550, 100.3480)) // Penang

    @Test fun alertsOnPlannedRoutesAreOnRoads() = runBlocking {
        val repo = DemoRepository(cannedDocs = { docs })
        val routes = repo.routes(RouteRequest(LatLngDto(3.12, 101.62), LatLngDto(3.1579, 101.7123), avoid = AvoidOptions()))
        val alerts = routes.flatMap { it.alerts }
        assertTrue(alerts.isNotEmpty())
        alerts.forEach { assertOnRoad("route alert ${it.id}", GeoPoint(it.lat, it.lng)) }
    }
}
