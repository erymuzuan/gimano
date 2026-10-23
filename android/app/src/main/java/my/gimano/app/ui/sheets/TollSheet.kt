package my.gimano.app.ui.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.trip.TollBadge
import my.gimano.app.data.model.Route
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.home.IconTile
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius

@Composable
fun tollBadgeText(b: TollBadge): String? = when (b) {
    TollBadge.None -> null
    is TollBadge.Exact -> TollBadge.rm(b.myr)
    is TollBadge.From -> stringResource(R.string.toll_from, TollBadge.rm(b.myr))
    TollBadge.Unknown -> stringResource(R.string.has_tolls)
}

/** The toll plazas on a route, each with its fare (or "fare unknown") and the tariff's source. */
@Composable
fun TollSheet(route: Route, onDismiss: () -> Unit, onAvoidTolls: () -> Unit) {
    val c = Gimano.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon("fare", null, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(tollBadgeText(TollBadge.of(route)) ?: stringResource(R.string.no_tolls), style = GimanoType.title, color = c.text)
            }
            Spacer(Modifier.height(8.dp))
            if (route.tolls.isEmpty()) {
                Text(stringResource(R.string.tolls_no_details), color = c.textMuted, fontSize = 15.sp)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(route.tolls) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile { GIcon("toll", null, size = 26.dp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name.ifBlank { stringResource(R.string.toll_plaza) }, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = c.text)
                            t.highway?.let { Text(it, fontSize = 13.sp, color = c.textMuted) }
                            t.source?.let { Text(stringResource(R.string.toll_source, it), fontSize = 12.sp, color = c.textMuted) }
                        }
                        Text(
                            when {
                                t.system == "closed_entry" -> stringResource(R.string.toll_entry)
                                t.fareMyr != null -> TollBadge.rm(t.fareMyr)
                                else -> stringResource(R.string.fare_unknown)
                            },
                            fontWeight = FontWeight.Black, fontSize = 16.sp, color = if (t.fareMyr != null) c.text else c.textMuted,
                        )
                    }
                }
            }
            Text(stringResource(R.string.tolls_disclaimer), fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = onAvoidTolls, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(Radius.md)) {
                Text(stringResource(R.string.avoid_tolls_action), fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
