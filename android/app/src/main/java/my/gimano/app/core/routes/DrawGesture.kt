package my.gimano.app.core.routes

/**
 * "Lukis" touch classification, kept free of Android types so it is unit-tested: one finger draws, two fingers move
 * and zoom the map.
 *
 *  - First finger down → a stroke starts ([Phase.DRAWING]); moves add a point every [stepPx].
 *  - A second finger lands → the stroke ends at once and every later touch of this gesture goes to the map
 *    ([Phase.MAP]) until all fingers are up. The stroke is kept when it was already a real line (at least
 *    [minKeepPoints] sampled points), otherwise dropped, so a pinch never leaves a zig-zag or a stray dot. The
 *    second finger's position is never added to the line.
 *  - Last finger up while drawing → the stroke ends with the lift point.
 */
class DrawGesture(private val stepPx: Float, private val minKeepPoints: Int = 3) {
    enum class Phase { IDLE, DRAWING, MAP }

    data class Pt(val x: Float, val y: Float)

    /** What ended a stroke: [points] to hand to the builder (null = nothing to keep). */
    data class End(val points: List<Pt>?)

    var phase: Phase = Phase.IDLE
        private set
    private val pts = ArrayList<Pt>()
    val points: List<Pt> get() = pts

    fun down(x: Float, y: Float) {
        pts.clear()
        pts += Pt(x, y)
        phase = Phase.DRAWING
    }

    /** One-finger move while drawing; true when a point was added. Ignored outside [Phase.DRAWING]. */
    fun move(x: Float, y: Float, pointerCount: Int): Boolean {
        if (phase != Phase.DRAWING || pointerCount != 1) return false
        val last = pts.last()
        if ((x - last.x) * (x - last.x) + (y - last.y) * (y - last.y) < stepPx * stepPx) return false
        pts += Pt(x, y)
        return true
    }

    /** A second finger landed. Returns how the stroke ended when one was in progress, else null. */
    fun secondFinger(): End? {
        val was = phase
        phase = Phase.MAP
        if (was != Phase.DRAWING) return null
        val keep = if (pts.size >= minKeepPoints) pts.toList() else null
        pts.clear()
        return End(keep)
    }

    /** Last finger up. Returns the stroke end when drawing, null when the gesture belonged to the map (or nothing). */
    fun up(x: Float, y: Float): End? {
        val was = phase
        phase = Phase.IDLE
        if (was != Phase.DRAWING) return null
        if (pts.last() != Pt(x, y)) pts += Pt(x, y)
        val line = if (pts.size >= 2) pts.toList() else null
        pts.clear()
        return End(line)
    }

    fun cancel() {
        phase = Phase.IDLE
        pts.clear()
    }
}
