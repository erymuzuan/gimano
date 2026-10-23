package my.gimano.app.ui.place

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.places.RatingSummary
import my.gimano.app.core.places.Verification
import my.gimano.app.data.model.Place
import my.gimano.app.data.model.Review
import my.gimano.app.data.model.SuggestEditRequest
import my.gimano.app.ui.MainViewModel
import my.gimano.app.ui.PlaceSel
import my.gimano.app.ui.common.Attribution
import my.gimano.app.ui.common.CategoryBadge
import my.gimano.app.ui.common.GIcon
import my.gimano.app.ui.common.GrabHandle
import my.gimano.app.ui.common.Labels
import my.gimano.app.ui.common.RoundFab
import my.gimano.app.ui.common.SheetSurface
import my.gimano.app.ui.common.TwoTone
import my.gimano.app.ui.common.TwoToneIcon
import my.gimano.app.ui.common.TwoTonePalette
import my.gimano.app.ui.contribute.ContributeViewModel
import my.gimano.app.ui.contribute.UploadItem
import my.gimano.app.ui.theme.Gimano
import my.gimano.app.ui.theme.GimanoType
import my.gimano.app.ui.theme.Radius
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Absolute URL for a media path (the API may return `/media/<key>.webp`). */
fun mediaUrl(url: String, apiBase: String): String =
    if (url.startsWith("/")) Regex("^(https?://[^/]+)").find(apiBase)?.value.orEmpty() + url else url

private val StarGold = Color(0xFFF5B301)
private val StarPale = Color(0xFFF9D77A)

/**
 * Place card (canvas "Place card from search"): two states, [PlaceSheetState].
 * - PEEK (default, ≤ 40 % of the screen): name, the rating line, category · address · open, Routes, and
 *   Save · Call · Share · Website.
 * - FULL (swipe up): + details, reviews (average, 5→1 bars, latest review, Write a review), photos with
 *   "Add photo" at the end, and Suggest an edit.
 * The resting height goes to [MainViewModel.setPlaceSheetDp], so the map centres the pin in the map left visible.
 */
