package my.gimano.app.data.events

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import my.gimano.app.core.events.DropLinks
import my.gimano.app.data.remote.GimanoJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Why a drop request failed (docs/events.md §5.6). */
class DropException(val kind: Kind, val status: Int? = null) : Exception("drop ${kind.name.lowercase()}${status?.let { " $it" } ?: ""}") {
    enum class Kind {
        /** 404 / 410: unknown, expired or deleted ("Jemputan ini telah tamat atau dipadam"). */
        GONE,
        /** 413, or the blob is over 256 KiB before sending. */
        TOO_LARGE,
        /** No connection, timeout. */
        NETWORK,
        /** Any other HTTP error (401 after a retry, 429 quota, 5xx). */
        SERVER,
        /** Demo mode: no server to talk to. */
        UNAVAILABLE,
    }
}

@Serializable
data class DropCreated(val dropId: String, val writeToken: String, val expiresAt: String? = null)

/** The relay endpoints (`/api/v1/drops`). The server only ever sees ciphertext; ids and tokens are never logged. */
interface DropApi {
    /** `POST /drops` with the app JWT. */
    suspend fun create(blob: ByteArray, expiresAt: Instant): DropCreated
    /** `PUT /drops/{id}` with `Authorization: Drop <writeToken>` (republished). */
    suspend fun update(dropId: String, writeToken: String, blob: ByteArray, expiresAt: Instant)
    /** `GET /drops/{id}` (public, no account token). */
    suspend fun fetch(dropId: String): ByteArray
    /** `DELETE /drops/{id}` with `Authorization: Drop <writeToken>`. 404 / 410 count as done. */
    suspend fun delete(dropId: String, writeToken: String)
}

/**
 * OkHttp client for [DropApi]. Its own client, without the app's interceptors: GET/PUT/DELETE carry no account token
 * (a guest's fetch is not tied to their pseudonym) and nothing logs the URL (it holds the dropId). Only POST sends the
 * JWT from [bearer] (refreshed once on 401).
 */
class HttpDropApi(
    private val baseUrl: () -> String,
    private val bearer: suspend (refresh: Boolean) -> String?,
    private val demo: () -> Boolean = { false },
    client: OkHttpClient = OkHttpClient(),
) : DropApi {
    /** Organizer calls: short, so "Kongsi jemputan" falls back to the full link within ~5 s. */
    private val quick = client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    private val fetcher = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()

        fun expiresHeader(i: Instant): String = DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(ChronoUnit.SECONDS))
    }

    private fun url(path: String = ""): String {
        if (demo()) throw DropException(DropException.Kind.UNAVAILABLE)
        val b = baseUrl().let { if (it.endsWith("/")) it else "$it/" }
        return b + "drops" + path
    }

    private fun idPath(dropId: String): String {
        require(DropLinks.isDropId(dropId))
        return "/$dropId"
    }

    private suspend fun call(client: OkHttpClient, req: Request): Response = withContext(Dispatchers.IO) {
        try { client.newCall(req).execute() } catch (e: IOException) { throw DropException(DropException.Kind.NETWORK) }
    }

    private fun failure(code: Int): DropException = DropException(
        when (code) { 404, 410 -> DropException.Kind.GONE; 413 -> DropException.Kind.TOO_LARGE; else -> DropException.Kind.SERVER }, code)

    private fun checkSize(blob: ByteArray) { if (blob.size > DropLinks.MAX_BLOB_BYTES) throw DropException(DropException.Kind.TOO_LARGE) }

    override suspend fun create(blob: ByteArray, expiresAt: Instant): DropCreated {
        checkSize(blob)
        val u = url()
        suspend fun attempt(refresh: Boolean): Response {
            val token = runCatching { bearer(refresh) }.getOrNull() ?: throw DropException(DropException.Kind.NETWORK)
            return call(quick, Request.Builder().url(u).post(blob.toRequestBody(OCTET))
                .header("Authorization", "Bearer $token").header("Accept", "application/json")
                .header("X-Drop-Expires", expiresHeader(expiresAt)).build())
        }
        var r = attempt(false)
        if (r.code == 401) { r.close(); r = attempt(true) }
        r.use {
            if (!it.isSuccessful) throw failure(it.code)
            val text = withContext(Dispatchers.IO) { it.body?.string() }.orEmpty()
            val created = runCatching { GimanoJson.decodeFromString(DropCreated.serializer(), text) }.getOrNull()
                ?: throw DropException(DropException.Kind.SERVER, it.code)
            if (!DropLinks.isDropId(created.dropId) || created.writeToken.isBlank()) throw DropException(DropException.Kind.SERVER, it.code)
            return created
        }
    }

    override suspend fun update(dropId: String, writeToken: String, blob: ByteArray, expiresAt: Instant) {
        checkSize(blob)
        call(quick, Request.Builder().url(url(idPath(dropId))).put(blob.toRequestBody(OCTET))
            .header("Authorization", "Drop $writeToken").header("X-Drop-Expires", expiresHeader(expiresAt)).build()).use {
            if (!it.isSuccessful) throw failure(it.code)
        }
    }

    override suspend fun fetch(dropId: String): ByteArray {
        call(fetcher, Request.Builder().url(url(idPath(dropId))).get().header("Accept", "application/octet-stream").build()).use { r ->
            if (!r.isSuccessful) throw failure(r.code)
            val body = r.body ?: throw DropException(DropException.Kind.SERVER, r.code)
            return withContext(Dispatchers.IO) {
                try {
                    // Bounded read: one byte over 256 KiB is enough to reject (drop_too_large when opened).
                    val max = DropLinks.MAX_BLOB_BYTES + 1
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    body.byteStream().use { input ->
                        while (out.size() < max) {
                            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.toByteArray()
                } catch (e: IOException) {
                    throw DropException(DropException.Kind.NETWORK)
                }
            }
        }
    }

    override suspend fun delete(dropId: String, writeToken: String) {
        call(quick, Request.Builder().url(url(idPath(dropId))).delete().header("Authorization", "Drop $writeToken").build()).use {
            if (!it.isSuccessful && it.code != 404 && it.code != 410) throw failure(it.code)
        }
    }
}
