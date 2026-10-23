package my.gimano.app.ui.more

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.model.SavedPlace
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.SearchPurpose
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType

/** My favourites (design board "My favourites"): Home/Work cards + labelled places, reorder, edit. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavouritesScreen(saved: List<SavedPlace>, me: GeoPoint?, lastBackup: String?, vm: MainViewModel) {
    val c = Gimano.colors
    val home = saved.firstOrNull { it.kind == "home" }
    val work = saved.firstOrNull { it.kind == "work" }
    val favs = saved.filter { it.kind == "favorite" }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.my_favourites), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = { vm.openSearch(SearchPurpose.ADD_FAVORITE) }, Modifier.size(48.dp), shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.brand, contentColor = c.brandOn)) { Icon(Icons.Filled.Add, stringResource(R.string.add_favorite_title)) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(Triple("home", home, R.string.home), Triple("work", work, R.string.work)).forEach { (k, sp, label) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(c.surface)
                                .combinedClickable(onClick = { if (sp != null) vm.routeToSaved(sp) else vm.openSearch(if (k == "home") SearchPurpose.SET_HOME else SearchPurpose.SET_WORK) },
                                    onLongClick = { sp?.let(vm::editFavorite) })
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { GIcon(k, null, size = 38.dp) }
                            Text(stringResource(label), fontSize = 19.sp, fontWeight = FontWeight.Black, color = c.text)
                            Text(sp?.address?.ifBlank { sp.name } ?: stringResource(if (k == "home") R.string.set_home else R.string.set_work), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.my_places).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Black, color = c.textMuted, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.long_press_to_edit), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c.brand)
                }
            }
            items(favs, key = { it.id }) { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .combinedClickable(onClick = { vm.routeToSaved(f) }, onLongClick = { vm.editFavorite(f) }).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { GIcon(f.icon, null, size = 34.dp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.address, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    me?.let { Text(Formatters.distance(GeoMath.distanceM(it, GeoPoint(f.lat, f.lng))), fontSize = 14.sp, fontWeight = FontWeight.Black, color = c.brand) }
                    Column {
                        IconButton(onClick = { vm.moveFavorite(f.id, -1) }, Modifier.size(30.dp)) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_left), tint = c.textMuted) }
                        IconButton(onClick = { vm.moveFavorite(f.id, 1) }, Modifier.size(30.dp)) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_right), tint = c.textMuted) }
                    }
                }
            }
        }
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.brandMist).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, tint = c.brand, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.saved_on_phone_backup, lastBackup?.take(16)?.replace('T', ' ') ?: stringResource(R.string.never)), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk)
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}
