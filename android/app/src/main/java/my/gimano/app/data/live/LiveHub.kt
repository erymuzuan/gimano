package my.gimano.app.data.live

import my.gimano.app.core.GLog
import com.google.gson.JsonElement
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.gimano.app.data.model.Jam
import my.gimano.app.data.model.Report
import my.gimano.app.data.remote.GimanoJson
import java.net.URI

sealed interface LiveEvent {
    data class AlertCreated(val report: Report) : LiveEvent
    data class AlertUpdated(val report: Report) : LiveEvent
    data class AlertExpired(val reportId: String) : LiveEvent
    data class JamUpdated(val jam: Jam) : LiveEvent
}

/**
 * SignalR client for `/hubs/live`. Payloads arrive as Gson trees and are re-parsed with the
 * same kotlinx.serialization models as REST, so there is one source of truth for DTOs.
 */
class LiveHub(
    private val scope: CoroutineScope,
    private val baseUrl: () -> String,
    private val token: () -> String?,
) {
    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var hub: HubConnection? = null
    private var job: Job? = null
    private var bbox: DoubleArray? = null

    companion object {
        /** `http://host:5080/api/v1/` → `http://host:5080/hubs/live`. */
        fun hubUrl(apiBase: String): String {
            val u = URI(apiBase)
            val port = if (u.port == -1) "" else ":${u.port}"
            return "${u.scheme}://${u.host}$port/hubs/live"
        }
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var backoff = 2_000L
            while (isActive) {
                try {
                    val h = build()
                    hub = h
                    try {
                        h.start().blockingAwait()
                        _connected.value = true
                        backoff = 2_000L
                        bbox?.let { h.send("Subscribe", it) }
                        while (isActive && h.connectionState == HubConnectionState.CONNECTED) delay(1_000)
                    } finally {
                        // Stopped (or the server URL changed) while connecting/connected: never leave this connection open.
                        if (!isActive) runCatching { h.stop().blockingAwait() }
                    }
                } catch (e: Exception) {
                    GLog.i("LiveHub", "SignalR not connected: ${e.javaClass.simpleName}")
                }
                _connected.value = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Capture the connection now: a start() right after (new server URL) assigns a new hub that must not be the one stopped.
        val h = hub
        hub = null
        if (h != null) scope.launch(Dispatchers.IO) { runCatching { h.stop().blockingAwait() } }
        _connected.value = false
    }

    fun subscribe(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double) {
        val b = doubleArrayOf(minLng, minLat, maxLng, maxLat)
        bbox = b
        val h = hub ?: return
        if (h.connectionState == HubConnectionState.CONNECTED) {
            scope.launch(Dispatchers.IO) { runCatching { h.send("Subscribe", b) } }
        }
    }

    private fun build(): HubConnection {
        val h = HubConnectionBuilder.create(hubUrl(baseUrl()))
            .withAccessTokenProvider(Single.defer { Single.just(token() ?: "") })
            .build()
        h.on("AlertCreated", { e: JsonElement -> parseReport(e)?.let { _events.tryEmit(LiveEvent.AlertCreated(it)) } }, JsonElement::class.java)
        h.on("AlertUpdated", { e: JsonElement -> parseReport(e)?.let { _events.tryEmit(LiveEvent.AlertUpdated(it)) } }, JsonElement::class.java)
        h.on("AlertExpired", { id: String -> _events.tryEmit(LiveEvent.AlertExpired(id)) }, String::class.java)
        h.on("JamUpdated", { e: JsonElement ->
            runCatching { GimanoJson.decodeFromString(Jam.serializer(), e.toString()) }.getOrNull()?.let { _events.tryEmit(LiveEvent.JamUpdated(it)) }
        }, JsonElement::class.java)
        h.onClosed { _connected.value = false }
        return h
    }

    private fun parseReport(e: JsonElement): Report? =
        runCatching { GimanoJson.decodeFromString(Report.serializer(), e.toString()) }.getOrNull()
}
