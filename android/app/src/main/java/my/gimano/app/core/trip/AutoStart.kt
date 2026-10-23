package my.gimano.app.core.trip

/**
 * The route preview's 10 s "Go" countdown. Off by default (Settings → "Start navigation automatically"); even then it
 * only runs for one-tap destinations (Home, Work, a favourite, a recent, a planned drive), never after a search, never
 * with a chosen start, stops or a departure time, and never while the app is in the background.
 */
object AutoStart {
    fun shouldCountdown(enabled: Boolean, quickPick: Boolean, originIsGps: Boolean, hasStops: Boolean, departLater: Boolean, foreground: Boolean): Boolean =
        enabled && quickPick && originIsGps && !hasStops && !departLater && foreground
}
