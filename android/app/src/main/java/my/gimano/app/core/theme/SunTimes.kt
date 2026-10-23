package my.gimano.app.core.theme

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunrise/sunset on the device (no network), NOAA's simplified solar position algorithm.
 * Accurate to a couple of minutes, which is plenty for switching the map theme.
 */
object SunTimes {
    data class Day(val sunrise: Instant?, val sunset: Instant?)

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    fun of(date: LocalDate, lat: Double, lng: Double): Day {
        val n = date.toEpochDay() + 2440588 - 2451545.0 + 0.0008 // days since J2000 (Julian day 2451545)
        val jStar = n - lng / 360.0
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + c + 180 + 102.9372).mod(360.0)
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val decl = asin(sin(rad(lambda)) * sin(rad(23.44)))
        val cosH = (sin(rad(-0.833)) - sin(rad(lat)) * sin(decl)) / (cos(rad(lat)) * cos(decl))
        if (cosH < -1 || cosH > 1) return Day(null, null) // polar day/night
        val h = deg(acos(cosH))
        fun toInstant(jd: Double): Instant = Instant.ofEpochMilli(((jd - 2440587.5) * 86_400_000).toLong())
        return Day(toInstant(jTransit - h / 360.0), toInstant(jTransit + h / 360.0))
    }

    /** True when [now] is between sunset and the next sunrise at that place. */
    fun isNight(now: Instant, lat: Double, lng: Double): Boolean {
        val localDate = now.atOffset(ZoneOffset.ofTotalSeconds(((lng / 15.0) * 3600).toInt().coerceIn(-18 * 3600, 18 * 3600))).toLocalDate()
        val d = of(localDate, lat, lng)
        val rise = d.sunrise ?: return false
        val set = d.sunset ?: return false
        return now.isBefore(rise) || !now.isBefore(set)
    }

}

/** Theme setting (Settings → Theme). Light is the default even on a dark-mode phone. */
enum class ThemeMode(val wire: String) {
    LIGHT("light"), DARK("dark"), SYSTEM("system"), AUTO("auto");

    companion object {
        fun from(s: String?): ThemeMode = entries.firstOrNull { it.wire == s } ?: LIGHT

        /** Resolves to dark/light. AUTO uses sunset/sunrise at [lat]/[lng] (Kuala Lumpur if unknown). */
        fun isDark(mode: ThemeMode, systemDark: Boolean, now: Instant, lat: Double?, lng: Double?): Boolean = when (mode) {
            LIGHT -> false
            DARK -> true
            SYSTEM -> systemDark
            AUTO -> SunTimes.isNight(now, lat ?: 3.139, lng ?: 101.687)
        }
    }
}
