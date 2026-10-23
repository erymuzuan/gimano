package my.gimano.app.ui.common

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.ui.theme.Gimano

/** "Navigasi perlukan lokasi tepat": shown before asking Android again for ACCESS_FINE_LOCATION. */
@Composable
fun PreciseLocationDialog(onAllow: () -> Unit, onKeep: () -> Unit) {
    val c = Gimano.colors
    AlertDialog(
        onDismissRequest = onKeep,
        icon = { Icon(Icons.Filled.LocationSearching, null, tint = c.brand, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.precise_title), fontWeight = FontWeight.Black) },
        text = { Text(stringResource(R.string.precise_body), fontSize = 15.sp) },
        confirmButton = {
            Button(onClick = onAllow, colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(R.string.precise_allow), fontWeight = FontWeight.Black)
            }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.precise_keep), fontWeight = FontWeight.ExtraBold) } },
    )
}

/** Persistent while only "Approximate" is granted: navigation still works but the instructions may be wrong. */
@Composable
fun ApproximateBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFF1CC)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.GpsNotFixed, null, tint = Color(0xFF7A5200), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.approx_banner), Modifier.weight(1f), color = Color(0xFF5C3D00), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text(stringResource(R.string.approx_change), fontWeight = FontWeight.Black, color = Color(0xFF0A2E6E)) }
    }
}

/** "Isyarat GPS lemah · ±xx m" */
@Composable
fun WeakGpsChip(accuracyM: Int) {
    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFF0A1F4D)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.GpsNotFixed, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.weak_gps, accuracyM), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
