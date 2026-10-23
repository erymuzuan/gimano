package my.gimano.app.core.nav

import my.gimano.app.core.format.Formatters
import my.gimano.app.core.format.Lang
import my.gimano.app.data.model.Maneuver

/** Icon family for a maneuver; the UI maps these to vector icons. */
enum class ManeuverIcon {
    DEPART, ARRIVE, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT, ROUNDABOUT, MERGE, RAMP_LEFT, RAMP_RIGHT, FORK_LEFT, FORK_RIGHT, FERRY,
}

/**
 * Builds the on-screen and spoken text for maneuvers in English or Bahasa Melayu.
 * The server's `instruction` is used only as a fallback for unknown types, so both
 * languages read consistently regardless of what the routing engine returns.
 */
object ManeuverFormatter {

    fun icon(type: String): ManeuverIcon = when (type) {
        "depart" -> ManeuverIcon.DEPART
        "arrive" -> ManeuverIcon.ARRIVE
        "straight" -> ManeuverIcon.STRAIGHT
        "slight_left" -> ManeuverIcon.SLIGHT_LEFT
        "left" -> ManeuverIcon.LEFT
        "sharp_left" -> ManeuverIcon.SHARP_LEFT
        "slight_right" -> ManeuverIcon.SLIGHT_RIGHT
        "right" -> ManeuverIcon.RIGHT
        "sharp_right" -> ManeuverIcon.SHARP_RIGHT
        "uturn_left" -> ManeuverIcon.UTURN_LEFT
        "uturn_right" -> ManeuverIcon.UTURN_RIGHT
        "roundabout_enter", "roundabout_exit" -> ManeuverIcon.ROUNDABOUT
        "merge" -> ManeuverIcon.MERGE
        "ramp_left", "exit_left" -> ManeuverIcon.RAMP_LEFT
        "ramp_right", "exit_right" -> ManeuverIcon.RAMP_RIGHT
        "keep_left" -> ManeuverIcon.FORK_LEFT
        "keep_right" -> ManeuverIcon.FORK_RIGHT
        "ferry" -> ManeuverIcon.FERRY
        else -> ManeuverIcon.STRAIGHT
    }

    /** Bare action phrase, e.g. "Turn left" / "Belok kiri". */
    fun action(type: String, lang: Lang, exitNumber: String? = null): String? {
        val en = lang == Lang.EN
        return when (type) {
            "depart" -> if (en) "Head out" else "Mula bergerak"
            "arrive" -> if (en) "You have arrived" else "Anda telah tiba"
            "straight" -> if (en) "Continue straight" else "Terus lurus"
            "slight_left" -> if (en) "Bear left" else "Belok sedikit ke kiri"
            "left" -> if (en) "Turn left" else "Belok kiri"
            "sharp_left" -> if (en) "Sharp left" else "Belok tajam ke kiri"
            "slight_right" -> if (en) "Bear right" else "Belok sedikit ke kanan"
            "right" -> if (en) "Turn right" else "Belok kanan"
            "sharp_right" -> if (en) "Sharp right" else "Belok tajam ke kanan"
            "uturn_left", "uturn_right" -> if (en) "Make a U-turn" else "Buat pusingan U"
            "roundabout_enter" -> when {
                exitNumber != null -> if (en) "At the roundabout, take exit $exitNumber" else "Di bulatan, ambil susur keluar $exitNumber"
                else -> if (en) "Enter the roundabout" else "Masuk ke bulatan"
            }
            "roundabout_exit" -> if (en) "Exit the roundabout" else "Keluar dari bulatan"
            "merge" -> if (en) "Merge" else "Gabung"
            "ramp_left" -> if (en) "Take the ramp on the left" else "Ambil susur di sebelah kiri"
            "ramp_right" -> if (en) "Take the ramp on the right" else "Ambil susur di sebelah kanan"
            "exit_left" -> exitPhrase(exitNumber, en, left = true)
            "exit_right" -> exitPhrase(exitNumber, en, left = false)
            "keep_left" -> if (en) "Keep left" else "Kekal di kiri"
            "keep_right" -> if (en) "Keep right" else "Kekal di kanan"
            "ferry" -> if (en) "Take the ferry" else "Naik feri"
            else -> null
        }
    }

