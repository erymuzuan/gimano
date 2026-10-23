package my.gimano.app.ui.contribute

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.AppContainer
import my.gimano.app.R
import my.gimano.app.data.model.PlaceSummary
import my.gimano.app.data.model.User
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.common.CategoryBadge
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius

/** My contributions: public places (server), reviews and photos (device log), points/level (profile). */
@Composable
fun ContributionsScreen(c: AppContainer, cvm: ContributeViewModel, vm: MainViewModel, user: User?) {
    val col = Gimano.colors
    var places by remember { mutableStateOf<List<PlaceSummary>?>(null) }
    val counts by cvm.log.counts.collectAsState(initial = 0 to emptyList())
    val privateCount = vm.ui.collectAsState().value.privatePins.size
    LaunchedEffect(Unit) { places = runCatching { c.repo.contributedPlaces() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize().background(col.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = col.text) }
            Text(stringResource(R.string.my_contributions), style = GimanoType.title, color = col.text)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("${user?.points ?: 0}", stringResource(R.string.points), Modifier.weight(1f))
            Stat("${places?.size ?: 0}", stringResource(R.string.places_n), Modifier.weight(1f))
            Stat("${counts.first}", stringResource(R.string.reviews), Modifier.weight(1f))
            Stat("${counts.second.size}", stringResource(R.string.photos), Modifier.weight(1f))
        }
        Text(stringResource(R.string.private_places_n, privateCount), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = col.textMuted)
        if (places == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = col.brand, trackColor = col.surfaceAlt)
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(places.orEmpty(), key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(p.category)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = col.text)
                        Text(stringResource(if (p.status == "published") R.string.status_published else R.string.status_pending), fontSize = 13.sp,
                            color = if (p.status == "published") col.brand else col.textMuted, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { vm.showPlace(p) }) { Text(stringResource(R.string.open_verb)) }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val c = Gimano.colors
    Column(modifier.clip(RoundedCornerShape(Radius.md)).background(c.brandMist).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = c.brandInk)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
    }
}
