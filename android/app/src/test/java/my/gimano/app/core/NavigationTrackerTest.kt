package my.gimano.app.core

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.core.nav.LocationBatcher
import my.gimano.app.core.nav.NavigationTracker
import my.gimano.app.core.nav.VoicePromptPlanner
import my.gimano.app.data.model.Leg
import my.gimano.app.data.model.LocationPoint
import my.gimano.app.data.model.Maneuver
import my.gimano.app.data.model.Route
import org.junit.Assert.*
import org.junit.Test

class NavigationTrackerTest {
    // An L-shaped route: 1 km north, then 1 km east. Points every 100 m.
    private val start = GeoPoint(3.10, 101.60)
    private val corner = GeoMath.offset(start, 0.0, 1000.0)
    private val end = GeoMath.offset(corner, 90.0, 1000.0)
    private val pts = (0..10).map { GeoMath.lerp(start, corner, it / 10.0) } + (1..10).map { GeoMath.lerp(corner, end, it / 10.0) }
    private val route = Route(
        id = "r1", label = "best", durationS = 240.0, distanceM = 2000.0, polyline = Polyline.encode(pts),
        legs = listOf(Leg(listOf(
            Maneuver("depart", streetName = "Jalan A", distanceM = 1000.0, beginShapeIndex = 0, endShapeIndex = 10),
            Maneuver("right", streetName = "Jalan B", distanceM = 1000.0, beginShapeIndex = 10, endShapeIndex = 20),
            Maneuver("arrive", beginShapeIndex = 20, endShapeIndex = 20),
        ))),
    )

    @Test fun progressAtStartPointsToTheTurn() {
        val t = NavigationTracker(route)
        val p = t.update(GeoMath.offset(start, 0.0, 100.0))!!
        assertEquals("right", p.nextManeuver?.type)
        assertEquals(900.0, p.distanceToNextM, 5.0)
        assertEquals(1900.0, p.remainingM, 10.0)
        assertEquals(1900.0 * 240 / 2000, p.remainingS, 3.0)
        assertEquals("arrive", p.thenManeuver?.type)
        assertFalse(p.arrived)
        assertEquals(0.0, p.bearing, 1.0)
    }

    @Test fun lateralOffsetIsReportedAsDistanceFromRoute() {
        val t = NavigationTracker(route)
        val onRoute = GeoMath.offset(start, 0.0, 500.0)
        val p = t.update(GeoMath.offset(onRoute, 90.0, 80.0))!!
        assertEquals(80.0, p.distanceFromRouteM, 2.0)
        assertEquals(500.0, p.distanceAlongM, 5.0)
    }

    @Test fun afterTheTurnNextIsArrival() {
        val t = NavigationTracker(route)
        t.update(GeoMath.offset(start, 0.0, 900.0))
        val p = t.update(GeoMath.offset(corner, 90.0, 300.0))!!
        assertEquals("arrive", p.nextManeuver?.type)
        assertEquals(700.0, p.distanceToNextM, 5.0)
        assertEquals(90.0, p.bearing, 1.0)
    }

    @Test fun arrivalNearTheEnd() {
        val t = NavigationTracker(route)
        t.update(GeoMath.offset(corner, 90.0, 900.0))
        assertTrue(t.update(GeoMath.offset(corner, 90.0, 990.0))!!.arrived)
    }

    @Test fun thenStripOnlyForCloseManeuvers() {
        val close = Maneuver("left", distanceM = 150.0)
        val far = Maneuver("left", distanceM = 2_000.0)
        val after = Maneuver("right")
        assertTrue(NavigationTracker.showThen(close, after))
        assertFalse(NavigationTracker.showThen(far, after))
        assertFalse(NavigationTracker.showThen(close, null))
    }

    @Test fun voicePlannerSpeaksEachStageOnce() {
        val v = VoicePromptPlanner()
        assertEquals(VoicePromptPlanner.Stage.FAR, v.check(1, 900.0, 60.0)) // 60 km/h → announce from 1 km
        val p = VoicePromptPlanner()
        assertEquals(VoicePromptPlanner.Stage.FAR, p.check(1, 450.0, 40.0))
        assertNull(p.check(1, 430.0, 40.0))
        assertEquals(VoicePromptPlanner.Stage.NEAR, p.check(1, 300.0, 40.0))
        assertNull(p.check(1, 250.0, 40.0))
        assertEquals(VoicePromptPlanner.Stage.NOW, p.check(1, 50.0, 40.0))
        assertNull(p.check(1, 20.0, 40.0))
        assertEquals(2000.0, p.farDistanceFor(100.0), 0.0)
    }

    @Test fun locationBatcherRespectsIntervalAndBatchSize() {
        val b = LocationBatcher(minIntervalMs = 5_000, maxBatch = 12, maxAgeMs = 120_000)
        fun pt(i: Int) = LocationPoint(3.1, 101.6, 40.0, 90.0, 5.0, "t$i")
        var sent: List<LocationPoint>? = null
        for (i in 0 until 12) {
            sent = b.offer(pt(i), i * 5_000L)
            if (i < 11) assertNull(sent)
            if (i < 11) assertNull("1 s later is dropped", b.offer(pt(100 + i), i * 5_000L + 1_000))
        }
        assertEquals(12, sent!!.size)
        assertEquals((0 until 12).map { "t$it" }, sent.map { it.ts })
        assertEquals(0, b.size)
    }

    @Test fun locationBatcherFlushesOldBatches() {
        val b = LocationBatcher(minIntervalMs = 5_000, maxBatch = 12, maxAgeMs = 30_000)
        val p = LocationPoint(3.1, 101.6, 0.0, null, null, "t")
        var out: List<LocationPoint>? = null
        var t = 0L
        while (out == null) { out = b.offer(p, t); t += 5_000 }
        assertEquals(7, out.size) // 0..30 s
        assertNull(b.drain())
    }
}
