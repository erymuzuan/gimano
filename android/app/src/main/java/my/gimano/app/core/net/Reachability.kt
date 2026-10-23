package my.gimano.app.core.net

import java.io.IOException

object Reachability {
    /** True only for "can't reach the server at all" failures, which may switch the app to demo data. */
    fun isUnreachable(e: IOException): Boolean =
        e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException
}
