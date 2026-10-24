package my.gimano.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import my.gimano.app.core.nav.ManeuverIcon
import my.gimano.app.ui.theme.Gimano

/**
 * Gimano's own rounded duotone icons (docs/icons/gimano-icons.json → GimanoIconPaths).
 * Stroke 2 (1.9 for big icons), round caps/joins, closed shapes filled with [fill] at 60 %.
 */
object Duotone {
    private val cache = HashMap<String, ImageVector>()

    fun vector(name: String, stroke: Color, fill: Color, strokeWidth: Float = 2f): ImageVector {
        val key = "$name|${stroke.value}|${fill.value}|$strokeWidth"
        return cache.getOrPut(key) {
            val p = GimanoIconPaths.all[name] ?: GimanoIconPaths.all.getValue("hazard")
            ImageVector.Builder(name = "gi_$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
                if (p.fill.isNotEmpty()) addPath(addPathNodes(p.fill), fill = SolidColor(fill), fillAlpha = 0.6f)
                addPath(
                    addPathNodes(p.stroke), stroke = SolidColor(stroke), strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }.build()
        }
    }
}

/**
 * Flat two-tone icons of the home panel (docs/icons/manotu-twotone-icons.json → TwoToneIconPaths):
 * the base shape in a pale tint, the detail (even-odd) in the ink. No strokes.
 */
object TwoTone {
    private val cache = HashMap<String, ImageVector>()

    fun vector(name: String, base: Color, detail: Color): ImageVector {
        val key = "$name|${base.value}|${detail.value}"
        return cache.getOrPut(key) {
            val p = TwoToneIconPaths.all[name] ?: PlaceTwoToneIconPaths.all[name] ?: NavTwoToneIconPaths.all[name] ?: TwoToneIconPaths.all.getValue("more")
            ImageVector.Builder(name = "tt_$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
                addPath(addPathNodes(p.base), fill = SolidColor(base))
                addPath(addPathNodes(p.detail), fill = SolidColor(detail), fillAlpha = p.detailAlpha, pathFillType = PathFillType.EvenOdd)
            }.build()
        }
    }
}

/** Pale cobalt (base) + cobalt (detail), gold for saved places, or muted grey for disabled actions. */
enum class TwoTonePalette { COBALT, GOLD, MUTED }

/** Draws a flat two-tone icon in the given palette for the current theme. */
@Composable
fun TwoToneIcon(name: String, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 26.dp, palette: TwoTonePalette = TwoTonePalette.COBALT) {
    val c = Gimano.colors
    val (base, detail) = when (palette) {
        TwoTonePalette.COBALT -> c.brandTint to c.brand
        TwoTonePalette.GOLD -> if (c.isDark) Color(0xFFB8902E) to Color(0xFF3D2A00) else Color(0xFFFFD466) to Color(0xFF7A5200)
        TwoTonePalette.MUTED -> c.outline to c.textMuted.copy(alpha = 0.6f)
    }
    val v = remember(name, base, detail) { TwoTone.vector(name, base, detail) }
    Image(rememberVectorPainter(v), contentDescription, modifier.size(size))
}

/** Draws a Gimano duotone icon. Defaults to the cobalt ink/tint of the current theme. */
@Composable
fun GIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    stroke: Color = Gimano.colors.brandInk,
    fill: Color = Gimano.colors.brandTint,
) {
    val sw = if (size >= 60.dp) 1.9f else 2f
    val v = remember(name, stroke, fill, sw) { Duotone.vector(name, stroke, fill, sw) }
    Image(rememberVectorPainter(v), contentDescription, modifier.size(size))
}

object GIcons {
    val reportTypes = listOf("traffic", "police", "accident", "hazard", "closure", "blocked_lane", "map_issue", "weather", "animal")

    /** Report type → icon name in the Gimano set. */
    fun report(type: String): String = when (type) {
        "traffic" -> "traffic"
        "police" -> "police"
        "accident" -> "accident"
        "hazard" -> "hazard"
        "closure" -> "closure"
        "blocked_lane" -> "lane"
        "map_issue" -> "map"
        "weather" -> "flood"
        "animal" -> "animal"
        "camera" -> "camera"
        else -> "hazard"
    }

    /** POI category → Gimano icon name, or null when the set has none (Material fallback). */
    fun categoryName(c: String): String? = when (c) {
        "fuel" -> "fuel"
        "ev_charging" -> "ev"
        "toll_booth" -> "toll"
        "parking" -> "parking"
        "restaurant", "cafe", "fast_food" -> "food"
        "mosque", "surau" -> "surau"
        "police" -> "police"
        else -> null
    }

    fun categoryFallback(c: String): ImageVector = when (c) {
        "rest_area" -> Icons.Filled.Deck
        "mall" -> Icons.Filled.LocalMall
        "supermarket" -> Icons.Filled.LocalGroceryStore
        "convenience" -> Icons.Filled.Store
        "temple" -> Icons.Filled.TempleHindu
        "church" -> Icons.Filled.Church
        "hospital" -> Icons.Filled.LocalHospital
        "clinic" -> Icons.Filled.MedicalServices
        "pharmacy" -> Icons.Filled.LocalPharmacy
        "school", "university" -> Icons.Filled.School
        "hotel" -> Icons.Filled.Hotel
        "atm" -> Icons.Filled.LocalAtm
        "bank", "government" -> Icons.Filled.AccountBalance
        "car_repair" -> Icons.Filled.CarRepair
        "car_wash" -> Icons.Filled.LocalCarWash
        "tourism" -> Icons.Filled.Landscape
        "transit_station" -> Icons.Filled.Train
        "airport" -> Icons.Filled.LocalAirport
        else -> Icons.Filled.Place
    }

    /** Turn arrows are not part of the Gimano set; Material Symbols are used for these. */
    fun maneuver(icon: ManeuverIcon): ImageVector = when (icon) {
        ManeuverIcon.DEPART -> Icons.Filled.Navigation
        ManeuverIcon.ARRIVE -> Icons.Filled.SportsScore
        ManeuverIcon.STRAIGHT -> Icons.Filled.Straight
        ManeuverIcon.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
        ManeuverIcon.LEFT -> Icons.Filled.TurnLeft
        ManeuverIcon.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
        ManeuverIcon.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
        ManeuverIcon.RIGHT -> Icons.Filled.TurnRight
        ManeuverIcon.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
        ManeuverIcon.UTURN_LEFT -> Icons.Filled.UTurnLeft
        ManeuverIcon.UTURN_RIGHT -> Icons.Filled.UTurnRight
        ManeuverIcon.ROUNDABOUT -> Icons.Filled.RoundaboutLeft
        ManeuverIcon.MERGE -> Icons.Filled.Merge
        ManeuverIcon.RAMP_LEFT -> Icons.Filled.RampLeft
        ManeuverIcon.RAMP_RIGHT -> Icons.Filled.RampRight
        ManeuverIcon.FORK_LEFT -> Icons.Filled.ForkLeft
        ManeuverIcon.FORK_RIGHT -> Icons.Filled.ForkRight
        ManeuverIcon.FERRY -> Icons.Filled.DirectionsBoat
    }
}

/** Category glyph: Gimano duotone where available, Material fallback tinted ink otherwise. */
@Composable
fun CategoryGlyph(category: String, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val name = GIcons.categoryName(category)
    if (name != null) GIcon(name, contentDescription, modifier, size)
    else Icon(GIcons.categoryFallback(category), contentDescription, modifier.size(size), tint = Gimano.colors.brandInk)
}
