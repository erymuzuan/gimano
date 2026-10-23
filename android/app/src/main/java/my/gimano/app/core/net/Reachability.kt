package my.gimano.app.core.net

import java.io.IOException

object Reachability {
    /** True only for "can't reach the server at all" failures, which may switch the app to demo data. */
    fun isUnreachable(e: IOException): Boolean =
        e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException

    /** HTTP statuses that mean "the server is not really there" (proxy up, API down). 501 is "endpoint not shipped" and is not an outage. */
    fun isGatewayDown(code: Int): Boolean = code == 500 || code == 502 || code == 503 || code == 504
}

/** Whether the app is on screen (set from ProcessLifecycleOwner). Network is often blocked in the background. */
object AppForeground {
    @Volatile var foreground: Boolean = true
}

/**
 * When the live server counts as "down" (→ demo data with a banner). A failure while the app is in the background
 * never counts (the OS blocks its network); in the foreground it takes [threshold] failures within [windowMs], and
 * any success resets the count.
 */
class FallbackPolicy(private val threshold: Int = 3, private val windowMs: Long = 60_000) {
    private val failures = ArrayDeque<Long>()

    @Synchronized fun onFailure(foreground: Boolean, nowMs: Long): Boolean {
        if (!foreground) return false
        failures.addLast(nowMs)
        while (failures.isNotEmpty() && nowMs - failures.first() > windowMs) failures.removeFirst()
        return failures.size >= threshold
    }

    @Synchronized fun onSuccess() = failures.clear()
}