@Composable
fun PlaceCard(sel: PlaceSel, vm: MainViewModel, cvm: ContributeViewModel, apiBase: String, modifier: Modifier = Modifier) =
    BoxWithConstraints(modifier.fillMaxSize()) {
    val c = Gimano.colors
    val p = sel.summary
    val d = sel.detail
    val priv = sel.privatePlace
    val density = LocalDensity.current
    val screenH = maxHeight.value
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
    val reviews by cvm.reviews.collectAsStateWithLifecycle()
    val uiState by vm.ui.collectAsState()
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var suggest by remember { mutableStateOf(false) }
    LaunchedEffect(d?.id) { if (d != null) cvm.loadReviews(d) }
    val addPhoto = my.gimano.app.ui.common.rememberPhotoSource(10) { uris ->
        if (d != null && uris.isNotEmpty()) cvm.addPlacePhotos(d, uris) { vm.refreshPlace() }
    }

    // --- Sheet geometry: measured content, two anchors, drag / fling / nested scroll (as the home panel) ---
    fun px(dp: Float) = dp * density.density
    var peekContentPx by remember { mutableIntStateOf(0) }
    var allContentPx by remember { mutableIntStateOf(0) }
    val measured = peekContentPx > 0
    val peekDp = if (measured) PlaceSheetLayout.peekHeight(peekContentPx / density.density, navInset, screenH) else PlaceSheetLayout.peekCap(screenH)
    val fullDp = PlaceSheetLayout.fullHeight(peekDp, allContentPx / density.density, navInset, screenH, statusInset)
    val anchors = PlaceSheetLayout.anchors(peekDp, fullDp)
    val anchorsPx = anchors.map(::px)
    var state by remember(p.id) { mutableStateOf(PlaceSheetState.PEEK) }
    var heightPx by remember(p.id) { mutableFloatStateOf(-1f) }
    val scope = rememberCoroutineScope()
    var anim by remember { mutableStateOf<Job?>(null) }
    val scroll = rememberScrollState()
    fun animateTo(target: Float) {
        anim?.cancel()
        val from = if (heightPx < 0f) target else heightPx
        anim = scope.launch { animate(from, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> heightPx = v } }
    }
    fun go(s: PlaceSheetState) { state = s; animateTo(anchorsPx[s.ordinal]) }
    fun dragBy(dyPx: Float): Float {   // dy > 0: finger down (shrink). Returns the part used.
        anim?.cancel()
        val cur = if (heightPx < 0f) anchorsPx[0] else heightPx
        val next = (cur - dyPx).coerceIn(anchorsPx.first(), anchorsPx.last())
        heightPx = next
        return cur - next
    }
    fun release(downVelocityPx: Float) =
        go(PlaceSheetLayout.settle(heightPx / density.density, -downVelocityPx / density.density, anchors, state))
    // First measurement snaps; later changes (details loaded, font scale, rotation) animate to the new anchor.
    LaunchedEffect(state, anchorsPx[0], anchorsPx[1], measured) {
        if (!measured) return@LaunchedEffect
        val target = anchorsPx[state.ordinal]
        if (heightPx < 0f) heightPx = target else if (abs(heightPx - target) > 1f) animateTo(target)
    }
    // The map centres the pin above the resting card (recomputed whenever the resting height changes).
    LaunchedEffect(state, anchors[0].roundToInt(), anchors[1].roundToInt(), measured) {
        if (measured) vm.setPlaceSheetDp(PlaceSheetLayout.cameraBottomPaddingDp(anchors[state.ordinal], screenH).roundToInt())
    }
    LaunchedEffect(state) { if (state == PlaceSheetState.PEEK) scroll.animateScrollTo(0) }
    BackHandler(enabled = state == PlaceSheetState.FULL) { go(PlaceSheetState.PEEK) }

    val sheetPx = if (heightPx < 0f) anchorsPx[0] else heightPx
    val progress = ((sheetPx - anchorsPx[0]) / (anchorsPx[1] - anchorsPx[0]).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val nested = remember(anchorsPx[0], anchorsPx[1]) {
        object : NestedScrollConnection {
            // Finger up: open the card before its content scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0 && source == NestedScrollSource.UserInput && (if (heightPx < 0f) anchorsPx[0] else heightPx) < anchorsPx.last() - 0.5f)
                    Offset(0f, -dragBy(available.y)) else Offset.Zero
            // Finger down with the content at its top: close it.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, -dragBy(available.y)) else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (anchorsPx.none { abs(it - heightPx) < 1f }) { release(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    // Attribution (left) and "back to the pin" (right) ride just above the card; the recentre button fades as it opens.
    Box(
        Modifier.fillMaxWidth().align(Alignment.BottomStart)
            .offset { IntOffset(0, -sheetPx.roundToInt()) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Attribution(Modifier.align(Alignment.BottomStart))
        if (progress < 0.5f) RoundFab(
            Icons.Filled.MyLocation, stringResource(R.string.recenter_on_place),
            Modifier.align(Alignment.BottomEnd).alpha(1f - progress * 2f), size = 52.dp, onClick = vm::recenterOnPlace,
        )
    }

    val sheetLabel = stringResource(R.string.place_sheet)
    val stateLabel = stringResource(if (state == PlaceSheetState.PEEK) R.string.place_sheet_peek else R.string.place_sheet_full)
    val expandLabel = stringResource(R.string.home_sheet_expand)
    val collapseLabel = stringResource(R.string.home_sheet_collapse)
    SheetSurface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { sheetPx.toDp() })) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxWidth().nestedScroll(nested).verticalScroll(scroll)) {
                Column(Modifier.fillMaxWidth().onSizeChanged { allContentPx = it.height }) {
                    // ---------------- PEEK ----------------
                    Column(Modifier.fillMaxWidth().onSizeChanged { peekContentPx = it.height }.padding(horizontal = 16.dp)) {
                        // Handle: tap toggles peek ↔ full. TalkBack: "Place card, collapsed. Swipe up for reviews and photos."
                        Box(
                            Modifier.fillMaxWidth().height(22.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "$sheetLabel, $stateLabel"
                                    if (state == PlaceSheetState.PEEK) expand { go(PlaceSheetState.FULL); true }
                                    else collapse { go(PlaceSheetState.PEEK); true }
                                }
                                .clickable(onClickLabel = if (state == PlaceSheetState.PEEK) expandLabel else collapseLabel) {
                                    go(if (state == PlaceSheetState.PEEK) PlaceSheetState.FULL else PlaceSheetState.PEEK)
                                },
                            contentAlignment = Alignment.TopCenter,
                        ) { GrabHandle(Modifier.padding(top = 8.dp)) }
                        PeekHeader(sel, vm, reviews, onSuggest = { suggest = true })
                        if (p.id.startsWith("pin:")) {
                            OutlinedButton(onClick = { cvm.startAdd(my.gimano.app.core.geo.GeoPoint(p.lat, p.lng), ""); vm.startAddPlace(my.gimano.app.core.geo.GeoPoint(p.lat, p.lng), "") },
                                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp), shape = RoundedCornerShape(Radius.md)) {
                                Icon(Icons.Filled.AddLocationAlt, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_place_here), fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        Button(
                            onClick = vm::routeToPlace,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.brandOn),
                        ) {
                            val v = remember(c.brandOn) { TwoTone.vector("route", c.brandOn.copy(alpha = 0.25f), c.brandOn) }
                            Image(rememberVectorPainter(v), null, Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.routes), style = GimanoType.button, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        }
                        ActionRow(sel, vm)
                    }

                    // ---------------- FULL ----------------
                    // Hidden from TalkBack while collapsed (it is below the card's edge).
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).alpha((progress * 3f).coerceAtMost(1f))
                            .then(if (state == PlaceSheetState.PEEK && progress < 0.01f) Modifier.clearAndSetSemantics { } else Modifier),
                    ) {
                        Details(sel, vm, cvm)
                        if (d != null) ReviewsSection(d, reviews.reviews, reviews.summary, reviews.loading, cvm, apiBase) { urls, i -> viewer = urls to i }

                        // Photos, with "Add photo" (camera or gallery; EXIF stripped before upload) at the END of the strip.
                        val photoUrls = if (priv != null) sel.privatePhotos.map { Uri.fromFile(it).toString() } else d?.photos.orEmpty().map { mediaUrl(it.thumbUrl.ifBlank { it.url }, apiBase) }
                        val fullUrls = if (priv != null) photoUrls else d?.photos.orEmpty().map { mediaUrl(it.url, apiBase) }
                        val pending = reviews.placeUploads.filter { it.photo?.status != "published" }
                        if (photoUrls.isNotEmpty() || d != null || pending.isNotEmpty()) {
                            SectionLabel(stringResource(R.string.photos))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                items(photoUrls.size) { i ->
                                    AsyncImage(photoUrls[i], stringResource(R.string.photos), Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).clickable { viewer = fullUrls to i }, contentScale = ContentScale.Crop)
                                }
                                items(pending) { u -> UploadTile(u, 96) }
                                if (d != null) item {
                                    Column(
                                        Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(c.brandMist).clickable { addPhoto() },
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                    ) {
                                        TwoToneIcon("add_photo", null, size = 30.dp)
                                        Text(stringResource(R.string.add_photo), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }
                        if (d != null) Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).clickable { suggest = true }.padding(horizontal = 2.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TwoToneIcon("edit", null, size = 22.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.suggest_edit), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.brandInk)
                        }
                        if (priv != null) TextButton(onClick = { vm.deletePrivatePlace(priv.id) }, Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.delete), color = c.report, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Spacer(Modifier.height(navInset.dp))
            }
            if (sel.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 18.dp, start = 40.dp, end = 40.dp).height(2.dp), color = c.brand, trackColor = c.surfaceAlt)
        }
    }

    viewer?.let { (urls, start) -> PhotoViewer(urls, start) { viewer = null } }
    // "Cadangkan suntingan": the six complaint options when the server has /issues; otherwise the classic edit form.
    var suggestForm by remember { mutableStateOf(false) }
    val issuesOn = uiState.issuesAvailable
    if (suggest && d != null && issuesOn) my.gimano.app.ui.issues.PlaceSuggestSheet(
        onDismiss = { suggest = false },
        onClosedPerm = { suggest = false; vm.reportPlaceIssue(my.gimano.app.data.model.IssueKind.PLACE_CLOSED_PERM) },
        onClosedTemp = { day -> suggest = false; vm.reportPlaceIssue(my.gimano.app.data.model.IssueKind.PLACE_CLOSED_TEMP, mapOf("until" to day.toString())) },
        onHours = { h -> suggest = false; vm.reportPlaceIssue(my.gimano.app.data.model.IssueKind.PLACE_HOURS_WRONG, mapOf("openingHours" to h)) },
        onEntrance = { suggest = false; vm.pickEntrance() },
        onInfo = { suggest = false; suggestForm = true },
        onDuplicate = { other -> suggest = false; vm.reportPlaceIssue(my.gimano.app.data.model.IssueKind.PLACE_DUPLICATE, other?.let { mapOf("comment" to it) }) },
    )
    if (((suggest && !issuesOn) || suggestForm) && d != null) SuggestEditDialog(d, onDismiss = { suggest = false; suggestForm = false }) { body ->
        suggest = false; suggestForm = false; cvm.suggestEdit(d.id, body)
    }
    reviews.draft?.let { if (d != null) WriteReviewSheet(d, it, cvm) }
}

