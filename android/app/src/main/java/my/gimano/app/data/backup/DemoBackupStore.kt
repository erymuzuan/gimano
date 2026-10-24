package my.gimano.app.data.backup

import androidx.annotation.StringRes
import my.gimano.app.R
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * "Demo storage (on this phone)": shown in DEMO MODE ONLY, for trying backup → restore end to end without any
 * account (e.g. a store reviewer). It writes the real backup format (BackupArchive, passphrase encryption included)
 * to app-private storage — filesDir/demo-backups, which is not in Android Auto Backup and goes with the app.
 * It is labelled "Demo, not Google Drive" everywhere and never presented as Drive. Nothing leaves the phone.
 */
class DemoBackupStore(private val dir: File) {
    data class Entry(val name: String, val createdAtMs: Long, val sizeBytes: Long)

    companion object {
        const val DIR = "demo-backups"
        /** Newest demo backups kept; older ones are removed. */
        const val KEEP = 5
        private val SAFE = Regex("^manotu-demo-[0-9]{8}-[0-9]{6}(-[0-9]+)?\\.manotu$")
    }

    fun save(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Entry {
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(nowMs))
        var f = File(dir, "manotu-demo-$stamp.manotu")
        var i = 1
        while (f.exists()) f = File(dir, "manotu-demo-$stamp-${i++}.manotu")
        val tmp = File(dir, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { tmp.delete(); throw IOException("demo storage: could not save") }
        f.setLastModified(nowMs)
        list().drop(KEEP).forEach { File(dir, it.name).delete() }
        return Entry(f.name, nowMs, bytes.size.toLong())
    }

    /** Newest first. */
    fun list(): List<Entry> = dir.listFiles().orEmpty()
        .filter { it.isFile && SAFE.matches(it.name) }
        .map { Entry(it.name, it.lastModified(), it.length()) }
        .sortedWith(compareByDescending<Entry> { it.createdAtMs }.thenByDescending { it.name })

    /** Only names this store wrote (no paths). */
    fun open(name: String): InputStream {
        require(SAFE.matches(name)) { "not a demo backup: $name" }
        return File(dir, name).inputStream()
    }

    fun clear() = dir.listFiles().orEmpty().forEach { it.delete() }
}

/**
 * Plain-language Google Drive failures (never a false success): what went wrong and that nothing was backed up or
 * restored. [statusCode] is the Google Play services status of an ApiException, when there is one.
 */
object DriveFailure {
    enum class Kind { CANCELLED, OFFLINE, UNAVAILABLE, REJECTED }

    /** CommonStatusCodes / GoogleSignInStatusCodes values we map. */
    private const val CANCELED = 16
    private const val SIGN_IN_CANCELLED = 12501
    private const val NETWORK_ERROR = 7
    private const val API_NOT_CONNECTED = 17
    private const val DEVELOPER_ERROR = 10

    /** [t] null = the consent screen came back without a token (closed, back pressed, account not chosen). */
    fun of(t: Throwable?, statusCode: Int? = null): Kind = when {
        t == null -> Kind.CANCELLED
        statusCode == CANCELED || statusCode == SIGN_IN_CANCELLED -> Kind.CANCELLED
        statusCode == NETWORK_ERROR -> Kind.OFFLINE
        statusCode == API_NOT_CONNECTED || statusCode == DEVELOPER_ERROR -> Kind.UNAVAILABLE
        statusCode != null -> Kind.UNAVAILABLE
        generateSequence(t) { it.cause }.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException } -> Kind.OFFLINE
        else -> Kind.REJECTED
    }

    @StringRes fun message(k: Kind): Int = when (k) {
        Kind.CANCELLED -> R.string.drive_cancelled
        Kind.OFFLINE -> R.string.drive_offline
        Kind.UNAVAILABLE -> R.string.drive_unavailable
        Kind.REJECTED -> R.string.drive_rejected
    }
}
