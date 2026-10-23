package my.gimano.app.net

import my.gimano.app.data.remote.LogRedaction
import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactionTest {
    @Test fun coordinatesAreRoundedTo2dpAndSearchTextDropped() {
        assertEquals(
            "http://h:5080/api/v1/live?bbox=101.60,3.10,101.70,3.20",
            LogRedaction.url("http://h:5080/api/v1/live?bbox=101.60123,3.10456,101.69876,3.19999"),
        )
        assertEquals(
            "http://h/api/v1/search?q=…&lat=3.14&lng=101.69&limit=20",
            LogRedaction.url("http://h/api/v1/search?q=rumah%20mak&lat=3.13901&lng=101.68654&limit=20"),
        )
        assertEquals("negative and path values too", "http://h/api/v1/x/-1.23/y", LogRedaction.url("http://h/api/v1/x/-1.23456/y"))
        assertEquals("ids untouched", "http://h/api/v1/places/abc-12/reviews?page=2", LogRedaction.url("http://h/api/v1/places/abc-12/reviews?page=2"))
    }
}
