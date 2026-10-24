package my.gimano.app.routes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.core.events.*
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.routes.*
import my.gimano.app.data.events.EventsMigrations
import my.gimano.app.data.remote.ApiFactory
import my.gimano.app.data.routes.ApiLegRouter
import my.gimano.app.data.routes.BuilderTarget
import my.gimano.app.data.routes.BuilderTool
import my.gimano.app.data.routes.LegRouter
import my.gimano.app.data.routes.RouteBuilder
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun pos(lat: Double, lng: Double) = Pos(lat, lng)

/** A route with [n] points along a line; every leg ROUTED with a 3-point geometry. */
private fun routed(n: Int, mode: String = "bicycle"): BuilderRoute {
    var r = BuilderRoute(EventIds.newItemId(), mode = mode)
    for (i in 0 until n) r = RouteEdit.addPoint(r, pos(3.0 + i * 0.01, 101.5 + i * 0.01))!!
    for (job in RouteEdit.pendingJobs(r)) {
        val k = job.kind as RouteEdit.JobKind.Route
        val mid = Pos((k.from.lat + k.to.lat) / 2, (k.from.lng + k.to.lng) / 2 + 0.002)
        r = RouteEdit.applyJob(r, job, RouteEdit.JobResult.Routed(listOf(k.from, mid, k.to), 1500, 300))!!
    }
    return r
}

class RoutePolylineTest {
    private fun vectorRoute(): JsonObject {
        val v = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/ride-two-day.json")!!.bufferedReader().use { it.readText() }).jsonObject
        val ev = Json.parseToJsonElement(v.getValue("eventJson").jsonPrimitive.content).jsonObject
        return ev.getValue("routes").jsonArray[0].jsonObject
    }

    @Test fun reEncodesTheRouteCoreVector() {
        val poly = vectorRoute().getValue("polyline").jsonPrimitive.content
        val pts = EventPolyline.decode(poly, 1000)
        assertEquals(7, pts.size)
        assertEquals(GeoPoint(2.95, 101.85), pts.first())
        assertEquals(poly, RoutePolyline.encode(pts))
    }

    @Test fun googleExampleAtPrecision6() {
        val pts = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        val enc = RoutePolyline.encode(pts)
        assertEquals("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI", enc)
        assertEquals(pts, EventPolyline.decode(enc, 10))
    }

    @Test fun roundsHalfAwayFromZeroAndRoundTrips() {
        val pts = listOf(GeoPoint(2.9871, 101.793), GeoPoint(2.9866, 101.7942), GeoPoint(-0.000001, -0.000001), GeoPoint(5.420312, 100.329871))
        val enc = RoutePolyline.encode(pts)
        val dec = EventPolyline.decode(enc, 100)
        pts.zip(dec).forEach { (a, b) -> assertEquals(a.lat, b.lat, 1e-9); assertEquals(a.lng, b.lng, 1e-9) }
        assertEquals(enc, RoutePolyline.encode(dec))
        // 0.0000005 rounds away from zero (route-core), not to even.
        assertEquals(RoutePolyline.encode(listOf(GeoPoint(0.000001, -0.000001))), RoutePolyline.encode(listOf(GeoPoint(0.0000005, -0.0000005))))
        assertEquals("", RoutePolyline.encode(emptyList()))
    }
}

class RouteEditTest {
    @Test fun pointsAndLegsStayConsistent() {
        var r = BuilderRoute("r1")
        r = RouteEdit.addPoint(r, pos(3.0, 101.0))!!
        assertEquals(0, r.legs.size)
        r = RouteEdit.addPoint(r, pos(3.1, 101.1))!!
        r = RouteEdit.addPoint(r, pos(3.2, 101.2))!!
        assertTrue(r.isConsistent)
        assertEquals(listOf(LegState.PENDING, LegState.PENDING), r.legs.map { it.state })
        r = RouteEdit.deletePoint(r, 1)!!
        assertTrue(r.isConsistent)
        assertEquals(1, r.legs.size)
        r = RouteEdit.insertPoint(r, 0, pos(3.05, 101.05))!!
        assertEquals(3, r.points.size)
        assertTrue(r.isConsistent)
        assertNull(RouteEdit.addPoint(r, pos(95.0, 0.0)))
    }

