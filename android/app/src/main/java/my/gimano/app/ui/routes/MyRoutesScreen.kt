package my.gimano.app.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.events.GeometrySource
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.routes.RouteAssembly
import my.gimano.app.data.routes.PersonalRoute
import my.gimano.app.data.routes.PersonalRouteStore
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius

/** "Laluan saya" (side menu): personal routes, on this phone only. */
@Composable
fun MyRoutesScreen(
    store: PersonalRouteStore, onBack: () -> Unit, onOpen: (String?) -> Unit,
    onUseInEvent: (PersonalRoute, String, String) -> Unit,
) {
    val c = Gimano.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routes by remember(store) { store.routes() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var gpxFor by remember { mutableStateOf<PersonalRoute?>(null) }
    var deleteFor by remember { mutableStateOf<PersonalRoute?>(null) }
    val start = stringResource(R.string.rb_point_start)
    val finish = stringResource(R.string.rb_point_end)
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.my_routes), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
        }
        Button(onClick = { onOpen(null) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(54.dp), shape = RoundedCornerShape(Radius.md),
            colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rb_new), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
        if (routes.isEmpty()) Text(stringResource(R.string.my_routes_none), Modifier.padding(24.dp), color = c.textMuted, fontSize = 16.sp)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(routes, key = { it.id }) { p ->
                val sum = remember(p.route) { RouteAssembly.summary(p.route) }
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.brandMist), contentAlignment = Alignment.Center) {
                        ModeIcon(p.route.mode, modeLabel(p.route.mode), size = 28.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifBlank { stringResource(R.string.rb_untitled) }, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            Formatters.distance(sum.distanceM.toDouble()),
                            pluralStringResource(R.plurals.rb_points_n, p.route.points.size, p.route.points.size),
                            if (sum.source != null && sum.source != GeometrySource.ROUTED) stringResource(R.string.rb_sketch) else null,
                        )
                        Text(meta.joinToString(" · "), fontSize = 14.sp, color = c.textMuted)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.rb_more), tint = c.textMuted) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Share, null, tint = c.brand) }, text = { Text(stringResource(R.string.rb_share_gpx), fontWeight = FontWeight.Bold) },
                                enabled = RouteAssembly.joinedGeometry(p.route.legs).size >= 2, onClick = { menu = false; gpxFor = p })
                            DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Celebration, null, tint = c.brand) }, text = { Text(stringResource(R.string.rb_use_in_event), fontWeight = FontWeight.Bold) },
                                enabled = p.route.points.size >= 2, onClick = { menu = false; onUseInEvent(p, start, finish) })
                            DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Delete, null, tint = c.report) }, text = { Text(stringResource(R.string.delete), fontWeight = FontWeight.Bold) },
                                onClick = { menu = false; deleteFor = p })
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = c.brand, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.my_routes_privacy), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
    gpxFor?.let { p ->
        AlertDialog(
            onDismissRequest = { gpxFor = null },
            icon = { Icon(Icons.Filled.Lock, null, tint = c.brand) },
            text = { Text(stringResource(R.string.rb_gpx_confirm)) },
            confirmButton = { TextButton(onClick = { gpxFor = null; shareGpx(context, p.name, p.route) }) { Text(stringResource(R.string.rb_share_gpx), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { gpxFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteFor?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            text = { Text(stringResource(R.string.rb_delete_confirm)) },
            confirmButton = { TextButton(onClick = { deleteFor = null; scope.launch { store.delete(p.id) } }) {
                Text(stringResource(R.string.delete), fontWeight = FontWeight.ExtraBold, color = c.report) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
