package my.gimano.app.data.ugc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Community rules (UGC terms) shown before a user's FIRST public contribution: review, photo, place, report, complaint
 * or vote. Accepted on this phone with the date and [VERSION]; asked again when the version changes.
 * Full terms: [URL].
 */
object UgcTerms {
    /** Bump when the rules or https://manotu.app/terms/ change materially: everyone is asked again. */
    const val VERSION = "2026-10-24"
    const val URL = "https://manotu.app/terms/"

    fun needsAcceptance(acceptedVersion: String?): Boolean = acceptedVersion != VERSION

    /** Shared `settings` keys (same as iOS, docs/privacy-and-backup.md), so acceptance travels in a backup. */
    const val VERSION_KEY = "community_rules_version"
    const val ACCEPTED_AT_KEY = "community_rules_accepted_at"

    /** Merge restore: keep whichever side accepted the later version (versions are ISO dates); ties keep this phone's. */
    fun newer(local: Pair<String?, String?>, backup: Pair<String?, String?>): Pair<String?, String?> =
        if ((backup.first ?: "") > (local.first ?: "")) backup else local
}

/**
 * The accepted version and date, in the local `settings` table (on this phone and in the user's own backup only),
 * mirrored in memory so the gate can ask synchronously. Reload after a restore or a local wipe.
 */
class CommunityRulesStore(private val dao: my.gimano.app.data.local.SettingDao) {
    @Volatile var acceptedVersion: String? = null
        private set

    suspend fun reload() { acceptedVersion = dao.get(UgcTerms.VERSION_KEY) }

    suspend fun accept(version: String, at: String) {
        dao.put(my.gimano.app.data.local.SettingEntity(UgcTerms.VERSION_KEY, version))
        dao.put(my.gimano.app.data.local.SettingEntity(UgcTerms.ACCEPTED_AT_KEY, at))
        acceptedVersion = version
    }
}

/**
 * Holds one pending public action while the rules are shown. [require] runs the action at once when the current
 * version is accepted; otherwise it is kept until "I agree" ([accept]) or dropped ("Not now", [decline]).
 */
class TermsGate(
    private val acceptedVersion: () -> String?,
    private val persistAcceptance: (version: String) -> Unit,
) {
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()
    private var pending: (() -> Unit)? = null
    /** Accepted in this session, before the (asynchronous) save lands: a retried action must not ask again. */
    @Volatile private var acceptedNow: String? = null

    fun accepted(): Boolean = !UgcTerms.needsAcceptance(acceptedNow ?: acceptedVersion())

    fun require(action: () -> Unit) {
        if (accepted()) { action(); return }
        pending = action
        _prompt.value = true
    }

    fun accept() {
        acceptedNow = UgcTerms.VERSION
        persistAcceptance(UgcTerms.VERSION)
        _prompt.value = false
        pending.also { pending = null }?.invoke()
    }

    fun decline() {
        pending = null
        _prompt.value = false
    }
}