    @Test fun movingAPointOnlyInvalidatesItsLegs() {
        val r = routed(4)
        val moved = RouteEdit.movePoint(r, 1, pos(3.011, 101.512))!!
        assertEquals(listOf(LegState.PENDING, LegState.PENDING, LegState.ROUTED), moved.legs.map { it.state })
        assertEquals(r.legs[2], moved.legs[2])
    }

    @Test fun offWithoutFollowRoadsLegsAreLabelledSketchesNeverPendingRoads() {
        var r = BuilderRoute("r", followRoads = false)
        r = RouteEdit.addPoint(r, pos(3.0, 101.0))!!
        r = RouteEdit.addPoint(r, pos(3.01, 101.0))!!
        assertEquals(LegState.SKETCH, r.legs[0].state)
        assertEquals(2, r.legs[0].geometry.size)
        assertTrue(r.legs[0].distanceM in 1100..1125)
        assertTrue(RouteEdit.pendingJobs(r).isEmpty())
        // Turning "Ikut jalan" on routes the straight sketch again.
        val on = RouteEdit.setOptions(r, followRoads = true)
        assertEquals(LegState.PENDING, on.legs[0].state)
    }

    @Test fun reverseRoutesRoadLegsAgainAndFlipsSketches() {
        var r = routed(3)
        r = RouteEdit.keepLegAsSketch(r, 1)!!
        val rev = RouteEdit.reverse(r)!!
        assertEquals(r.points.reversed().map { it.id }, rev.points.map { it.id })
        // Old leg 1 (sketch) is now leg 0, its geometry reversed; the road leg must be routed again (one-way streets).
        assertEquals(LegState.SKETCH, rev.legs[0].state)
        assertEquals(r.legs[1].geometry.reversed(), rev.legs[0].geometry)
        assertEquals(LegState.PENDING, rev.legs[1].state)
        assertNull(RouteEdit.reverse(BuilderRoute("x")))
    }

    @Test fun outAndBackAddsTheSamePointsBackwards() {
        val r = routed(3)
        val ob = RouteEdit.outAndBack(r)!!
        assertEquals(5, ob.points.size)
        assertEquals(listOf(r.points[0].pos, r.points[1].pos, r.points[2].pos, r.points[1].pos, r.points[0].pos), ob.points.map { it.pos })
        assertEquals(ob.points.size, ob.points.map { it.id }.toSet().size)
        assertEquals(listOf(LegState.ROUTED, LegState.ROUTED, LegState.PENDING, LegState.PENDING), ob.legs.map { it.state })
    }

    @Test fun closeLoopOnceOnly() {
        val r = routed(3)
        val loop = RouteEdit.closeLoop(r)!!
        assertEquals(r.points.first().pos, loop.points.last().pos)
        assertEquals(3, loop.legs.size)
        assertEquals(LegState.PENDING, loop.legs.last().state)
        assertNull(RouteEdit.closeLoop(loop))
    }

    @Test fun lateResultsForEditedLegsAreDropped() {
        var r = RouteEdit.addPoint(RouteEdit.addPoint(BuilderRoute("r"), pos(3.0, 101.0))!!, pos(3.1, 101.1))!!
        val job = RouteEdit.pendingJobs(r).single()
        r = RouteEdit.movePoint(r, 1, pos(3.2, 101.2))!!
        assertNull(RouteEdit.applyJob(r, job, RouteEdit.JobResult.Routed(listOf(pos(3.0, 101.0), pos(3.1, 101.1)), 1, 1)))
        val failed = RouteEdit.applyJob(r, RouteEdit.pendingJobs(r).single(), RouteEdit.JobResult.Failed(RouteProblem.OFFLINE))!!
        assertEquals(LegState.UNRESOLVED, failed.legs[0].state)
        assertTrue(failed.legs[0].geometry.isEmpty())   // nothing drawn, no straight line
        assertEquals(1, RouteAssembly.summary(failed).openLegs)
        val retried = RouteEdit.retryUnresolved(failed)!!
        assertEquals(LegState.PENDING, retried.legs[0].state)
    }

