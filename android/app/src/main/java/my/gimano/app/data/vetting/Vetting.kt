package my.gimano.app.data.vetting

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import my.gimano.app.core.identity.RequestSigning
import my.gimano.app.data.local.SettingDao
import my.gimano.app.data.local.SettingEntity

/** Places this phone already answered (or dismissed), so the nearby prompt never asks twice. Kept on the phone only. */
class VettingMemory(private val settings: SettingDao) {
    private val key = "vet_answered"

    suspend fun answered(): Set<String> = settings.get(key)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    suspend fun markAnswered(placeId: String) {
        val list = (answered().toList() + placeId).distinct().takeLast(300)
        settings.put(SettingEntity(key, list.joinToString(",")))
    }
}

/**
 * Play Integrity standard request for votes (docs/identity-and-reputation.md §5). Needs the Google Cloud
 * project number (git-ignored `gimano.cloudProjectNumber`); without it, or when Play services can't
 * answer quickly, the vote is sent with no attestation (the server is in `log` mode at first).
 */
class Integrity(private val context: Context, private val cloudProjectNumber: Long) {
    @Volatile private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    /** [clientData] = "GIMANO-ATTEST\n" + placeId + "\n" + ts; requestHash = hex(sha256(clientData)). */
    suspend fun token(clientData: String): String? {
        if (cloudProjectNumber <= 0) return null
        return withTimeoutOrNull(8_000) {
            runCatching {
                val p = provider ?: IntegrityManagerFactory.createStandard(context)
                    .prepareIntegrityToken(StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
                    .await().also { provider = it }
                p.request(
                    StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(RequestSigning.sha256Hex(clientData.toByteArray()))
                        .build(),
                ).await().token()
            }.getOrNull()
        }
    }
}
