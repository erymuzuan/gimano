package my.gimano.app.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType

/** Theme (design board "Theme"): four preview cards, Light by default. */
@Composable
fun ThemeScreen(current: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    val c = Gimano.colors
    val light = Color(0xFFEEF2F8); val dark = Color(0xFF0E1626)
    data class Opt(val key: String, val label: Int, val sub: Int, val icon: ImageVector, val bg: Brush, val line: Color)
    val opts = listOf(
        Opt("light", R.string.theme_light, R.string.theme_light_sub, Icons.Filled.LightMode, Brush.linearGradient(listOf(light, light)), Color(0xFF0047AB)),
        Opt("dark", R.string.theme_dark, R.string.theme_dark_sub, Icons.Filled.DarkMode, Brush.linearGradient(listOf(dark, dark)), Color(0xFF5B8DEF)),
        Opt("system", R.string.theme_system, R.string.theme_system_sub, Icons.Filled.PhoneAndroid, Brush.horizontalGradient(0.5f to light, 0.5f to dark), Color(0xFF0047AB)),
        Opt("auto", R.string.theme_auto, R.string.theme_auto_sub, Icons.Filled.WbTwilight, Brush.verticalGradient(0.55f to light, 0.55f to dark), Color(0xFF0047AB)),
    )
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.theme), style = GimanoType.title, color = c.text)
        }
        opts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { o ->
                    val on = o.key == current
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(c.surface)
                            .border(3.dp, if (on) c.brand else Color.Transparent, RoundedCornerShape(22.dp))
                            .clickable { onPick(o.key) }.semantics { role = Role.RadioButton; selected = on }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp)).background(o.bg).padding(10.dp), contentAlignment = Alignment.BottomStart) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth(0.7f).height(10.dp).clip(RoundedCornerShape(999.dp)).background(o.line))
                                Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(o.icon, null, tint = c.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(o.label), fontSize = 16.sp, fontWeight = FontWeight.Black, color = c.text)
                        }
                        Text(stringResource(o.sub), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
                    }
                }
            }
        }
        Text(stringResource(R.string.theme_note), Modifier.clip(RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
    }
}
