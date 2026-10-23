package my.gimano.app.data.remote

import my.gimano.app.core.identity.RequestSigning
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import kotlin.math.abs

/** Signs the contribution endpoints (docs/identity-and-reputation.md §2) with the pseudonym's Ed25519 key. */
class SigningInterceptor(
    private val sign: (method: String, pathAndQuery: String, ts: Long, body: ByteArray) -> String?,
    private val now: () -> Long = { System.currentTimeMillis() },
) : Interceptor {
    /** Server clock minus ours, in seconds, learned from response Date headers (the server allows ±300 s). */
    @Volatile var skewS: Long = 0

    fun nowTs(): Long = now() / 1000 + skewS

    override fun intercept(chain: Interceptor.Chain): Response {
        val r = chain.request()
        // A request may pin its own ts (a vote's attestation is bound to it); otherwise now + server skew.
        val ts = r.header(RequestSigning.HEADER_TS)?.toLongOrNull() ?: nowTs()
        val req = signed(r, ts, sign) ?: r
        val resp = chain.proceed(req)
        resp.headers.getDate("Date")?.let { d ->
            val s = (d.time - now()) / 1000
            skewS = if (abs(s) > 30) s else 0
        }
        return resp
    }

    companion object {
        fun pathAndQuery(r: Request) = r.url.encodedPath + (r.url.encodedQuery?.let { "?$it" } ?: "")

        /** [r] with X-Gimano-Ts / X-Gimano-Sig added, or null when the endpoint isn't signed or there is no key yet. */
        fun signed(r: Request, ts: Long, sign: (String, String, Long, ByteArray) -> String?): Request? {
            if (!RequestSigning.isSigned(r.method, r.url.encodedPath)) return null
            val body = r.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() } ?: ByteArray(0)
            val sig = sign(r.method, pathAndQuery(r), ts, body) ?: return null
            return r.newBuilder().header(RequestSigning.HEADER_TS, ts.toString()).header(RequestSigning.HEADER_SIG, sig).build()
        }
    }
}
