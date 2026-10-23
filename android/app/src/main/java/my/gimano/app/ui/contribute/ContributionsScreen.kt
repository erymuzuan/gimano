package my.gimano.app.ui.contribute

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import my.gimano.app.AppContainer
import my.gimano.app.R
import my.gimano.app.data.model.PlaceSummary
import my.gimano.app.data.model.User
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.common.CategoryGlyph
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.place.mediaUrl
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType

/** My contributions (design board): level ring + points, stats, tabs Places / Reviews / Photos. */
@Composable
fun ContributionsScreen(c: AppContainer, cvm: ContributeViewModel, vm: MainViewModel, user: User?) {
    val col = Gimano.colors
    var places by remember { mutableStateOf<List<PlaceSummary>?>(null) }
    val counts by cvm.log.counts.collectAsState(initial = 0 to emptyList())
    val reports by cvm.log.reports.collectAsState(initial = 0)
    val reviewNames by cvm.log.reviewNames.collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { places = runCatching { c.repo.contributedPlaces() }.getOrDefault(emptyList()) }
    val points = user?.points ?: 0
    val level = user?.level ?: 1
    val nextAt = level * 500   // display only: progress inside the current level
    val progress = ((points % 500) / 500f).coerceIn(0f, 1f)

    Column(Modifier.fillMaxSize().background(col.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = col.text) }
            Text(stringResource(R.string.my_contributions), style = GimanoType.title, color = col.text)
        }
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(col.brand).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val ringLabel = stringResource(R.string.level_n, level)
            Box(Modifier.size(72.dp).semantics { contentDescription = ringLabel }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(8.dp.toPx()))
                    drawArc(Color.White, -90f, 360f * progress, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Text("$level", color = col.brandOn, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("%,d".format(points), color = col.brandOn, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(stringResource(R.string.points_to_next, maxOf(0, nextAt - points), level + 1), color = col.brandOn.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("report", "$reports", stringResource(R.string.reports_n), Modifier.weight(1f))
            Stat("add_stop", "${places?.size ?: 0}", stringResource(R.string.places_n), Modifier.weight(1f))
            Stat("star", "${counts.first}", stringResource(R.string.reviews), Modifier.weight(1f))
            Stat("map", "${counts.second.size}", stringResource(R.string.photos), Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(col.brandMist).padding(4.dp)) {
            listOf(R.string.places_n, R.string.reviews, R.string.photos).forEachIndexed { i, l ->
                val sel = tab == i
                Text(stringResource(l), Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) col.surface else Color.Transparent).clickable { tab = i }.padding(vertical = 12.dp),
                    fontSize = 15.sp, fontWeight = if (sel) FontWeight.Black else FontWeight.ExtraBold, color = if (sel) col.text else col.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        if (tab == 0 && places == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = col.brand, trackColor = col.surfaceAlt)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> items(places.orEmpty(), key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(col.surface).clickable { vm.showPlace(p) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(col.brandMist), contentAlignment = Alignment.Center) { CategoryGlyph(p.category, 30.dp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Black, fontSize = 17.sp, color = col.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.review_count, p.reviewCount), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = col.textMuted)
                        }
                        val published = p.status == "published"
                        Text(stringResource(if (published) R.string.status_published else R.string.status_pending),
                            Modifier.clip(RoundedCornerShape(999.dp)).background(if (published) Color(0xFFDDF4F2) else Color(0xFFFFF1CC)).padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 12.sp, fontWeight = FontWeight.Black, color = if (published) Color(0xFF0E5A54) else Color(0xFF7A5200))
                    }
                }
                1 -> items(reviewNames) { name ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(col.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        GIcon("star", null, size = 30.dp); Spacer(Modifier.width(12.dp))
                        Text(name, fontWeight = FontWeight.Black, fontSize = 16.sp, color = col.text)
                    }
                }
                else -> items(counts.second) { url ->
                    AsyncImage(mediaUrl(url, c.settings.current.apiBaseUrl), null, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun Stat(icon: String, value: String, label: String, modifier: Modifier) {
    val c = Gimano.colors
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.surface).padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GIcon(icon, null, size = 30.dp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.text)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted, maxLines = 1)
    }
}

