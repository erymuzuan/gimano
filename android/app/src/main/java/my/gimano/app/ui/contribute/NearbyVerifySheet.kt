package my.gimano.app.ui.contribute

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.data.model.PlaceSummary
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.common.CategoryGlyph
import my.gimano.app.ui.common.Labels
import my.gimano.app.ui.theme.Gimano

/** Canvas "Nearby driver: verify prompt": shown only while parked near a pending community place. */
@Composable
fun NearbyVerifySheet(p: PlaceSummary, vm: MainViewModel, cvm: ContributeViewModel) {
    val c = Gimano.colors
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 84.dp).height(40.dp)
                .clip(RoundedCornerShape(999.dp)).background(c.surface).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF0E8A5F)))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vet_parked), fontSize = 14.sp, fontWeight = FontWeight.Black, color = c.text)
        }
    }
    ModalBottomSheet(
        onDismissRequest = { vm.dismissNearby(remember = false) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UnverifiedChip(p.confirmationsWeighted, p.required)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)).background(c.brandMist), contentAlignment = Alignment.Center) { CategoryGlyph(p.category, 40.dp) }
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, fontSize = 19.sp, fontWeight = FontWeight.Black, color = c.text)
                    val meta = listOfNotNull(Labels.category(p.category), p.distanceM?.let { stringResource(R.string.vet_from_you, Formatters.distance(it)) })
                    Text(meta.joinToString(" · "), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
                    if (p.address.isNotBlank()) Text(p.address, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
                }
            }
            VerifyPanel(onAnswer = { a, n, l ->
                cvm.verify(VetTarget.of(p), a, n, l)
                vm.dismissNearby(remember = true)
            })
            TextButton(onClick = { vm.dismissNearby(remember = true) }, Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.vet_later), fontWeight = FontWeight.ExtraBold, color = c.textMuted)
            }
        }
    }
}
