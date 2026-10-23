package my.gimano.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R

/** Gimano "Cobalt" design tokens (docs/design-tokens.md). */
@Immutable
data class GimanoColors(
    val brand: Color,
    val brandOn: Color,
    val brandInk: Color,
    val brandTint: Color,
    val brandMist: Color,
    val report: Color,
    val reportOn: Color,
    val reportTint: Color,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
    val navBanner: Color = Color(0xFF0A1F4D),
    val navBannerOn: Color = Color.White,
    val routeActive: Color,
    val routeAlt: Color,
    val traffic1: Color = Color(0xFFF5C518),
    val traffic2: Color = Color(0xFFFFB020),
    val traffic3: Color = Color(0xFFE5484D),
    val traffic4: Color = Color(0xFF7F1D1D),
    val speeding: Color = Color(0xFFE5484D),
    val mapBackground: Color,
    val isDark: Boolean,
)

val LightTokens = GimanoColors(
    brand = Color(0xFF0047AB), brandOn = Color.White, brandInk = Color(0xFF0A2E6E), brandTint = Color(0xFF8FB0E8), brandMist = Color(0xFFE3ECFA),
    report = Color(0xFFFF6B4A), reportOn = Color.White, reportTint = Color(0xFFFFD3C7),
    background = Color(0xFFF5F8FD), surface = Color.White, surfaceAlt = Color(0xFFEEF3FC), outline = Color(0xFFC9D6EE),
    text = Color(0xFF0B1B3A), textMuted = Color(0xFF3A4A66),
    routeActive = Color(0xFF0047AB), routeAlt = Color(0xFFA9C2EE), mapBackground = Color(0xFFEEF2F8),
    isDark = false,
)

val DarkTokens = GimanoColors(
    brand = Color(0xFF5B8DEF), brandOn = Color(0xFF071430), brandInk = Color(0xFFBFD3F7), brandTint = Color(0xFF3A5A99), brandMist = Color(0xFF16233D),
    report = Color(0xFFFF8266), reportOn = Color(0xFF2A0A03), reportTint = Color(0xFF7A2E1C),
    background = Color(0xFF0B1220), surface = Color(0xFF121B2E), surfaceAlt = Color(0xFF1A2540), outline = Color(0xFF2A3A5C),
    text = Color(0xFFEEF3FC), textMuted = Color(0xFFA9B6CF),
    routeActive = Color(0xFF5B8DEF), routeAlt = Color(0xFF2F4777), mapBackground = Color(0xFF0E1626),
    isDark = true,
)

/** Duotone palette for a report type: tile background, icon fill, icon stroke. */
@Immutable
data class Duotone(val tile: Color, val fill: Color, val stroke: Color)

object ReportColors {
    private val table = mapOf(
        "traffic" to Duotone(Color(0xFFFFEBD6), Color(0xFFFFB870), Color(0xFF8A4300)),
        "police" to Duotone(Color(0xFFE3ECFA), Color(0xFF8FB0E8), Color(0xFF0A2E6E)),
        "accident" to Duotone(Color(0xFFFFE2E2), Color(0xFFF5A3A3), Color(0xFF8B1A1A)),
        "hazard" to Duotone(Color(0xFFFFF1CC), Color(0xFFFFD466), Color(0xFF7A5200)),
        "closure" to Duotone(Color(0xFFECEEF1), Color(0xFFC3C8D0), Color(0xFF2F3640)),
        "blocked_lane" to Duotone(Color(0xFFEEE6FF), Color(0xFFC4B1F7), Color(0xFF4C2A9E)),
        "weather" to Duotone(Color(0xFFDDF4F2), Color(0xFF8EDCD3), Color(0xFF0E5A54)),
        "camera" to Duotone(Color(0xFFE3ECFA), Color(0xFF8FB0E8), Color(0xFF0A2E6E)),
        "map_issue" to Duotone(Color(0xFFE3ECFA), Color(0xFF8FB0E8), Color(0xFF0A2E6E)),
        "animal" to Duotone(Color(0xFFE8F5D6), Color(0xFFB7DC7F), Color(0xFF3F6212)),
    )

    fun duotone(type: String): Duotone = table[type] ?: table.getValue("hazard")

    /** Accent used for bars/borders (the stroke colour). */
    fun forType(type: String): Color = duotone(type).stroke
}

object Radius {
    val sm = 8.dp
    val md = 14.dp
    val lg = 20.dp
    val sheet = 28.dp
    val pill = 999.dp
    val reportFab = 28.dp
    val menuFab = 22.dp
    val search = 22.dp
    val tile = 24.dp
    val reportTile = 32.dp
}

object Space {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp
    val gutter = 16.dp
}

object Sizes {
    val roundFab = 64.dp
    val roundFabIcon = 30.dp
    val reportFab = 84.dp
    val reportFabIcon = 46.dp
    val searchField = 64.dp
    val searchIcon = 30.dp
    val shortcutTile = 76.dp
    val shortcutIcon = 42.dp
    val reportTile = 104.dp
    val reportTileIcon = 60.dp
    val speedometer = 72.dp
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun nunito(w: Int) = Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Nunito (SIL OFL), bundled as a variable font in res/font. */
val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800), nunito(900))

object GimanoType {
    val navDistance = TextStyle(fontFamily = Nunito, fontSize = 44.sp, fontWeight = FontWeight.Black)
    val etaClock = TextStyle(fontFamily = Nunito, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
    val title = TextStyle(fontFamily = Nunito, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    val body = TextStyle(fontFamily = Nunito, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontFamily = Nunito, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    val caption = TextStyle(fontFamily = Nunito, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val button = TextStyle(fontFamily = Nunito, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
}

val LocalGimanoColors = staticCompositionLocalOf { LightTokens }

object Gimano {
    val colors: GimanoColors @Composable get() = LocalGimanoColors.current
}

val PebbleShape = RoundedCornerShape(Radius.md)

@Composable
fun GimanoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = t.brand, onPrimary = t.brandOn, primaryContainer = t.brandMist, onPrimaryContainer = t.brandInk,
        secondary = t.report, onSecondary = t.reportOn,
        surface = t.surface, onSurface = t.text, surfaceVariant = t.surfaceAlt, onSurfaceVariant = t.textMuted,
        background = t.background, onBackground = t.text, outline = t.outline, outlineVariant = t.outline,
        surfaceContainer = t.surface, surfaceContainerLow = t.surface, surfaceContainerLowest = t.surface,
        surfaceContainerHigh = t.surface, surfaceContainerHighest = t.surfaceAlt,
        inverseSurface = t.navBanner, inverseOnSurface = Color.White, inversePrimary = t.brandTint,
    )
    val base = Typography()
    fun TextStyle.n() = copy(fontFamily = Nunito)
    val typography = Typography(
        displayLarge = base.displayLarge.n(), displayMedium = base.displayMedium.n(), displaySmall = base.displaySmall.n(),
        headlineLarge = base.headlineLarge.n(), headlineMedium = base.headlineMedium.n(), headlineSmall = base.headlineSmall.n(),
        titleLarge = GimanoType.title, titleMedium = base.titleMedium.n().copy(fontWeight = FontWeight.ExtraBold), titleSmall = base.titleSmall.n(),
        bodyLarge = GimanoType.body, bodyMedium = GimanoType.caption.copy(fontSize = 15.sp), bodySmall = GimanoType.caption,
        labelLarge = GimanoType.button, labelMedium = GimanoType.label.copy(fontSize = 13.sp), labelSmall = base.labelSmall.n(),
    )
    CompositionLocalProvider(LocalGimanoColors provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides GimanoType.body, content = content)
        }
    }
}
