package my.gimano.app.core.crash

/**
 * Removes anything personal from crash text ON THE PHONE before it is shown or sent (docs/api.md "Crash reports"):
 * coordinates, emails, phone numbers, JWTs / bearer tokens / long secrets, URL query strings and paths under the
 * app's (or the user's) data directories become `[redacted]`.
 */
object Scrubber {
    const val R = "[redacted]"

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // JWTs and bearer/API tokens.
        Regex("(?i)\\bbearer\\s+[^\\s\"',;)]+") to { _ -> "Bearer $R" },
        Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}") to { _ -> R },
        Regex("(?i)\\b(bearer|token|authorization|sig|signature|api[_-]?key|secret|password)(\\s*[:=]\\s*|\\s+)[\"']?[^\\s\"',;)]+") to { m -> m.groupValues[1] + m.groupValues[2] + R },
        // URL query strings (keep scheme/host/path for debugging).
        Regex("(https?://[^\\s?#\"']+)\\?[^\\s#\"')]*") to { m -> m.groupValues[1] + "?" + R },
        // Emails.
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to { _ -> R },
        // Named coordinates: lat=3.139, "lng": 101.68, latitude: -6.2, GeoPoint(lat=3.1, lng=101.6)
        Regex("(?i)\\b(lat|lng|lon|long|latitude|longitude)(\"?\\s*[=:]\\s*)-?\\d{1,3}(\\.\\d+)?") to { m -> m.groupValues[1] + m.groupValues[2] + R },
        // Bare coordinate pairs: "3.13900, 101.68690" / "3.139 101.687" / "(3.1,101.6)"
        Regex("-?\\d{1,3}\\.\\d{2,}\\s*[,; ]\\s*-?\\d{1,3}\\.\\d{2,}") to { _ -> R },
        // App data / user storage paths.
        Regex("/(data/user/\\d+|data/data|data/user_de/\\d+|storage/emulated/\\d+|sdcard|mnt/media_rw/[^/\\s]+)/[^\\s:\"')]*") to { _ -> R },
        // Phone numbers (+60 12-345 6789, 012-3456789, +6591234567).
        Regex("(?<![\\w.:/])(\\+\\d[\\d\\s-]{7,16}\\d|0\\d{1,2}[\\s-]?\\d{3,4}[\\s-]?\\d{4})(?![\\w.])") to { _ -> R },
        // Long secrets: hex ≥ 32 or base64-ish ≥ 40 chars without dots (class names have dots).
        Regex("\\b[A-Fa-f0-9]{32,}\\b") to { _ -> R },
        Regex("(?<![A-Za-z0-9+/_.-])[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9+/_.-])") to { _ -> R },
    )

    fun scrub(text: String?): String? = text?.let { t -> rules.fold(t) { acc, (re, f) -> re.replace(acc, f) } }

    /** Caps for the API (stack/threads ≤ 32 KB, message ≤ 500 chars). */
    fun cap(text: String?, maxChars: Int): String? = text?.let { if (it.length <= maxChars) it else it.take(maxChars) + "\n…[truncated]" }
}
