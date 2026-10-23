package my.gimano.app.ui.menu

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.data.model.User
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.theme.Gimano

/** Menu (design board "Gimano menu (updated)"). */
@Composable
fun SideMenu(
    user: User?, inboxUnread: Int,
    onProfile: () -> Unit, onFavourites: () -> Unit, onPlanned: () -> Unit, onInbox: () -> Unit, onContributions: () -> Unit,
    onAddPlace: () -> Unit, onBackup: () -> Unit, onTheme: () -> Unit, onFeedback: () -> Unit, onSettings: () -> Unit,
    /** Newer version waiting (after "Later"): a row with a badge. */
    updateVersion: String? = null, onUpdate: () -> Unit = {},
) {
    val c = Gimano.colors
    ModalDrawerSheet(drawerContainerColor = c.surface, modifier = Modifier.width(330.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp, bottom = 16.dp)) {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.brandMist).clickable(onClick = onProfile).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.brand), contentAlignment = Alignment.Center) {
                    Text((user?.displayName ?: "G").take(1).uppercase(), color = c.brandOn, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.greeting, user?.displayName ?: "Gimano"), fontSize = 19.sp, fontWeight = FontWeight.Black, color = c.text)
                    if (user != null) Text(stringResource(R.string.points_level, user.points, user.level), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (updateVersion != null) Item(icon = Icons.Filled.SystemUpdate, label = stringResource(R.string.update_menu, updateVersion), badge = 1, onClick = onUpdate)
            Item(gicon = "star", label = stringResource(R.string.my_favourites), onClick = onFavourites)
            Item(icon = Icons.Filled.Event, label = stringResource(R.string.planned_drives), onClick = onPlanned)
            Item(icon = Icons.Filled.Inbox, label = stringResource(R.string.inbox), badge = inboxUnread, onClick = onInbox)
            Item(gicon = "add_stop", label = stringResource(R.string.my_contributions), onClick = onContributions)
            Item(gicon = "map", label = stringResource(R.string.add_a_place), onClick = onAddPlace)
            Item(icon = Icons.Filled.Backup, label = stringResource(R.string.backup_restore), onClick = onBackup)
            Item(icon = Icons.Filled.DarkMode, label = stringResource(R.string.theme), onClick = onTheme)
            Item(gicon = "feedback", label = stringResource(R.string.add_feedback), onClick = onFeedback)
            Item(icon = Icons.Filled.Settings, label = stringResource(R.string.settings), onClick = onSettings)
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).clickable(onClick = onBackup).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Shield, null, tint = c.brand, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.places_stay_on_phone), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
            }
            VersionLine()
        }
    }
}

@Composable
private fun Item(label: String, icon: ImageVector? = null, gicon: String? = null, badge: Int = 0, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(c.brandMist), contentAlignment = Alignment.Center) {
            if (gicon != null) GIcon(gicon, null, size = 24.dp) else if (icon != null) Icon(icon, null, tint = c.brandInk, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
        if (badge > 0) Text("$badge", Modifier.clip(RoundedCornerShape(999.dp)).background(c.report).padding(horizontal = 9.dp, vertical = 2.dp), color = c.reportOn, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}


/** "Gimano 0.2.0 (2)"; tap 7 times to copy diagnostic info (version / OS / device, nothing personal) for support. */
@Composable
private fun VersionLine() {
    val c = Gimano.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    val copied = stringResource(R.string.diagnostics_copied)
    Text(
        "Gimano ${my.gimano.app.BuildConfig.VERSION_NAME} (${my.gimano.app.BuildConfig.VERSION_CODE})",
        Modifier.fillMaxWidth().clickable {
            taps++
            if (taps >= 7) {
                taps = 0
                val info = (ctx.applicationContext as my.gimano.app.GimanoApp).container.deviceInfo.diagnosticsText()
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Gimano diagnostics", info))
                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }.padding(horizontal = 16.dp, vertical = 14.dp),
        fontSize = 12.sp, color = c.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
