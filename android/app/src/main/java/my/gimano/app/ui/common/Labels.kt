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
        "restaurant", "cafe", "fast_food" -> stringResource(R.string.cat_food)
        "mosque", "surau" -> stringResource(R.string.cat_mosque)
        "hospital", "clinic" -> stringResource(R.string.cat_hospital)
        "mall" -> stringResource(R.string.cat_mall)
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
}
