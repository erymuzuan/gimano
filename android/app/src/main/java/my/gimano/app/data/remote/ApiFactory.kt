package my.gimano.app.data.remote

import kotlinx.serialization.json.Json
import my.gimano.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    fun create(baseUrl: String, client: OkHttpClient): GimanoApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GimanoJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GimanoApi::class.java)
}
