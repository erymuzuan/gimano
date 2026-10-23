package my.gimano.app.data.remote

import kotlinx.serialization.json.Json
import my.gimano.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

val GimanoJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

object ApiFactory {
    /** "ms-MY,ms;q=0.9,en;q=0.5" or "en-MY,en;q=0.9,ms;q=0.5" from the app language. */
    fun acceptLanguage(malay: Boolean) = if (malay) "ms-MY,ms;q=0.9,en;q=0.5" else "en-MY,en;q=0.9,ms;q=0.5"

    fun okHttp(tokenProvider: () -> String?, signer: SigningInterceptor? = null, language: () -> String = { acceptLanguage(false) }): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = tokenProvider()
            val req = chain.request().newBuilder()
                .header("Accept", "application/json")
                // Server texts (suggestions, categories, problem details) follow the app language.
                .header("Accept-Language", language())
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            chain.proceed(req)
        }
        .apply { if (signer != null) addInterceptor(signer) }
        .apply {
            // Debug only, and never a precise coordinate in a log line (AGENTS.md: ≤ 2 dp): method, redacted URL, status, time.
            if (BuildConfig.DEBUG) addInterceptor { chain ->
                val r = chain.request()
                val t0 = System.nanoTime()
                val resp = chain.proceed(r)
                android.util.Log.d("OkHttp", "${r.method} ${LogRedaction.url(r.url.toString())} → ${resp.code} (${(System.nanoTime() - t0) / 1_000_000} ms)")
                resp
            }
        }
        .build()

    fun create(baseUrl: String, client: OkHttpClient): GimanoApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GimanoJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GimanoApi::class.java)
}

/**
 * Log lines never carry precise coordinates (every decimal number with more than 2 dp is rounded to 2 dp) or what
 * the user typed (the search text `q` is dropped).
 */
object LogRedaction {
    private val PRECISE = Regex("""-?\d+\.\d{3,}""")
    private val QUERY_TEXT = Regex("""([?&]q=)[^&]*""")

    fun url(u: String): String = PRECISE.replace(QUERY_TEXT.replace(u, "$1…")) { m ->
        java.math.BigDecimal(m.value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    }
}
