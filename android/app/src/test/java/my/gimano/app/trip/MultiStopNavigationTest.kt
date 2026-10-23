package my.gimano.app.trip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.gimano.app.core.format.Lang
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.core.nav.NavigationTracker
import my.gimano.app.data.location.Fix
import my.gimano.app.data.model.*
import my.gimano.app.data.repo.DemoRepository
import my.gimano.app.data.repo.GimanoRepository
import my.gimano.app.data.settings.SoundMode
import my.gimano.app.nav.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiStopNavigationTest {
    // 2 km L: 1 km north to the stop (corner), then 1 km east to the destination.
    private val start = GeoPoint(3.10, 101.60)
    private val corner = GeoMath.offset(start, 0.0, 1000.0)
    private val end = GeoMath.offset(corner, 90.0, 1000.0)
    private val pts = (0..10).map { GeoMath.lerp(start, corner, it / 10.0) } + (1..10).map { GeoMath.lerp(corner, end, it / 10.0) }
    private val route = Route(
        id = "multi", label = "best", durationS = 240.0, distanceM = 2000.0, polyline = Polyline.encode(pts), vehicle = "car",
        legs = listOf(
            Leg(listOf(Maneuver("depart", streetName = "Jalan A", distanceM = 1000.0, beginShapeIndex = 0, endShapeIndex = 10), Maneuver("arrive", beginShapeIndex = 10, endShapeIndex = 10)),
                durationS = 120.0, to = LegTarget(corner.lat, corner.lng, "Kedai Mak")),
            Leg(listOf(Maneuver("depart", streetName = "Jalan B", distanceM = 1000.0, beginShapeIndex = 10, endShapeIndex = 20), Maneuver("arrive", beginShapeIndex = 20, endShapeIndex = 20)),
                durationS = 120.0, to = LegTarget(end.lat, end.lng, "Rumah")),
        ),
    )
    private val stop = NavDestination("Kedai Mak", "", corner, null)
    private val dest = NavDestination("Rumah", "", end, null)

    @Test fun trackerReportsTheStopOnceAndTheLeg() {
        val t = NavigationTracker(route)
        assertEquals(listOf(1000.0), t.stopAlongM.map { Math.round(it).toDouble() })
        val before = t.update(GeoMath.offset(start, 0.0, 500.0))!!
        assertEquals(0, before.legIndex)
        assertNull(t.stopReached(before))
        val atStop = t.update(GeoMath.offset(start, 0.0, 990.0))!!
        assertEquals(0, t.stopReached(atStop))
        assertNull("only once", t.stopReached(t.update(GeoMath.offset(start, 0.0, 995.0))!!))
        assertEquals(1, t.update(GeoMath.offset(corner, 90.0, 200.0))!!.legIndex)
    }

    private class Speaker2 : Speaker { val said = ArrayList<String>(); override fun speak(text: String, lang: Lang, flush: Boolean) { said += text }; override fun stop() {} }
    private class Svc : NavServiceControl { override fun start() {}; override fun stop() {} }
    private class Repo(d: DemoRepository) : GimanoRepository by d {
        val reroutes = ArrayList<RerouteRequest>()
        override suspend fun reroute(routeId: String, req: RerouteRequest): List<Route> { reroutes += req; return emptyList() }
        override suspend fun postLocations(batch: LocationBatch) {}
    }

    @Test fun simulatedDriveStopsAtTheStopUntilContinue() = runTest {
        val speaker = Speaker2()
        val c = NavigationController(backgroundScope, Repo(DemoRepository()), FixSource { kotlinx.coroutines.flow.emptyFlow() }, speaker, Svc(), { SoundMode.ON }, { Lang.EN })
        c.start(route, dest, RouteRequest(LatLngDto(start.lat, start.lng), dest.toDto(), waypoints = listOf(stop.toDto())), simulate = true, stops = listOf(stop))
        var t = 0
        while (c.state.value.arrivedAtStop == null && t < 600) { advanceTimeBy(1_000); runCurrent(); t++ }
        assertEquals(stop, c.state.value.arrivedAtStop)
        assertTrue(speaker.said.any { it == "You have arrived at Kedai Mak" })
        advanceTimeBy(3_000); runCurrent()   // the stop is detected within 25 m; the driver then pulls in
        val heldAt = c.state.value.progress!!.distanceAlongM
        assertEquals(1000.0, heldAt, 2.0)
        advanceTimeBy(20_000); runCurrent()
        assertEquals("the simulated driver waits at the stop", heldAt, c.state.value.progress!!.distanceAlongM, 1.0)
        c.continueAfterStop()
        advanceTimeBy(10_000); runCurrent()
        assertNull(c.state.value.arrivedAtStop)
        assertTrue(c.state.value.stops.isEmpty())
        assertTrue(c.state.value.progress!!.distanceAlongM > heldAt + 50)
        assertEquals(1, c.state.value.progress!!.legIndex)
        c.stop()
    }

    @Test fun rerouteAfterTheFirstStopSendsNextStopIndex() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
        val repo = Repo(DemoRepository())
        val c = NavigationController(backgroundScope, repo, FixSource { fixes }, Speaker2(), Svc(), { SoundMode.OFF }, { Lang.EN })
        c.start(route, dest, RouteRequest(LatLngDto(start.lat, start.lng), dest.toDto(), waypoints = listOf(stop.toDto())), simulate = false, stops = listOf(stop))
        runCurrent()
        var now = 1_000_000L
        fun fix(p: GeoPoint) = Fix(p, 10f, 90f, 5f, now.also { now += 1_000 })
        // Reach the stop, continue, then drive 200 m off the second leg for several fixes.
        fixes.emit(fix(GeoMath.offset(start, 0.0, 995.0))); runCurrent()
        assertNotNull(c.state.value.arrivedAtStop)
        c.continueAfterStop()
        val off = GeoMath.offset(GeoMath.offset(corner, 90.0, 300.0), 180.0, 200.0)
        repeat(4) { fixes.emit(fix(off)); runCurrent() }
        assertEquals(1, repo.reroutes.size)
        assertEquals("first waypoint already visited", 1, repo.reroutes.single().nextStopIndex)
        c.stop()
    }

    @Test fun stopCardClosesAfterARerouteOnceTheDriverPullsAway() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
        // The driver leaves the stop the "wrong" way: 200 m west of it, then the reroute goes from there.
        val off = GeoMath.offset(corner, 270.0, 200.0)
        val newPts = (0..10).map { GeoMath.lerp(off, end, it / 10.0) }
        val newLen = GeoMath.distanceM(off, end)
        val rerouted = Route(
            id = "rerouted", label = "best", durationS = 150.0, distanceM = newLen, polyline = Polyline.encode(newPts), vehicle = "car",
            legs = listOf(Leg(listOf(Maneuver("depart", streetName = "Jalan C", distanceM = newLen, beginShapeIndex = 0, endShapeIndex = 10),
                Maneuver("arrive", beginShapeIndex = 10, endShapeIndex = 10)), durationS = 150.0, to = LegTarget(end.lat, end.lng, "Rumah"))),
        )
        val repo = object : GimanoRepository by DemoRepository() {
            override suspend fun reroute(routeId: String, req: RerouteRequest): List<Route> = listOf(rerouted)
            override suspend fun postLocations(batch: LocationBatch) {}
        }
        val c = NavigationController(backgroundScope, repo, FixSource { fixes }, Speaker2(), Svc(), { SoundMode.OFF }, { Lang.EN })
        c.start(route, dest, RouteRequest(LatLngDto(start.lat, start.lng), dest.toDto(), waypoints = listOf(stop.toDto())), simulate = false, stops = listOf(stop))
        runCurrent()
        var now = 1_000_000L
        fun fix(p: GeoPoint, brg: Float) = Fix(p, 10f, brg, 5f, now.also { now += 1_000 })
        fixes.emit(fix(GeoMath.offset(start, 0.0, 995.0), 0f)); runCurrent()
        assertNotNull(c.state.value.arrivedAtStop)
        repeat(3) { fixes.emit(fix(off, 270f)); runCurrent() }
        assertEquals("rerouted", c.state.value.route?.id)
        assertNotNull("still at the stop", c.state.value.arrivedAtStop)
        // 300 m along the new route (the old route's stop was ~1000 m along): the driver has clearly left.
        val brgToEnd = GeoMath.bearingDeg(off, end)
        fixes.emit(fix(GeoMath.offset(off, brgToEnd, 300.0), brgToEnd.toFloat())); runCurrent()
        assertNull("stop card closed, prompts resume", c.state.value.arrivedAtStop)
        c.stop()
    }

    @Test fun addingAStopNeverRestartsADriveThatEndedMeanwhile() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = object : GimanoRepository by DemoRepository() {
            override suspend fun routes(req: RouteRequest): List<Route> { gate.await(); return listOf(route) }
            override suspend fun postLocations(batch: LocationBatch) {}
        }
        val c = NavigationController(backgroundScope, repo, FixSource { fixes }, Speaker2(), Svc(), { SoundMode.OFF }, { Lang.EN })
        c.start(route, dest, RouteRequest(LatLngDto(start.lat, start.lng), dest.toDto()), simulate = false)
        runCurrent()
        fixes.emit(Fix(GeoMath.offset(start, 0.0, 300.0), 10f, 0f, 5f)); runCurrent()
        val added = async { c.addStop(stop) }
        runCurrent()
        c.stop()   // End tapped while the new route loads
        gate.complete(Unit); runCurrent()
        assertFalse(added.await())
        assertEquals(NavState(), c.state.value)
    }
}
