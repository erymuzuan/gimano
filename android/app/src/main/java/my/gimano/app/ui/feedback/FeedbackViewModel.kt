package my.gimano.app.ui.feedback

import android.os.Build
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.gimano.app.AppContainer
import my.gimano.app.BuildConfig
import my.gimano.app.core.feedback.FeedbackRules
import my.gimano.app.data.feedback.FeedbackItem
import my.gimano.app.data.feedback.FeedbackSendResult
import my.gimano.app.data.model.FeedbackRequest

data class FeedbackDraft(
    val open: Boolean = false,
    val screen: String = "other",
    val category: String? = null,
    val message: String = "",
    val rating: Int? = null,
    val screenshot: ByteArray? = null,
    val includeScreenshot: Boolean = true,
    /** "Sertakan maklumat peranti" (default on; previewed with "Lihat apa yang dihantar"). */
    val includeDevice: Boolean = true,
    /** "Sertakan lokasi kasar" (default OFF; rounded to 2 dp on the phone). */
    val includeLocation: Boolean = false,
    /** "Boleh kami hubungi anda tentang laporan ini?": never prefilled. */
    val email: String = "",
    val whatsapp: String = "+60 ",
    val consent: Boolean = false,
    val routeId: String? = null,
    val placeId: String? = null,
    val sending: Boolean = false,
) {
    val contact get() = my.gimano.app.core.feedback.ContactRules.state(email, whatsapp, consent)
    val canSend get() = category != null && FeedbackRules.validMessage(message) && !sending &&
        (contact is my.gimano.app.core.feedback.ContactRules.State.None || contact is my.gimano.app.core.feedback.ContactRules.State.Ok)
}

class FeedbackViewModel(private val c: AppContainer) : ViewModel() {
    private val _draft = MutableStateFlow(FeedbackDraft())
    val draft: StateFlow<FeedbackDraft> = _draft.asStateFlow()

    private val _result = MutableSharedFlow<FeedbackSendResult>(extraBufferCapacity = 2)
    val result: SharedFlow<FeedbackSendResult> = _result

    private val statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Server-side "has contact details" per feedback id (the phone keeps no copy of them). */
    val contacts = MutableStateFlow<Set<String>>(emptySet())
    /** "My feedback": local queue + sent items, with the latest server status when available. */
    val items = combine(c.feedback.localItems(), statuses) { list, st -> list.map { i -> st[i.id]?.let { i.copy(status = it) } ?: i } }

    fun open(screen: String, screenshot: ByteArray?, routeId: String?, placeId: String?) {
        _draft.value = FeedbackDraft(
            open = true, screen = screen, screenshot = screenshot, includeScreenshot = screenshot != null,
            includeDevice = c.settings.current.feedbackDiagnostics, routeId = routeId, placeId = placeId,
        )
    }

    fun update(f: (FeedbackDraft) -> FeedbackDraft) = _draft.update(f)
    fun close() = _draft.update { it.copy(open = false) }

    fun send(lat: Double?, lng: Double?) {
        val d = _draft.value
        if (!d.canSend) return
        _draft.update { it.copy(sending = true) }
        viewModelScope.launch {
            c.settings.setFeedbackDiagnostics(d.includeDevice)
            val contact = (d.contact as? my.gimano.app.core.feedback.ContactRules.State.Ok)?.let { my.gimano.app.data.model.ContactDetails(it.email, it.whatsapp, consent = true) }
            val req = FeedbackRequest(
                category = d.category!!, message = d.message.trim().take(FeedbackRules.MAX_MESSAGE), rating = d.rating, screen = d.screen,
                device = if (d.includeDevice) c.deviceInfo.current() else null,
                // Privacy rule: only a ~1 km area, and only with its own tick.
                location = if (d.includeLocation && lat != null && lng != null) my.gimano.app.data.model.CoarseLocation(FeedbackRules.round2(lat), FeedbackRules.round2(lng)) else null,
                contact = contact,
                routeId = d.routeId, placeId = d.placeId,
                screenshot = if (d.includeScreenshot && d.screenshot != null) Base64.encodeToString(d.screenshot, Base64.NO_WRAP) else null,
            )
            val r = c.feedback.submit(req)
            _draft.value = FeedbackDraft()
            _result.tryEmit(r)
        }
    }

    /** "Hubungan disimpan · Padam": DELETE /feedback/{id}/contact. */
    fun deleteContact(id: String) = viewModelScope.launch {
        if (runCatching { c.feedback.deleteContact(id) }.isSuccess) refreshMine()
    }

    /** Exactly what "Lihat apa yang dihantar" shows: the device block (and coarse area when ticked). */
    fun previewJson(lat: Double?, lng: Double?): String {
        val d = _draft.value
        val o = kotlinx.serialization.json.buildJsonObject {
            if (d.includeDevice) put("device", my.gimano.app.data.remote.GimanoJson.encodeToJsonElement(my.gimano.app.data.model.DeviceInfo.serializer(), c.deviceInfo.current()))
            if (d.includeLocation && lat != null && lng != null) put("location", my.gimano.app.data.remote.GimanoJson.encodeToJsonElement(
                my.gimano.app.data.model.CoarseLocation.serializer(), my.gimano.app.data.model.CoarseLocation(FeedbackRules.round2(lat), FeedbackRules.round2(lng))))
        }
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), o)
    }

    fun refreshMine() {
        viewModelScope.launch {
            runCatching { c.feedback.retryDue(force = true) }
            val server = c.feedback.serverStatuses()
            statuses.value = server.mapValues { it.value.status }
            contacts.value = server.filterValues { it.hasContact }.keys
        }
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedbackViewModel(c) as T
    }
}

