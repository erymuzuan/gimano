package my.gimano.app.ui.place

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.places.Verification
import my.gimano.app.data.model.Photo
import my.gimano.app.data.model.Place
import my.gimano.app.data.model.Review
import my.gimano.app.data.model.SuggestEditRequest
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.PlaceSel
import my.gimano.app.ui.common.CategoryBadge
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.common.GrabHandle
import my.gimano.app.ui.common.Labels
import my.gimano.app.ui.common.SheetSurface
import my.gimano.app.ui.contribute.ContributeViewModel
import my.gimano.app.ui.contribute.UploadItem
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.io.File

/** Absolute URL for a media path (the API may return `/media/<key>.webp`). */
fun mediaUrl(url: String, apiBase: String): String =
    if (url.startsWith("/")) Regex("^(https?://[^/]+)").find(apiBase)?.value.orEmpty() + url else url

@Composable
fun PlaceCard(sel: PlaceSel, vm: MainViewModel, cvm: ContributeViewModel, apiBase: String, modifier: Modifier = Modifier) {
    val c = Gimano.colors
    val p = sel.summary
    val d = sel.detail
    val priv = sel.privatePlace
    val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    val reviews by cvm.reviews.collectAsStateWithLifecycle()
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var suggest by remember { mutableStateOf(false) }
    LaunchedEffect(d?.id) { if (d != null) cvm.loadReviews(d) }
    val addPhoto = my.gimano.app.ui.common.rememberPhotoSource(10) { uris ->
        if (d != null && uris.isNotEmpty()) cvm.addPlacePhotos(d, uris) { vm.refreshPlace() }
    }

    SheetSurface(modifier.fillMaxWidth().heightIn(max = maxH)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { GrabHandle() }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                CategoryBadge(p.category, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = GimanoType.title, color = c.text, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                        if (priv != null) Badge(stringResource(R.string.just_for_me), c.brandMist, c.brandInk, Icons.Filled.Lock)
                        if (d?.isCommunity == true) Badge(stringResource(R.string.community), c.brandMist, c.brandInk)
                        if (d?.isUnverified == true) my.gimano.app.ui.contribute.UnverifiedChip(d.confirmationsWeighted, d.required)
                        if (d?.isGold == true) my.gimano.app.ui.contribute.GoldChip()
                    }
                    val meta = buildList {
                        add(Labels.category(p.category))
                        d?.openNow?.let { add(if (it) stringResource(R.string.open_now) else stringResource(R.string.closed)) }
                        p.distanceM?.let { add(Formatters.distance(it)) }
                    }
                    Text(meta.joinToString(" · "), fontSize = 14.sp, color = if (d?.openNow == true) c.brand else c.textMuted, fontWeight = FontWeight.Bold)
                }
                PlaceOverflow(vm, canSuggest = d != null, onSuggest = { suggest = true })
                IconButton(onClick = vm::back) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = c.textMuted) }
            }
            val address = priv?.address ?: p.address
            if (address.isNotBlank()) Text(address, Modifier.padding(top = 6.dp), fontSize = 15.sp, color = c.textMuted)
            (priv?.openingHours ?: d?.openingHours)?.let { Text(stringResource(R.string.hours, it), Modifier.padding(top = 4.dp), fontSize = 14.sp, color = c.textMuted) }
            (priv?.description ?: d?.description)?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 15.sp, color = c.text) }
            if (p.id.startsWith("pin:")) {
                OutlinedButton(onClick = { cvm.startAdd(my.gimano.app.core.geo.GeoPoint(p.lat, p.lng), ""); vm.startAddPlace(my.gimano.app.core.geo.GeoPoint(p.lat, p.lng), "") },
                    Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp), shape = RoundedCornerShape(Radius.md)) {
                    Icon(Icons.Filled.AddLocationAlt, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_place_here), fontWeight = FontWeight.ExtraBold)
                }
            }
            if (sel.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp), color = c.brand, trackColor = c.surfaceAlt)

            // "Is this place here?" for nearby people (unverified community places).
            if (d != null && Verification.canVerify(d.isCommunity, d.status, p.distanceM, d.rural)) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(Radius.md)).background(c.brandMist).padding(14.dp)) {
                    my.gimano.app.ui.contribute.VerifyPanel(onAnswer = { a, n, l ->
                        cvm.verify(my.gimano.app.ui.contribute.VetTarget.of(d), a, n, l) { vm.refreshPlace() }
                    })
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action(if (sel.savedId != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, stringResource(if (sel.savedId != null) R.string.saved else R.string.save)) { vm.openFavoriteSheet() }
                val phone = priv?.phone ?: d?.phone
                Action(Icons.Filled.Call, stringResource(R.string.call), enabled = phone != null) { phone?.let(vm::call) }
                Action(Icons.Filled.Share, stringResource(R.string.share), enabled = priv == null) { vm.sharePlace() }
                val web = priv?.website ?: d?.website
                Action(Icons.Filled.Language, stringResource(R.string.website), enabled = web != null) { web?.let(vm::openWeb) }
            }

            // Photo strip (+ add photo, upload progress, processing state).
            val photoUrls = if (priv != null) sel.privatePhotos.map { Uri.fromFile(it).toString() } else d?.photos.orEmpty().map { mediaUrl(it.thumbUrl.ifBlank { it.url }, apiBase) }
            val fullUrls = if (priv != null) photoUrls else d?.photos.orEmpty().map { mediaUrl(it.url, apiBase) }
            if (photoUrls.isNotEmpty() || d != null || reviews.placeUploads.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    if (d != null) item {
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(Radius.md)).background(c.brandMist).clickable { addPhoto() },
                            contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.AddAPhoto, null, tint = c.brand, modifier = Modifier.size(32.dp))
                                Text(stringResource(R.string.add_photo), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk)
                            }
                        }
                    }
                    items(reviews.placeUploads.filter { it.photo?.status != "published" }) { u -> UploadTile(u, 96) }
                    items(photoUrls.size) { i ->
                        AsyncImage(photoUrls[i], stringResource(R.string.photos), Modifier.size(96.dp).clip(RoundedCornerShape(Radius.md)).clickable { viewer = fullUrls to i }, contentScale = ContentScale.Crop)
                    }
                }
            }

            if (d != null) ReviewsSection(d, reviews.reviews, reviews.summary, reviews.loading, cvm, apiBase) { urls, i -> viewer = urls to i }

            Button(
                onClick = vm::routeToPlace,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn),
            ) {
                Icon(Icons.Filled.Directions, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.routes), style = GimanoType.button)
            }
            if (priv != null) TextButton(onClick = { vm.deletePrivatePlace(priv.id) }, Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.delete), color = c.report, fontWeight = FontWeight.ExtraBold)
            }
        }
    }

    viewer?.let { (urls, start) -> PhotoViewer(urls, start) { viewer = null } }
    if (suggest && d != null) SuggestEditDialog(d, onDismiss = { suggest = false }) { body -> suggest = false; cvm.suggestEdit(d.id, body) }
    reviews.draft?.let { if (d != null) WriteReviewSheet(d, it, cvm) }
}

