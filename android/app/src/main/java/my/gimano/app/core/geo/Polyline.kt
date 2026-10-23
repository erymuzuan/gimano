package my.gimano.app.core.geo

import kotlin.math.roundToLong

/**
 * Google encoded polyline algorithm. Gimano's API (like Valhalla) uses precision 6
 * (coordinates multiplied by 1e6), not Google's default 1e5.
 */
object Polyline {
    const val PRECISION_6 = 6

    fun decode(encoded: String, precision: Int = PRECISION_6): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lng = 0L
        val len = encoded.length
        while (index < len) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                require(index < len) { "Truncated polyline" }
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            result = 0L
            shift = 0
            do {
                require(index < len) { "Truncated polyline" }
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            out += GeoPoint(lat / factor, lng / factor)
        }
        return out
    }

    fun encode(points: List<GeoPoint>, precision: Int = PRECISION_6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lng = (p.lng * factor).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lng - prevLng, sb)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value + 63).toInt().toChar())
    }
}
