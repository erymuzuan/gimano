package my.gimano.app.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
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
import my.gimano.app.ui.theme.Gimano

@Composable
fun SideMenu(
    user: User?, onProfile: () -> Unit, onPlanned: () -> Unit, onInbox: () -> Unit, onSettings: () -> Unit, onHelp: () -> Unit, onExit: () -> Unit,
    onAddPlace: () -> Unit, onContributions: () -> Unit, onFeedback: () -> Unit, onMyFeedback: () -> Unit, onBackup: () -> Unit,
) {
    val c = Gimano.colors
    ModalDrawerSheet(drawerContainerColor = c.surface, modifier = Modifier.width(310.dp)) {
        Column(Modifier.fillMaxWidth().background(c.brand).windowInsetsPadding(WindowInsets.statusBars).padding(20.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.brandOn), contentAlignment = Alignment.Center) {
                Text(user?.mood ?: "🙂", fontSize = 28.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.greeting, user?.displayName ?: "Gimano"), color = c.brandOn, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (user != null) Text(stringResource(R.string.points_level, user.points, user.level), color = c.brandOn.copy(alpha = 0.85f), fontSize = 14.sp)
            TextButton(onClick = onProfile, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.view_profile), color = c.brandOn, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Item(Icons.Filled.AddLocationAlt, stringResource(R.string.add_a_place), onAddPlace)
        Item(Icons.Filled.EmojiEvents, stringResource(R.string.my_contributions), onContributions)
        Item(Icons.Filled.EventNote, stringResource(R.string.planned_drives), onPlanned)
        Item(Icons.Filled.Inbox, stringResource(R.string.inbox), onInbox)
        Item(Icons.Filled.Feedback, stringResource(R.string.add_feedback), onFeedback)
        Item(Icons.Filled.ChecklistRtl, stringResource(R.string.my_feedback), onMyFeedback)
        Item(Icons.Filled.Backup, stringResource(R.string.backup_restore), onBackup)
        Item(Icons.Filled.Settings, stringResource(R.string.settings), onSettings)
        Item(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.help), onHelp)
        Item(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.exit), onExit)
    }
}

@Composable
private fun Item(icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, fontSize = 17.sp) },
        icon = { Icon(icon, null, tint = Gimano.colors.text) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
