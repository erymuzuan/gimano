package my.gimano.app.ui

import my.gimano.app.ui.home.HomeSheetLayout
import my.gimano.app.ui.home.HomeSheetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSheetTest {
    // Samsung S24 at its default display size: 1080×2340 px at 420 dpi → 891 dp tall.
    private val s24 = 891f
    private val threeButtonNav = 48f
    private val gestureNav = 16f

    /** Android 14+ font scaling is non-linear: at 200 % 13 sp ≈ 25 dp and 18 sp ≈ 30 dp (not 26 / 36). */
    private fun sizes(labelSpDp: Float, searchSpDp: Float) =
        HomeSheetLayout.searchHeight(searchSpDp * 1.36f) to HomeSheetLayout.chipHeight(labelSpDp * 1.36f)

    @Test fun handleTapCyclesPeekHalfFull() {
        assertEquals(HomeSheetState.HALF, HomeSheetState.PEEK.next())
        assertEquals(HomeSheetState.FULL, HomeSheetState.HALF.next())
        assertEquals(HomeSheetState.PEEK, HomeSheetState.FULL.next())
    }

    @Test fun peekIsAboutOneFiftyDpPlusTheNavBar() {
        val (search, chip) = sizes(13f, 18f)
        assertEquals(52f, search, 0.01f)
        assertEquals(44f, chip, 0.01f)
        val peek = HomeSheetLayout.peekHeight(1, search, chip, 0f)
        assertTrue("peek $peek", peek in 135f..155f)
        assertEquals(1, HomeSheetLayout.chipRows(1f, s24, search, chip, threeButtonNav))
        assertTrue(HomeSheetLayout.fitsCap(peek + threeButtonNav, s24))
    }

    @Test fun peekStaysWithinAQuarterAtTheLargestFontScale() {
        val (search, chip) = sizes(25f, 30f)   // 200 %
        for (inset in listOf(0f, gestureNav, threeButtonNav)) {
            for (screen in listOf(780f, s24)) {   // S23 (the QA report's phone) and S24
                val rows = HomeSheetLayout.chipRows(2f, screen, search, chip, inset)
                assertTrue(rows in 1..2)
                val peek = HomeSheetLayout.peekHeight(rows, search, chip, inset)
                assertTrue("peek $peek dp on $screen dp (inset $inset)", HomeSheetLayout.fitsCap(peek, screen))
            }
        }
    }

    @Test fun chipsWrapToTwoRowsOnlyWhenLargeAndItFits() {
        val (search, chip) = sizes(25f, 30f)
        assertEquals(2, HomeSheetLayout.chipRows(2f, s24, search, chip, gestureNav))
        assertEquals(1, HomeSheetLayout.chipRows(2f, s24, search, chip, threeButtonNav))   // 2 rows would be > 25 %
        assertEquals(1, HomeSheetLayout.chipRows(1f, s24, search, chip, 0f))                // normal text never wraps
    }

    @Test fun anchorsAreOrderedAndFullStopsBelowTheStatusBar() {
        val a = HomeSheetLayout.anchors(190f, s24, 40f)
        assertEquals(3, a.size)
        assertTrue(a[0] < a[1] && a[1] < a[2])
        assertEquals(s24 - 48f, a[2], 0.01f)
        assertEquals(s24 * HomeSheetLayout.HALF_FRACTION, a[1], 0.01f)
        // A tiny screen still gives three distinct states.
        val t = HomeSheetLayout.anchors(200f, 320f, 24f)
        assertTrue(t[0] < t[1] && t[1] < t[2])
    }

    @Test fun releaseSnapsToNearestOrFlingsOneState() {
        val a = listOf(190f, 500f, 843f)
        assertEquals(HomeSheetState.PEEK, HomeSheetLayout.settle(300f, 0f, a))
        assertEquals(HomeSheetState.HALF, HomeSheetLayout.settle(400f, 0f, a))
        assertEquals(HomeSheetState.FULL, HomeSheetLayout.settle(700f, 0f, a))
        // Swipe up once from peek → half, again from half → full.
        assertEquals(HomeSheetState.HALF, HomeSheetLayout.settle(230f, 2000f, a))
        assertEquals(HomeSheetState.FULL, HomeSheetLayout.settle(520f, 2000f, a))
        // Swipe down → one state down; past the bottom stays at peek.
        assertEquals(HomeSheetState.HALF, HomeSheetLayout.settle(800f, -2000f, a))
        assertEquals(HomeSheetState.PEEK, HomeSheetLayout.settle(450f, -2000f, a))
        assertEquals(HomeSheetState.PEEK, HomeSheetLayout.settle(190f, -2000f, a))
        assertEquals(HomeSheetState.FULL, HomeSheetLayout.settle(843f, 2000f, a))
    }

    @Test fun aSlowDragPastTheCommitDistanceStillChangesState() {
        val a = listOf(190f, 500f, 843f)
        assertEquals(HomeSheetState.HALF, HomeSheetLayout.settle(260f, 0f, a, from = HomeSheetState.PEEK))
        assertEquals(HomeSheetState.PEEK, HomeSheetLayout.settle(230f, 0f, a, from = HomeSheetState.PEEK))   // too short: back
        assertEquals(HomeSheetState.FULL, HomeSheetLayout.settle(780f, 0f, a, from = HomeSheetState.PEEK))   // long drag: nearest above
        assertEquals(HomeSheetState.PEEK, HomeSheetLayout.settle(420f, 0f, a, from = HomeSheetState.HALF))
        assertEquals(HomeSheetState.HALF, HomeSheetLayout.settle(770f, 0f, a, from = HomeSheetState.FULL))
    }
}