    @Test fun keepAllOpenLegsAsSketchInOneEdit() {
        var r = BuilderRoute("r")
        for (i in 0 until 4) r = RouteEdit.addPoint(r, pos(3.0 + i * 0.1, 101.0 + i * 0.1))!!
        val jobs = RouteEdit.pendingJobs(r)
        // Leg 0 routed, leg 1 failed (demo mode), leg 2 still waiting.
        r = RouteEdit.applyJob(r, jobs[0], RouteEdit.JobResult.Routed(listOf(pos(3.0, 101.0), pos(3.05, 101.06), pos(3.1, 101.1)), 1500, 300))!!
        r = RouteEdit.applyJob(r, jobs[1], RouteEdit.JobResult.Failed(RouteProblem.DEMO))!!
        assertEquals(listOf(LegState.ROUTED, LegState.UNRESOLVED, LegState.PENDING), r.legs.map { it.state })
        val kept = RouteEdit.keepOpenLegsAsSketch(r)!!
        assertEquals(listOf(LegState.ROUTED, LegState.SKETCH, LegState.SKETCH), kept.legs.map { it.state })
        assertEquals(r.legs[0], kept.legs[0])                       // the road leg is untouched
        assertEquals(listOf(r.points[1].pos, r.points[2].pos), kept.legs[1].geometry)
        assertTrue(kept.legs.all { it.problem == null })
        assertEquals(0, RouteAssembly.summary(kept).openLegs)
        assertNull(RouteEdit.keepOpenLegsAsSketch(kept))            // nothing left to keep
    }

    @Test fun builderKeepAllAsSketchIsOneUndoStep() = runTest {
        val router = object : LegRouter {
            override suspend fun run(job: RouteEdit.LegJob): RouteEdit.JobResult = RouteEdit.JobResult.Failed(RouteProblem.DEMO)
        }
        val b = RouteBuilder(this, router)
        b.start(BuilderRoute("r"), BuilderTarget.Event) {}
        for (i in 0 until 4) b.tapMap(pos(3.0 + i * 0.1, 101.0 + i * 0.1))
        advanceUntilIdle()
        assertEquals(List(3) { LegState.UNRESOLVED }, b.route!!.legs.map { it.state })
        assertTrue(b.keepOpenAsSketch())
        assertEquals(List(3) { LegState.SKETCH }, b.route!!.legs.map { it.state })
        b.undo()
        assertEquals(List(3) { LegState.UNRESOLVED }, b.route!!.legs.map { it.state })
    }

    @Test fun stopLinksFollowAndDrop() {
        var r = routed(3)
        r = RouteEdit.snapToStop(r, 1, "stopA", pos(3.0105, 101.5105))!!
        assertEquals("stopA", r.points[1].stopId)
        assertEquals(LegState.PENDING, r.legs[0].state)
        r = RouteEdit.syncStop(r, "stopA", pos(3.02, 101.52))
        assertEquals(pos(3.02, 101.52), r.points[1].pos)
        assertNull(RouteEdit.dropStop(r, "stopA").points[1].stopId)
    }
}

class EditHistoryTest {
    @Test fun undoRedo() {
        val h = EditHistory<Int>(limit = 3)
        assertFalse(h.canUndo)
        var cur = 0
        for (v in 1..5) { h.record(cur); cur = v }
        assertEquals(4, h.undo(cur)); cur = 4
        assertEquals(3, h.undo(cur)); cur = 3
        assertEquals(2, h.undo(cur)); cur = 2
        assertNull(h.undo(cur))          // limit 3
        assertEquals(3, h.redo(cur)); cur = 3
        h.record(cur); cur = 9           // a new edit clears redo
        assertFalse(h.canRedo)
        assertEquals(3, h.undo(cur))
    }

