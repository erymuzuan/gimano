package my.gimano.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import my.gimano.app.BuildConfig
import my.gimano.app.R
import my.gimano.app.data.settings.AppSettings
import my.gimano.app.data.settings.SoundMode
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.Radius

@Composable
fun SettingsScreen(s: AppSettings, vm: MainViewModel, fallback: Boolean, displayName: String?, onBackup: () -> Unit, onMyFeedback: () -> Unit) {
    val c = Gimano.colors
    Column(Modifier.fillMaxSize().background(c.surface).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = c.text) }
            Text(stringResource(R.string.settings), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
            if (fallback && !s.demoMode) {
                Card(colors = CardDefaults.cardColors(containerColor = c.report.copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.server_unreachable), color = c.text)
                        TextButton(onClick = vm::retryServer) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            my.gimano.app.ui.common.PrivacyCard(onBackup = onBackup, modifier = Modifier.padding(vertical = 8.dp))
            NavRow(stringResource(R.string.backup_restore), onBackup)
            NavRow(stringResource(R.string.my_feedback), onMyFeedback)
            TextSetting(stringResource(R.string.display_name), displayName ?: s.displayName ?: "", onApply = vm::setDisplayName)

            Section(stringResource(R.string.language))
            val appLocales = AppCompatDelegate.getApplicationLocales()
            val current = if (appLocales.isEmpty) "" else appLocales[0]?.language ?: ""
            Choice(listOf("" to stringResource(R.string.lang_system), "en" to stringResource(R.string.english), "ms" to stringResource(R.string.malay)), current) {
                AppCompatDelegate.setApplicationLocales(if (it.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(it))
            }

            Section(stringResource(R.string.voice_language))
            Choice(listOf("auto" to stringResource(R.string.voice_auto), "en" to stringResource(R.string.english), "ms" to stringResource(R.string.malay)), s.voiceLang) { vm.setVoiceLang(it) }

            Section(stringResource(R.string.sound))
            Choice(
                listOf(SoundMode.ON.name to stringResource(R.string.sound_on), SoundMode.ALERTS_ONLY.name to stringResource(R.string.sound_alerts), SoundMode.OFF.name to stringResource(R.string.sound_off)),
                s.soundMode.name,
            ) { vm.setSoundMode(SoundMode.valueOf(it)) }

            Section(stringResource(R.string.vehicle))
            Choice(listOf("car" to stringResource(R.string.car), "motorcycle" to stringResource(R.string.motorbike), "bicycle" to stringResource(R.string.bicycle), "walk" to stringResource(R.string.walk)), s.vehicle) { vm.setVehicleHome(it) }

            Spacer(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.clock_24h), null, s.use24h, vm::setUse24h)
            SwitchRow(stringResource(R.string.demo_mode), stringResource(R.string.demo_mode_desc), s.demoMode, vm::setDemoMode)
            SwitchRow(stringResource(R.string.simulate_drive), stringResource(R.string.simulate_drive_desc), s.simulateDrive, vm::setSimulate)

            Section(stringResource(R.string.server_url))
            TextSetting(null, s.apiBaseUrl, onApply = vm::setApiBaseUrl)
            Section(stringResource(R.string.map_style))
            TextSetting(null, s.mapStyleUrl, onApply = vm::setMapStyleUrl)

            Section(stringResource(R.string.about))
            Text(stringResource(R.string.about_text), color = c.textMuted, fontSize = 14.sp)
            Text("v${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"})", color = c.textMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 32.dp))
        }
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Gimano.colors.text, modifier = Modifier.weight(1f))
        Text("›", fontSize = 22.sp, color = Gimano.colors.textMuted)
    }
}

@Composable
private fun Section(t: String) {
    Text(t, Modifier.padding(top = 18.dp, bottom = 6.dp), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Gimano.colors.textMuted)
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val c = Gimano.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (k, label) ->
            FilterChip(
                selected = k == selected, onClick = { onSelect(k) }, label = { Text(label, fontSize = 14.sp) },
                shape = RoundedCornerShape(Radius.pill),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.brand, selectedLabelColor = c.brandOn),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = c.text)
            if (desc != null) Text(desc, fontSize = 13.sp, color = c.textMuted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.brand))
    }
}

@Composable
private fun TextSetting(label: String?, value: String, onApply: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            text, { text = it }, Modifier.weight(1f), singleLine = true,
            label = label?.let { { Text(it) } }, shape = RoundedCornerShape(Radius.md), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        )
        if (text != value) TextButton(onClick = { onApply(text) }) { Text(stringResource(R.string.apply)) }
    }
}
