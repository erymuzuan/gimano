package my.gimano.app.ui.contribute

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.gimano.app.AppContainer
import my.gimano.app.R
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.places.OpeningHours
import my.gimano.app.core.places.RatingSummary
import my.gimano.app.data.model.*
import my.gimano.app.data.places.ContributionLog
import my.gimano.app.data.places.PhotoPrep
import my.gimano.app.data.remote.GimanoJson
import retrofit2.HttpException
import java.io.File
import java.util.UUID

enum class AddStep { PIN, DETAILS, VISIBILITY }

data class AddPlaceState(
    val step: AddStep = AddStep.PIN,
    val pin: GeoPoint = GeoPoint(0.0, 0.0),
    val address: String = "",
    val name: String = "",
    val category: String? = null,
    val phone: String = "",
    val website: String = "",
    val description: String = "",
    val amenities: Set<String> = emptySet(),
    val hoursEnabled: Boolean = false,
    val open247: Boolean = false,
    val week: Map<String, List<OpeningHours.Range>> = OpeningHours.DAYS.take(5).associateWith { listOf(OpeningHours.Range("09:00", "18:00")) },
    val photos: List<Uri> = emptyList(),
    val saving: Boolean = false,
    val duplicate: Place? = null,
    val error: String? = null,
) {
    val hours: String? get() = if (!hoursEnabled) null else OpeningHours.build(week, open247).ifBlank { null }
    val canContinue get() = name.isNotBlank() && category != null
}

/** One photo being attached: uploading → processing → published (or failed). */
data class UploadItem(val key: String, val uri: Uri, val photo: Photo? = null, val failed: Boolean = false) {
    val processing get() = photo?.status == "processing"
}

data class ReviewDraft(val rating: Int = 0, val text: String = "", val uploads: List<UploadItem> = emptyList(), val editingId: String? = null, val sending: Boolean = false)

data class ReviewsState(
    val placeId: String? = null,
    val reviews: List<Review> = emptyList(),
    val loading: Boolean = false,
    val summary: RatingSummary = RatingSummary.of(emptyList()),
    val draft: ReviewDraft? = null,
    val placeUploads: List<UploadItem> = emptyList(),
)

sealed interface ContribEvent {
    data class Message(val res: Int) : ContribEvent
    data class ShowPlace(val summary: PlaceSummary) : ContribEvent
}

class ContributeViewModel(private val c: AppContainer) : ViewModel() {
    private val _add = MutableStateFlow(AddPlaceState())
    val add: StateFlow<AddPlaceState> = _add.asStateFlow()
    private val _reviews = MutableStateFlow(ReviewsState())
    val reviews: StateFlow<ReviewsState> = _reviews.asStateFlow()
    private val _events = MutableSharedFlow<ContribEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ContribEvent> = _events
    val log = ContributionLog(c.db.outbox())

    // ---------------------------------------------------------------- add a place

    fun startAdd(at: GeoPoint, name: String) {
        _add.value = AddPlaceState(pin = at, name = name)
        onPinMoved(at)
    }

    private var geoJob: Job? = null

    /** The map moves under a fixed centre pin; the address follows (reverse geocoded). */
    fun onPinMoved(p: GeoPoint) {
        _add.update { it.copy(pin = p) }
        geoJob?.cancel()
        geoJob = viewModelScope.launch {
            delay(350)
            val r = runCatching { c.repo.reverse(p.lat, p.lng) }.getOrNull()
            _add.update { it.copy(address = r?.address?.ifBlank { r.name } ?: it.address) }
        }
    }

    fun update(f: (AddPlaceState) -> AddPlaceState) = _add.update(f)
    fun showExisting(p: Place) { _add.value = AddPlaceState(); _events.tryEmit(ContribEvent.ShowPlace(p.summary())) }
    fun step(s: AddStep) = _add.update { it.copy(step = s, error = null) }
    fun addPhotos(uris: List<Uri>) = _add.update { it.copy(photos = (it.photos + uris).distinct().take(10)) }
    fun removePhoto(u: Uri) = _add.update { it.copy(photos = it.photos - u) }

