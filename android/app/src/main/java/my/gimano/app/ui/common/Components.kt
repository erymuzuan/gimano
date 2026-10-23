package my.gimano.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.data.settings.SoundMode
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import my.gimano.app.ui.theme.ReportColors
import my.gimano.app.ui.theme.Sizes

private val FabShadow = Color(0x290B1B3A) // rgba(11,27,58,.16)

/** Floating surface with the Cobalt FAB shadow (0 3 10 rgba(11,27,58,.16)). */
@Composable
fun FloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    color: Color = Gimano.colors.surface,
    shadowColor: Color = FabShadow,
    elevation: Dp = 8.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 64 dp round map FAB with a 30 dp duotone icon. */
@Composable
fun RoundFab(icon: String, contentDescription: String, modifier: Modifier = Modifier, size: Dp = Sizes.roundFab, onClick: () -> Unit) {
    FloatingSurface(modifier.size(size).semantics { this.contentDescription = contentDescription }, CircleShape, onClick = onClick) {
        GIcon(icon, null, size = size * 0.47f)
    }
}

@Composable
fun RoundFab(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, size: Dp = Sizes.roundFab, tint: Color = Gimano.colors.brand, onClick: () -> Unit) {
    FloatingSurface(modifier.size(size), CircleShape, onClick = onClick) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.47f))
    }
}

/** Sound FAB: the "sound" glyph, with a slash for off and a small dot for alerts-only. */
@Composable
fun SoundFab(mode: SoundMode, modifier: Modifier = Modifier, size: Dp = Sizes.roundFab, onClick: () -> Unit) {
    val c = Gimano.colors
    val label = stringResource(when (mode) { SoundMode.ON -> R.string.sound_on; SoundMode.ALERTS_ONLY -> R.string.sound_alerts; SoundMode.OFF -> R.string.sound_off })
    FloatingSurface(modifier.size(size).semantics { contentDescription = label }, CircleShape, onClick = onClick) {
        Box(Modifier.size(size * 0.47f)) {
            GIcon("sound", null, size = size * 0.47f)
            if (mode == SoundMode.OFF) {
                Canvas(Modifier.matchParentSize()) {
                    drawLine(c.report, Offset(this.size.width * 0.04f, this.size.height * 0.04f), Offset(this.size.width * 0.96f, this.size.height * 0.96f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            if (mode == SoundMode.ALERTS_ONLY) {
                Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(c.report))
            }
        }
    }
}

/** 64 dp menu button (rounded square, radius 22). */
@Composable
fun MenuFab(contentDescription: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FloatingSurface(modifier.size(Sizes.roundFab).semantics { this.contentDescription = contentDescription }, RoundedCornerShape(Radius.menuFab), onClick = onClick) {
        GIcon("menu", null, size = Sizes.roundFabIcon)
    }
}

/** The big coral report button: 84 dp, radius 28, 46 dp duotone "report" glyph. */
@Composable
fun ReportFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Gimano.colors
    val label = stringResource(R.string.report)
    FloatingSurface(
        modifier.size(Sizes.reportFab).semantics { contentDescription = label }, RoundedCornerShape(Radius.reportFab),
        color = c.report, shadowColor = Color(0x73FF6B4A), elevation = 12.dp, onClick = onClick,
    ) {
        GIcon("report", null, size = Sizes.reportFabIcon, stroke = c.reportOn, fill = c.reportOn)
    }
}

/** 72 dp round speedometer; turns red above the speed limit (when one is known). */
@Composable
fun Speedometer(speedKmh: Int, limitKmh: Int?, modifier: Modifier = Modifier) {
    val c = Gimano.colors
    val speeding = limitKmh != null && speedKmh > limitKmh
    FloatingSurface(modifier.size(Sizes.speedometer), CircleShape, color = if (speeding) c.speeding else c.surface) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$speedKmh", fontSize = 26.sp, fontWeight = FontWeight.Black, color = if (speeding) Color.White else c.text, lineHeight = 26.sp)
            Text(stringResource(R.string.km_h), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = if (speeding) Color.White else c.textMuted, lineHeight = 12.sp)
        }
    }
}

/** Report-type tile: pale tile + duotone icon in the type's palette. */
@Composable
fun ReportTile(type: String, size: Dp, modifier: Modifier = Modifier, iconSize: Dp = size * 0.58f, radius: Dp = size * 0.31f) {
    val d = ReportColors.duotone(type)
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(d.tile), contentAlignment = Alignment.Center) {
        GIcon(GIcons.report(type), null, size = iconSize, stroke = d.stroke, fill = d.fill)
    }
}

@Composable
fun CategoryBadge(category: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Gimano.colors.brandMist), contentAlignment = Alignment.Center) {
        CategoryGlyph(category, size * 0.58f)
    }
}

/** Shortcut tile for the home grid: 76 dp mist tile, 42 dp icon, one-word label. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShortcutTile(icon: String, label: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Gimano.colors
    Column(
        modifier.clip(RoundedCornerShape(Radius.md))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "edit" else null)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(Sizes.shortcutTile).clip(RoundedCornerShape(Radius.tile)).background(c.brandMist), contentAlignment = Alignment.Center) {
            GIcon(icon, null, size = Sizes.shortcutIcon)
        }
        Text(label, style = GimanoType.label, color = c.text, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun Attribution(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.osm_attribution),
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xB3FFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
        fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3A4A66), textAlign = TextAlign.Start,
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Gimano.colors.surface, textColor: Color = Gimano.colors.text, icon: ImageVector? = null, gicon: String? = null, onClick: (() -> Unit)? = null) {
    FloatingSurface(modifier.height(40.dp), RoundedCornerShape(Radius.pill), color = color, onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (gicon != null) {
                GIcon(gicon, null, size = 20.dp); Spacer(Modifier.width(6.dp))
            } else if (icon != null) {
                Icon(icon, null, tint = textColor, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
            }
            Text(text, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun GrabHandle(modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Gimano.colors.outline))
}

@Composable
fun SheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        color = Gimano.colors.surface,
        shadowElevation = 14.dp,
    ) { Column(content = content) }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Gimano.colors.outline.copy(alpha = 0.6f)))
}

fun Modifier.thinBorder(color: Color, shape: Shape) = this.border(1.dp, color, shape)
