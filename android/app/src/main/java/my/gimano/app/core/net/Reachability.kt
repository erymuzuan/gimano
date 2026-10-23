package my.gimano.app.core.net

import java.io.IOException

object Reachability {
    /** True only for "can't reach the server at all" failures, which may switch the app to demo data. */
    fun isUnreachable(e: IOException): Boolean =
        e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException

    /** HTTP statuses that mean "the server is not really there" (proxy up, API down). 501 is "endpoint not shipped" and is not an outage. */
    fun isGatewayDown(code: Int): Boolean = code == 500 || code == 502 || code == 503 || code == 504
}
