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
import my.gimano.app.data.model.FeedbackContext
import my.gimano.app.data.model.FeedbackRequest

data class FeedbackDraft(
    val open: Boolean = false,
    val screen: String = "other",
    val category: String? = null,
    val message: String = "",
    val rating: Int? = null,
    val screenshot: ByteArray? = null,
    val includeScreenshot: Boolean = true,
    val includeDiagnostics: Boolean = false,
    val routeId: String? = null,
    val placeId: String? = null,
    val sending: Boolean = false,
) {
    val canSend get() = category != null && FeedbackRules.validMessage(message) && !sending
}

class FeedbackViewModel(private val c: AppContainer) : ViewModel() {
    private val _draft = MutableStateFlow(FeedbackDraft())
    val draft: StateFlow<FeedbackDraft> = _draft.asStateFlow()

    private val _result = MutableSharedFlow<FeedbackSendResult>(extraBufferCapacity = 2)
    val result: SharedFlow<FeedbackSendResult> = _result

    private val statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** "My feedback": local queue + sent items, with the latest server status when available. */
    val items = combine(c.feedback.localItems(), statuses) { list, st -> list.map { i -> st[i.id]?.let { i.copy(status = it) } ?: i } }

    fun open(screen: String, screenshot: ByteArray?, routeId: String?, placeId: String?) {
        _draft.value = FeedbackDraft(
            open = true, screen = screen, screenshot = screenshot, includeScreenshot = screenshot != null,
            includeDiagnostics = c.settings.current.feedbackDiagnostics, routeId = routeId, placeId = placeId,
        )
    }

    fun update(f: (FeedbackDraft) -> FeedbackDraft) = _draft.update(f)
    fun close() = _draft.update { it.copy(open = false) }

    fun send(lat: Double?, lng: Double?) {
        val d = _draft.value
        if (!d.canSend) return
        _draft.update { it.copy(sending = true) }
        viewModelScope.launch {
            c.settings.setFeedbackDiagnostics(d.includeDiagnostics)
            val ctx = if (d.includeDiagnostics) FeedbackContext(
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                osVersion = Build.VERSION.RELEASE ?: "", deviceModel = Build.MODEL ?: "",
                locale = c.lang().tag,
                // Privacy rule: only a ~1 km area, and only because the user ticked diagnostics.
                lat = lat?.let(FeedbackRules::round2), lng = lng?.let(FeedbackRules::round2),
                routeId = d.routeId, placeId = d.placeId,
            ) else FeedbackContext(appVersion = BuildConfig.VERSION_NAME, osVersion = "", deviceModel = "", locale = c.lang().tag)
            val req = FeedbackRequest(
                category = d.category!!, message = d.message.trim().take(FeedbackRules.MAX_MESSAGE), rating = d.rating, screen = d.screen,
                context = ctx,
                screenshot = if (d.includeScreenshot && d.screenshot != null) Base64.encodeToString(d.screenshot, Base64.NO_WRAP) else null,
            )
            val r = c.feedback.submit(req)
            _draft.value = FeedbackDraft()
            _result.tryEmit(r)
        }
    }

    fun refreshMine() {
        viewModelScope.launch {
            runCatching { c.feedback.retryDue(force = true) }
            statuses.value = c.feedback.serverStatuses().mapValues { it.value.status }
        }
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedbackViewModel(c) as T
    }
}

