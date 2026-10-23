package my.gimano.app.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.gimano.app.core.format.Lang
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.data.location.Fix
import my.gimano.app.data.repo.CannedRoutes
import my.gimano.app.data.repo.DemoRepository
import my.gimano.app.data.settings.SoundMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * User report: during navigation the puck was not visible and not where the driver was ("Approximate" location).
 * Poor fixes must never snap or advance the instructions, and the puck must always exist while navigating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PuckVisibilityTest {
    private val docs = File("src/main/assets/demo-routes").listFiles { f -> f.extension == "json" }!!.map { it.readText() }
    private val route = CannedRoutes.parse(docs).all.single { it.id == "ss2-midvalley" }.routes.first()
    private val pts = Polyline.decode(route.polyline)
    private val dest = NavDestination("Mid Valley Megamall", "", GeoPoint(3.1176, 101.6774), "midvalley")

    private object Quiet : Speaker { override fun speak(text: String, lang: Lang, flush: Boolean) {}; override fun stop() {} }
    private object NoService : NavServiceControl { override fun start() {}; override fun stop() {} }

    @Test fun approximateFixesKeepThePuckVisibleButNeverAdvance() = runTest {
        val fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 16)
        val c = NavigationController(backgroundScope, DemoRepository(cannedDocs = { docs }), FixSource { fixes }, Quiet, NoService,
            soundMode = { SoundMode.OFF }, langProvider = { Lang.EN })
        c.start(route, dest, null, simulate = false)
        runCurrent()

        // 1) First fix is an "Approximate" one (±2 km, 900 m off the road): puck shown at the raw fix, no progress.
        val off = GeoPoint(pts[0].lat + 0.008, pts[0].lng)
        fixes.emit(Fix(off, 0f, null, 2000f, 1_000)); runCurrent()
        var s = c.state.value
        assertEquals("puck visible even with only a poor fix", off, s.puck)
        assertNull("instructions not advanced by a poor fix", s.progress)
        assertFalse(s.puckSnapped)
        assertEquals(2000.0, s.accuracyM!!, 0.1)

        // 2) A good fix on the route: snapped, progress starts.
        val on = pts[pts.size / 4]
        fixes.emit(Fix(on, 10f, null, 6f, 2_000)); runCurrent()
        s = c.state.value
        assertNotNull(s.progress); assertTrue(s.puckSnapped)
        val along = s.progress!!.distanceAlongM
        val puck = s.puck

        // 3) Poor fixes far ahead must not jump the instructions or move the snapped puck, but the puck stays.
        for (t in 3..20) { fixes.emit(Fix(pts[pts.size - 2], 10f, null, 150f, t * 1_000L)); runCurrent() }
        s = c.state.value
        assertEquals("progress frozen on poor fixes", along, s.progress!!.distanceAlongM, 0.01)
        assertEquals("puck kept at the last good place", puck, s.puck)
        assertNotNull("puck never disappears while navigating", s.puck)
        assertEquals("weak-GPS chip after >10 s of >50 m fixes", 150, s.weakGpsM)

        // 4) Good fixes again clear the chip.
        fixes.emit(Fix(pts[pts.size / 3], 10f, null, 5f, 21_000)); runCurrent()
        assertNull(c.state.value.weakGpsM)
        c.stop()
    }
}