    @Test fun builderUndoRedoWithoutAskingTheServerAgain() = runTest {
        val calls = ArrayList<RouteEdit.LegJob>()
        val router = object : LegRouter {
            override suspend fun run(job: RouteEdit.LegJob): RouteEdit.JobResult {
                calls += job
                val k = job.kind as RouteEdit.JobKind.Route
                return RouteEdit.JobResult.Routed(listOf(k.from, k.to), 1000, 200)
            }
        }
        val b = RouteBuilder(this, router)
        var saved: BuilderRoute? = null
        b.start(BuilderRoute("r", mode = "car"), BuilderTarget.Personal(isNew = true)) { saved = it }
        b.tapMap(pos(3.0, 101.0)); b.tapMap(pos(3.1, 101.1)); b.tapMap(pos(3.2, 101.2))
        advanceUntilIdle()
        assertEquals(listOf(LegState.ROUTED, LegState.ROUTED), b.route!!.legs.map { it.state })
        assertEquals(2, calls.size)
        b.undo()
        assertEquals(2, b.route!!.points.size)
        b.redo()
        advanceUntilIdle()
        assertEquals(3, b.route!!.points.size)
        assertEquals(listOf(LegState.ROUTED, LegState.ROUTED), b.route!!.legs.map { it.state })
        assertEquals(2, calls.size)   // cached
        assertTrue(b.ui.value!!.canUndo)
        // Draw mode: taps don't add points.
        b.setTool(BuilderTool.DRAW); b.tapMap(pos(3.3, 101.3))
        assertEquals(3, b.route!!.points.size)
        assertTrue(b.save())
        assertEquals(b.route, saved)
        assertFalse(b.ui.value!!.dirty)
    }

    @Test fun builderOfflineKeepsPointsEditable() = runTest {
        val gate = CompletableDeferred<Unit>()
        val router = object : LegRouter {
            override suspend fun run(job: RouteEdit.LegJob): RouteEdit.JobResult { gate.await(); return RouteEdit.JobResult.Failed(RouteProblem.OFFLINE) }
        }
        val b = RouteBuilder(this, router)
        b.start(BuilderRoute("r"), BuilderTarget.Event) {}
        b.tapMap(pos(3.0, 101.0)); b.tapMap(pos(3.1, 101.1))
        advanceUntilIdle()
        assertTrue(b.ui.value!!.computing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(b.ui.value!!.computing)
        assertEquals(LegState.UNRESOLVED, b.route!!.legs[0].state)
        assertEquals(RouteProblem.OFFLINE, b.route!!.legs[0].problem)
        // Still editable; back asks before discarding.
        b.tapMap(pos(3.2, 101.2))
        assertEquals(3, b.route!!.points.size)
        assertFalse(b.requestBack())
        assertTrue(b.ui.value!!.confirmDiscard)
    }
}

class RouteAssemblyTest {
    private fun stop(id: String, kind: String, label: String, p: Pos) = HostStop(id, kind, label, p.lat, p.lng)

