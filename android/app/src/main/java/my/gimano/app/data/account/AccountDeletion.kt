package my.gimano.app.data.account

import java.io.IOException

/**
 * "Delete my Manotu account" (Settings → Account; also https://manotu.app/delete/):
 * 1. DELETE /api/v1/me — the server deletes the account; public contributions are anonymised, not deleted. Skipped when
 *    this phone never registered (nothing on the server).
 * 2. Only if the server confirmed: forget the contributor key and the server token on this phone.
 * 3. Only if the user asked: also clear the phone's own data (favourites, recents, "Just for me" places, planned
 *    drives…). Otherwise it stays on the phone (it was never on the server).
 * If step 1 fails, nothing on the phone changes.
 */
class AccountDeletion(
    private val isRegistered: () -> Boolean,
    private val deleteOnServer: suspend () -> Unit,
    private val forgetIdentity: suspend () -> Unit,
    private val clearLocalData: suspend () -> Unit,
) {
    enum class Outcome { DELETED, OFFLINE, FAILED }

    suspend fun run(alsoClearLocal: Boolean): Outcome {
        if (isRegistered()) {
            try {
                deleteOnServer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                return Outcome.OFFLINE
            } catch (e: Exception) {
                return Outcome.FAILED
            }
        }
        forgetIdentity()
        if (alsoClearLocal) clearLocalData()
        return Outcome.DELETED
    }

    companion object {
        const val WEB_URL = "https://manotu.app/delete/"
    }
}
