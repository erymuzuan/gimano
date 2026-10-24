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
    /** Schema 3; absent (0) in older manifests. */
    val personal_routes: Int = 0,
)

@Serializable
data class KdfParams(val alg: String = "argon2id13", val opslimit: Long, val memlimit: Long, val salt: String, val chunkSize: Int = BackupCrypto.CHUNK)

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

/**
 * Reads/writes the `.gimano` v1 zip (docs/privacy-and-backup.md), byte-compatible with iOS:
 *  - unencrypted: zip { manifest.json, data.sqlite, photos/<id>.webp }
 *  - encrypted:   zip { manifest.json, payload.enc }, payload.enc = secretstream(zip { data.sqlite, photos/… })
 */
object BackupArchive {
    const val MANIFEST = "manifest.json"
    const val DATA = "data.sqlite"
    const val PHOTOS = "photos/"
    const val PAYLOAD = "payload.enc"

    /**
     * @param files entry name → file (data.sqlite, photos/x.webp). With a [passphrase] they are
     *   zipped into an inner archive which is encrypted as payload.enc.
     */
    fun write(
        out: OutputStream, manifest: BackupManifest, files: List<Pair<String, File>>,
        passphrase: String?, crypto: BackupCrypto?,
        opslimit: Long = BackupCrypto.OPSLIMIT_MODERATE, memlimit: Long = BackupCrypto.MEMLIMIT_MODERATE,
    ) {
        if (passphrase.isNullOrEmpty()) {
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(GimanoJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                files.forEach { (name, f) -> zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
            return
        }
        requireNotNull(crypto)
        val salt = crypto.randomSalt()
        val key = crypto.deriveKey(passphrase, salt, opslimit, memlimit)
        val m = manifest.copy(encryption = BackupManifest.ENCRYPTION_PASSPHRASE, kdf = KdfParams(opslimit = opslimit, memlimit = memlimit, salt = Base64.getEncoder().encodeToString(salt)))
        val inner = ByteArrayOutputStream().also { buf ->
            ZipOutputStream(buf).use { zip -> files.forEach { (name, f) -> zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() } }
        }.toByteArray()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(GimanoJson.encodeToString(BackupManifest.serializer(), m).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PAYLOAD))
            crypto.encrypt(key, inner.inputStream(), zip)
            zip.closeEntry()
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
        throw BackupFormatException("Not a Manotu backup (no manifest.json)")
    }

    private fun parseManifest(bytes: ByteArray): BackupManifest {
        val m = runCatching { GimanoJson.decodeFromString(BackupManifest.serializer(), bytes.decodeToString()) }
            .getOrElse { throw BackupFormatException("Damaged manifest.json") }
        if (m.format != 1) throw BackupFormatException("Unsupported backup format ${m.format}")
        return m
    }

    data class Extracted(val manifest: BackupManifest, val dbFile: File, val photos: List<File>)

    private fun readEntries(input: InputStream): List<Pair<String, ByteArray>> = ZipInputStream(input).use { zip ->
        buildList { while (true) { val e = zip.nextEntry ?: break; if (!e.isDirectory) add(e.name to zip.readBytes()) } }
    }

    /** Extracts into [workDir], decrypting payload.enc with [passphrase] when the manifest says so. */
    fun extract(input: InputStream, workDir: File, passphrase: String?, crypto: BackupCrypto?): Extracted {
        workDir.mkdirs()
        val outer = readEntries(input)
        val m = outer.firstOrNull { it.first == MANIFEST }?.let { parseManifest(it.second) } ?: throw BackupFormatException("Not a Manotu backup (no manifest.json)")
        val entries = when (m.encryption) {
            BackupManifest.ENCRYPTION_NONE -> outer.filter { it.first != MANIFEST }
            BackupManifest.ENCRYPTION_PASSPHRASE -> {
                if (passphrase.isNullOrEmpty()) throw WrongPassphraseException()
                val kdf = m.kdf ?: throw BackupFormatException("Missing kdf parameters")
                if (kdf.chunkSize != BackupCrypto.CHUNK) throw BackupFormatException("Unsupported chunk size ${kdf.chunkSize}")
                val payload = outer.firstOrNull { it.first == PAYLOAD }?.second ?: throw BackupFormatException("Missing payload.enc")
                val key = requireNotNull(crypto).deriveKey(passphrase, Base64.getDecoder().decode(kdf.salt), kdf.opslimit, kdf.memlimit)
                val inner = ByteArrayOutputStream().also { crypto.decrypt(key, payload.inputStream(), it) }.toByteArray()
                readEntries(inner.inputStream())
            }
            else -> throw BackupFormatException("Unknown encryption ${m.encryption}")
        }
        var db: File? = null
        val photos = ArrayList<File>()
        entries.forEach { (name, bytes) ->
            // Zip-slip guard: only our two known locations are accepted.
            val safe = name == DATA || (name.startsWith(PHOTOS) && !name.contains("..") && name.count { it == '/' } == 1)
            if (!safe) return@forEach
            val f = File(workDir, name).also { it.parentFile?.mkdirs() }
            f.writeBytes(bytes)
            if (name == DATA) db = f else photos += f
        }
        return Extracted(m, db ?: throw BackupFormatException("Backup has no data.sqlite"), photos)
    }
}
