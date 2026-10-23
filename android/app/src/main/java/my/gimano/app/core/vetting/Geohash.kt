package my.gimano.app.core.vetting

/** Standard geohash (base32 "0123456789bcdefghjkmnpqrstuvwxyz"). Precision 6 ≈ 1.2 × 0.6 km. */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lng: Double, precision: Int = 6): String {
        var latLo = -90.0; var latHi = 90.0; var lngLo = -180.0; var lngHi = 180.0
        val sb = StringBuilder()
        var even = true; var bit = 0; var ch = 0
        while (sb.length < precision) {
            if (even) {
                val mid = (lngLo + lngHi) / 2
                if (lng >= mid) { ch = ch or (1 shl (4 - bit)); lngLo = mid } else lngHi = mid
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latLo = mid } else latHi = mid
            }
            even = !even
            if (bit < 4) bit++ else { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    /** Centre of a geohash cell (lat, lng). */
    fun decodeCenter(hash: String): Pair<Double, Double> {
        var latLo = -90.0; var latHi = 90.0; var lngLo = -180.0; var lngHi = 180.0
        var even = true
        for (c in hash) {
            val v = BASE32.indexOf(c).also { require(it >= 0) { "bad geohash" } }
            for (b in 4 downTo 0) {
                val on = (v shr b) and 1 == 1
                if (even) { val m = (lngLo + lngHi) / 2; if (on) lngLo = m else lngHi = m }
                else { val m = (latLo + latHi) / 2; if (on) latLo = m else latHi = m }
                even = !even
            }
        }
        return (latLo + latHi) / 2 to (lngLo + lngHi) / 2
    }
}
