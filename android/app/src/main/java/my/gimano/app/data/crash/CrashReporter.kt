package my.gimano.app.data.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import my.gimano.app.core.crash.Scrubber
import my.gimano.app.core.feedback.FeedbackRules
import my.gimano.app.data.feedback.DeviceInfoProvider
import my.gimano.app.data.local.OutboxDao
import my.gimano.app.data.local.OutboxEntity
import my.gimano.app.data.model.ContactDetails
import my.gimano.app.data.model.CrashReportRequest
import my.gimano.app.data.remote.GimanoJson
import my.gimano.app.data.repo.GimanoRepository
import retrofit2.HttpException
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.UUID

/** A crash as recorded on the phone (raw; scrubbed when previewed/sent). Never contains favourites, routes, search text or screenshots. */
@Serializable
data class CrashRecord(
    val id: String,
    val occurredAt: String,
    val kind: String,
    val exception: String,
    val message: String? = null,
    val stack: String,
    val threads: String? = null,
    val screen: String? = null,
    val uptimeS: Long? = null,
)

/** "Tanya setiap kali" (default) · "Sentiasa hantar" · "Jangan hantar". */
enum class CrashMode { ASK, ALWAYS, NEVER }

/**
 * Gimano's own crash reporting (no third-party SDK; docs/api.md "Crash reports"): an uncaught-exception
 * handler writes a local record to noBackupFilesDir; on API 30+ ANRs and native exits are read from
 * ApplicationExitInfo on the next launch. Nothing leaves the phone without the user's choice, and every
 * text field is scrubbed on the phone first.
 */
class CrashReporter(
    private val context: Context,
    private val outbox: OutboxDao,
    private val repo: GimanoRepository,
    private val device: DeviceInfoProvider,
) {
    companion object {
        const val KIND = "crash"
        private const val MAX_STACK = 32 * 1024
        private const val MAX_MESSAGE = 500
    }

    private val dir = File(context.noBackupFilesDir, "crashes").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("gimano.crash", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow<List<CrashRecord>>(emptyList())
    val pending: StateFlow<List<CrashRecord>> = _pending.asStateFlow()

    /** Name of the screen in use, set by the UI (no content, just e.g. "navigation"). */
    @Volatile var screen: String? = null

    var mode: CrashMode
        get() = runCatching { CrashMode.valueOf(prefs.getString("mode", null) ?: "ASK") }.getOrDefault(CrashMode.ASK)
        set(v) { prefs.edit().putString("mode", v.name).apply() }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(fromThrowable(e, t)) }
            previous?.uncaughtException(t, e)
        }
    }

    private fun fromThrowable(e: Throwable, t: Thread): CrashRecord {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        return CrashRecord(
            id = UUID.randomUUID().toString(), occurredAt = Instant.now().toString(), kind = "crash",
            exception = e.javaClass.name, message = e.message, stack = "thread: ${t.name}\n$sw", screen = screen,
            // App uptime (since this process started), not device uptime.
            uptimeS = (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000,
        )
    }

    private fun write(r: CrashRecord) {
        File(dir, "${r.id}.json").writeText(GimanoJson.encodeToString(CrashRecord.serializer(), r))
    }

    /** On launch: collect ANR/native exits (API 30+), then apply the remembered choice. */
    suspend fun onAppStart() {
        if (Build.VERSION.SDK_INT >= 30) collectExitInfo()
        val list = dir.listFiles()?.mapNotNull { f -> runCatching { GimanoJson.decodeFromString(CrashRecord.serializer(), f.readText()) }.getOrNull() }.orEmpty()
            .sortedBy { it.occurredAt }
        when (mode) {
            CrashMode.NEVER -> list.forEach(::discard)
            CrashMode.ALWAYS -> list.forEach { send(it, null) }
            CrashMode.ASK -> _pending.value = list
        }
        retryOutbox()
    }

    private fun collectExitInfo() {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val last = prefs.getLong("lastExitTs", -1L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrDefault(emptyList())
        if (last < 0) { prefs.edit().putLong("lastExitTs", exits.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()).apply(); return }
        exits.filter { it.timestamp > last && (it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) }.forEach { x ->
            val trace = if (x.reason == ApplicationExitInfo.REASON_ANR) runCatching { x.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull() else null
            write(CrashRecord(
                id = UUID.randomUUID().toString(), occurredAt = Instant.ofEpochMilli(x.timestamp).toString(),
                kind = if (x.reason == ApplicationExitInfo.REASON_ANR) "anr" else "native",
                exception = if (x.reason == ApplicationExitInfo.REASON_ANR) "ANR" else "NativeCrash",
                message = x.description, stack = trace?.take(MAX_STACK) ?: (x.description ?: ""), uptimeS = null,
            ))
        }
        exits.maxOfOrNull { it.timestamp }?.let { if (it > last) prefs.edit().putLong("lastExitTs", it).apply() }
    }

    /** The exact body that would be sent: scrubbed and capped. Shown by "Lihat butiran". */
    fun request(r: CrashRecord, contact: ContactDetails?): CrashReportRequest = CrashReportRequest(
        occurredAt = r.occurredAt, kind = r.kind,
        exception = Scrubber.scrub(r.exception)!!.take(200),
        message = Scrubber.cap(Scrubber.scrub(r.message), MAX_MESSAGE),
        stack = Scrubber.cap(Scrubber.scrub(r.stack), MAX_STACK)!!,
        threads = Scrubber.cap(Scrubber.scrub(r.threads), MAX_STACK),
        screen = r.screen, uptimeS = r.uptimeS, device = device.current(), contact = contact,
    )

    fun previewJson(r: CrashRecord, contact: ContactDetails?): String =
        // Same settings as the request body (GimanoJson: defaults included), so the preview is exactly what is sent.
        kotlinx.serialization.json.Json(GimanoJson) { prettyPrint = true }.encodeToString(CrashReportRequest.serializer(), request(r, contact))

    fun discard(r: CrashRecord) {
        File(dir, "${r.id}.json").delete()
        _pending.value = _pending.value.filterNot { it.id == r.id }
    }

    /** Queue (outbox, retried when offline) and drop the local record. */
    suspend fun send(r: CrashRecord, contact: ContactDetails?) {
        val row = OutboxEntity("crash-${r.id}", KIND, GimanoJson.encodeToString(CrashReportRequest.serializer(), request(r, contact)), 0, Instant.now().toString())
        outbox.upsert(row)
        discard(r)
        attempt(row)
    }

    private suspend fun attempt(row: OutboxEntity) {
        try {
            repo.sendCrashReport(GimanoJson.decodeFromString(CrashReportRequest.serializer(), row.payloadJson))
            outbox.delete(row.id)
        } catch (e: HttpException) {
            if (FeedbackRules.isPermanentFailure(e.code())) outbox.delete(row.id) else reschedule(row)
        } catch (e: Exception) {
            reschedule(row)
        }
    }

    private suspend fun reschedule(row: OutboxEntity) {
        val n = row.attempts + 1
        outbox.upsert(row.copy(attempts = n, nextAttemptAt = Instant.now().plusSeconds(FeedbackRules.backoffSeconds(n)).toString()))
    }

    suspend fun retryOutbox() = outbox.all(KIND).forEach { attempt(it) }
}
