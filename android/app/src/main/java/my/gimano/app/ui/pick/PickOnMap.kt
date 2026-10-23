package my.gimano.app.ui.pick

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.PickState
import my.gimano.app.ui.PinSel
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.theme.Gimano

/** The coral pin, its tip exactly at the centre of the map (the map moves underneath). */
@Composable
fun CentrePin() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp, 60.dp).offset(y = (-30).dp)) {
            val w = size.width; val h = size.height
            drawOval(Color(0x400B1B3A), topLeft = Offset(w / 2 - 9.dp.toPx(), h - 4.dp.toPx()), size = androidx.compose.ui.geometry.Size(18.dp.toPx(), 8.dp.toPx()))
            val p = Path().apply {
                moveTo(w / 2, h)
                cubicTo(w / 2, h, 0f, h * 0.55f, 0f, w / 2)
                cubicTo(0f, w * 0.22f, w * 0.22f, 0f, w / 2, 0f)
                cubicTo(w * 0.78f, 0f, w, w * 0.22f, w, w / 2)
                cubicTo(w, h * 0.55f, w / 2, h, w / 2, h)
                close()
            }
            drawPath(p, Color(0xFFFF6B4A))
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(w / 2, w / 2))
        }
    }
}

/** Canvas CariPeta: full screen, pin fixed in the middle, address below; no satellite (no open imagery). */
@Composable
fun PickOnMapOverlay(pick: PickState, vm: MainViewModel) {
    val c = Gimano.colors
    CentrePin()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).fillMaxWidth().shadow(6.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp)).background(c.surface).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.brand) }
            Column {
                Text(stringResource(R.string.pick_title), fontSize = 16.sp, fontWeight = FontWeight.Black, color = c.text)
                Text(stringResource(R.string.pick_hint), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(RoundedCornerShape(99.dp)).background(c.outline))
            Text(pick.label ?: stringResource(R.string.pin_on_map), fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.text, maxLines = 2)
            val dist = my.gimano.app.core.geo.GeoMath.distanceM(vm.here(), pick.center)
            Text(listOfNotNull(pick.area, stringResource(R.string.pick_from_you, Formatters.distance(dist))).joinToString(" · "),
                fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted, maxLines = 2)
            if (pick.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.brand, trackColor = c.surfaceAlt)
            pick.nearPoi?.let { poi ->
                Text(stringResource(R.string.pick_is_this, poi.name, Formatters.distance(poi.distanceM ?: 0.0)),
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.brandMist).clickable(onClick = vm::pickPoi).padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Black, color = c.brandInk)
            }
            Button(onClick = vm::finishPick, Modifier.fillMaxWidth().height(62.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.pick_choose), fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** Canvas CariPin: Navigasi ke sini · Simpan · Tambah tempat · Kongsi. */
@Composable
fun PinActionsSheet(pin: PinSel, vm: MainViewModel, modifier: Modifier = Modifier) {
    val c = Gimano.colors
    val p = pin.place
    Column(
        modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp).navigationBarsPadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(RoundedCornerShape(99.dp)).background(c.outline))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(p.name, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.text, maxLines = 2)
            val trip = listOfNotNull(pin.etaS?.let { Formatters.duration(it, my.gimano.app.ui.report.currentLang()) }, (pin.routeM ?: p.distanceM)?.let { Formatters.distance(it) }).joinToString(" · ")
            if (trip.isNotEmpty()) Text(trip, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted)
        }
        if (p.address.isNotBlank()) Text(p.address, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted, maxLines = 2)
        Button(onClick = vm::pinNavigate, Modifier.fillMaxWidth().height(66.dp), shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
            Icon(Icons.Filled.Navigation, null, Modifier.size(26.dp)); Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.pin_navigate), fontSize = 19.sp, fontWeight = FontWeight.Black)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Modifier.weight(1f), stringResource(R.string.pin_save), onClick = vm::pinSave) { GIcon("star", null, size = 28.dp) }
            Tile(Modifier.weight(1f), stringResource(R.string.pin_add_place), onClick = vm::pinAddPlace) { Icon(Icons.Filled.AddLocationAlt, null, tint = c.brandInk, modifier = Modifier.size(28.dp)) }
            Tile(Modifier.weight(1f), stringResource(R.string.pin_share), onClick = vm::pinShare) { Icon(Icons.Filled.Share, null, tint = c.brandInk, modifier = Modifier.size(28.dp)) }
        }
        Text(stringResource(R.string.pin_note), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textMuted, lineHeight = 17.sp)
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val c = Gimano.colors
    Column(modifier.height(86.dp).clip(RoundedCornerShape(18.dp)).background(c.brandMist).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        icon(); Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Black, color = c.brandInk)
    }
}

/** "Nak namakan tempat ini?" after arriving at an unnamed pin (once; can be ignored). */
@Composable
fun NamePinDialog(onName: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onName(null) },
        title = { Text(stringResource(R.string.name_pin_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.name_pin_body))
                OutlinedTextField(text, { text = it.take(40) }, singleLine = true, placeholder = { Text(stringResource(R.string.name_pin_hint)) })
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onName(text) }) { Text(stringResource(R.string.save), fontWeight = FontWeight.Black) } },
        dismissButton = { TextButton(onClick = { onName(null) }) { Text(stringResource(R.string.not_now)) } },
    )
}
