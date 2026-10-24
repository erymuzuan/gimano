package my.gimano.app.data.repo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Moderation outcomes the app must explain: a moderator-banned account gets 403 on every public contribution
 * (docs/api.md). The UI shows one plain message ("your account can't post…, contact …") instead of a generic error.
 */
object Moderation {
    private val _banned = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val banned: SharedFlow<Unit> = _banned

    fun reportBanned() { _banned.tryEmit(Unit) }

    /** Is this failure the "banned" 403? */
    fun isBanned(t: Throwable?): Boolean = (t as? retrofit2.HttpException)?.code() == 403
}