    @Test fun legsAssembleIntoAnEventRouteTheReaderAccepts() {
        var r = routed(4).copy(title = "Hari 1", day = "2026-11-21")
        val a = EventIds.newItemId(); val b = EventIds.newItemId(); val c = EventIds.newItemId()
        r = RouteEdit.snapToStop(r, 0, a, r.points[0].pos)!!
        r = RouteEdit.snapToStop(r, 2, b, r.points[2].pos)!!
        r = RouteEdit.snapToStop(r, 3, c, r.points[3].pos)!!
        val d = HostDraft(id = EventIds.newEventId(), template = "ride", title = "Kayuhan", date = "2026-11-21", startTime = "07:00", endTime = "13:00",
            stops = listOf(stop(a, "start", "Mula", r.points[0].pos), stop(b, "checkpoint", "CP1", r.points[2].pos), stop(c, "finish", "Tamat", r.points[3].pos)),
            routes = listOf(r))
        val now = Instant.parse("2026-11-01T00:00:00Z")
        assertTrue(HostDraftRules.issues(d, now).isEmpty())
        val prepared = EventRevisions.prepare(d, null, now)
        val plan = EventParser.parse(prepared.bytes)
        val er = plan.routes.single()
        assertEquals(r.id, er.id)
        assertEquals("Hari 1", er.title)
        assertEquals(EventTravelMode.BICYCLE, er.mode)
        assertEquals(listOf(a, b, c), er.stopIds)
        assertEquals(GeometrySource.ROUTED, er.source)
        assertEquals(4500L, er.distanceM)
        assertEquals(900L, er.durationS)
        assertNull(er.ascentM)                       // absent = unavailable, never 0
        assertFalse(String(prepared.bytes).contains("ascentM"))
        assertEquals(listOf(a to b, b to c), er.legs.map { it.fromStopId to it.toStopId })
        assertEquals(listOf(3000L, 1500L), er.legs.map { it.distanceM })
        assertEquals(7, er.points.size)              // 3 legs × 3 points, shared ends once
        assertEquals(er.polyline, RoutePolyline.encode(er.points))
    }

    @Test fun sketchesAndBlockers() {
        val d0 = HostDraft(id = EventIds.newEventId(), title = "T", date = "2026-11-21", stops = listOf(HostStop(EventIds.newItemId(), lat = 3.0, lng = 101.0, label = "A")))
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val pending = RouteEdit.addPoint(RouteEdit.addPoint(BuilderRoute(EventIds.newItemId()), pos(3.0, 101.0))!!, pos(3.1, 101.1))!!
        assertEquals(listOf(DraftIssue(DraftIssue.Code.ROUTE_NOT_READY, route = 0)), HostDraftRules.issues(d0.copy(routes = listOf(pending)), now))
        // An empty route is left out, not an error.
        assertTrue(HostDraftRules.issues(d0.copy(routes = listOf(BuilderRoute(EventIds.newItemId()))), now).isEmpty())
        val sketch = RouteEdit.keepLegAsSketch(pending, 0)!!
        val plan = EventParser.parse(EventRevisions.prepare(d0.copy(routes = listOf(sketch)), null, now).bytes)
        assertEquals(GeometrySource.SKETCH, plan.routes.single().source)
        assertNull(plan.routes.single().durationS)
        assertTrue(plan.routes.single().stopIds.isEmpty())
        // "Kongsi jemputan" with blockers scrolls to the first problem.
        assertEquals("route:0", DraftIssue(DraftIssue.Code.ROUTE_NOT_READY, route = 0).anchor)
        assertEquals("add_stop", HostDraftRules.issues(d0.copy(stops = emptyList()), now).first().anchor)
        assertEquals("title", DraftIssue(DraftIssue.Code.TITLE_REQUIRED).anchor)
        assertEquals("stop:2", DraftIssue(DraftIssue.Code.STOP_LABEL_REQUIRED, stop = 2).anchor)
        // Too many routes.
        val many = (0..20).map { routed(2).copy(id = EventIds.newItemId()) }
        assertTrue(HostDraftRules.issues(d0.copy(routes = many), now).any { it.code == DraftIssue.Code.TOO_MANY_ROUTES })
    }

