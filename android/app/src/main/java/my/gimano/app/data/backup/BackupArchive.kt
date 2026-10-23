package my.gimano.app.data.backup

import kotlinx.serialization.Serializable
import my.gimano.app.data.remote.GimanoJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupCounts(
    val saved_places: Int = 0,
    val recents: Int = 0,
    val private_places: Int = 0,
    val photos: Int = 0,
    val planned_drives: Int = 0,
)

@Serializable
data class KdfParams(val opslimit: Long, val memlimit: Long, val salt: String, val chunkBytes: Int = BackupCrypto.CHUNK)

@Serializable
data class BackupManifest(
    val format: Int = 1,
    val schemaVersion: Int,
    val appVersion: String,
    val platform: String = "android",
    val createdAt: String,
    val counts: BackupCounts,
    val encryption: String = ENCRYPTION_NONE,
    val kdf: KdfParams? = null,
) {
    companion object {
        const val ENCRYPTION_NONE = "none"
        const val ENCRYPTION_PASSPHRASE = "passphrase-argon2id-xchacha20poly1305"
    }
}

class BackupFormatException(msg: String) : Exception(msg)

/** Reads/writes the `.gimano` v1 zip: manifest.json + data.sqlite + photos/ (see docs/privacy-and-backup.md). */
object BackupArchive {
    const val MANIFEST = "manifest.json"
    const val DATA = "data.sqlite"
    const val PHOTOS = "photos/"

    /**
     * @param files entry name → file. With a [passphrase] every entry but the manifest is encrypted.
     */
    fun write(
        out: OutputStream, manifest: BackupManifest, files: List<Pair<String, File>>,
        passphrase: String?, crypto: BackupCrypto?,
        opslimit: Long = BackupCrypto.OPSLIMIT_MODERATE, memlimit: Long = BackupCrypto.MEMLIMIT_MODERATE,
    ) {
        var m = manifest
        var key: ByteArray? = null
        if (!passphrase.isNullOrEmpty()) {
            requireNotNull(crypto)
            val salt = crypto.randomSalt()
            key = crypto.deriveKey(passphrase, salt, opslimit, memlimit)
            m = m.copy(encryption = BackupManifest.ENCRYPTION_PASSPHRASE, kdf = KdfParams(opslimit, memlimit, Base64.getEncoder().encodeToString(salt)))
        }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(GimanoJson.encodeToString(BackupManifest.serializer(), m).toByteArray())
            zip.closeEntry()
            files.forEach { (name, f) ->
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { input -> if (key != null) crypto!!.encrypt(key, input, zip) else input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Reads just the manifest (to ask for a passphrase before extracting). */
    fun readManifest(input: InputStream): BackupManifest {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == MANIFEST) return parseManifest(zip.readBytes())
            }
        }
        throw BackupFormatException("Not a Gimano backup (no manifest.json)")
    }

    private fun parseManifest(bytes: ByteArray): BackupManifest {
        val m = runCatching { GimanoJson.decodeFromString(BackupManifest.serializer(), bytes.decodeToString()) }
            .getOrElse { throw BackupFormatException("Damaged manifest.json") }
        if (m.format != 1) throw BackupFormatException("Unsupported backup format ${m.format}")
        return m
    }

    data class Extracted(val manifest: BackupManifest, val dbFile: File, val photos: List<File>)

    /** Extracts into [workDir], decrypting with [passphrase] when the manifest says so. */
    fun extract(input: InputStream, workDir: File, passphrase: String?, crypto: BackupCrypto?): Extracted {
        workDir.mkdirs()
        val pending = ArrayList<Pair<String, ByteArray>>()
        var manifest: BackupManifest? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zip.readBytes()
                if (e.name == MANIFEST) manifest = parseManifest(bytes) else pending += e.name to bytes
            }
        }
        val m = manifest ?: throw BackupFormatException("Not a Gimano backup (no manifest.json)")
        var key: ByteArray? = null
        if (m.encryption == BackupManifest.ENCRYPTION_PASSPHRASE) {
            if (passphrase.isNullOrEmpty()) throw WrongPassphraseException()
            val kdf = m.kdf ?: throw BackupFormatException("Missing kdf parameters")
            key = requireNotNull(crypto).deriveKey(passphrase, Base64.getDecoder().decode(kdf.salt), kdf.opslimit, kdf.memlimit)
        } else if (m.encryption != BackupManifest.ENCRYPTION_NONE) {
            throw BackupFormatException("Unknown encryption ${m.encryption}")
        }
        var db: File? = null
        val photos = ArrayList<File>()
        pending.forEach { (name, bytes) ->
            // Zip-slip guard: only our two known locations are accepted.
            val safe = name == DATA || (name.startsWith(PHOTOS) && !name.contains("..") && name.count { it == '/' } == 1)
            if (!safe) return@forEach
            val plain = if (key != null) ByteArrayOutputStream().also { crypto!!.decrypt(key, bytes.inputStream(), it) }.toByteArray() else bytes
            val f = File(workDir, name).also { it.parentFile?.mkdirs() }
            f.writeBytes(plain)
            if (name == DATA) db = f else photos += f
        }
        return Extracted(m, db ?: throw BackupFormatException("Backup has no data.sqlite"), photos)
    }
}