    /** "Just for me": stays in the on-device DB (and the user's own backup), never sent to the server. */
    fun savePrivate() {
        val s = _add.value
        if (!s.canContinue) return
        _add.update { it.copy(saving = true) }
        viewModelScope.launch {
            val dir = File(c.app.filesDir, "photos")
            val files = s.photos.mapNotNull { u -> PhotoPrep.decode(c.app, u)?.let { PhotoPrep.saveWebp(it, dir, UUID.randomUUID().toString()) } }
            val e = c.local.addPrivatePlace(s.name.trim(), s.category!!, s.pin.lat, s.pin.lng, s.address.ifBlank { null }, s.description.ifBlank { null },
                s.phone.ifBlank { null }, s.website.ifBlank { null }, s.hours, s.amenities.toList(), files)
            _add.value = AddPlaceState()
            _events.tryEmit(ContribEvent.Message(R.string.place_saved_private))
            _events.tryEmit(ContribEvent.ShowPlace(PlaceSummary("private:${e.id}", e.name, e.category, e.address ?: "", e.lat, e.lng)))
        }
    }

    /** "Share with everyone": POST /places, then upload the photos; other drivers verify it. */
    fun savePublic(force: Boolean = false) {
        val s = _add.value
        if (!s.canContinue) return
        _add.update { it.copy(saving = true, error = null, duplicate = if (force) null else it.duplicate) }
        viewModelScope.launch {
            try {
                val p = c.repo.createPlace(CreatePlaceRequest(
                    s.name.trim(), s.category!!, s.pin.lat, s.pin.lng, s.address.ifBlank { null }, s.description.ifBlank { null },
                    s.phone.ifBlank { null }, s.website.ifBlank { null }, s.hours, s.amenities.toList().ifEmpty { null },
                ))
                s.photos.forEach { u -> PhotoPrep.decode(c.app, u)?.let { b -> runCatching { c.repo.uploadPhoto(p.id, PhotoPrep.jpeg(b), null) }.onSuccess { ph -> log.photo(ph.id, ph.url) } } }
                _add.value = AddPlaceState()
                _events.tryEmit(ContribEvent.Message(R.string.place_added_public))
                _events.tryEmit(ContribEvent.ShowPlace(p.summary()))
            } catch (e: HttpException) {
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
                if (e.code() == 409) {
                    val existing = body?.let { b -> runCatching { GimanoJson.decodeFromString(Place.serializer(), b) }.getOrNull()
                        ?: runCatching { GimanoJson.decodeFromString(DuplicateProblem.serializer(), b).place }.getOrNull() }
                    _add.update { it.copy(saving = false, duplicate = existing, error = if (existing == null) "duplicate" else null) }
                } else {
                    _add.update { it.copy(saving = false, error = problemDetail(body) ?: "HTTP ${e.code()}") }
                }
            } catch (e: Exception) {
                _add.update { it.copy(saving = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun problemDetail(body: String?): String? = body?.let { b ->
        runCatching { GimanoJson.decodeFromString(ProblemDetails.serializer(), b) }.getOrNull()?.let { p ->
            p.errors?.toString()?.replace(Regex("[\\[\\]{}\"]"), "")?.replace(":", ": ")?.takeIf { it.isNotBlank() } ?: p.detail ?: p.title
        }
    }

    // ---------------------------------------------------------------- verify / suggest an edit

    fun verify(placeId: String, exists: Boolean, onDone: (Place) -> Unit) = viewModelScope.launch {
        runCatching { c.repo.verifyPlace(placeId, exists) }
            .onSuccess { onDone(it); _events.tryEmit(ContribEvent.Message(R.string.thanks_verify)) }
            .onFailure { _events.tryEmit(ContribEvent.Message(R.string.action_failed)) }
    }

    fun suggestEdit(placeId: String, body: SuggestEditRequest) = viewModelScope.launch {
        runCatching { c.repo.suggestEdit(placeId, body) }
            .onSuccess { _events.tryEmit(ContribEvent.Message(R.string.thanks_suggestion)) }
            .onFailure { _events.tryEmit(ContribEvent.Message(R.string.action_failed)) }
    }

    // ---------------------------------------------------------------- reviews & photos

    fun loadReviews(place: Place) {
        _reviews.value = ReviewsState(placeId = place.id, loading = true, summary = RatingSummary.of(emptyList(), place.rating, place.reviewCount))
        viewModelScope.launch {
            val page = runCatching { c.repo.reviews(place.id, "recent") }.getOrNull()
            val list = page?.reviews.orEmpty()
            _reviews.update {
                if (it.placeId != place.id) it else it.copy(reviews = list, loading = false,
                    summary = RatingSummary.of(list.map { r -> r.rating }, place.rating ?: list.takeIf { l -> l.isNotEmpty() }?.map { r -> r.rating }?.average(), maxOf(place.reviewCount, list.size)))
            }
        }
    }

    private var myReviewIds = HashSet<String>()
    fun mine(r: Review) = r.mine || r.id in myReviewIds

    fun openReview(existing: Review? = null) = _reviews.update {
        it.copy(draft = existing?.let { r -> ReviewDraft(r.rating, r.text.orEmpty(), editingId = r.id) } ?: ReviewDraft())
    }
    fun closeReview() = _reviews.update { it.copy(draft = null) }
    fun updateDraft(f: (ReviewDraft) -> ReviewDraft) = _reviews.update { it.copy(draft = it.draft?.let(f)) }

    /** Review photos upload as soon as they are picked (≤ 5); the review then references them. */
    fun addReviewPhotos(uris: List<Uri>) {
        val placeId = _reviews.value.placeId ?: return
        val room = 5 - (_reviews.value.draft?.uploads?.size ?: 0)
        uris.take(room).forEach { u ->
            val item = UploadItem(UUID.randomUUID().toString(), u)
            updateDraft { it.copy(uploads = it.uploads + item) }
            viewModelScope.launch {
                val ph = upload(placeId, u)
                updateDraft { d -> d.copy(uploads = d.uploads.map { if (it.key == item.key) it.copy(photo = ph, failed = ph == null) else it }) }
            }
        }
    }

    private suspend fun upload(placeId: String, u: Uri): Photo? {
        val b = PhotoPrep.decode(c.app, u) ?: return null
        return runCatching { c.repo.uploadPhoto(placeId, PhotoPrep.jpeg(b), null) }.getOrNull()?.also { log.photo(it.id, it.url) }
    }

    fun submitReview(place: Place) {
        val d = _reviews.value.draft ?: return
        if (d.rating !in 1..5) return
        updateDraft { it.copy(sending = true) }
        viewModelScope.launch {
            val body = ReviewRequest(d.rating, d.text.trim().ifBlank { null }, d.uploads.mapNotNull { it.photo?.id }.ifEmpty { null })
            runCatching { if (d.editingId != null) c.repo.updateReview(d.editingId, body) else c.repo.createReview(place.id, body) }
                .onSuccess { r ->
                    myReviewIds += r.id
                    log.review(r.id, place.name)
                    _reviews.update { it.copy(draft = null) }
                    _events.tryEmit(ContribEvent.Message(R.string.review_posted))
                    loadReviews(place)
                }
                .onFailure { e ->
                    updateDraft { it.copy(sending = false) }
                    _events.tryEmit(ContribEvent.Message(if ((e as? HttpException)?.code() == 409) R.string.review_one_per_place else R.string.action_failed))
                }
        }
    }

    fun helpful(r: Review) = viewModelScope.launch {
        runCatching { c.repo.helpful(r.id) }.onSuccess {
            _reviews.update { s -> s.copy(reviews = s.reviews.map { if (it.id == r.id) it.copy(helpfulCount = it.helpfulCount + 1) else it }) }
        }
    }

    fun flag(r: Review, reason: String) = viewModelScope.launch {
        runCatching { c.repo.flagReview(r.id, reason) }.onSuccess { _events.tryEmit(ContribEvent.Message(R.string.thanks_flag)) }
    }

    /** "Add photo" on the place card: upload with progress, then show the processing state. */
    fun addPlacePhotos(place: Place, uris: List<Uri>, onDone: () -> Unit) {
        uris.take(10).forEach { u ->
            val item = UploadItem(UUID.randomUUID().toString(), u)
            _reviews.update { it.copy(placeUploads = it.placeUploads + item) }
            viewModelScope.launch {
                val ph = upload(place.id, u)
                _reviews.update { s -> s.copy(placeUploads = s.placeUploads.map { if (it.key == item.key) it.copy(photo = ph, failed = ph == null) else it }) }
                if (ph != null) onDone()
            }
        }
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ContributeViewModel(c) as T
    }
}

@kotlinx.serialization.Serializable
data class DuplicateProblem(val place: Place? = null)