    @Test fun deletingAStopUnlinksItsPoints() {
        val ed = my.gimano.app.data.events.HostEditor(TestScope(StandardTestDispatcher()), my.gimano.app.data.events.HostStore(
            my.gimano.app.events.FakeHostDao(), object : my.gimano.app.data.events.EventVault {
                override fun seal(plain: ByteArray) = plain
                override fun open(sealed: ByteArray) = sealed
            }, EventPackWriter(com.goterl.lazysodium.LazySodiumJava(com.goterl.lazysodium.SodiumJava()))))
        ed.newEvent("ms", ZoneId.of("Asia/Kuala_Lumpur"), LocalDate.of(2026, 11, 1))
        ed.addStop(3.0, 101.0, "A")
        val sid = ed.draft.value!!.stops.single().id
        var r = routed(2)
        r = RouteEdit.snapToStop(r, 0, sid, pos(3.0, 101.0))!!
        ed.saveRoute(r)
        ed.updateStop(sid) { it.copy(lat = 3.001) }
        assertEquals(3.001, ed.route(r.id)!!.points[0].lat, 1e-9)
        assertEquals(LegState.PENDING, ed.route(r.id)!!.legs[0].state)
        ed.removeStop(sid)
        assertNull(ed.route(r.id)!!.points[0].stopId)
    }
}

class GpxWriterTest {
    @Test fun gpx11Track() {
        val x = GpxWriter.track("Kayuhan <pagi> & \"Ahad\"", listOf(GeoPoint(2.95, 101.85), GeoPoint(-0.0000001, 101.9000004), GeoPoint(3.123456789, 101.0)))
        assertEquals("""
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Manotu" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Kayuhan &lt;pagi&gt; &amp; &quot;Ahad&quot;</name>
                <trkseg>
                  <trkpt lat="2.95" lon="101.85"/>
                  <trkpt lat="0" lon="101.9"/>
                  <trkpt lat="3.123457" lon="101"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent() + "\n", x)
        assertFalse(x.contains("<time>"))
        assertEquals("Kayuhan-pagi.gpx", GpxWriter.fileName(" Kayuhan  pagi "))
        assertEquals("laluan.gpx", GpxWriter.fileName("<>"))
    }
}

/** The routing API through Retrofit with a canned network (OkHttp interceptor), as the app builds it. */
class LegRouterTest {
    private class Canned(val code: Int, val body: String) : Interceptor {
        val requests = ArrayList<Pair<String, String>>()
        var fail: IOException? = null
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val buf = Buffer().also { req.body?.writeTo(it) }
            requests += req.url.encodedPath to buf.readUtf8()
            fail?.let { throw it }
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun router(c: Canned) = ApiLegRouter { ApiFactory.create("http://test.invalid/api/v1/", OkHttpClient.Builder().addInterceptor(c).build()) }

    private val drawn = (0..9).map { pos(3.0 + it * 0.001, 101.0) }
    private fun matchJob(r: BuilderRoute) = RouteEdit.pendingJobs(r).single()

    private fun drawnRoute() = RouteEdit.addDrawnLeg(BuilderRoute("r", mode = "bicycle"), drawn)!!

    @Test fun routeLegRequestIsCoordinatesAndModeOnlyWithStoreFalse() = runTest {
        val poly = RoutePolyline.encode(listOf(GeoPoint(3.0, 101.0), GeoPoint(3.05, 101.02), GeoPoint(3.1, 101.1)))
        val c = Canned(200, """{"routes":[{"id":"x-1","polyline":"$poly","distanceM":1234.6,"durationS":300.2,"legs":[]}]}""")
        val r = RouteEdit.addPoint(RouteEdit.addPoint(BuilderRoute("r", mode = "motorcycle"), pos(3.0, 101.0))!!, pos(3.1, 101.1))!!
        val res = router(c).run(RouteEdit.pendingJobs(r).single()) as RouteEdit.JobResult.Routed
        assertEquals(3, res.points.size)
        assertEquals(1235L, res.distanceM)
        assertEquals(300L, res.durationS)
        val (path, body) = c.requests.single()
        assertEquals("/api/v1/routes", path)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("origin", "destination", "waypoints", "vehicle", "store"), o.keys)
        assertEquals("false", o.getValue("store").jsonPrimitive.content)
        assertEquals("motorcycle", o.getValue("vehicle").jsonPrimitive.content)
        assertEquals(setOf("lat", "lng"), o.getValue("origin").jsonObject.keys)
        assertFalse(body.contains("name"))
    }

    @Test fun matchWithUnmatchedRanges() = runTest {
        val poly = RoutePolyline.encode(listOf(GeoPoint(3.0, 101.0), GeoPoint(3.004, 101.0005), GeoPoint(3.009, 101.0)))
        val c = Canned(200, """{"polyline":"$poly","distanceM":1000,"durationS":240,"unmatched":[{"fromIndex":3,"toIndex":5}]}""")
        var r = drawnRoute()
        val job = matchJob(r)
        assertTrue(job.kind is RouteEdit.JobKind.Match)
        val res = router(c).run(job)
        assertEquals(RouteEdit.JobResult.Matched(listOf(pos(3.0, 101.0), pos(3.004, 101.0005), pos(3.009, 101.0)), 1000, 240, listOf(Span(3, 5))), res)
        val o = Json.parseToJsonElement(c.requests.single().second).jsonObject
        assertEquals("/api/v1/routes/match", c.requests.single().first)
        assertEquals(setOf("points", "vehicle"), o.keys)
        assertEquals(10, o.getValue("points").jsonArray.size)
        r = RouteEdit.applyJob(r, job, res)!!
        assertEquals(LegState.MATCHED, r.legs[0].state)
        assertEquals(listOf(Span(3, 5)), r.legs[0].unmatched)
        // Not publishable until fixed or kept as a sketch.
        assertEquals(Assembled.Blocked(RouteBlock.UNMATCHED), RouteAssembly.toEventRoute(r, emptySet()))
        val kept = RouteEdit.keepLegAsSketch(r, 0)!!
        val ok = RouteAssembly.toEventRoute(kept, emptySet()) as Assembled.Ok
        assertEquals(GeometrySource.SKETCH, ok.route.source)
        // The drawn line, simplified at 5 m like every published route (§5.4); this one is almost straight.
        assertEquals(my.gimano.app.core.routes.RouteSimplify.simplify(kept.legs[0].geometry.map { it.geo }), ok.route.points)
        assertTrue(ok.route.points.size < 10)
        // "Tambah titik": a point in the unmatched stretch, both halves routed point to point.
        val split = RouteEdit.insertPoint(r, 0, r.legs[0].drawn!![4])!!
        assertEquals(listOf(LegState.PENDING, LegState.PENDING), split.legs.map { it.state })
        assertTrue(RouteEdit.pendingJobs(split).all { it.kind is RouteEdit.JobKind.Route })
    }

    @Test fun matchNotDeployedFallsBackToSketch() = runTest {
        for (code in listOf(404, 405)) {
            val c = Canned(code, """{"title":"Not Found"}""")
            var r = drawnRoute()
            val job = matchJob(r)
            val res = router(c).run(job)
            assertEquals(RouteEdit.JobResult.Failed(RouteProblem.MATCH_UNAVAILABLE), res)
            r = RouteEdit.applyJob(r, job, res)!!
            assertEquals(LegState.UNRESOLVED, r.legs[0].state)
            assertTrue(r.legs[0].geometry.isEmpty())
            val kept = RouteEdit.keepLegAsSketch(r, 0)!!
            assertEquals(LegState.SKETCH, kept.legs[0].state)
            assertEquals(drawn.size, kept.legs[0].geometry.size)
        }
    }

    @Test fun builderRemembersThatMatchIsMissing() = runTest {
        val b = RouteBuilder(this, router(Canned(404, "{}")))
        b.start(BuilderRoute("r"), BuilderTarget.Personal(true)) {}
        b.setTool(BuilderTool.DRAW)
        b.drawn(drawn)
        // OkHttp answers on its own thread: let the test scheduler pick the result up.
        repeat(300) { if (b.ui.value!!.matchUnavailable) return@repeat; advanceUntilIdle(); Thread.sleep(10) }
        advanceUntilIdle()
        assertTrue(b.ui.value!!.matchUnavailable)
        assertEquals(RouteProblem.MATCH_UNAVAILABLE, b.route!!.legs[0].problem)
    }

    @Test fun errorCodes() = runTest {
        val r = RouteEdit.addPoint(RouteEdit.addPoint(BuilderRoute("r"), pos(3.0, 101.0))!!, pos(3.1, 101.1))!!
        val job = RouteEdit.pendingJobs(r).single()
        assertEquals(RouteEdit.JobResult.Failed(RouteProblem.NO_ROUTE), router(Canned(422, "{}")).run(job))
        assertEquals(RouteEdit.JobResult.Failed(RouteProblem.UNAVAILABLE), router(Canned(503, "{}")).run(job))
        assertEquals(RouteEdit.JobResult.Failed(RouteProblem.ERROR), router(Canned(404, "{}")).run(job))   // 404 on /routes is not "match missing"
        assertEquals(RouteEdit.JobResult.Failed(RouteProblem.NO_ROUTE), router(Canned(200, """{"routes":[]}""")).run(job))
        val off = Canned(200, "{}").also { it.fail = IOException("no network") }
        assertEquals(RouteEdit.JobResult.Failed(RouteProblem.OFFLINE), router(off).run(job))
        assertEquals(RouteEdit.JobResult.Failed(RouteProblem.OFFLINE), ApiLegRouter { null }.run(job))   // server unreachable (fallback)
        // Demo data mode: an honest "demo" problem, never OFFLINE, and the server is not even asked.
        var asked = false
        assertEquals(RouteEdit.JobResult.Failed(RouteProblem.DEMO), ApiLegRouter(demo = { true }) { asked = true; null }.run(job))
        assertFalse(asked)
    }
}

/** The v2 → v3 SQL on a real SQLite database (xerial) built from Room's exported v2 schema. */
class EventsMigrationTest {
    private fun schema(v: Int): JsonObject {
        val f = listOf(File("schemas/my.gimano.app.data.events.EventsDatabase/$v.json"), File("app/schemas/my.gimano.app.data.events.EventsDatabase/$v.json")).first { it.exists() }
        return Json.parseToJsonElement(f.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun creates(db: JsonObject): Map<String, String> = db.getValue("entities").jsonArray.associate { e ->
        val o = e.jsonObject
        val t = o.getValue("tableName").jsonPrimitive.content
        t to o.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", t)
    }

    @Test fun v2ToV3AddsPersonalRoutesAndKeepsData() {
        val dir = kotlin.io.path.createTempDirectory("evdb").toFile()
        DriverManager.getConnection("jdbc:sqlite:" + File(dir, "e.db").path).use { conn ->
            val st = conn.createStatement()
            val v2 = schema(2)
            creates(v2).values.forEach(st::execute)
            v2.getValue("entities").jsonArray.forEach { e ->
                e.jsonObject["indices"]?.jsonArray?.forEach { i ->
                    st.execute(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", e.jsonObject.getValue("tableName").jsonPrimitive.content))
                }
            }
            st.execute("INSERT INTO event_host (event_id, draft, secrets, updated_at) VALUES ('e1', x'01', x'02', 5)")
            EventsMigrations.V2_TO_V3.forEach(st::execute)
            val expected = creates(schema(3))
            assertEquals(setOf("event_pin", "event_revision", "event_host", "personal_route"), expected.keys)
            val actual = conn.createStatement().executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='personal_route'").use { rs -> rs.next(); rs.getString(1) }
            assertEquals(expected.getValue("personal_route").removePrefix("CREATE TABLE IF NOT EXISTS "), actual.removePrefix("CREATE TABLE "))
            st.execute("INSERT INTO personal_route (id, payload, updated_at) VALUES ('r1', x'0102', 7)")
            assertEquals(1, conn.createStatement().executeQuery("SELECT COUNT(*) FROM event_host").use { it.next(); it.getInt(1) })
            assertEquals(1, conn.createStatement().executeQuery("SELECT COUNT(*) FROM personal_route").use { it.next(); it.getInt(1) })
            // The v2 tables are untouched by the migration.
            creates(v2).forEach { (t, sql) ->
                val got = conn.createStatement().executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='$t'").use { rs -> rs.next(); rs.getString(1) }
                assertEquals(sql.removePrefix("CREATE TABLE IF NOT EXISTS "), got.removePrefix("CREATE TABLE "))
                assertEquals(expected.getValue(t), sql)
            }
        }
        dir.deleteRecursively()
    }
}
