package my.gimano.app.ui.home

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The home panel's three resting states (docs: HomeFlow board).
 * PEEK is the default: search + one row of chips, so the map keeps ~80 % of the screen.
 */
enum class HomeSheetState {
    PEEK, HALF, FULL;

    /** Tapping the handle: peek → half → full → peek. */
    fun next(): HomeSheetState = when (this) { PEEK -> HALF; HALF -> FULL; FULL -> PEEK }
}

/** Pure sizing / snapping rules for the home panel, in dp. Kept free of Compose so it can be unit-tested. */
object HomeSheetLayout {
    /** The peek may never take more than a quarter of the screen (largest font scale included). */
    const val MAX_PEEK_FRACTION = 0.25f
    /** From this font scale the chips may wrap onto a second row (if that still fits the cap). */
    const val WRAP_FONT_SCALE = 1.3f
    /** Half state ≈ 470 of 844 dp on the boards. */
    const val HALF_FRACTION = 0.56f

    const val HANDLE_BLOCK = 23f   // 8 top padding + 5 handle + 10 gap
    const val SEARCH_MIN = 52f
    const val GAP = 10f
    const val CHIP_MIN = 44f
    const val CHIP_ROW_GAP = 8f
    const val BOTTOM = 12f
    /** A fling faster than this (dp/s) moves one state in its direction. */
    const val FLING_DP_S = 400f

    /** Search field height for a given text line height (dp): at least 52, grows with very large text. */
    fun searchHeight(textLineDp: Float): Float = max(SEARCH_MIN, textLineDp + 12f)

    /** Chip height for a given label line height (dp): at least 44 (touch target). */
    fun chipHeight(labelLineDp: Float): Float = max(CHIP_MIN, labelLineDp + 12f)

    /** Height of the peek, navigation-bar inset included. */
    fun peekHeight(chipRows: Int, searchDp: Float, chipDp: Float, navInsetDp: Float): Float =
        HANDLE_BLOCK + searchDp + GAP + chipRows * chipDp + (chipRows - 1).coerceAtLeast(0) * CHIP_ROW_GAP + BOTTOM + navInsetDp

    /**
     * One row of chips, or two at large font scales, but only when two rows still keep the peek
     * (inset included) within [MAX_PEEK_FRACTION] of the screen.
     */
    fun chipRows(fontScale: Float, screenHeightDp: Float, searchDp: Float, chipDp: Float, navInsetDp: Float): Int =
        if (fontScale >= WRAP_FONT_SCALE && peekHeight(2, searchDp, chipDp, navInsetDp) <= MAX_PEEK_FRACTION * screenHeightDp) 2 else 1

    fun fitsCap(peekDp: Float, screenHeightDp: Float): Boolean = peekDp <= MAX_PEEK_FRACTION * screenHeightDp + 0.5f

    /** Resting heights [peek, half, full], strictly increasing. Full stops just below the status bar. */
    fun anchors(peekDp: Float, screenHeightDp: Float, statusBarDp: Float): List<Float> {
        val full = max(peekDp + 2f, screenHeightDp - statusBarDp - 8f)
        val half = min(max(screenHeightDp * HALF_FRACTION, peekDp + 160f), full - 1f).coerceAtLeast(peekDp + 1f)
        return listOf(peekDp, half, full)
    }

    fun heightOf(state: HomeSheetState, anchors: List<Float>): Float = anchors[state.ordinal]

    /**
     * After a drag, the sheet must settle (and the state change) whenever it isn't resting at its state's height,
     * including a drag clamped exactly onto another anchor (full → swipe down hard → exactly the peek height).
     * Checking "between anchors" missed that case and left the state at FULL while the sheet showed the peek.
     */
    fun needsSettle(heightDp: Float, state: HomeSheetState, anchors: List<Float>): Boolean =
        abs(heightDp - anchors[state.ordinal]) >= 0.5f

    /**
     * A drag that reaches an end of the sheet commits that state at once: PEEK at the bottom anchor, FULL at the top.
     * Needed because the list leaves composition at the peek height mid-gesture, so no fling (and no settle) follows.
     * Null in between.
     */
    fun stateAtEnd(heightDp: Float, anchors: List<Float>): HomeSheetState? = when {
        heightDp <= anchors.first() + 0.5f -> HomeSheetState.PEEK
        heightDp >= anchors.last() - 0.5f -> HomeSheetState.FULL
        else -> null
    }

    /** A drag of at least this many dp away from the resting state commits to a new state, even without a fling. */
    const val COMMIT_DP = 56f

    /**
     * Where the sheet comes to rest after a drag that started at [from] and ended at [heightDp].
     * [growVelocityDpS] > 0 means the finger was moving up (the sheet growing).
     * A fast fling goes to the next state in its direction; a drag of [COMMIT_DP] or more goes to the nearest
     * state in its direction; otherwise the nearest state wins.
     */
    fun settle(heightDp: Float, growVelocityDpS: Float, anchors: List<Float>, from: HomeSheetState? = null): HomeSheetState {
        val states = HomeSheetState.entries
        if (growVelocityDpS > FLING_DP_S) {
            val i = anchors.indexOfFirst { it > heightDp + 1f }
            return if (i >= 0) states[i] else states.last()
        }
        if (growVelocityDpS < -FLING_DP_S) {
            val i = anchors.indexOfLast { it < heightDp - 1f }
            return if (i >= 0) states[i] else states.first()
        }
        if (from != null) {
            val start = anchors[from.ordinal]
            val candidates = when {
                heightDp - start >= COMMIT_DP -> anchors.indices.filter { it > from.ordinal }
                start - heightDp >= COMMIT_DP -> anchors.indices.filter { it < from.ordinal }
                else -> emptyList()
            }
            if (candidates.isNotEmpty()) return states[candidates.minBy { abs(anchors[it] - heightDp) }]
        }
        return states[anchors.indices.minBy { abs(anchors[it] - heightDp) }]
    }
}
