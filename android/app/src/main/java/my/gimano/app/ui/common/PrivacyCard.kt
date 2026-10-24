package my.gimano.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.Radius

/** "Your places stay on your phone" (docs/privacy-promise.md short version, BM/EN). */
@Composable
fun PrivacyCard(onBackup: (() -> Unit)?, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = Gimano.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.brandMist).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = c.brand, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.privacy_title), fontSize = 18.sp, fontWeight = FontWeight.Black, color = c.text, modifier = Modifier.weight(1f))
            if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = c.textMuted) }
        }
        Text(stringResource(if (my.gimano.app.BuildConfig.DRIVE_BACKUP) R.string.privacy_body else R.string.privacy_body_no_drive), fontSize = 14.sp, color = c.text, modifier = Modifier.padding(top = 6.dp))
        if (onBackup != null) TextButton(onClick = onBackup, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.privacy_backup_cta), fontWeight = FontWeight.ExtraBold, color = c.brand)
        }
    }
}
