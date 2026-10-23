package my.gimano.app.ui.contribute

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import my.gimano.app.AppContainer
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.map.MapHandle
import my.gimano.app.ui.theme.Gimano

@Composable
fun ContributionsScreen(c: AppContainer, vm: MainViewModel) {
    Box(Modifier.fillMaxSize().background(Gimano.colors.background), contentAlignment = Alignment.Center) { Text(androidx.compose.ui.res.stringResource(my.gimano.app.R.string.coming_soon)) }
}

@Composable
fun AddPlaceScreen(c: AppContainer, vm: MainViewModel, handle: MapHandle) {
    Box(Modifier.fillMaxSize().background(Gimano.colors.background), contentAlignment = Alignment.Center) { Text(androidx.compose.ui.res.stringResource(my.gimano.app.R.string.coming_soon)) }
}
