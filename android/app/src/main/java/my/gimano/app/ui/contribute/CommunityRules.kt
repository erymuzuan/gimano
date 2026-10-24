package my.gimano.app.ui.contribute

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.data.ugc.UgcTerms
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType

/**
 * Community rules, before a user's first public contribution: what's not allowed, that it's removed, that abusive users
 * can be blocked, how to report, and the full terms link. "I agree" is stored on this phone (date + version).
 */
@Composable
fun CommunityRulesDialog(onAgree: () -> Unit, onNotNow: () -> Unit) {
    val ctx = LocalContext.current
    val c = Gimano.colors
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.rules_title), style = GimanoType.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rules_intro), fontWeight = FontWeight.Bold, color = c.text)
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEach {
                    Text("• " + stringResource(it), fontSize = 15.sp, color = c.text)
                }
                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UgcTerms.URL))) } }) {
                    Text(stringResource(R.string.rules_full_terms, UgcTerms.URL), fontWeight = FontWeight.ExtraBold)
                }
            }
        },
        confirmButton = { Button(onClick = onAgree) { Text(stringResource(R.string.rules_agree), fontWeight = FontWeight.Black) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.rules_not_now)) } },
    )
}
