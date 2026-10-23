package my.gimano.app.data.share

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import my.gimano.app.nav.NavState
import java.time.Instant

@Serializable
data class CreateShareRequest(val expiresInMinutes: Int)

@Serializable
data class ShareCreated(val shareId: String, val writeToken: String, val expiresAt: String)

/** The share endpoints the session needs (RemoteRepository / DemoRepository implement them). */
interface ShareApi {
    suspend fun createShare(expiresInMinutes: Int): ShareCreated
    suspend fun updateShare(shareId: String, writeToken: String, sealed: SealedShare)
    suspend fun deleteShare(shareId: String, writeToken: String)
}

data class ActiveShare(val shareId: String, val link: String, val expiresAt: Instant)

/**
 * One live "Share my drive" at a time. Every [intervalMs] it encrypts the driver's position/ETA and
 * PUTs the ciphertext. Ends on arrival, when navigation stops, on "Stop sharing" or at expiry.
 */
class ShareSession(
    private val scope: CoroutineScope,
    private val api: ShareApi,
    private val crypto: ShareCrypto,
    private val nav: StateFlow<NavState>,
    private val now: () -> Instant = Instant::now,
    private val intervalMs: Long = 15_000,
) {
    private val _active = MutableStateFlow<ActiveShare?>(null)
    val active: StateFlow<ActiveShare?> = _active.asStateFlow()

    private var job: Job? = null
    private var key: ByteArray? = null
    private var token: String? = null
    /** Last payload sent; the final "arrived"/"ended" update reuses it when navigation state is gone. */
    private var last: SharePayload? = null

    /** Starts sharing (or returns the running share). Lasts until arrival, max 12 h. */
    suspend fun start(): ActiveShare {
        _active.value?.let { return it }
        val remainingS = nav.value.progress?.remainingS ?: 3600.0
        val minutes = ((remainingS / 60) * 2 + 30).toInt().coerceIn(15, 720)
        val created = api.createShare(minutes)
        val k = crypto.newKey()
        key = k; token = created.writeToken
        val share = ActiveShare(created.shareId, ShareCrypto.link(created.shareId, k), runCatching { Instant.parse(created.expiresAt) }.getOrElse { now().plusSeconds(minutes * 60L) })
        _active.value = share
        job = scope.launch { loop(share) }
        return share
    }

    private suspend fun loop(share: ActiveShare) {
        while (scope.isActive) {
            val s = nav.value
            when {
                !s.active -> { finish("ended"); return }
                s.arrived -> { push(share, "arrived"); finish(null); return }
                !now().isBefore(share.expiresAt) -> { finish(null); return }
                else -> push(share, "driving")
            }
            delay(intervalMs)
        }
    }

    private suspend fun push(share: ActiveShare, status: String) {
        val s = nav.value
        val k = key ?: return
        val t = token ?: return
        val p = s.puck ?: s.fix?.point
        val payload = if (p != null && s.active) SharePayload(
            lat = p.lat, lng = p.lng, heading = s.puckBearing, speedKmh = s.speedKmh.toDouble(),
            etaIso = s.progress?.let { now().plusSeconds(it.remainingS.toLong()).toString() },
            remainingM = s.progress?.remainingM, destinationName = s.destination?.name ?: "",
            status = status, ts = now().toString(),
        ) else last?.copy(status = status, speedKmh = 0.0, ts = now().toString()) ?: return
        last = payload
        runCatching { api.updateShare(share.shareId, t, crypto.seal(k, share.shareId, payload)) }
    }

    /** "Stop sharing": tell viewers it ended, then delete the share. */
    suspend fun stop() = finish("ended")

    private suspend fun finish(finalStatus: String?) {
        val share = _active.value ?: return
        val t = token
        if (finalStatus != null) push(share, finalStatus)
        if (t != null) runCatching { api.deleteShare(share.shareId, t) }
        _active.value = null
        key?.fill(0); key = null; token = null; last = null
        val j = job; job = null
        if (j != null && j != kotlinx.coroutines.currentCoroutineContext()[Job]) j.cancel()
    }
}
