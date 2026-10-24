package my.gimano.app.core.routes

import my.gimano.app.core.geo.GeoPoint

/**
 * Douglas–Peucker line simplification (docs/events.md §5.4), a port of route-core `simplify.rs`: distances on a local
 * equirectangular projection around each chord, in metres, against the chord **segment** (so every dropped point lies
 * within the tolerance of the simplified line, closed loops included). The first and last points are always kept.
 * Iterative, so 50 000-point routes cannot overflow the stack.
 */
object RouteSimplify {
    /** Tolerance writers apply before signing an event. */
    const val EVENT_TOLERANCE_M = 5.0
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Simplified copy of [points]; fewer than 3 points come back unchanged. */
    fun simplify(points: List<GeoPoint>, toleranceM: Double = EVENT_TOLERANCE_M): List<GeoPoint> {
        val n = points.size
        if (n < 3 || !toleranceM.isFinite() || toleranceM <= 0.0) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue
            val chord = Chord(points[first], points[last])
            var index = first
            var max = -1.0
            for (i in first + 1 until last) {
                val d = chord.distanceM(points[i])
                if (d > max) { max = d; index = i }
            }
            if (max > toleranceM) {
                keep[index] = true
                stack.addLast(first to index)
                stack.addLast(index to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** A chord projected around its start point: x east, y north, metres. */
    private class Chord(private val origin: GeoPoint, b: GeoPoint) {
        private val ky = Math.toRadians(EARTH_RADIUS_M)
        private val kx = ky * Math.cos(Math.toRadians((origin.lat + b.lat) / 2.0))
        private val bx: Double
        private val by: Double
        private val len2: Double

        init {
            val (x, y) = project(b)
            bx = x; by = y
            len2 = bx * bx + by * by
        }

        private fun project(p: GeoPoint): Pair<Double, Double> {
            var dlng = p.lng - origin.lng
            // Routes never span half the globe; this only keeps antimeridian crossings sane.
            if (dlng > 180.0) dlng -= 360.0 else if (dlng < -180.0) dlng += 360.0
            return dlng * kx to (p.lat - origin.lat) * ky
        }

        fun distanceM(p: GeoPoint): Double {
            val (px, py) = project(p)
            val t = if (len2 > 0.0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
            val dx = px - t * bx
            val dy = py - t * by
            return Math.sqrt(dx * dx + dy * dy)
        }
    }
}
