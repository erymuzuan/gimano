package my.gimano.app.issues

import kotlinx.coroutines.test.runTest
import my.gimano.app.data.issues.IssueGate
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.UnknownHostException

/** A start without network must leave complaints "unknown" (probed again later), not hidden for the session. */
class IssueGateProbeTest {
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test fun probeOutcomes() = runTest {
        assertEquals(true, IssueGate.probeOutcome { })
        assertEquals("endpoint missing", false, IssueGate.probeOutcome { throw http(404) })
        assertEquals("endpoint missing", false, IssueGate.probeOutcome { throw http(501) })
        assertEquals("the server answered", true, IssueGate.probeOutcome { throw http(400) })
        assertNull("offline: unknown, ask again", IssueGate.probeOutcome { throw UnknownHostException("no dns") })
    }
}
