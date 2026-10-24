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
