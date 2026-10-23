package my.gimano.app.data.issues

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.gimano.app.core.issues.IssueSupport
import my.gimano.app.data.repo.SwitchingRepository
import retrofit2.HttpException

/**
 * Complaints v1 are only shown once the server proves it has `/issues` (docs/api.md "Issues"). A 404/405/501 on the
 * collection endpoints hides every complaints feature for this session; nothing is queued or retried behind the user's back.
 */
class IssueGate(private val repo: SwitchingRepository) {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()
    @Volatile private var probed = false

    /** Cheap probe (a tiny bbox) at start and after the server comes back. Demo mode always has issues. */
    suspend fun probe() {
        if (repo.isDemo) { _available.value = true; return }
        _available.value = try {
            repo.issuesNearby(101.69, 3.14, 101.70, 3.15); true
        } catch (e: HttpException) {
            !IssueSupport.endpointMissing(e.code())
        } catch (e: Exception) {
            false
        }
        probed = true
    }

    /** Runs [block] when issues are available; null when hidden or the endpoint turned out to be missing. */
    suspend fun <T> call(collection: Boolean = true, block: suspend SwitchingRepository.() -> T): T? {
        if (!_available.value && !repo.isDemo) return null
        return try {
            repo.block()
        } catch (e: HttpException) {
            if (collection && IssueSupport.endpointMissing(e.code())) _available.value = false
            // Debug builds only: the server's problem title/errors (validation messages, no request body).
            if (my.gimano.app.BuildConfig.DEBUG) my.gimano.app.core.GLog.w("Issues", "HTTP ${e.code()} ${runCatching { e.response()?.errorBody()?.string()?.take(400) }.getOrNull()}")
            null
        } catch (e: Exception) {
            null
        }
    }
}
