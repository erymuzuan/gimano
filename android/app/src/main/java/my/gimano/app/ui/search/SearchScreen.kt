package my.gimano.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.data.model.PlaceSummary
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.SearchPurpose
import my.gimano.app.ui.UiState
import my.gimano.app.ui.common.CategoryBadge
import my.gimano.app.ui.common.Divider
import my.gimano.app.ui.common.GIcons
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.common.CategoryGlyph
import my.gimano.app.ui.theme.Sizes
import my.gimano.app.ui.home.PlaceRow
import my.gimano.app.ui.home.RecentRow
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.Radius

private val quickCategories = listOf(
    "fuel" to R.string.cat_fuel, "parking" to R.string.cat_parking, "restaurant" to R.string.cat_food,
    "ev_charging" to R.string.cat_ev, "mosque" to R.string.cat_mosque, "rest_area" to R.string.cat_rest_area,
    "hospital" to R.string.cat_hospital,
)

@Composable
fun SearchScreen(ui: UiState, purpose: SearchPurpose, vm: MainViewModel, onMic: () -> Unit) {
    val c = Gimano.colors
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(c.surface).windowInsetsPadding(WindowInsets.statusBars).imePadding(),
    ) {
        val title = when (purpose) {
            SearchPurpose.SET_HOME -> R.string.set_home_title
            SearchPurpose.SET_WORK -> R.string.set_work_title
            SearchPurpose.ADD_STOP -> R.string.add_stop_title
            SearchPurpose.ADD_FAVORITE -> R.string.add_favorite_title
            SearchPurpose.ADD_STOP_PREVIEW -> R.string.add_stop_title
            SearchPurpose.NAVIGATE -> null
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            TextField(
                value = ui.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.weight(1f).heightIn(min = Sizes.searchField).focusRequester(focus),
                leadingIcon = { GIcon("search", null, size = 26.dp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                placeholder = { Text(if (title != null) stringResource(title) else stringResource(R.string.search_hint), color = c.textMuted) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceAlt, unfocusedContainerColor = c.surfaceAlt,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = c.brand,
                ),
                trailingIcon = {
                    if (ui.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
                    else IconButton(onClick = onMic) { GIcon("mic", stringResource(R.string.voice_search), size = 26.dp) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
            )
        }
        if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.brand, trackColor = c.surfaceAlt)
        else Spacer(Modifier.height(2.dp))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (ui.query.isBlank()) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(quickCategories) { (cat, label) ->
                            FilterChip(
                                selected = ui.activeCategory == cat,
                                onClick = { vm.searchCategory(cat) },
                                label = { Text(stringResource(label)) },
                                leadingIcon = { CategoryGlyph(cat, 22.dp) },
                                shape = RoundedCornerShape(Radius.pill),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.brandMist, selectedLabelColor = c.brandInk),
                                modifier = Modifier.height(40.dp),
                            )
                        }
                    }
                }
            }
            if (ui.query.isBlank() && ui.activeCategory == null) {
                item { SectionTitle(stringResource(R.string.recents)) }
                items(ui.recents, key = { "r" + it.id }) { r ->
                    RecentRow(r, ui.myLocation?.point) {
                        vm.pickResult(PlaceSummary(r.placeId ?: "pin:${r.lat},${r.lng}", r.name, "other", r.address, r.lat, r.lng))
                    }
                }
            } else {
                // On-device matches (favourites, private places) come first.
                items(ui.localHits, key = { "l" + it.id }) { h ->
                    Column {
                        PlaceRow(h.icon, h.name, h.address, ui.myLocation?.point?.let { Formatters.distance(my.gimano.app.core.geo.GeoMath.distanceM(it, my.gimano.app.core.geo.GeoPoint(h.lat, h.lng))) },
                            onClick = { kb?.hide(); vm.pickLocal(h) },
                            leading = { my.gimano.app.ui.home.IconTile { if (h.kind == "private") Icon(Icons.Filled.Lock, null, tint = c.brand) else GIcon(h.icon, null, size = 26.dp) } })
                        Divider(Modifier.padding(start = 70.dp))
                    }
                }
                items(ui.results, key = { "s" + it.id }) { p -> ResultRow(p) { kb?.hide(); vm.pickResult(p) } }
                if (vm.canAddPlaceFromSearch()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.no_results), color = c.textMuted)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { kb?.hide(); vm.addPlaceFromSearch() }, modifier = Modifier.height(56.dp), shape = RoundedCornerShape(Radius.md),
                                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn),
                            ) {
                                Icon(Icons.Filled.AddLocationAlt, null); Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.cant_find_add), fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Gimano.colors.textMuted)
}

@Composable
private fun ResultRow(p: PlaceSummary, onClick: () -> Unit) {
    Column {
        PlaceRow(
            "map", p.name, p.address,
            p.distanceM?.let { Formatters.distance(it) }, onClick,
            leading = { CategoryBadge(p.category) },
            badge = if (p.source == "community") stringResource(if (p.status == "published") R.string.community else R.string.unverified) else null,
        )
        Divider(Modifier.padding(start = 70.dp))
    }
}