/** Icon tile, name, the one rating line, badges, and "category · address · open". */
@Composable
private fun PeekHeader(sel: PlaceSel, vm: MainViewModel, reviews: my.gimano.app.ui.contribute.ReviewsState, onSuggest: () -> Unit) {
    val c = Gimano.colors
    val p = sel.summary
    val d = sel.detail
    val priv = sel.privatePlace
    // Distance only from a real fix (search distances are from the map view otherwise).
    val distance = p.distanceM?.takeIf { vm.myFreshLocation() != null }?.let { Formatters.distance(it) }
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(p.category, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 21.sp, fontWeight = FontWeight.Black, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 24.sp)
            when {
                priv != null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Badge(stringResource(R.string.just_for_me), c.brandMist, c.brandInk, Icons.Filled.Lock)
                    distance?.let { Text("  $it", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted) }
                }
                p.id.startsWith("pin:") -> distance?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted) }
                else -> {
                    // The card's review summary once loaded for this place, else the detail's / search result's figures.
                    val summary = reviews.summary.takeIf { reviews.placeId != null && reviews.placeId == d?.id }
                    val mean = summary?.mean ?: d?.rating ?: p.rating
                    val count = summary?.count ?: d?.reviewCount ?: p.reviewCount
                    RatingLineView(mean, count, distance)
                }
            }
        }
        PlaceOverflow(vm, canSuggest = d != null, onSuggest = onSuggest)
        IconButton(onClick = vm::back) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = c.textMuted) }
    }
    if (d?.isCommunity == true || d?.isUnverified == true || d?.isGold == true) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (d.isCommunity) Badge(stringResource(R.string.community), c.brandMist, c.brandInk)
            if (d.isUnverified) my.gimano.app.ui.contribute.UnverifiedChip(d.confirmationsWeighted, d.required)
            if (d.isGold) my.gimano.app.ui.contribute.GoldChip()
        }
    }
    val uiState by vm.ui.collectAsState()
    if (priv == null) my.gimano.app.ui.issues.PlaceIssueBadges(uiState.placeIssues, vm::votePlaceIssue)
    val address = priv?.address ?: p.address
    val open = d?.openNow
    val openText = open?.let { stringResource(if (it) R.string.open_now else R.string.closed) }
    val meta = buildAnnotatedString {
        append(listOf(Labels.category(p.category), address).filter { it.isNotBlank() }.joinToString(" · "))
        if (openText != null) {
            append(" · ")
            withStyle(SpanStyle(color = if (open == true) c.brand else c.report, fontWeight = FontWeight.ExtraBold)) { append(openText) }
        }
    }
    Text(meta, Modifier.padding(top = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** ★★★★☆ 4.3 · 12 ulasan · 450 m, or grey stars and "Baharu · 450 m" with no reviews. */
@Composable
private fun RatingLineView(mean: Double?, count: Int, distance: String?) {
    val c = Gimano.colors
    val newLabel = stringResource(R.string.rating_new)
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val line = RatingLine.of(mean, count, distance, newLabel, countLabel = { n -> res.getQuantityString(R.plurals.review_count_n, n, n) },
        decimal = { String.format(Locale.US, "%.1f", it) })
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp).semantics(mergeDescendants = true) { }) {
        StarRow(line.stars, 16)
        Spacer(Modifier.width(6.dp))
        Text(
            buildAnnotatedString {
                line.mean?.let { withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Black)) { append(it) }; append(" · ") }
                append(line.parts.joinToString(" · "))
            },
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StarRow(stars: List<StarFill>, size: Int) {
    val c = Gimano.colors
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        stars.forEach { f ->
            val fill = when (f) { StarFill.FULL -> StarGold; StarFill.PALE -> StarPale; StarFill.EMPTY -> c.outline }
            GIcon("star", null, size = size.dp, stroke = fill, fill = fill)
        }
    }
}

