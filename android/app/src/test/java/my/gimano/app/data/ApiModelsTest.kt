package my.gimano.app.data

import my.gimano.app.data.live.LiveHub
import my.gimano.app.data.model.Report
import my.gimano.app.data.model.RoutesResponse
import my.gimano.app.data.remote.GimanoJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {
    @Test fun parsesRouteContractSample() {
        val json = """
        {"routes":[{"id":"r1","label":"best","durationS":600,"distanceM":3700,"trafficDurationS":660,
          "summary":"Via E11 LDP, Exit 1107 SS2 Petaling Jaya","trafficDescription":"Typical traffic",
          "hasTolls":true,"tollEstimateMyr":2.1,"polyline":"_p~iF~ps|U","bbox":[101.6,3.1,101.7,3.2],
          "legs":[{"maneuvers":[{"type":"depart","instruction":"Drive north","distanceM":100,"durationS":10,"beginShapeIndex":0,"endShapeIndex":1,"futureField":1}]}],
          "trafficSegments":[{"fromIndex":0,"toIndex":1,"level":2}],
          "alerts":[{"id":"a1","type":"police","subtype":"visible","lat":3.1,"lng":101.6,"reportedBy":{"displayName":"Aiman","mood":"x"},
             "createdAt":"2026-10-23T00:00:00Z","expiresAt":"2026-10-23T00:30:00Z","confirmations":2,"thanks":0,"distanceAlongRouteM":420.5}],
          "unknownTopLevel":"ignored"}]}
        """.trimIndent()
        val r = GimanoJson.decodeFromString(RoutesResponse.serializer(), json).routes.single()
        assertTrue(r.isBest)
        assertEquals(660.0, r.effectiveDurationS, 0.0)
        assertEquals("depart", r.maneuvers.single().type)
        assertEquals(420.5, r.alerts.single().distanceAlongRouteM, 0.0)
        assertEquals("Aiman", r.alerts.single().toReport().reportedBy?.displayName)
    }

    @Test fun reportWithNullsAndMissingOptionals() {
        val r = GimanoJson.decodeFromString(Report.serializer(),
            """{"id":"x","type":"closure","subtype":null,"lat":1.0,"lng":2.0,"createdAt":"a","expiresAt":"b"}""")
        assertNull(r.subtype)
        assertEquals(0, r.confirmations)
    }

    @Test fun hubUrlIsDerivedFromApiBase() {
        assertEquals("http://100.101.150.9:5080/hubs/live", LiveHub.hubUrl("http://100.101.150.9:5080/api/v1/"))
        assertEquals("https://api.gimano.my/hubs/live", LiveHub.hubUrl("https://api.gimano.my/api/v1/"))
    }
}
