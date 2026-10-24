package my.gimano.app.ui.place

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The place card's two resting states (canvas "Place card from search": TempatIntai / TempatPenuh). */
enum class PlaceSheetState { PEEK, FULL }

/** Pure sizing / snapping / camera rules for the place card, in dp. Free of Compose so it can be unit-tested. */
object PlaceSheetLayout {
    /** The peek (navigation-bar inset included) never takes more than 40 % of the screen, largest font included. */
    const val MAX_PEEK_FRACTION = 0.40f
    /** Full stops this far below the status bar, so the pin stays visible in a strip of map above the card. */
    const val FULL_MAP_STRIP_DP = 76f
    /** A fling faster than this (dp/s) moves to the state in its direction. */
    const val FLING_DP_S = 400f
    /** A slow drag of at least this many dp commits to the other state. */
    const val COMMIT_DP = 56f

    fun peekCap(screenHeightDp: Float): Float = MAX_PEEK_FRACTION * screenHeightDp

    /** Peek height: the compact content (header → action row) plus the nav inset, capped at [MAX_PEEK_FRACTION]. */
    fun peekHeight(peekContentDp: Float, navInsetDp: Float, screenHeightDp: Float): Float =
        min(peekContentDp + navInsetDp, peekCap(screenHeightDp))

    fun fitsCap(peekDp: Float, screenHeightDp: Float): Boolean = peekDp <= peekCap(screenHeightDp) + 0.5f

    /**
     * Very large text: when header + Routes/actions don't fit under the cap, Routes and the action row are pinned to
     * the bottom of the card (always visible above the navigation bar) and the header scrolls above them.
     * Header and footer are measured on their own, so pinning doesn't change the decision.
     */
    fun pinFooter(headerDp: Float, footerDp: Float, navInsetDp: Float, screenHeightDp: Float): Boolean =
        !fitsCap(headerDp + footerDp + navInsetDp, screenHeightDp)

    /**
     * Full height: all the content plus the nav inset, but stopping [FULL_MAP_STRIP_DP] below the status bar
     * (a short card stays short). Always at least 1 dp above the peek, so the two states stay distinct.
     */
    fun fullHeight(peekDp: Float, allContentDp: Float, navInsetDp: Float, screenHeightDp: Float, statusBarDp: Float): Float {
        val limit = screenHeightDp - statusBarDp - FULL_MAP_STRIP_DP
        return max(peekDp + 1f, min(allContentDp + navInsetDp, limit))
    }

    fun anchors(peekDp: Float, fullDp: Float): List<Float> = listOf(peekDp, max(fullDp, peekDp + 1f))

    fun heightOf(state: PlaceSheetState, anchors: List<Float>): Float = anchors[state.ordinal]

    /** Where the card rests after a drag from [from] that ended at [heightDp]; [growVelocityDpS] > 0 = finger moving up. */
    fun settle(heightDp: Float, growVelocityDpS: Float, anchors: List<Float>, from: PlaceSheetState): PlaceSheetState {
        if (growVelocityDpS > FLING_DP_S) return PlaceSheetState.FULL
        if (growVelocityDpS < -FLING_DP_S) return PlaceSheetState.PEEK
        val start = anchors[from.ordinal]
        if (from == PlaceSheetState.PEEK && heightDp - start >= COMMIT_DP) return PlaceSheetState.FULL
        if (from == PlaceSheetState.FULL && start - heightDp >= COMMIT_DP) return PlaceSheetState.PEEK
        return if (abs(anchors[0] - heightDp) <= abs(anchors[1] - heightDp)) PlaceSheetState.PEEK else PlaceSheetState.FULL
    }

    // --- Map camera -------------------------------------------------------------------------------------------

    /**
     * Bottom padding (dp) for the MapLibre camera so its target (the pin) sits in the centre of the map that is
     * still visible above the card, not the centre of the screen. The card's resting height is the padding.
     */
    fun cameraBottomPaddingDp(sheetDp: Float, screenHeightDp: Float): Float = sheetDp.coerceIn(0f, screenHeightDp - 1f)

    /**
     * Screen y (dp from the top) where MapLibre draws the camera target with these paddings (the map fills the
     * screen, edge to edge): the middle of the unpadded band.
     */
    fun targetScreenY(screenHeightDp: Float, bottomPaddingDp: Float, topPaddingDp: Float = 0f): Float =
        topPaddingDp + (screenHeightDp - topPaddingDp - bottomPaddingDp) / 2f

    /** Centre (dp from the top) of the map left visible between the status bar ([topDp]) and a card of [sheetDp]. */
    fun visibleMapCentreY(screenHeightDp: Float, sheetDp: Float, topDp: Float = 0f): Float = (topDp + screenHeightDp - sheetDp) / 2f
}

/** How each of the five header stars is drawn. */
enum class StarFill { FULL, PALE, EMPTY }

/**
 * The one line under the place name: "★★★★☆ 4.3 · 12 reviews · 450 m".
 * - 3+ reviews: the stars, the mean (one decimal) and the count.
 * - 1–2 reviews: the stars, "New" instead of the mean (docs/api.md: fewer than 3 reviews show "New"), and the count.
 * - none: grey stars and "New" (no count, no empty bars).
 * The distance is appended when known.
 */
data class RatingLine(val stars: List<StarFill>, val mean: String?, val parts: List<String>) {
    val text: String get() = (listOfNotNull(mean) + parts).joinToString(" · ")

    companion object {
        const val NEW_BELOW = 3

        fun stars(mean: Double?): List<StarFill> = (1..5).map { n ->
            val v = mean ?: 0.0
            when {
                v >= n - 0.25 -> StarFill.FULL
                v >= n - 0.75 -> StarFill.PALE
                else -> StarFill.EMPTY
            }
        }

        /** [decimal] formats the mean (locale-aware in the app); [countLabel] is e.g. "12 reviews" / "12 ulasan". */
        fun of(
            mean: Double?, count: Int, distance: String?, newLabel: String,
            countLabel: (Int) -> String, decimal: (Double) -> String = { String.format(java.util.Locale.US, "%.1f", it) },
        ): RatingLine {
            val tail = listOfNotNull(distance)
            return when {
                count <= 0 || mean == null -> RatingLine(List(5) { StarFill.EMPTY }, null, listOf(newLabel) + tail)
                count < NEW_BELOW -> RatingLine(stars(mean), null, listOf(newLabel, countLabel(count)) + tail)
                else -> RatingLine(stars(mean), decimal(mean), listOf(countLabel(count)) + tail)
            }
        }
    }
}
