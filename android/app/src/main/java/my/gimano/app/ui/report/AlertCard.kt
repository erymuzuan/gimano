package my.gimano.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.format.Lang
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.model.Report
import my.gimano.app.ui.common.Labels
import my.gimano.app.ui.common.ReportTile
import my.gimano.app.ui.common.FloatingSurface
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.Radius
import my.gimano.app.ui.theme.ReportColors
import java.time.Instant

@Composable
fun currentLang(): Lang = Lang.from(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]?.language)

/**
 * "Still there? / Not there" card for a community report. Shows age, reporter, distance and a
 * shrinking bar for the remaining TTL.
 */
@Composable
fun AlertCard(
    report: Report,
    me: GeoPoint?,
    onVote: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    distanceAheadM: Double? = null,
) {
    val c = Gimano.colors
    val lang = currentLang()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(report.id) { while (true) { delay(15_000); now = Instant.now() } }
    val created = Formatters.parseInstant(report.createdAt) ?: now
    val expires = Formatters.parseInstant(report.expiresAt) ?: now
    val dist = distanceAheadM ?: me?.let { GeoMath.distanceM(it, report.point) }

    FloatingSurface(modifier.fillMaxWidth(), RoundedCornerShape(Radius.lg)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReportTile(report.type, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(Labels.reportTitle(report.type, report.subtype), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val age = Formatters.reportAge(created, now, lang)
                    val by = report.reportedBy?.displayName?.takeIf { it.isNotBlank() }
                    Text(
                        listOfNotNull(
                            if (by != null) stringResource(R.string.reported_by, age, by) else age,
                            dist?.let { if (distanceAheadM != null) stringResource(R.string.ahead, Formatters.navDistance(it)) else Formatters.distance(it) },
                        ).joinToString(" · "),
                        fontSize = 14.sp, color = c.textMuted, maxLines = 2,
                    )
                    report.street?.let { Text(it, fontSize = 14.sp, color = c.textMuted, maxLines = 1) }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = c.textMuted) }
            }
            Spacer(Modifier.height(10.dp))
            // TTL bar
            val frac = Formatters.reportTtlFraction(created, expires, now)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceAlt)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(ReportColors.forType(report.type)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(Formatters.reportTimeLeft(expires, now, lang), fontSize = 12.sp, color = c.textMuted, modifier = Modifier.weight(1f))
                if (report.confirmations > 0) Text(stringResource(R.string.confirmations, report.confirmations), fontSize = 12.sp, color = c.textMuted)
            }
            Spacer(Modifier.height(10.dp))
            // Same driving confirm buttons as "Masih ditutup?" (84 dp, icon above the full label).
            my.gimano.app.ui.common.ConfirmButtons(listOf(
                my.gimano.app.ui.common.ConfirmOption(stringResource(R.string.still_there), my.gimano.app.ui.common.ConfirmIcons.up, my.gimano.app.ui.common.ConfirmStyle.SOLID) { onVote(true) },
                my.gimano.app.ui.common.ConfirmOption(stringResource(R.string.not_there), my.gimano.app.ui.common.ConfirmIcons.down, my.gimano.app.ui.common.ConfirmStyle.PALE) { onVote(false) },
            ))
        }
    }
}
