package my.gimano.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import my.gimano.app.R

/** Localised labels for report types/subtypes, categories and server traffic phrases. */
object Labels {
    @StringRes
    fun reportType(type: String): Int = when (type) {
        "traffic" -> R.string.rt_traffic
        "police" -> R.string.rt_police
        "accident" -> R.string.rt_accident
        "hazard" -> R.string.rt_hazard
        "closure" -> R.string.rt_closure
        "blocked_lane" -> R.string.rt_blocked_lane
        "map_issue" -> R.string.rt_map_issue
        "weather" -> R.string.rt_weather
        "animal" -> R.string.rt_animal
        else -> R.string.rt_hazard
    }

    /** One-word labels for the report grid ("big icons, little text"). */
    @StringRes
    fun shortType(type: String): Int = when (type) {
        "traffic" -> R.string.rs_traffic
        "police" -> R.string.rs_police
        "accident" -> R.string.rs_accident
        "hazard" -> R.string.rs_hazard
        "closure" -> R.string.rs_closure
        "blocked_lane" -> R.string.rs_blocked_lane
        "map_issue" -> R.string.rs_map_issue
        "weather" -> R.string.rs_weather
        "animal" -> R.string.rs_animal
        else -> R.string.rs_hazard
    }

    val subtypes: Map<String, List<Pair<String, Int>>> = mapOf(
        "traffic" to listOf("moderate" to R.string.st_moderate, "heavy" to R.string.st_heavy, "standstill" to R.string.st_standstill),
        "police" to listOf("visible" to R.string.st_visible, "hidden" to R.string.st_hidden, "roadblock" to R.string.st_roadblock),
        "accident" to listOf("minor" to R.string.st_minor, "major" to R.string.st_major, "other_side" to R.string.st_other_side),
        "hazard" to listOf(
            "object_on_road" to R.string.st_object_on_road, "pothole" to R.string.st_pothole, "stopped_vehicle" to R.string.st_stopped_vehicle,
            "roadkill" to R.string.st_roadkill, "construction" to R.string.st_construction, "flood" to R.string.st_flood, "oil_spill" to R.string.st_oil_spill,
        ),
        "blocked_lane" to listOf("left" to R.string.st_lane_left, "center" to R.string.st_lane_center, "right" to R.string.st_lane_right),
        "weather" to listOf("heavy_rain" to R.string.st_heavy_rain, "fog" to R.string.st_fog, "flood" to R.string.st_flood, "haze" to R.string.st_haze),
        "animal" to listOf("on_road" to R.string.st_on_road, "on_shoulder" to R.string.st_on_shoulder),
    )

    @StringRes
    fun subtype(type: String, sub: String?): Int? = subtypes[type]?.firstOrNull { it.first == sub }?.second

    @Composable
    fun reportTitle(type: String, sub: String?): String {
        val t = stringResource(reportType(type))
        val s = subtype(type, sub)?.let { stringResource(it) }
        return if (s != null) "$t · $s" else t
    }

    @Composable
    fun category(c: String): String = when (c) {
        "fuel" -> stringResource(R.string.cat_fuel)
        "ev_charging" -> stringResource(R.string.cat_ev)
        "rest_area" -> stringResource(R.string.cat_rest_area)
        "parking" -> stringResource(R.string.cat_parking)
        "restaurant" -> stringResource(R.string.cat_food)
        "cafe" -> stringResource(R.string.cat_cafe)
        "fast_food" -> stringResource(R.string.cat_fast_food)
        "mosque" -> stringResource(R.string.cat_mosque)
        "surau" -> stringResource(R.string.cat_surau)
        "hospital" -> stringResource(R.string.cat_hospital)
        "clinic" -> stringResource(R.string.cat_clinic)
        "mall" -> stringResource(R.string.cat_mall)
        "supermarket" -> stringResource(R.string.cat_supermarket)
        "convenience" -> stringResource(R.string.cat_convenience)
        "pharmacy" -> stringResource(R.string.cat_pharmacy)
        "school" -> stringResource(R.string.cat_school)
        "hotel" -> stringResource(R.string.cat_hotel)
        "atm" -> stringResource(R.string.cat_atm)
        "bank" -> stringResource(R.string.cat_bank)
        "car_repair" -> stringResource(R.string.cat_car_repair)
        "car_wash" -> stringResource(R.string.cat_car_wash)
        "temple" -> stringResource(R.string.cat_temple)
        "church" -> stringResource(R.string.cat_church)
        "tourism" -> stringResource(R.string.cat_tourism)
        "transit_station" -> stringResource(R.string.cat_transit)
        "university" -> stringResource(R.string.cat_university)
        "government" -> stringResource(R.string.cat_government)
        "airport" -> stringResource(R.string.cat_airport)
        "other" -> stringResource(R.string.cat_other)
        "police" -> stringResource(R.string.rt_police)
        "toll_booth" -> stringResource(R.string.avoid_tolls)
        else -> c.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    @Composable
    fun traffic(desc: String?): String? = when (desc) {
        null -> null
        "Typical traffic" -> stringResource(R.string.traffic_typical)
        "Heavier than usual" -> stringResource(R.string.traffic_heavier)
        "Light traffic" -> stringResource(R.string.traffic_light)
        else -> desc
    }

    @Composable
    fun amenity(a: String): String = when (a) {
        "toilets" -> stringResource(R.string.am_toilets)
        "surau" -> stringResource(R.string.am_surau)
        "parking" -> stringResource(R.string.am_parking)
        "wifi" -> stringResource(R.string.am_wifi)
        "card_payment" -> stringResource(R.string.am_card_payment)
        "ewallet" -> stringResource(R.string.am_ewallet)
        "wheelchair" -> stringResource(R.string.am_wheelchair)
        "air_conditioning" -> stringResource(R.string.am_air_conditioning)
        "ev_charging" -> stringResource(R.string.am_ev_charging)
        "atm" -> stringResource(R.string.am_atm)
        "food" -> stringResource(R.string.am_food)
        "air_pump" -> stringResource(R.string.am_air_pump)
        else -> a.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** OSM day code (Mo…Su) → "Isn"/"Mon"… */
    @Composable
    fun day(code: String): String = when (code) {
        "Mo" -> stringResource(R.string.day_mo); "Tu" -> stringResource(R.string.day_tu); "We" -> stringResource(R.string.day_we)
        "Th" -> stringResource(R.string.day_th); "Fr" -> stringResource(R.string.day_fr); "Sa" -> stringResource(R.string.day_sa)
        "Su" -> stringResource(R.string.day_su); else -> code
    }

    /**
     * Next-tier requirement from the server ("placesAccepted:7", "accuracy:95", "accountAgeDays:40",
     * "noUpheldDisputes") as a readable phrase; unknown items are shown as sent.
     */
    @Composable
    fun tierNeed(item: String): String {
        val key = item.substringBefore(':').trim()
        val n = item.substringAfter(':', "").trim().filter { it.isDigit() }.toIntOrNull()
        return when {
            key.equals("placesAccepted", true) && n != null -> stringResource(R.string.tier_need_places, n)
            key.equals("accuracy", true) && n != null -> stringResource(R.string.tier_need_accuracy, n)
            (key.equals("accountAgeDays", true) || key.equals("accountAge", true)) && n != null -> stringResource(R.string.tier_need_age, n)
            key.startsWith("noUpheldDispute", true) || key.equals("disputes", true) -> stringResource(R.string.tier_need_disputes)
            else -> item
        }
    }
}
