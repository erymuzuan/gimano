package my.gimano.app.ui.place

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.PlaceSel
import my.gimano.app.ui.common.CategoryBadge
import my.gimano.app.ui.common.Labels
import my.gimano.app.ui.common.SheetSurface
import my.gimano.app.ui.common.GrabHandle
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.Radius

@Composable
fun PlaceCard(sel: PlaceSel, vm: MainViewModel, modifier: Modifier = Modifier) {
    val c = Gimano.colors
    val p = sel.summary
    val d = sel.detail
    SheetSurface(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { GrabHandle() }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                CategoryBadge(p.category, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2)
                    val meta = buildList {
                        add(Labels.category(p.category))
                        d?.openNow?.let { add(if (it) stringResource(R.string.open_now) else stringResource(R.string.closed)) }
                        p.distanceM?.let { add(Formatters.distance(it)) }
                    }
                    Text(meta.joinToString(" · "), fontSize = 14.sp, color = if (d?.openNow == true) c.brand else c.textMuted)
                }
                PlaceOverflow(vm)
                IconButton(onClick = vm::back) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = c.textMuted) }
            }
            if (p.address.isNotBlank()) Text(p.address, Modifier.padding(top = 6.dp), fontSize = 15.sp, color = c.textMuted)
            d?.openingHours?.let { Text(stringResource(R.string.hours, it), Modifier.padding(top = 4.dp), fontSize = 14.sp, color = c.textMuted) }
            if (sel.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp), color = c.brand, trackColor = c.surfaceAlt)

            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action(if (sel.savedId != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, stringResource(if (sel.savedId != null) R.string.saved else R.string.save)) { vm.openFavoriteSheet() }
                Action(Icons.Filled.Call, stringResource(R.string.call), enabled = d?.phone != null) { d?.phone?.let(vm::call) }
                Action(Icons.Filled.Share, stringResource(R.string.share)) { vm.sharePlace() }
                Action(Icons.Filled.Language, stringResource(R.string.website), enabled = d?.website != null) { d?.website?.let(vm::openWeb) }
            }
            if (d != null && d.amenities.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    items(d.amenities) { a ->
                        Text(
                            a.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            Modifier.clip(RoundedCornerShape(Radius.pill)).background(c.surfaceAlt).padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 13.sp, color = c.text,
                        )
                    }
                }
            }
            Button(
                onClick = vm::routeToPlace,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn),
            ) {
                Icon(Icons.Filled.Directions, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.routes), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Gimano.colors
    Column(
        Modifier.clip(RoundedCornerShape(Radius.md)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(c.surfaceAlt), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (enabled) c.brand else c.textMuted.copy(alpha = 0.5f))
        }
        Text(label, fontSize = 13.sp, color = if (enabled) c.text else c.textMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PlaceOverflow(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more), tint = Gimano.colors.textMuted) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_feedback), fontWeight = FontWeight.Bold) },
                leadingIcon = { my.gimano.app.ui.common.GIcon("feedback", null, size = 24.dp) },
                onClick = { open = false; vm.requestFeedback("place") },
            )
        }
    }
}
