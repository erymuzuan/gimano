package my.gimano.app.issues

import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.issues.IssueSupport
import my.gimano.app.core.issues.IssueText
import my.gimano.app.data.model.Issue
import my.gimano.app.data.model.Route
import my.gimano.app.data.model.RouteIssue
import my.gimano.app.data.remote.GimanoJson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class IssueSupportTest {
    @Test fun missingEndpointCodes() {
        assertTrue(IssueSupport.endpointMissing(404)); assertTrue(IssueSupport.endpointMissing(501))
        assertFalse(IssueSupport.endpointMissing(400)); assertFalse(IssueSupport.endpointMissing(500))
    }

    @Test fun deviationAnswersOnlySendRoadProblems() {
        assertEquals("road_closed_temp", IssueSupport.deviationKind("closed"))
        assertEquals("turn_not_allowed", IssueSupport.deviationKind("no_turn"))
        assertNull("jam is not an issue", IssueSupport.deviationKind("jam"))
        assertNull("own choice sends nothing", IssueSupport.deviationKind("own_choice"))
    }

    @Test fun driversAndAge() {
        assertEquals(1, IssueText.drivers(1.5)); assertEquals(3, IssueText.drivers(3.0)); assertEquals(0, IssueText.drivers(0.0))
        val now = Instant.parse("2026-10-23T12:00:00Z")
        assertEquals("2 j lalu", IssueText.age("2026-10-23T10:00:00Z", now, ms = true))
        assertEquals("5 min ago", IssueText.age("2026-10-23T11:55:00Z", now, ms = false))
        assertNull(IssueText.age(null, now, ms = true))
    }

    @Test fun stillClosedPromptWithin300mOfOpenRoadIssues() {
        val me = GeoPoint(3.1000, 101.6000)
        val near = Issue("a", "road_closed_temp", state = "confirmed", lat = 3.1020, lng = 101.6000)      // ~220 m
        val far = Issue("b", "road_closed_temp", state = "suspected", lat = 3.1100, lng = 101.6000)       // ~1.1 km
        val place = Issue("c", "place_closed_perm", state = "suspected", lat = 3.1001, lng = 101.6000)
        val gone = Issue("d", "road_closed_temp", state = "refuted", lat = 3.1001, lng = 101.6000)
        assertEquals("a", IssueText.stillClosedCandidate(listOf(far, place, gone, near), me, emptySet())?.id)
        assertNull(IssueText.stillClosedCandidate(listOf(far, place, gone, near), me, setOf("a")))
    }

    @Test fun routeIssuesParseWithAliasesAndBannerOrder() {
        val json = """{"id":"r","durationS":60,"distanceM":100,"polyline":"",
            "issues":[{"id":"i1","kind":"road_closed_temp","state":"suspected","street":"Jln Ampang","relation":"on_route"},
                      {"id":"i2","kind":"road_closed_temp","state":"confirmed","avoided":true,"road":"Jln Tun Razak","subtype":"flood","confirmationsWeighted":3.5,"createdAt":"2026-10-23T10:00:00Z"},
                      {"id":"i3","kind":"road_not_exist","state":"refuted"}]}"""
        val r = GimanoJson.decodeFromString(Route.serializer(), json)
        assertEquals(3, r.issues.size)
        assertEquals("Jln Ampang", r.issues[0].roadName)
        assertTrue(r.issues[1].isAvoided); assertFalse(r.issues[0].isAvoided); assertEquals(3.5, r.issues[1].confirmations, 0.0)
        assertEquals(listOf("i2", "i1"), IssueText.bannerIssues(r.issues).map(RouteIssue::id))
        // An old server without issues[] still parses.
        assertTrue(GimanoJson.decodeFromString(Route.serializer(), """{"id":"r","durationS":1,"distanceM":1,"polyline":""}""").issues.isEmpty())
    }

    @Test fun requestEncodingMatchesIos() {
        val req = my.gimano.app.data.model.RouteRequest(my.gimano.app.data.model.LatLngDto(3.0, 101.0), my.gimano.app.data.model.LatLngDto(3.1, 101.1), ignoreIssues = listOf("i2"))
        assertTrue(GimanoJson.encodeToString(my.gimano.app.data.model.RouteRequest.serializer(), req).contains("\"ignoreIssues\":[\"i2\"]"))
        val plain = my.gimano.app.data.model.RouteRequest(my.gimano.app.data.model.LatLngDto(3.0, 101.0), my.gimano.app.data.model.LatLngDto(3.1, 101.1))
        assertFalse(GimanoJson.encodeToString(my.gimano.app.data.model.RouteRequest.serializer(), plain).contains("ignoreIssues"))
        val issue = my.gimano.app.data.model.CreateIssueRequest("place_entrance_wrong", lat = 3.0, lng = 101.0, placeId = "p", source = "place_edit",
            payload = mapOf("navLat" to "3.000100", "navLng" to "101.000200"))
        val j = GimanoJson.encodeToString(my.gimano.app.data.model.CreateIssueRequest.serializer(), issue)
        assertTrue(j.contains("\"navLat\":\"3.000100\"")); assertTrue(j.contains("\"source\":\"place_edit\""))
    }

    @Test fun bboxAroundPoint() {
        val b = IssueSupport.bbox(GeoPoint(3.0, 101.0), 1_113.2)
        assertEquals(0.01, b[3] - 3.0, 1e-4)
        assertTrue(b[0] < 101.0 && b[2] > 101.0)
    }
}
