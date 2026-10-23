package my.gimano.app.data

import my.gimano.app.core.net.Reachability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FallbackTest {
    @Test fun onlyRealOutagesSwitchToDemoData() {
        assertTrue(Reachability.isUnreachable(SocketTimeoutException("failed to connect")))
        assertTrue(Reachability.isUnreachable(ConnectException("refused")))
        assertTrue(Reachability.isUnreachable(UnknownHostException("no dns")))
        assertFalse("a dropped keep-alive connection is retried, not an outage", Reachability.isUnreachable(IOException("unexpected end of stream")))
    }

    @Test fun gatewayErrorsSwitchToDemoButMissingEndpointsDoNot() {
        assertTrue(Reachability.isGatewayDown(502)); assertTrue(Reachability.isGatewayDown(503)); assertTrue(Reachability.isGatewayDown(500))
        assertFalse(Reachability.isGatewayDown(501)); assertFalse(Reachability.isGatewayDown(404)); assertFalse(Reachability.isGatewayDown(400))
    }
}