@Composable
private fun Stars(value: Double, size: Int) = StarRow(RatingLine.stars(value), size)

/** Save · Call · Share · Website (four equal columns, flat two-tone icons in pale cobalt circles). */
@Composable
private fun ActionRow(sel: PlaceSel, vm: MainViewModel) {
    val d = sel.detail
    val priv = sel.privatePlace
    val phone = priv?.phone ?: d?.phone
    val web = priv?.website ?: d?.website
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        val saved = sel.savedId != null
        Action("bookmark", stringResource(if (saved) R.string.saved else R.string.save), if (saved) TwoTonePalette.GOLD else TwoTonePalette.COBALT) { vm.openFavoriteSheet() }
        Action("call", stringResource(R.string.call), enabled = phone != null) { phone?.let(vm::call) }
        Action("share", stringResource(R.string.share), enabled = priv == null) { vm.sharePlace() }
        Action("website", stringResource(R.string.website), enabled = web != null) { web?.let(vm::openWeb) }
    }
}

@Composable
private fun RowScope.Action(icon: String, label: String, palette: TwoTonePalette = TwoTonePalette.COBALT, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Gimano.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(Radius.md)).clickable(enabled = enabled, onClickLabel = label, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (enabled) c.brandMist else c.surfaceAlt), contentAlignment = Alignment.Center) {
            TwoToneIcon(icon, null, size = 26.dp, palette = if (enabled) palette else TwoTonePalette.MUTED)
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (enabled) c.textMuted else c.textMuted.copy(alpha = 0.5f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Full only: opening hours, description, and "Is this place here?" for nearby people. */
@Composable
private fun Details(sel: PlaceSel, vm: MainViewModel, cvm: ContributeViewModel) {
    val c = Gimano.colors
    val p = sel.summary
    val d = sel.detail
    val priv = sel.privatePlace
    (priv?.openingHours ?: d?.openingHours)?.let { Text(stringResource(R.string.hours, it), Modifier.padding(top = 2.dp, bottom = 4.dp), fontSize = 14.sp, color = c.textMuted) }
    (priv?.description ?: d?.description)?.let { Text(it, Modifier.padding(bottom = 6.dp), fontSize = 15.sp, color = c.text) }
    if (d != null && Verification.canVerify(d.isCommunity, d.status, p.distanceM, d.rural)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(Radius.md)).background(c.brandMist).padding(14.dp)) {
            my.gimano.app.ui.contribute.VerifyPanel(onAnswer = { a, n, l ->
                cvm.verify(my.gimano.app.ui.contribute.VetTarget.of(d), a, n, l) { vm.refreshPlace() }
            })
        }
    }
}

