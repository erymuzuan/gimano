package my.gimano.app.core

import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    // Google's reference example (precision 5): (38.5,-120.2), (40.7,-120.95), (43.252,-126.453)
    private val reference = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test fun decodesReferenceAtPrecision5() {
        val pts = Polyline.decode(reference, 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-9); assertEquals(-120.2, pts[0].lng, 1e-9)
        assertEquals(40.7, pts[1].lat, 1e-9); assertEquals(-120.95, pts[1].lng, 1e-9)
        assertEquals(43.252, pts[2].lat, 1e-9); assertEquals(-126.453, pts[2].lng, 1e-9)
    }

    @Test fun sameStringAtPrecision6IsTenTimesSmaller() {
        // Precision-6 strings (Valhalla / Gimano API) must NOT be decoded with factor 1e5.
        val pts = Polyline.decode(reference)
        assertEquals(3.85, pts[0].lat, 1e-9)
        assertEquals(-12.02, pts[0].lng, 1e-9)
        assertEquals(4.3252, pts[2].lat, 1e-9)
    }

    @Test fun roundTripsMalaysianCoordinatesAtPrecision6() {
        val pts = listOf(GeoPoint(3.139003, 101.686855), GeoPoint(3.157900, 101.712300), GeoPoint(1.463000, 103.765000), GeoPoint(5.355000, 100.348000))
        val enc = Polyline.encode(pts)
        val dec = Polyline.decode(enc)
        assertEquals(pts.size, dec.size)
        pts.zip(dec).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6); assertEquals(a.lng, b.lng, 1e-6)
        }
    }

    @Test fun knownPrecision6Encoding() {
        // Hand-checked: (3.1, 101.6) → lat 3100000, lng 101600000.
        val enc = Polyline.encode(listOf(GeoPoint(3.1, 101.6)))
        val dec = Polyline.decode(enc)
        assertEquals(3.1, dec[0].lat, 1e-9)
        assertEquals(101.6, dec[0].lng, 1e-9)
        assertEquals(enc, Polyline.encode(dec))
    }

    @Test fun handlesNegativeAndSouthernHemisphere() {
        val pts = listOf(GeoPoint(-33.865143, 151.209900), GeoPoint(-33.8, 151.3), GeoPoint(0.0, 0.0))
        val dec = Polyline.decode(Polyline.encode(pts))
        pts.zip(dec).forEach { (a, b) -> assertEquals(a.lat, b.lat, 1e-6); assertEquals(a.lng, b.lng, 1e-6) }
    }

    @Test fun emptyStringDecodesToEmptyList() {
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test fun truncatedInputThrows() {
        assertThrows(IllegalArgumentException::class.java) { Polyline.decode("_p~iF~ps|U_") }
    }
}
