package my.gimano.app.trip

import my.gimano.app.core.trip.LeaveBy
import my.gimano.app.core.trip.PlannedSchedule
import my.gimano.app.data.planned.PlacePoint
import my.gimano.app.data.planned.PlannedDrive
import my.gimano.app.data.planned.toEntity
import my.gimano.app.data.planned.toModel
import my.gimano.app.data.planned.scheduleJson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class PlannedDrivesTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(kl).toInstant()

    @Test fun weekdaySchedulesSkipTheWeekend() {
        val s = PlannedSchedule.repeating("08:30", PlannedSchedule.WEEKDAYS)
        // Fri 23 Oct 2026, 09:00 → next is Mon 26 Oct 08:30.
        assertEquals(at("2026-10-26T08:30"), s.nextArrival(at("2026-10-23T09:00"), kl)!!.toInstant())
        // Fri 07:00 → today 08:30.
        assertEquals(at("2026-10-23T08:30"), s.nextArrival(at("2026-10-23T07:00"), kl)!!.toInstant())
        assertEquals(at("2026-10-24T10:00"), PlannedSchedule.repeating("10:00", listOf("Sa")).nextArrival(at("2026-10-23T09:00"), kl)!!.toInstant())
    }

    @Test fun oneOffInThePastHasNoNextArrival() {
        val s = PlannedSchedule.oneOff(java.time.LocalDate.parse("2026-10-22"), "18:00", kl)
        assertNull(s.nextArrival(at("2026-10-23T09:00"), kl))
        val next = PlannedSchedule.oneOff(java.time.LocalDate.parse("2026-10-24"), "18:00", kl)
        assertEquals("stored as an instant like iOS", "2026-10-24T10:00:00Z", next.arriveAt)
        assertEquals(at("2026-10-24T18:00"), next.nextArrival(at("2026-10-23T09:00"), kl)!!.toInstant())
        assertEquals("18:00", next.timeOfDay(kl))
    }

    @Test fun leaveByUsesTheLatestSlotThatStillArrivesInTime() {
        val arrive = at("2026-10-26T08:30")
        val slots = listOf(
            LeaveBy.Slot(at("2026-10-26T06:00"), 1800.0),   // arrives 06:30
            LeaveBy.Slot(at("2026-10-26T07:00"), 2700.0),   // arrives 07:45 ← latest in time
            LeaveBy.Slot(at("2026-10-26T08:00"), 3000.0),   // arrives 08:50 (late)
        )
        assertEquals(at("2026-10-26T07:45"), LeaveBy.compute(arrive, slots, 1200.0))
        assertEquals(at("2026-10-26T07:30"), LeaveBy.reminderAt(LeaveBy.compute(arrive, slots, 1200.0)))
        assertEquals("no slots → fallback duration", at("2026-10-26T08:10"), LeaveBy.compute(arrive, emptyList(), 1200.0))
        // Every slot is too late → closest one's duration.
        assertEquals(at("2026-10-26T07:40"), LeaveBy.compute(arrive, listOf(LeaveBy.Slot(at("2026-10-26T08:00"), 3000.0)), 1200.0))
    }

    @Test fun storedLikeIosDoes() {
        val d = PlannedDrive("p1", "Kerja", null, PlacePoint(3.134, 101.6865, "KL Sentral", "50470 KL"), listOf(PlacePoint(3.12, 101.62, "Kedai Mak")),
            "motorcycle", PlannedSchedule.repeating("08:30", PlannedSchedule.WEEKDAYS), "2026-10-23T00:00:00Z")
        val e = d.toEntity()
        assertNull(e.originJson)
        assertNull("repeating: depart_at is null", e.departAt)
        assertEquals("""{"arriveBy":"08:30","days":["Mo","Tu","We","Th","Fr"]}""", d.scheduleJson())
        assertEquals(d, e.toModel(d.scheduleJson()))
        // A one-off stores its computed leave-by in depart_at.
        val once = d.copy(schedule = PlannedSchedule.oneOff(java.time.LocalDate.parse("2026-10-24"), "08:30", kl))
        assertEquals("2026-10-24T00:00:00Z", once.toEntity(Instant.parse("2026-10-24T00:00:00Z")).departAt)
        // An iOS-written row parses (schedule from settings["planned_drive:<id>"]).
        val ios = """{"arriveBy":"07:15","days":["Mo","We"]}"""
        assertEquals(listOf("Mo", "We"), e.toModel(ios)!!.schedule.days)
        // No schedule row: iOS falls back to depart_at as a one-off; so do we.
        assertEquals("2026-10-24T00:00:00Z", once.toEntity(Instant.parse("2026-10-24T00:00:00Z")).toModel(null)!!.schedule.arriveAt)
    }
}
