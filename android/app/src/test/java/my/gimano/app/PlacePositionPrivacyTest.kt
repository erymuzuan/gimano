package my.gimano.app

import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.places.PlaceDistance
import my.gimano.app.data.remote.GimanoApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** The user's location goes to the server only when routing: opening a place card sends no position. */
class PlacePositionPrivacyTest {
    private val place = GimanoApi::class.java.methods.single { it.name == "place" }

    @Test fun placeDetailRequestCarriesNoPosition() {
        assertEquals("places/{id}", place.getAnnotation(GET::class.java)!!.value)
        val annotations = place.parameterAnnotations.flatMap { it.toList() }
        assertTrue("no query parameters at all: $annotations", annotations.none { it is Query })
        assertEquals(listOf("id"), annotations.filterIsInstance<Path>().map { it.value })
        // Retrofit adds a Continuation for suspend; the only real parameter is the id.
        assertEquals(String::class.java, place.parameterTypes.first())
    }

    @Test fun distanceIsComputedOnThePhoneFromAFreshFix() {
        // Masjid Negara from KL Sentral ≈ 1.1 km.
        val m = PlaceDistance.metres(GeoPoint(3.1343, 101.6863), 3.1418, 101.6917)!!
        assertTrue("$m m", m in 950.0..1150.0)
        assertEquals(0.0, PlaceDistance.metres(GeoPoint(3.1418, 101.6917), 3.1418, 101.6917)!!, 0.01)
        // No fresh fix → no distance (never the map centre, never asked from the server).
        assertNull(PlaceDistance.metres(null, 3.1418, 101.6917))
    }
}