    private fun exitPhrase(exit: String?, en: Boolean, left: Boolean): String {
        val side = if (en) (if (left) "left" else "right") else (if (left) "kiri" else "kanan")
        return if (exit != null) {
            if (en) "Take exit $exit on the $side" else "Ambil susur keluar $exit di sebelah $side"
        } else {
            if (en) "Take the exit on the $side" else "Ambil susur keluar di sebelah $side"
        }
    }

    /** Road name shown in the banner: "Jalan Ampang" or "E11 LDP". */
    fun road(m: Maneuver): String? {
        val street = m.streetName?.takeIf { it.isNotBlank() }
        val ref = m.roadRef?.takeIf { it.isNotBlank() }
        return when {
            street != null && ref != null && !street.contains(ref) -> "$ref $street"
            street != null -> street
            else -> ref
        }
    }

    /** Mode-specific wording for walking and cycling ("Start walking", "Keep cycling straight"). */
    private fun modeAction(type: String, lang: Lang, mode: String): String? {
        val en = lang == Lang.EN
        return when {
            mode == "walk" && type == "depart" -> if (en) "Start walking" else "Mula berjalan"
            mode == "walk" && type == "straight" -> if (en) "Keep walking straight" else "Terus berjalan lurus"
            mode == "bicycle" && type == "depart" -> if (en) "Start cycling" else "Mula berbasikal"
            mode == "bicycle" && type == "straight" -> if (en) "Keep cycling straight" else "Terus berbasikal lurus"
            else -> null
        }
    }

    /** Full instruction, e.g. "Turn left onto Jalan Ampang" / "Belok kiri ke Jalan Ampang". */
    fun instruction(m: Maneuver, lang: Lang, mode: String = "car"): String {
        modeAction(m.type, lang, mode)?.let { a ->
            val road = road(m) ?: return a
            return if (lang == Lang.EN) "$a on $road" else "$a di $road"
        }
        val action = action(m.type, lang, m.exitNumber) ?: return m.instruction.ifBlank { road(m) ?: "" }
        val road = road(m) ?: return action
        return when (m.type) {
            "arrive" -> action
            "depart" -> if (lang == Lang.EN) "Head out on $road" else "Mula di $road"
            "straight", "keep_left", "keep_right" -> if (lang == Lang.EN) "$action on $road" else "$action di $road"
            "exit_left", "exit_right", "ramp_left", "ramp_right" -> if (lang == Lang.EN) "$action towards $road" else "$action menuju $road"
            else -> if (lang == Lang.EN) "$action onto $road" else "$action ke $road"
        }
    }

    /** Spoken prompt before a maneuver: "In 500 metres, turn left onto Jalan Ampang". */
    fun spokenApproach(m: Maneuver, distanceM: Double, lang: Lang, mode: String = "car"): String {
        val instr = instruction(m, lang, mode)
        val lowered = instr.replaceFirstChar { it.lowercase() }
        val dist = spokenDistance(distanceM, lang)
        return if (lang == Lang.EN) "In $dist, $lowered" else "Dalam $dist, $lowered"
    }

    /** Spoken prompt at the maneuver itself. */
    fun spokenNow(m: Maneuver, lang: Lang, mode: String = "car"): String = instruction(m, lang, mode)

    /** "You have arrived at Kedai Mak" / "Anda telah tiba di Kedai Mak". */
    fun arrivedAtStop(name: String, lang: Lang): String = if (lang == Lang.EN) "You have arrived at $name" else "Anda telah tiba di $name"

    fun spokenDistance(distanceM: Double, lang: Lang): String {
        val en = lang == Lang.EN
        return if (distanceM >= 950) {
            val km = Formatters.distance(distanceM).removeSuffix(" km")
            val kmWord = if (en) (if (km == "1.0" || km == "1") "kilometre" else "kilometres") else "kilometer"
            "${km.removeSuffix(".0")} $kmWord"
        } else {
            val m = when {
                distanceM < 100 -> (distanceM / 10).toInt() * 10
                else -> ((distanceM + 25) / 50).toInt() * 50
            }.coerceAtLeast(10)
            if (en) "$m metres" else "$m meter"
        }
    }
}
