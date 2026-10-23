package my.gimano.app.trip

import my.gimano.app.core.trip.LeaveBy
import my.gimano.app.core.trip.PlannedSchedule
import my.gimano.app.data.planned.PlacePoint
import my.gimano.app.data.planned.PlannedDrive
import my.gimano.app.data.planned.toEntity
import my.gimano.app.data.planned.toModel
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class PlannedDrivesTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(kl).toInstant()

    @Test fun weekdaySchedulesSkipTheWeekend() {
        val s = PlannedSchedule("08:30", PlannedSchedule.WEEKDAYS)
        // Fri 23 Oct 2026, 09:00 → next is Mon 26 Oct 08:30.
        assertEquals(at("2026-10-26T08:30"), s.nextArrival(at("2026-10-23T09:00"), kl)!!.toInstant())
        // Fri 07:00 → today 08:30.
        assertEquals(at("2026-10-23T08:30"), s.nextArrival(at("2026-10-23T07:00"), kl)!!.toInstant())
        assertEquals(at("2026-10-24T10:00"), PlannedSchedule("10:00", listOf("sa")).nextArrival(at("2026-10-23T09:00"), kl)!!.toInstant())
    }

    @Test fun oneOffInThePastHasNoNextArrival() {
        val s = PlannedSchedule("18:00", date = "2026-10-22")
        assertNull(s.nextArrival(at("2026-10-23T09:00"), kl))
        assertEquals(at("2026-10-24T18:00"), PlannedSchedule("18:00", date = "2026-10-24").nextArrival(at("2026-10-23T09:00"), kl)!!.toInstant())
    }

    @Test fun leaveByUsesTheLatestSlotThatStillArrivesInTime() {
        val arrive = at("2026-10-26T08:30")
        val slots = listOf(
            LeaveBy.Slot(at("2026-10-26T06:00"), 1800.0),   // arrives 06:30
            LeaveBy.Slot(at("2026-10-26T07:00"), 2700.0),   // arrives 07:45 ← latest in time
            LeaveBy.Slot(at("2026-10-26T08:00"), 3000.0),   // arrives 08:50 (late)
        )
        assertEquals(at("2026-10-26T07:45"), LeaveBy.compute(arrive, slots, 1200.0))
        assertEquals(at("2026-10-26T07:35"), LeaveBy.reminderAt(LeaveBy.compute(arrive, slots, 1200.0)))
        assertEquals("no slots → fallback duration", at("2026-10-26T08:10"), LeaveBy.compute(arrive, emptyList(), 1200.0))
        // Every slot is too late → closest one's duration.
        assertEquals(at("2026-10-26T07:40"), LeaveBy.compute(arrive, listOf(LeaveBy.Slot(at("2026-10-26T08:00"), 3000.0)), 1200.0))
    }

    @Test fun storedInTheSharedTableAsJson() {
        val d = PlannedDrive("p1", "Kerja", null, PlacePoint(3.134, 101.6865, "KL Sentral", "50470 KL"), listOf(PlacePoint(3.12, 101.62, "Kedai Mak")),
            "motorcycle", PlannedSchedule("08:30", PlannedSchedule.WEEKDAYS), "2026-10-23T00:00:00Z")
        val e = d.toEntity()
        assertNull(e.originJson)
        assertTrue(e.departAt!!.contains("\"arriveBy\":\"08:30\""))
        assertTrue(e.departAt!!.contains("\"days\":[\"mo\",\"tu\",\"we\",\"th\",\"fr\"]"))
        assertEquals(d, e.toModel())
    }
}