@Composable
fun UploadTile(u: UploadItem, sizeDp: Int) {
    val c = Gimano.colors
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(Radius.md))) {
        AsyncImage(u.uri, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            when {
                u.failed -> Icon(Icons.Filled.ErrorOutline, stringResource(R.string.action_failed), tint = Color.White)
                u.photo == null -> CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                u.processing -> Text(stringResource(R.string.processing), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                else -> Icon(Icons.Filled.CheckCircle, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color, icon: ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = fg)
    }
}

@Composable
private fun ReviewsSection(place: Place, reviews: List<Review>, summary: my.gimano.app.core.places.RatingSummary, loading: Boolean, cvm: ContributeViewModel, apiBase: String, onPhoto: (List<String>, Int) -> Unit) {
    val c = Gimano.colors
    Text(stringResource(R.string.reviews), fontWeight = FontWeight.Black, fontSize = 18.sp, color = c.text, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
            Text(if (summary.isNew) stringResource(R.string.rating_new) else "%.1f".format(summary.mean ?: 0.0), fontSize = 34.sp, fontWeight = FontWeight.Black, color = c.text)
            Stars(summary.mean ?: 0.0, 16)
            Text(stringResource(R.string.review_count, summary.count), fontSize = 12.sp, color = c.textMuted)
        }
        Column(Modifier.weight(1f)) {
            val max = (summary.distribution.values.maxOrNull() ?: 0).coerceAtLeast(1)
            (5 downTo 1).forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(16.dp)) {
                    Text("$n", fontSize = 12.sp, color = c.textMuted, modifier = Modifier.width(14.dp))
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceAlt)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((summary.distribution[n] ?: 0).toFloat() / max).background(c.report))
                    }
                }
            }
        }
    }
    val mine = reviews.firstOrNull { cvm.mine(it) }
    OutlinedButton(onClick = { cvm.openReview(mine) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(Radius.md)) {
        Icon(Icons.Filled.RateReview, null); Spacer(Modifier.width(8.dp))
        Text(stringResource(if (mine != null) R.string.edit_review else R.string.write_review), fontWeight = FontWeight.ExtraBold)
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = c.brand, trackColor = c.surfaceAlt)
    reviews.take(10).forEach { r -> ReviewRow(r, cvm, apiBase, onPhoto) }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Stars(value: Double, size: Int) {
    val c = Gimano.colors
    Row { (1..5).forEach { n -> GIcon("star", null, size = size.dp, stroke = if (value >= n - 0.25) c.report else c.outline, fill = if (value >= n - 0.25) c.report else Color.Transparent) } }
}

@Composable
private fun ReviewRow(r: Review, cvm: ContributeViewModel, apiBase: String, onPhoto: (List<String>, Int) -> Unit) {
    val c = Gimano.colors
    var flagMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(c.brandMist), contentAlignment = Alignment.Center) {
                Text(r.author.displayName.take(1).uppercase(), fontWeight = FontWeight.Black, color = c.brandInk)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(r.author.displayName.ifBlank { stringResource(R.string.app_name) } + (r.author.level?.let { " · L$it" } ?: ""), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) { Stars(r.rating.toDouble(), 12); Spacer(Modifier.width(6.dp)); Text(my.gimano.app.core.format.Formatters.localStamp(r.createdAt).take(10), fontSize = 12.sp, color = c.textMuted) }
            }
        }
        r.text?.let { Text(it, fontSize = 15.sp, color = c.text, modifier = Modifier.padding(top = 4.dp)) }
        if (r.photos.isNotEmpty()) {
            val urls = r.photos.map { mediaUrl(it.url, apiBase) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                items(r.photos.size) { i -> AsyncImage(mediaUrl(r.photos[i].thumbUrl.ifBlank { r.photos[i].url }, apiBase), null, Modifier.size(72.dp).clip(RoundedCornerShape(Radius.sm)).clickable { onPhoto(urls, i) }, contentScale = ContentScale.Crop) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { cvm.helpful(r) }) { Icon(Icons.Filled.ThumbUp, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.helpful_n, r.helpfulCount), fontSize = 13.sp) }
            Box {
                TextButton(onClick = { flagMenu = true }) { Icon(Icons.Filled.Flag, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.flag), fontSize = 13.sp) }
                DropdownMenu(flagMenu, { flagMenu = false }) {
                    listOf("spam" to R.string.flag_spam, "offensive" to R.string.flag_offensive, "off_topic" to R.string.flag_off_topic, "conflict_of_interest" to R.string.flag_conflict, "other" to R.string.fb_other).forEach { (k, l) ->
                        DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { flagMenu = false; cvm.flag(r, k) })
                    }
                }
            }
            if (cvm.mine(r)) TextButton(onClick = { cvm.openReview(r) }) { Text(stringResource(R.string.edit), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun WriteReviewSheet(place: Place, d: my.gimano.app.ui.contribute.ReviewDraft, cvm: ContributeViewModel) {
    val c = Gimano.colors
    val pick = my.gimano.app.ui.common.rememberPhotoSource(5) { cvm.addReviewPhotos(it) }
    ModalBottomSheet(onDismissRequest = cvm::closeReview, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding()) {
            Text(place.name, style = GimanoType.title, color = c.text)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { n ->
                    val on = d.rating >= n
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).clickable { cvm.updateDraft { it.copy(rating = n) } }, contentAlignment = Alignment.Center) {
                        GIcon("star", "$n", size = 44.dp, stroke = if (on) c.report else c.outline, fill = if (on) c.report else Color.Transparent)
                    }
                }
            }
            OutlinedTextField(d.text, { v -> cvm.updateDraft { it.copy(text = v.take(2000)) } }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                placeholder = { Text(stringResource(R.string.review_hint)) }, shape = RoundedCornerShape(Radius.md))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                items(d.uploads) { u -> UploadTile(u, 72) }
                if (d.uploads.size < 5) item {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(Radius.md)).background(c.brandMist).clickable { pick() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AddAPhoto, stringResource(R.string.add_photo), tint = c.brand)
                    }
                }
            }
            val uploading = d.uploads.any { it.photo == null && !it.failed }
            Button(onClick = { cvm.submitReview(place) }, enabled = d.rating in 1..5 && !d.sending && !uploading,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn)) {
                Text(stringResource(if (d.editingId != null) R.string.save else R.string.post_review), style = GimanoType.button)
            }
        }
    }
}

