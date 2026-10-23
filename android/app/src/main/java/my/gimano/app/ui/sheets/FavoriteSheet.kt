package my.gimano.app.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.data.local.Favorites
import my.gimano.app.ui.FavoriteDraft
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius

/** Save / edit a labelled favourite: quick labels, free text, icon picker, Home/Work. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoriteSheet(d: FavoriteDraft, vm: MainViewModel) {
    val c = Gimano.colors
    ModalBottomSheet(
        onDismissRequest = vm::closeFavoriteSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(Radius.tile)).background(c.brandMist), contentAlignment = Alignment.Center) {
                    GIcon(if (d.kind == "favorite") d.icon else d.kind, null, size = 40.dp)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = d.label,
                    onValueChange = { vm.updateFavoriteDraft(d.copy(label = it.take(Favorites.MAX_LABEL))) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = d.kind == "favorite",
                    placeholder = { Text(d.place?.name ?: stringResource(R.string.fav_name_hint)) },
                    supportingText = { Text("${d.label.length}/${Favorites.MAX_LABEL}") },
                    shape = RoundedCornerShape(Radius.md),
                    textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                )
            }
            if (d.kind == "favorite") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Favorites.QUICK_LABELS.forEach { (label, icon) ->
                        FilterChip(
                            selected = d.label == label,
                            onClick = { vm.updateFavoriteDraft(d.copy(label = label, icon = icon)) },
                            label = { Text(label, fontWeight = FontWeight.ExtraBold) },
                            leadingIcon = { GIcon(icon, null, size = 22.dp) },
                            shape = RoundedCornerShape(Radius.pill),
                            modifier = Modifier.height(44.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.brandMist),
                        )
                    }
                }
                // Icon picker: 10 icons, 5 per row.
                Favorites.ICONS.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { icon ->
                            val sel = d.icon == icon
                            Box(
                                Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) c.brandMist else c.surfaceAlt)
                                    .then(if (sel) Modifier.border(2.dp, c.brand, RoundedCornerShape(18.dp)) else Modifier)
                                    .clickable { vm.updateFavoriteDraft(d.copy(icon = icon)) }
                                    .semantics { contentDescription = icon },
                                contentAlignment = Alignment.Center,
                            ) { GIcon(icon, null, size = 34.dp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = vm::saveFavorite, enabled = d.kind == "favorite",
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn),
            ) { Text(stringResource(R.string.save), style = GimanoType.button) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("home" to R.string.set_as_home, "work" to R.string.set_as_work).forEach { (kind, label) ->
                    OutlinedButton(
                        onClick = { vm.setDraftAsHomeWork(kind) }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(Radius.md),
                    ) { GIcon(kind, null, size = 22.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(label), fontWeight = FontWeight.ExtraBold) }
                }
            }
            if (d.editingId != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.kind == "favorite") {
                        IconButton(onClick = { vm.moveFavorite(d.editingId, -1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.move_left)) }
                        IconButton(onClick = { vm.moveFavorite(d.editingId, 1) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.move_right)) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteFavorite(d.editingId) }) {
                        Text(stringResource(R.string.delete), color = c.report, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}