@Composable
private fun SectionLabel(t: String) {
    Text(t.uppercase(), Modifier.padding(top = 10.dp, bottom = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp, color = Gimano.colors.textMuted)
}

/** Big average, 5→1 bars, the latest review (the rest on request), and Write a review. No bars without reviews. */
@Composable
private fun ReviewsSection(place: Place, reviews: List<Review>, summary: RatingSummary, loading: Boolean, cvm: ContributeViewModel, apiBase: String, onPhoto: (List<String>, Int) -> Unit) {
    val c = Gimano.colors
    SectionLabel(stringResource(R.string.reviews))
    if (summary.count > 0) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 86.dp)) {
                Text(if (summary.isNew) stringResource(R.string.rating_new) else String.format(Locale.US, "%.1f", summary.mean ?: 0.0),
                    fontSize = if (summary.isNew) 22.sp else 34.sp, fontWeight = FontWeight.Black, color = c.text)
                Stars(summary.mean ?: 0.0, 14)
                Text(pluralStringResource(R.plurals.review_count_n, summary.count, summary.count), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textMuted)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val total = summary.distribution.values.sum().coerceAtLeast(1)
                (5 downTo 1).forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textMuted, modifier = Modifier.width(14.dp))
                        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceAlt)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((summary.distribution[n] ?: 0).toFloat() / total).background(StarGold))
                        }
                    }
                }
            }
        }
    } else if (!loading) {
        Text(stringResource(R.string.no_reviews_yet), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textMuted, modifier = Modifier.padding(bottom = 8.dp))
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = c.brand, trackColor = c.surfaceAlt)
    var all by remember(place.id) { mutableStateOf(false) }
    val shown = if (all) reviews.take(10) else reviews.take(1)
    shown.forEachIndexed { i, r ->
        Box(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (i == 0) c.background else Color.Transparent).padding(horizontal = 12.dp)) {
            ReviewRow(r, cvm, apiBase, onPhoto)
        }
    }
    if (reviews.size > 1) TextButton(onClick = { all = !all }) {
        Text(if (all) stringResource(R.string.fewer_reviews) else stringResource(R.string.all_reviews, reviews.size.coerceAtMost(10)), fontWeight = FontWeight.ExtraBold)
    }
    val mine = reviews.firstOrNull { cvm.mine(it) }
    OutlinedButton(
        onClick = { cvm.openReview(mine) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
        shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(2.dp, c.outline),
    ) {
        TwoToneIcon("review", null, size = 22.dp); Spacer(Modifier.width(8.dp))
        Text(stringResource(if (mine != null) R.string.edit_review else R.string.write_review), fontSize = 15.sp, fontWeight = FontWeight.Black, color = c.brandInk)
    }
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
