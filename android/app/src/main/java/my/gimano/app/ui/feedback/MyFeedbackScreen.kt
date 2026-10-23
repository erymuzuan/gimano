package my.gimano.app.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.ui.home.IconTile
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType

@Composable
fun MyFeedbackScreen(vm: FeedbackViewModel, onBack: () -> Unit, onNew: () -> Unit) {
    val c = Gimano.colors
    val items by vm.items.collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { vm.refreshMine() }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.my_feedback), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew) { Text(stringResource(R.string.add_feedback), fontWeight = FontWeight.ExtraBold) }
        }
        if (items.isEmpty()) Text(stringResource(R.string.no_feedback_yet), Modifier.padding(24.dp), color = c.textMuted)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(items, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile { FeedbackCategoryIcon(f.category, 26.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.message, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(f.createdAt.take(16).replace('T', ' '), fontSize = 12.sp, color = c.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusChip(f.status)
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val c = Gimano.colors
    val (label, bg) = when (status) {
        "queued" -> R.string.fb_status_queued to c.reportTint
        "triaged" -> R.string.fb_status_triaged to c.brandMist
        "planned" -> R.string.fb_status_planned to c.brandMist
        "done" -> R.string.fb_status_done to c.brandTint
        "wont_fix" -> R.string.fb_status_wont_fix to c.surfaceAlt
        else -> R.string.fb_status_received to c.brandMist
    }
    Text(stringResource(label), Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
}
