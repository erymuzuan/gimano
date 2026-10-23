package my.gimano.app.places

import my.gimano.app.core.places.OpeningHours
import my.gimano.app.core.places.OpeningHours.Range
import my.gimano.app.core.places.PhotoSize
import my.gimano.app.core.places.RatingSummary
import my.gimano.app.core.places.Verification
import org.junit.Assert.*
import org.junit.Test

class OpeningHoursTest {
    private val day = listOf(Range("08:00", "18:00"))

    @Test fun groupsConsecutiveDays() {
        val week = mapOf("Mo" to day, "Tu" to day, "We" to day, "Th" to day, "Fr" to day, "Sa" to listOf(Range("08:00", "13:00")))
        assertEquals("Mo-Fr 08:00-18:00; Sa 08:00-13:00", OpeningHours.build(week))
    }

    @Test fun splitShiftsAndFridayPrayers() {
        val split = listOf(Range("08:00", "12:00"), Range("14:00", "17:00"))
        val friday = listOf(Range("14:30", "17:00"), Range("08:00", "12:00"))   // unsorted on purpose
        val week = mapOf("Mo" to split, "Tu" to split, "We" to split, "Th" to split, "Fr" to friday)
        assertEquals("Mo-Th 08:00-12:00,14:00-17:00; Fr 08:00-12:00,14:30-17:00", OpeningHours.build(week))
    }

    @Test fun twoDaysUseACommaAndClosedDaysAreOmitted() {
        assertEquals("Sa,Su 10:00-22:00", OpeningHours.build(mapOf("Sa" to listOf(Range("10:00", "22:00")), "Su" to listOf(Range("10:00", "22:00")))))
        assertEquals("Mo 09:00-17:00; We 09:00-17:00", OpeningHours.build(mapOf("Mo" to day.map { Range("09:00", "17:00") }, "We" to listOf(Range("09:00", "17:00")))))
        assertEquals("", OpeningHours.build(emptyMap()))
    }

    @Test fun open24Hours() {
        assertEquals("24/7", OpeningHours.build(emptyMap(), open247 = true))
        assertEquals("24/7", OpeningHours.build(OpeningHours.DAYS.associateWith { listOf(Range("00:00", "24:00")) }))
        assertEquals("Mo-Fr 00:00-24:00", OpeningHours.build(OpeningHours.DAYS.take(5).associateWith { listOf(Range("00:00", "24:00")) }))
    }

    @Test fun overnightRangesAreAllowed() {
        assertEquals("Fr,Sa 18:00-02:00", OpeningHours.build(mapOf("Fr" to listOf(Range("18:00", "02:00")), "Sa" to listOf(Range("18:00", "02:00")))))
    }

    @Test fun invalidTimesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Range("8:00", "18:00") }
        assertThrows(IllegalArgumentException::class.java) { Range("08:00", "25:00") }
        assertThrows(IllegalArgumentException::class.java) { Range("08:00", "08:00") }
    }

    @Test fun parseRoundTrips() {
        listOf("Mo-Fr 08:00-18:00; Sa 08:00-13:00", "Mo-Th 08:00-12:00,14:00-17:00; Fr 08:00-12:00,14:30-17:00", "Sa,Su 10:00-22:00", "24/7").forEach { s ->
            val (week, all) = OpeningHours.parse(s)!!
            assertEquals(s, OpeningHours.build(week, all))
        }
        assertEquals(listOf(Range("10:00", "14:00")), OpeningHours.parse("Sa-Mo 10:00-14:00")!!.first["Su"])
        assertTrue(OpeningHours.parse("Mo-Fr 08:00-18:00; Fr off")!!.first["Fr"]!!.isEmpty())
        assertNull(OpeningHours.parse("sunrise-sunset"))
        assertNull(OpeningHours.parse("Mo-Fr 8-18"))
    }
}

class PlacesLogicTest {
    @Test fun photoDownscaleKeepsAspectAndLimit() {
        assertEquals(2048 to 1536, PhotoSize.target(4000, 3000))
        assertEquals(1536 to 2048, PhotoSize.target(3000, 4000))
        assertEquals(1200 to 800, PhotoSize.target(1200, 800))
        assertEquals(2048 to 1, PhotoSize.target(8000, 2))
        assertEquals(2, PhotoSize.sampleSize(8160, 6120))   // 50 MP → decode at 4080 px, then scale to 2048 (never below the target)
        assertEquals(1, PhotoSize.sampleSize(2000, 1500))
    }

    @Test fun ratingSummary() {
        val s = RatingSummary.of(listOf(5, 5, 4, 1))
        assertEquals(3.75, s.mean!!, 1e-9)
        assertEquals(4, s.count)
        assertEquals(mapOf(1 to 1, 2 to 0, 3 to 0, 4 to 1, 5 to 2), s.distribution)
        assertFalse(s.isNew)
        assertTrue(RatingSummary.of(listOf(4, 5)).isNew)
        assertNull(RatingSummary.of(emptyList()).mean)
        assertEquals("server values win", 4.2, RatingSummary.of(listOf(5), 4.2, 37).mean!!, 0.0)
    }

    @Test fun onlyNearbyPeopleVerifyUnverifiedCommunityPlaces() {
        assertTrue(Verification.canVerify(true, "pending", 120.0))
        assertFalse(Verification.canVerify(true, "pending", 1200.0))
        assertFalse(Verification.canVerify(true, "published", 50.0))
        assertFalse(Verification.canVerify(false, null, 10.0))
        assertFalse(Verification.canVerify(true, "pending", null))
    }
}
