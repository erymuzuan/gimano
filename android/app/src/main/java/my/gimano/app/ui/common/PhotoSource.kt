package my.gimano.app.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.gimano.app.R
import my.gimano.app.data.places.PhotoPrep
import my.gimano.app.ui.theme.Gimano
import java.io.File

/**
 * "Ambil gambar · Take photo" / "Pilih dari galeri · Choose from gallery". The camera writes to a cache file
 * through our FileProvider (no CAMERA permission: the system camera app takes the picture); a cancelled shot's
 * file is deleted at once. Returns a function that opens the sheet.
 */
@Composable
fun rememberPhotoSource(max: Int, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(max.coerceAtLeast(2))) { uris ->
        if (uris.isNotEmpty()) onPicked(uris.take(max))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPath?.let(::File)
        pendingPath = null
        if (ok && f != null && f.length() > 0) onPicked(listOf(PhotoPrep.cameraUri(ctx, f))) else f?.delete()
    }
    if (open) {
        val c = Gimano.colors
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_photo), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c.text, modifier = Modifier.padding(bottom = 4.dp))
                SourceRow(Icons.Filled.PhotoCamera, stringResource(R.string.photo_take)) {
                    open = false
                    val f = PhotoPrep.newCameraFile(ctx)
                    pendingPath = f.path
                    runCatching { camera.launch(PhotoPrep.cameraUri(ctx, f)) }.onFailure { f.delete(); pendingPath = null }
                }
                SourceRow(Icons.Filled.PhotoLibrary, stringResource(R.string.photo_choose)) {
                    open = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }
    return { open = true }
}

@Composable
private fun SourceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Gimano.colors
    Row(Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(16.dp), color = c.brandMist, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.brandInk, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = c.text)
    }
}
