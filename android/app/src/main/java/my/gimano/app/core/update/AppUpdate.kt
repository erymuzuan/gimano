package my.gimano.app.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `version.json` published next to the APK (https://www.bukitbunga.com/gimano/download/version.json):
 * {versionCode, versionName, apkUrl, minSupported, sha256, notes: {ms, en} | "text", publishedUtc, sizeBytes?}.
 * Parsed leniently: unknown fields are ignored, absent or null optional fields take defaults.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minSupported: Int = 0,
    val notesMs: String? = null,
    val notesEn: String? = null,
    val publishedUtc: String? = null,
    val sizeBytes: Long? = null,
) {
    fun notes(lang: String): String? = (if (lang == "ms") notesMs ?: notesEn else notesEn ?: notesMs)?.takeIf { it.isNotBlank() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        /** null when the manifest is unusable (no versionCode, no https apkUrl, or no valid sha256). */
        fun parse(text: String): UpdateManifest? {
            val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()
            fun int(k: String) = (o[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
            val code = int("versionCode") ?: return null
            val url = str("apkUrl")?.takeIf { it.startsWith("https://") } ?: return null
            val sha = str("sha256")?.takeIf { SHA256.matches(it) }?.lowercase() ?: return null
            val (ms, en) = when (val n: JsonElement? = o["notes"]) {
                is JsonObject -> (n["ms"] as? JsonPrimitive)?.contentOrNull to (n["en"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> n.contentOrNull.let { it to it }
                else -> null to null
            }
            return UpdateManifest(
                versionCode = code,
                versionName = str("versionName") ?: code.toString(),
                apkUrl = url,
                sha256 = sha,
                minSupported = int("minSupported") ?: 0,
                notesMs = ms, notesEn = en,
                publishedUtc = str("publishedUtc"),
                sizeBytes = (o["sizeBytes"] as? JsonPrimitive)?.longOrNull,
            )
        }
    }
}

sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision
    /** A newer version; [snoozed] = the user said "Later" less than 24 h ago (show only a badge). */
    data class Available(val manifest: UpdateManifest, val snoozed: Boolean) : UpdateDecision
    /** Installed < minSupported: block the app (except exporting a backup) until updated. */
    data class Required(val manifest: UpdateManifest) : UpdateDecision
}

object UpdatePolicy {
    const val CHECK_EVERY_MS = 6 * 3_600_000L
    const val SNOOZE_MS = 24 * 3_600_000L

    fun decide(installedCode: Int, m: UpdateManifest?, snoozedUntilMs: Long, nowMs: Long): UpdateDecision = when {
        m == null -> UpdateDecision.UpToDate
        installedCode < m.minSupported && m.versionCode > installedCode -> UpdateDecision.Required(m)
        m.versionCode > installedCode -> UpdateDecision.Available(m, snoozed = nowMs < snoozedUntilMs)
        else -> UpdateDecision.UpToDate
    }

    fun shouldCheck(lastCheckMs: Long, nowMs: Long, force: Boolean) = force || nowMs - lastCheckMs >= CHECK_EVERY_MS
}

/** What the downloaded APK says about itself (from PackageManager.getPackageArchiveInfo). */
data class ArchiveInfo(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

sealed interface VerifyOutcome {
    data object Ok : VerifyOutcome
    data class HashMismatch(val expected: String, val actual: String) : VerifyOutcome
    data class WrongPackage(val packageName: String) : VerifyOutcome
    data class WrongVersion(val expected: Int, val actual: Long) : VerifyOutcome
    data object SignerMismatch : VerifyOutcome
    data object Unreadable : VerifyOutcome
}

object UpdateVerifier {
    /**
     * Every check must pass before the APK reaches PackageInstaller: the file's SHA-256 equals the manifest's,
     * it is [expectedPackage] at exactly the manifest's versionCode, and it is signed by the same certificate(s)
     * as the installed app.
     */
    fun verify(fileSha256: String, m: UpdateManifest, archive: ArchiveInfo?, expectedPackage: String, installedSignerSha256: Set<String>): VerifyOutcome {
        if (!fileSha256.equals(m.sha256, ignoreCase = true)) return VerifyOutcome.HashMismatch(m.sha256, fileSha256.lowercase())
        if (archive == null) return VerifyOutcome.Unreadable
        if (archive.packageName != expectedPackage) return VerifyOutcome.WrongPackage(archive.packageName)
        if (archive.versionCode != m.versionCode.toLong()) return VerifyOutcome.WrongVersion(m.versionCode, archive.versionCode)
        if (installedSignerSha256.isEmpty() || archive.signerSha256 != installedSignerSha256) return VerifyOutcome.SignerMismatch
        return VerifyOutcome.Ok
    }
}
