package my.gimano.app.ui

import my.gimano.app.ui.place.PlaceSheetLayout
import my.gimano.app.ui.place.PlaceSheetState
import my.gimano.app.ui.place.RatingLine
import my.gimano.app.ui.place.StarFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSheetTest {
    // Samsung S24 at its default display size: 891 dp tall; status bar ≈ 40 dp.
    private val s24 = 891f
    private val status = 40f
    private val gestureNav = 16f
    private val threeButtonNav = 48f
    private val count: (Int) -> String = { n -> if (n == 1) "1 review" else "$n reviews" }

    // --- Rating line -----------------------------------------------------------------------------------------

    @Test fun ratingLineShowsMeanCountAndDistance() {
        val l = RatingLine.of(4.3, 12, "450 m", "New", count)
        assertEquals("4.3", l.mean)
        assertEquals("4.3 · 12 reviews · 450 m", l.text)
        assertEquals(listOf(StarFill.FULL, StarFill.FULL, StarFill.FULL, StarFill.FULL, StarFill.PALE), l.stars)
    }

    @Test fun ratingLineWithoutReviewsIsGreyStarsAndNew() {
        val l = RatingLine.of(null, 0, "450 m", "Baharu", count)
        assertNull(l.mean)
        assertEquals("Baharu · 450 m", l.text)
        assertTrue(l.stars.all { it == StarFill.EMPTY })
        // A server mean with zero reviews is still "new" (no stale average).
        assertEquals("Baharu", RatingLine.of(4.0, 0, null, "Baharu", count).text)
    }

    @Test fun fewerThanThreeReviewsShowNewWithTheCount() {
        val l = RatingLine.of(5.0, 2, null, "New", count)
        assertNull(l.mean)
        assertEquals("New · 2 reviews", l.text)
        assertTrue(l.stars.all { it == StarFill.FULL })
        assertEquals("New · 1 review · 2.4 km", RatingLine.of(4.0, 1, "2.4 km", "New", count).text)
    }

    @Test fun meanUsesOneDecimalWithADot() {
        assertEquals("4.0 · 3 reviews", RatingLine.of(4.0, 3, null, "New", count).text)
        assertEquals("3.7", RatingLine.of(3.66, 9, null, "New", count).mean)
    }

    @Test fun starsRoundToTheNearestHalf() {
        assertEquals(listOf(StarFill.FULL, StarFill.FULL, StarFill.FULL, StarFill.FULL, StarFill.FULL), RatingLine.stars(4.8))
        assertEquals(listOf(StarFill.FULL, StarFill.FULL, StarFill.FULL, StarFill.PALE, StarFill.EMPTY), RatingLine.stars(3.5))
        assertEquals(listOf(StarFill.FULL, StarFill.EMPTY, StarFill.EMPTY, StarFill.EMPTY, StarFill.EMPTY), RatingLine.stars(1.2))
        assertTrue(RatingLine.stars(null).all { it == StarFill.EMPTY })
    }

    // --- Peek cap ---------------------------------------------------------------------------------------------

    @Test fun peekIsTheContentPlusInsetWithinFortyPercent() {
        // Board TempatIntai: ~306 dp of content at font 1.0 → ≈ 39 % of the S24 with the 3-button bar.
        val peek = PlaceSheetLayout.peekHeight(306f, threeButtonNav, s24)
        assertEquals(354f, peek, 0.01f)
        assertTrue(PlaceSheetLayout.fitsCap(peek, s24))
        assertTrue(peek / s24 <= 0.40f)
    }

    @Test fun peekNeverExceedsFortyPercentAtTheLargestFont() {
        for (inset in listOf(0f, gestureNav, threeButtonNav)) {
            for (screen in listOf(780f, s24)) {
                val peek = PlaceSheetLayout.peekHeight(560f, inset, screen)   // 200 % font: content far taller than the cap
                assertEquals(0.40f * screen, peek, 0.01f)
                assertTrue(PlaceSheetLayout.fitsCap(peek, screen))
            }
        }
    }

    @Test fun routesAndActionsArePinnedOnlyWhenThePeekOverflows() {
        // Measured on the S24 at font 1.0: 273 dp of content + 48 dp 3-button bar = 321 dp (36 %) → inline.
        assertTrue(!PlaceSheetLayout.pinFooter(120f, 153f, threeButtonNav, s24))
        // 200 %: header ≈ 260 dp + footer ≈ 230 dp → way over 356 dp: pinned, the peek stays at the cap.
        assertTrue(PlaceSheetLayout.pinFooter(260f, 230f, threeButtonNav, s24))
        assertEquals(0.40f * s24, PlaceSheetLayout.peekHeight(260f + 230f, threeButtonNav, s24), 0.01f)
        // Exactly at the cap still fits.
        assertTrue(!PlaceSheetLayout.pinFooter(0.40f * s24 - 230f - gestureNav, 230f, gestureNav, s24))
    }

    @Test fun fullStopsBelowTheStatusBarLeavingAMapStrip() {
        val peek = 354f
        val full = PlaceSheetLayout.fullHeight(peek, 1400f, gestureNav, s24, status)
        assertEquals(s24 - status - PlaceSheetLayout.FULL_MAP_STRIP_DP, full, 0.01f)
        // A card with little below the peek stays short, but is always taller than the peek.
        assertEquals(500f + gestureNav, PlaceSheetLayout.fullHeight(peek, 500f, gestureNav, s24, status), 0.01f)
        assertEquals(peek + 1f, PlaceSheetLayout.fullHeight(peek, 300f, gestureNav, s24, status), 0.01f)
    }

    @Test fun swipeUpOpensSwipeDownCloses() {
        val a = PlaceSheetLayout.anchors(354f, 775f)
        assertEquals(PlaceSheetState.FULL, PlaceSheetLayout.settle(380f, 2000f, a, PlaceSheetState.PEEK))
        assertEquals(PlaceSheetState.PEEK, PlaceSheetLayout.settle(740f, -2000f, a, PlaceSheetState.FULL))
        assertEquals(PlaceSheetState.FULL, PlaceSheetLayout.settle(420f, 0f, a, PlaceSheetState.PEEK))    // slow, past commit
        assertEquals(PlaceSheetState.PEEK, PlaceSheetLayout.settle(380f, 0f, a, PlaceSheetState.PEEK))    // too short
        assertEquals(PlaceSheetState.PEEK, PlaceSheetLayout.settle(700f, 0f, a, PlaceSheetState.FULL))    // slow, past commit
        assertEquals(PlaceSheetState.FULL, PlaceSheetLayout.settle(760f, 0f, a, PlaceSheetState.FULL))
    }

    @Test fun aSwipeDownClampedOntoThePeekStillSettles() {
        val a = PlaceSheetLayout.anchors(354f, 775f)
        // The S24 bug: full → swipe down lands exactly on the peek; the state must still become PEEK.
        assertTrue(PlaceSheetLayout.needsSettle(354f, PlaceSheetState.FULL, a))
        assertEquals(PlaceSheetState.PEEK, PlaceSheetLayout.settle(354f, -1500f, a, PlaceSheetState.FULL))
        assertTrue(PlaceSheetLayout.needsSettle(775f, PlaceSheetState.PEEK, a))
        assertTrue(!PlaceSheetLayout.needsSettle(354f, PlaceSheetState.PEEK, a))
        assertTrue(!PlaceSheetLayout.needsSettle(775f, PlaceSheetState.FULL, a))
    }

    // --- Camera offset ----------------------------------------------------------------------------------------

    @Test fun pinIsCentredInTheVisibleMapNotTheScreen() {
        val peek = 354f
        val pad = PlaceSheetLayout.cameraBottomPaddingDp(peek, s24)
        val y = PlaceSheetLayout.targetScreenY(s24, pad, status)
        // Middle of [status bar, top of the card]: (40 + 537) / 2.
        assertEquals((status + (s24 - peek)) / 2f, y, 0.01f)
        assertEquals(PlaceSheetLayout.visibleMapCentreY(s24, peek, status), y, 0.01f)
        assertTrue("pin above the card", y < s24 - peek)
        // Without the padding the pin would sit at the screen centre, only 92 dp above the card's edge.
        assertTrue(s24 / 2f > y)
    }

    @Test fun cameraFollowsTheSheetWhenItOpens() {
        val full = PlaceSheetLayout.fullHeight(354f, 1400f, gestureNav, s24, status)
        val y = PlaceSheetLayout.targetScreenY(s24, PlaceSheetLayout.cameraBottomPaddingDp(full, s24), status)
        assertTrue("pin in the strip: $y", y > status && y < s24 - full)
        assertEquals((status + s24 - full) / 2f, y, 0.01f)
        // The padding never reaches the whole screen.
        assertEquals(s24 - 1f, PlaceSheetLayout.cameraBottomPaddingDp(2000f, s24), 0.01f)
    }
}