@Composable
private fun SuggestEditDialog(place: Place, onDismiss: () -> Unit, onSend: (SuggestEditRequest) -> Unit) {
    var name by remember { mutableStateOf(place.name) }
    var phone by remember { mutableStateOf(place.phone.orEmpty()) }
    var hours by remember { mutableStateOf(place.openingHours.orEmpty()) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suggest_edit), style = GimanoType.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.plan_name)) })
                OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text(stringResource(R.string.phone)) })
                OutlinedTextField(hours, { hours = it }, singleLine = true, label = { Text(stringResource(R.string.opening_hours)) })
                OutlinedTextField(comment, { comment = it }, minLines = 2, label = { Text(stringResource(R.string.comment_hint)) })
            }
        },
        confirmButton = {
            Button(onClick = {
                onSend(SuggestEditRequest(
                    name = name.takeIf { it != place.name && it.isNotBlank() }, phone = phone.takeIf { it != place.phone.orEmpty() },
                    openingHours = hours.takeIf { it != place.openingHours.orEmpty() }, comment = comment.ifBlank { null },
                ))
            }) { Text(stringResource(R.string.send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PhotoViewer(urls: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(initialPage = start) { urls.size }
            HorizontalPager(pager, Modifier.fillMaxSize()) { i -> AsyncImage(urls[i], null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            IconButton(onClick = onClose, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(8.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text("${pager.currentPage + 1} / ${urls.size}", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp))
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Gimano.colors
    Column(
        Modifier.clip(RoundedCornerShape(Radius.md)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.brandMist), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (enabled) c.brand else c.textMuted.copy(alpha = 0.5f))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (enabled) c.text else c.textMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PlaceOverflow(vm: MainViewModel, canSuggest: Boolean, onSuggest: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more), tint = Gimano.colors.textMuted) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (canSuggest) DropdownMenuItem(
                text = { Text(stringResource(R.string.suggest_edit), fontWeight = FontWeight.Bold) },
                leadingIcon = { Icon(Icons.Filled.EditNote, null) },
                onClick = { open = false; onSuggest() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_feedback), fontWeight = FontWeight.Bold) },
                leadingIcon = { GIcon("feedback", null, size = 24.dp) },
                onClick = { open = false; vm.requestFeedback("place") },
            )
        }
    }
}

@Suppress("unused") private fun unusedPhoto(p: Photo) = p
@Suppress("unused") private fun unusedFile(f: File) = f
