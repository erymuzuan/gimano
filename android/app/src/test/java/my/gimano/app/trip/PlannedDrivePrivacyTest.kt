package my.gimano.app.trip

import kotlinx.coroutines.test.runTest
import my.gimano.app.core.trip.LeaveBy
import my.gimano.app.core.trip.PlannedSchedule
import my.gimano.app.data.planned.LeavePlanner
import my.gimano.app.data.planned.PlacePoint
import my.gimano.app.data.planned.PlannedDrive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** User decision: planned drives never send the user's position in the background. */
class PlannedDrivePrivacyTest {
    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private val now = Instant.parse("2026-10-24T00:00:00Z")               // 08:00 MYT
    private val arrive = Instant.parse("2026-10-24T02:00:00Z")             // 10:00 MYT
    private val home = PlacePoint(3.1201, 101.6202, "Rumah")
    private val klcc = PlacePoint(3.1579, 101.7116, "KLCC")

    private fun drive(origin: PlacePoint?) = PlannedDrive("d1", "Kerja", origin, klcc, emptyList(), "car",
        PlannedSchedule(arriveAt = arrive.toString()), now.toString())

    /** Records every departure-times call: each one's origin. */
    private class Recorder : LeavePlanner.DepartureTimes {
        val origins = mutableListOf<Pair<Double, Double>>()
        override suspend fun slots(oLat: Double, oLng: Double, dLat: Double, dLng: Double, vehicle: String?): List<LeaveBy.Slot> {
            origins += oLat to oLng
            return listOf(LeaveBy.Slot(Instant.parse("2026-10-24T01:20:00Z"), 1800.0))   // leave 09:20, 30 min
        }
    }

    @Test fun whereIAmDriveMakesNoBackgroundCallAndAsksTheUserToOpen() = runTest {
        val rec = Recorder()
        val p = LeavePlanner.plan(drive(origin = null), now, zone, rec)!!
        assertTrue("no request at all for a \"where I am\" drive", rec.origins.isEmpty())
        assertEquals(arrive, p.arrive)
        assertNull("no traffic-based time (\"Open for the best time\")", p.leaveBy)
        // The reminder still fires: 15 min before arrival − 30 min.
        assertEquals(arrive.minusSeconds(1800 + 900), LeavePlanner.reminderAt(p))
    }

    @Test fun savedStartChecksTrafficFromThatSavedPlaceOnly() = runTest {
        val rec = Recorder()
        val p = LeavePlanner.plan(drive(origin = home), now, zone, rec)!!
        assertEquals("the request carries the saved start, nothing else", listOf(home.lat to home.lng), rec.origins)
        assertEquals(Instant.parse("2026-10-24T01:30:00Z"), p.leaveBy)
        assertEquals(Instant.parse("2026-10-24T01:15:00Z"), LeavePlanner.reminderAt(p))
    }

    @Test fun plannerHasNoWayToReadTheLiveFix() {
        // The planner's only inputs are the drive, the clock and the request port: no position provider exists.
        val params = LeavePlanner::class.java.methods.single { it.name == "plan" }.parameterTypes.map { it.simpleName }
        assertEquals(listOf("PlannedDrive", "Instant", "ZoneId", "DepartureTimes", "Continuation"), params)
        // PlannedDrives(context, dao, settingsDao, repo, lang, use24h): the old `here` position provider is gone.
        val ctor = my.gimano.app.data.planned.PlannedDrives::class.java.constructors.single().parameterTypes.map { it.simpleName }
        assertEquals(listOf("Context", "PlannedDriveDao", "SettingDao", "GimanoRepository", "Function0", "Function0"), ctor)
    }
}
