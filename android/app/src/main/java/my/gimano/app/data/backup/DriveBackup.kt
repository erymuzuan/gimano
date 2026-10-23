package my.gimano.app.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import my.gimano.app.BuildConfig
import my.gimano.app.data.remote.GimanoJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream

@Serializable
data class DriveFile(val id: String, val name: String = "", val createdTime: String = "", val size: String? = null)

@Serializable
private data class DriveList(val files: List<DriveFile> = emptyList())

sealed interface DriveAuth {
    data class Token(val accessToken: String) : DriveAuth
    /** User consent needed: launch this, then call [DriveBackup.tokenFromConsent]. */
    data class NeedsConsent(val intent: PendingIntent) : DriveAuth
}

/**
 * Backups in the user's OWN Google Drive app-private folder (`appDataFolder`, scope
 * `drive.appdata`): only Gimano can see the files; Gimano's servers are never involved.
 * Drive REST v3 directly, keeping the last [KEEP] backups.
 */
class DriveBackup(private val context: Context, private val http: OkHttpClient = OkHttpClient()) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val KEEP = 5
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,createdTime,size"
    }

    /** False until an OAuth client id is put in gimano.local.properties. */
    val configured: Boolean get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank()

    suspend fun authorize(): DriveAuth {
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val r = Identity.getAuthorizationClient(context).authorize(req).await()
        return if (r.hasResolution()) DriveAuth.NeedsConsent(r.pendingIntent!!) else DriveAuth.Token(r.accessToken!!)
    }

    fun tokenFromConsent(data: Intent?): String? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()

    suspend fun upload(token: String, name: String, bytes: ByteArray): DriveFile = withContext(Dispatchers.IO) {
        val meta = """{"name":"$name","parents":["appDataFolder"]}"""
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody("application/zip".toMediaType()))
            .build()
        val resp = http.newCall(Request.Builder().url(UPLOAD).header("Authorization", "Bearer $token").post(body).build()).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("Drive upload failed: HTTP ${it.code}")
            GimanoJson.decodeFromString(DriveFile.serializer(), it.body!!.string())
        }
    }

    suspend fun list(token: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val url = "$API/files?spaces=appDataFolder&orderBy=createdTime%20desc&fields=files(id,name,createdTime,size)&pageSize=50"
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use {
            if (!it.isSuccessful) throw IOException("Drive list failed: HTTP ${it.code}")
            GimanoJson.decodeFromString(DriveList.serializer(), it.body!!.string()).files
        }
    }

    suspend fun download(token: String, id: String): InputStream = withContext(Dispatchers.IO) {
        val resp = http.newCall(Request.Builder().url("$API/files/$id?alt=media").header("Authorization", "Bearer $token").build()).execute()
        if (!resp.isSuccessful) { resp.close(); throw IOException("Drive download failed: HTTP ${resp.code}") }
        resp.body!!.bytes().inputStream().also { resp.close() }
    }

    /** Deletes all but the newest [KEEP] backups. */
    suspend fun prune(token: String) = withContext(Dispatchers.IO) {
        list(token).drop(KEEP).forEach { f ->
            http.newCall(Request.Builder().url("$API/files/${f.id}").header("Authorization", "Bearer $token").delete().build()).execute().close()
        }
    }
}
