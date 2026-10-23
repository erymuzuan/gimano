package my.gimano.app.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import my.gimano.app.data.location.DriveSimulator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.gimano.app.core.format.Lang
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.model.LocationBatch
import my.gimano.app.data.model.Route
import my.gimano.app.data.repo.CannedRoutes
import my.gimano.app.data.repo.DemoRepository
import my.gimano.app.data.repo.GimanoRepository
import my.gimano.app.data.settings.SoundMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Every way out of navigation must leave nothing running: no GPS job, service, notification, TTS. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationControllerTest {
    private val docs = File("src/main/assets/demo-routes").listFiles { f -> f.extension == "json" }!!.map { it.readText() }
    private val canned = CannedRoutes.parse(docs)
    private fun route(id: String): Route = canned.all.single { it.id == id }.routes.first()

    private class RecordingSpeaker : Speaker {
        val spoken = ArrayList<String>()
        var stops = 0
        var speaking = false
        override fun speak(text: String, lang: Lang, flush: Boolean) { spoken += text; speaking = true }
        override fun stop() { stops++; speaking = false }
    }

    private class RecordingService : NavServiceControl {
        var running = false
        var notificationShown = false
        var starts = 0
        var stops = 0
        override fun start() { starts++; running = true; notificationShown = true }
        override fun stop() { stops++; running = false; notificationShown = false }
    }

    private class RecordingRepo(private val d: DemoRepository) : GimanoRepository by d {
        val batches = ArrayList<LocationBatch>()
        override suspend fun postLocations(batch: LocationBatch) { batches += batch }
    }

    private data class Rig(val c: NavigationController, val speaker: RecordingSpeaker, val service: RecordingService, val repo: RecordingRepo)

    private fun TestScope.rig(fixes: FixSource = FixSource { emptyFlow() }): Rig {
        val speaker = RecordingSpeaker()
        val service = RecordingService()
        val repo = RecordingRepo(DemoRepository(cannedDocs = { docs }))
        val c = NavigationController(
            backgroundScope, repo, fixes, speaker, service,
            soundMode = { SoundMode.ON }, langProvider = { Lang.EN },
        )
        return Rig(c, speaker, service, repo)
    }

    private val dest = NavDestination("Mid Valley Megamall", "", GeoPoint(3.1176, 101.6774), "midvalley")

    @Test fun stopDuringSimulatedDriveLeavesNothingRunning() = runTest {
        val (c, speaker, service, repo) = rig()
        c.start(route("ss2-midvalley"), dest, null, simulate = true)
        advanceTimeBy(30_000); runCurrent()
        assertTrue(c.state.value.active)
        assertNotNull(c.state.value.progress)
        assertTrue(service.running && service.notificationShown)

        c.stop()
        runCurrent()
        assertEquals("state is idle", NavState(), c.state.value)
        assertFalse("service stopped", service.running)
        assertFalse("notification removed", service.notificationShown)
        assertTrue("TTS stopped", speaker.stops >= 1 && !speaker.speaking)
        assertTrue("a simulated drive never sends traffic pings", repo.batches.isEmpty())

        // Nothing keeps driving in the background.
        val batches = repo.batches.size
        advanceTimeBy(120_000); runCurrent()
        assertEquals(NavState(), c.state.value)
        assertEquals(batches, repo.batches.size)
        assertEquals(1, service.stops)
    }

    @Test fun stopWhileSpeakingSilencesTts() = runTest {
        val (c, speaker, _, _) = rig()
        c.start(route("ss2-midvalley"), dest, null, simulate = true)
        runCurrent()
        assertTrue("first instruction spoken", speaker.spoken.isNotEmpty() && speaker.speaking)
        c.stop()
        assertFalse(speaker.speaking)
    }

    @Test fun arrivalStopsTrackingAndServiceButKeepsTheArrivalCard() = runTest {
        val (c, _, service, _) = rig()
        c.start(route("ss2-ttdi"), NavDestination("TTDI", "", GeoPoint(3.146, 101.629), null), null, simulate = true)
        var t = 0
        while (!c.state.value.arrived && t < 3_600) { advanceTimeBy(1_000); runCurrent(); t++ }
        assertTrue("arrived within an hour of simulated time", c.state.value.arrived)
        assertFalse("service + notification gone on arrival", service.running || service.notificationShown)
        val atArrival = c.state.value
        advanceTimeBy(60_000); runCurrent()
        assertEquals("no more fixes after arrival", atArrival.fix, c.state.value.fix)

        c.stop() // Done on the arrival card
        assertEquals(NavState(), c.state.value)
    }

    @Test fun stopIsIdempotentAndSafeWhenIdle() = runTest {
        val (c, _, service, _) = rig()
        c.stop()
        assertEquals(0, service.stops)
        c.start(route("ss2-midvalley"), dest, null, simulate = true)
        runCurrent()
        c.stop(); c.stop()
        assertEquals(1, service.stops)
        assertFalse(service.running)
    }

    @Test fun restartingADriveReplacesTheOldOne() = runTest {
        val (c, _, service, _) = rig()
        c.start(route("ss2-midvalley"), dest, null, simulate = true)
        runCurrent()
        c.start(route("ss2-klsentral"), dest, null, simulate = true)
        runCurrent()
        assertTrue(service.running)
        assertEquals(2, service.starts)
        assertEquals(1, service.stops)
        c.stop()
        assertFalse(service.running)
    }

    /** Real GPS along the route: the simulator's geometry, but marked as real fixes. */
    private fun realGps(r: Route) = FixSource {
        DriveSimulator.drive(my.gimano.app.core.nav.NavigationTracker(r).geometry, 40.0).map { it.copy(simulated = false) }
    }

    @Test fun realDriveSendsPingsAndFlushesThemOnStop() = runTest {
        val r = route("ss2-midvalley")
        val (c, _, _, repo) = rig(realGps(r))
        c.start(r, dest, null, simulate = false)
        advanceTimeBy(60_000); runCurrent()
        c.stop(); runCurrent()
        assertTrue("pings sent", repo.batches.isNotEmpty() && repo.batches.last().points.isNotEmpty())
        assertNotNull("flushed batch keeps the route id", repo.batches.last().routeId)
    }

    @Test fun walkingAndCyclingNeverFeedTrafficSpeeds() = runTest {
        for (mode in listOf("walk", "bicycle")) {
            val r = route("ss2-midvalley").copy(vehicle = mode)
            val (c, _, _, repo) = rig(realGps(r))
            c.start(r, dest, null, simulate = false)
            advanceTimeBy(60_000); runCurrent()
            c.stop(); runCurrent()
            assertTrue("$mode: no pings", repo.batches.isEmpty())
        }
    }
}
