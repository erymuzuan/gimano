package my.gimano.app.core.places

/**
 * Builds/parses the OSM `opening_hours` subset the editor produces:
 *  "24/7" · "Mo-Fr 08:00-18:00; Sa 08:00-13:00" · "Mo-Th 08:00-12:00,14:00-17:00; Fr 08:00-12:00,14:30-17:00"
 * Days with no ranges are closed (OSM's default), so they are simply left out.
 */
object OpeningHours {
    val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    /** "HH:mm-HH:mm"; end may be 24:00, and an end before the start means past midnight (OSM allows it). */
    data class Range(val from: String, val to: String) {
        init {
            require(TIME.matches(from) && (TIME.matches(to) || to == "24:00")) { "bad time range $from-$to" }
            require(from != to) { "empty range $from-$to" }
        }
        override fun toString() = "$from-$to"
    }

    private val TIME = Regex("([01]\\d|2[0-3]):[0-5]\\d")

    /** @param week day code → ranges (empty/missing = closed). */
    fun build(week: Map<String, List<Range>>, open247: Boolean = false): String {
        if (open247) return "24/7"
        if (DAYS.all { week[it].orEmpty().singleOrNull() == Range("00:00", "24:00") }) return "24/7"
        val rules = ArrayList<String>()
        var i = 0
        while (i < DAYS.size) {
            val ranges = week[DAYS[i]].orEmpty().sortedBy { it.from }
            if (ranges.isEmpty()) { i++; continue }
            var j = i
            while (j + 1 < DAYS.size && week[DAYS[j + 1]].orEmpty().sortedBy { it.from } == ranges) j++
            val days = when (j - i) { 0 -> DAYS[i]; 1 -> "${DAYS[i]},${DAYS[j]}"; else -> "${DAYS[i]}-${DAYS[j]}" }
            rules += "$days ${ranges.joinToString(",")}"
            i = j + 1
        }
        return rules.joinToString("; ")
    }

    /** Parses what [build] produces (plus "off" rules); returns null for syntax it doesn't understand. */
    fun parse(s: String): Pair<Map<String, List<Range>>, Boolean>? {
        val t = s.trim()
        if (t.isEmpty()) return emptyMap<String, List<Range>>() to false
        if (t == "24/7") return emptyMap<String, List<Range>>() to true
        val week = HashMap<String, List<Range>>()
        for (rule in t.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val sp = rule.indexOf(' ')
            if (sp < 0) return null
            val daysPart = rule.substring(0, sp)
            val timesPart = rule.substring(sp + 1).trim()
            val days = daysPart.split(',').flatMap { d ->
                if ('-' in d) {
                    val (a, b) = d.split('-')
                    val ia = DAYS.indexOf(a); val ib = DAYS.indexOf(b)
                    if (ia < 0 || ib < 0) return null
                    if (ia <= ib) DAYS.subList(ia, ib + 1) else DAYS.subList(ia, 7) + DAYS.subList(0, ib + 1)
                } else listOf(d.takeIf { it in DAYS } ?: return null)
            }
            val ranges = if (timesPart == "off" || timesPart == "closed") emptyList() else timesPart.split(',').map { r ->
                val parts = r.trim().split('-')
                if (parts.size != 2) return null
                runCatching { Range(parts[0], parts[1]) }.getOrNull() ?: return null
            }
            days.forEach { week[it] = ranges }
        }
        return week to false
    }
}

/** Photos are downsized on the device before upload: long edge ≤ 2048 px (docs/api.md). */
object PhotoSize {
    const val MAX_EDGE = 2048
    fun target(w: Int, h: Int, max: Int = MAX_EDGE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val f = max.toDouble() / long
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }

    /** Power-of-two decode sampling so a 48 MP photo never fully lands in memory. */
    fun sampleSize(w: Int, h: Int, max: Int = MAX_EDGE): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= max) s *= 2
        return s
    }
}

/** Rating summary shown on the place card. */
data class RatingSummary(val mean: Double?, val count: Int, val distribution: Map<Int, Int>) {
    /** Places with < 3 reviews show "New" (docs/api.md). */
    val isNew get() = count < 3

    companion object {
        fun of(ratings: List<Int>, serverMean: Double? = null, serverCount: Int? = null): RatingSummary {
            val dist = (1..5).associateWith { n -> ratings.count { it == n } }
            val count = serverCount ?: ratings.size
            val mean = serverMean ?: ratings.takeIf { it.isNotEmpty() }?.average()
            return RatingSummary(mean, count, dist)
        }
    }
}

object Verification {
    const val NEARBY_M = 500.0

    /** "Is this place here?" is asked only of people near an unverified community place. */
    fun canVerify(isCommunity: Boolean, status: String?, distanceM: Double?): Boolean =
        isCommunity && status != "published" && distanceM != null && distanceM <= NEARBY_M
}
