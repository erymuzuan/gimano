package my.gimano.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.data.model.InboxItem
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.report.currentLang
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import java.time.Instant

/** Inbox: news about the user's public contributions + announcements (nothing personal). */
@Composable
fun InboxScreen(items: List<InboxItem>, read: Set<String>, onBack: () -> Unit, onRefresh: () -> Unit, onMarkAll: () -> Unit) {
    val c = Gimano.colors
    val lang = currentLang()
    LaunchedEffect(Unit) { onRefresh() }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.inbox), style = GimanoType.title, color = c.text, modifier = Modifier.weight(1f))
            if (items.any { it.id !in read }) FilledTonalButton(onClick = onMarkAll) { Text(stringResource(R.string.mark_all_read), fontWeight = FontWeight.Black, fontSize = 14.sp) }
        }
        Text(stringResource(R.string.inbox_intro), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Inbox, null, tint = c.brandTint, modifier = Modifier.size(72.dp))
                Text(stringResource(R.string.inbox_empty), fontWeight = FontWeight.ExtraBold, color = c.textMuted, modifier = Modifier.padding(top = 12.dp))
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { m ->
                val unread = m.id !in read
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .then(if (unread) Modifier.border(2.dp, c.report, RoundedCornerShape(20.dp)) else Modifier).padding(14.dp),
                ) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.brandMist), contentAlignment = Alignment.Center) {
                        when (m.kind) {
                            "place_published", "place_rejected" -> GIcon("add_stop", null, size = 28.dp)
                            "report_thanks" -> GIcon("heart", null, size = 28.dp)
                            "review_helpful" -> GIcon("star", null, size = 28.dp)
                            "feedback_status" -> GIcon("feedback", null, size = 28.dp)
                            "edit_accepted" -> GIcon("map", null, size = 28.dp)
                            else -> Icon(Icons.Filled.Campaign, null, tint = c.brandInk, modifier = Modifier.size(26.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, fontSize = 16.sp, fontWeight = FontWeight.Black, color = c.text)
                        if (m.body.isNotBlank()) Text(m.body, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
                        Formatters.parseInstant(m.createdAt)?.let { Text(Formatters.reportAge(it, Instant.now(), lang), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.textMuted) }
                    }
                }
            }
        }
    }
}
