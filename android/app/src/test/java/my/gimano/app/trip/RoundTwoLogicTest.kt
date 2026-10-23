package my.gimano.app.trip

import my.gimano.app.core.feedback.FeedbackRules
import my.gimano.app.core.format.Lang
import my.gimano.app.core.nav.ManeuverFormatter
import my.gimano.app.core.nav.VoicePromptPlanner
import my.gimano.app.core.trip.RouteLegs
import my.gimano.app.core.trip.StopList
import my.gimano.app.core.trip.TollBadge
import my.gimano.app.core.trip.TravelMode
import my.gimano.app.data.local.Favorites
import my.gimano.app.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RoundTwoLogicTest {
    private fun route(vehicle: String? = "car", hasTolls: Boolean = false, est: Double? = null, complete: Boolean = false, tolls: List<TollPlaza> = emptyList()) =
        Route(id = "r", durationS = 600.0, distanceM = 5000.0, polyline = "", vehicle = vehicle, hasTolls = hasTolls, tollEstimateMyr = est, tollFareComplete = complete, tolls = tolls)

    // --- Tolls ---
    @Test fun tollBadgeStates() {
        assertEquals(TollBadge.None, TollBadge.of(route()))
        assertEquals(TollBadge.Exact(2.5), TollBadge.of(route(hasTolls = true, est = 2.5, complete = true)))
        assertEquals(TollBadge.From(1.6), TollBadge.of(route(hasTolls = true, est = 1.6, complete = false)))
        assertEquals(TollBadge.Unknown, TollBadge.of(route(hasTolls = true, est = null)))
        assertEquals("tolls list alone implies tolls", TollBadge.Unknown, TollBadge.of(route(tolls = listOf(TollPlaza(name = "PJS")))))
        assertEquals("never for walking", TollBadge.None, TollBadge.of(route(vehicle = "walk", hasTolls = true, est = 2.0, complete = true)))
        assertEquals("never for cycling", TollBadge.None, TollBadge.of(route(vehicle = "bicycle", hasTolls = true)))
        assertEquals("RM 2.50", TollBadge.rm(2.5))
        assertEquals("RM 10.00", TollBadge.rm(10.0))
    }

    // --- Travel modes ---
    @Test fun travelModes() {
        assertEquals(TravelMode.WALK, TravelMode.from("walk"))
        assertEquals(TravelMode.CAR, TravelMode.from("hovercraft"))
        assertTrue(TravelMode.MOTORCYCLE.isMotorised)
        assertFalse(TravelMode.BICYCLE.isMotorised)
        assertFalse(TravelMode.WALK.isMotorised)
        assertEquals(listOf("car", "motorcycle", "bicycle", "walk"), TravelMode.entries.map { it.wire })
    }

    @Test fun walkingAndCyclingWording() {
        val depart = Maneuver("depart", streetName = "Jalan SS 2/24")
        val straight = Maneuver("straight")
        assertEquals("Start walking on Jalan SS 2/24", ManeuverFormatter.instruction(depart, Lang.EN, "walk"))
        assertEquals("Mula berbasikal di Jalan SS 2/24", ManeuverFormatter.instruction(depart, Lang.MS, "bicycle"))
        assertEquals("Keep cycling straight", ManeuverFormatter.instruction(straight, Lang.EN, "bicycle"))
        assertEquals("Head out on Jalan SS 2/24", ManeuverFormatter.instruction(depart, Lang.EN, "car"))
        assertEquals("Turn left", ManeuverFormatter.instruction(Maneuver("left"), Lang.EN, "walk"))
        assertEquals("You have arrived at Kedai Mak", ManeuverFormatter.arrivedAtStop("Kedai Mak", Lang.EN))
        assertEquals("Anda telah tiba di Kedai Mak", ManeuverFormatter.arrivedAtStop("Kedai Mak", Lang.MS))
    }

    @Test fun walkingPromptsAreShortRange() {
        val walk = VoicePromptPlanner("walk")
        assertEquals(150.0, walk.farDistanceFor(5.0), 0.0)
        assertNull("no 500 m prompt when walking", walk.check(1, 480.0, 5.0))
        assertEquals(VoicePromptPlanner.Stage.NEAR, walk.check(1, 120.0, 5.0))
        assertEquals(VoicePromptPlanner.Stage.NOW, walk.check(1, 12.0, 5.0))
    }

    // --- Stops ---
    @Test fun stopListRules() {
        var stops = listOf("A")
        stops = StopList.add(stops, "B")
        assertEquals(listOf("A", "B"), stops)
        assertEquals(listOf("N", "A", "B"), StopList.insertNext(stops, "N"))
        assertEquals(listOf("B"), StopList.remove(stops, 0))
        assertEquals(listOf("C", "A", "B"), StopList.move(listOf("A", "B", "C"), 2, 0))
        assertEquals(listOf("B", "C", "A"), StopList.move(listOf("A", "B", "C"), 0, 9))
        val five = listOf("1", "2", "3", "4", "5")
        assertThrows(IllegalArgumentException::class.java) { StopList.add(five, "6") }
        assertThrows(IllegalArgumentException::class.java) { StopList.insertNext(five, "6") }
    }

    private fun twoLegRoute(relativeIndexes: Boolean): Route {
        val off = if (relativeIndexes) 0 else 10
        return Route(
            id = "m", durationS = 1200.0, distanceM = 2000.0, trafficDurationS = 1500.0, polyline = "",
            legs = listOf(
                Leg(listOf(Maneuver("depart", durationS = 300.0, beginShapeIndex = 0, endShapeIndex = 10), Maneuver("arrive", beginShapeIndex = 10, endShapeIndex = 10)), durationS = 400.0, to = LegTarget(0.0, 0.0, "Kedai Mak")),
                Leg(listOf(Maneuver("depart", durationS = 500.0, beginShapeIndex = 0 + off, endShapeIndex = 10 + off), Maneuver("arrive", beginShapeIndex = 10 + off, endShapeIndex = 10 + off)), durationS = 800.0),
            ),
        )
    }

    @Test fun legManeuversUseGlobalShapeIndexes() {
        listOf(true, false).forEach { relative ->
            val ms = RouteLegs.maneuvers(twoLegRoute(relative))
            assertEquals(listOf(0, 10, 10, 20), ms.map { it.maneuver.beginShapeIndex })
            assertEquals(listOf(0, 0, 1, 1), ms.map { it.leg })
            assertEquals(listOf(10, 20), RouteLegs.legEndShapeIndexes(twoLegRoute(relative)))
        }
    }

    @Test fun etaPerStopScalesToTrafficTotal() {
        val t0 = Instant.parse("2026-10-23T00:00:00Z")
        val etas = RouteLegs.stopEtas(twoLegRoute(false), t0)
        // legs 400 + 800 = 1200 s raw, total with traffic 1500 s → 500 s and 1500 s.
        assertEquals(listOf(t0.plusSeconds(500), t0.plusSeconds(1500)), etas)
    }

    // --- Favourites ---
    @Test fun favouriteLabelsAndIcons() {
        assertEquals("Rumah Mertua", Favorites.normalizeLabel("  Rumah   Mertua "))
        assertEquals(40, Favorites.normalizeLabel("x".repeat(60)).length)
        assertEquals("mertua", Favorites.iconForLabel("rumah mertua"))
        assertEquals("kampung", Favorites.iconForLabel("Kampung"))
        assertEquals("star", Favorites.iconForLabel("Pejabat Abah"))
        assertEquals("star", Favorites.validIcon("rocket"))
        assertEquals(10, Favorites.ICONS.size)
        assertEquals(listOf("b", "a", "c"), Favorites.move(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("a", "b", "c"), Favorites.move(listOf("a", "b", "c"), "a", -1))
    }

    // --- Feedback rules ---
    @Test fun feedbackRules() {
        assertTrue(FeedbackRules.allowedWhileNavigating(0, false))
        assertTrue(FeedbackRules.allowedWhileNavigating(4, false))
        assertFalse(FeedbackRules.allowedWhileNavigating(5, false))
        assertTrue(FeedbackRules.allowedWhileNavigating(80, passengerConfirmed = true))
        assertEquals(3.12, FeedbackRules.round2(3.118), 0.0)
        assertEquals(101.62, FeedbackRules.round2(101.6249), 0.0)
        assertFalse(FeedbackRules.validMessage("   "))
        assertFalse(FeedbackRules.validMessage("x".repeat(2001)))
        assertEquals(30, FeedbackRules.backoffSeconds(0))
        assertEquals(60, FeedbackRules.backoffSeconds(1))
        assertEquals(3600, FeedbackRules.backoffSeconds(20))
        assertTrue(FeedbackRules.isPermanentFailure(400))
        assertTrue(FeedbackRules.isPermanentFailure(413))
        assertFalse("rate limited: retry later", FeedbackRules.isPermanentFailure(429))
        assertFalse("endpoint not deployed yet: keep it queued", FeedbackRules.isPermanentFailure(404))
        assertFalse(FeedbackRules.isPermanentFailure(503))
        assertFalse(FeedbackRules.isPermanentFailure(null))
    }
}
