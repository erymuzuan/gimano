package my.gimano.app.data.backup

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretStream
import com.sun.jna.NativeLong
import java.io.InputStream
import java.io.OutputStream

class WrongPassphraseException : Exception("Wrong passphrase, or the backup is damaged")

/**
 * Passphrase encryption for `.gimano` backups (docs/privacy-and-backup.md), libsodium only, so
 * iOS (swift-sodium) can read Android backups and vice versa:
 *
 *  key   = crypto_pwhash(passphrase, salt[16], opslimit, memlimit, ARGON2ID13) → 32 bytes
 *  entry = header[24] ‖ chunk₁ ‖ … ‖ chunkₙ
 *  chunk = crypto_secretstream_xchacha20poly1305_push(plaintext ≤ 65 536 bytes), TAG_FINAL on the last
 *          (an empty entry is a single 17-byte FINAL chunk)
 *
 * Every zip entry except manifest.json is encrypted this way, each with its own header.
 */
class BackupCrypto(private val sodium: LazySodium) {
    companion object {
        const val CHUNK = 65_536
        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
        val OPSLIMIT_MODERATE: Long = PwHash.ARGON2ID_OPSLIMIT_MODERATE
        val MEMLIMIT_MODERATE: Long = PwHash.ARGON2ID_MEMLIMIT_MODERATE.toLong()
    }

    fun randomSalt(): ByteArray = sodium.randomBytesBuf(SALT_BYTES)

    fun deriveKey(passphrase: String, salt: ByteArray, opslimit: Long, memlimit: Long): ByteArray {
        val pw = passphrase.toByteArray(Charsets.UTF_8)
        val key = ByteArray(KEY_BYTES)
        check(sodium.cryptoPwHash(key, KEY_BYTES, pw, pw.size, salt, opslimit, NativeLong(memlimit), PwHash.Alg.PWHASH_ALG_ARGON2ID13)) {
            "Key derivation failed (out of memory?)"
        }
        return key
    }

    fun encrypt(key: ByteArray, input: InputStream, output: OutputStream) {
        val state = SecretStream.State()
        val header = ByteArray(SecretStream.HEADERBYTES)
        check(sodium.cryptoSecretStreamInitPush(state, header, key))
        output.write(header)
        val buf = ByteArray(CHUNK)
        var n = readFully(input, buf)
        while (true) {
            val peek = ByteArray(CHUNK)
            val m = if (n == CHUNK) readFully(input, peek) else 0
            val last = m == 0
            val cipher = ByteArray(n + SecretStream.ABYTES)
            val tag = if (last) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
            check(sodium.cryptoSecretStreamPush(state, cipher, buf, n.toLong(), tag))
            output.write(cipher)
            if (last) break
            System.arraycopy(peek, 0, buf, 0, m)
            n = m
        }
    }

    fun decrypt(key: ByteArray, input: InputStream, output: OutputStream) {
        val state = SecretStream.State()
        val header = ByteArray(SecretStream.HEADERBYTES)
        if (readFully(input, header) != header.size) throw WrongPassphraseException()
        if (!sodium.cryptoSecretStreamInitPull(state, header, key)) throw WrongPassphraseException()
        val cbuf = ByteArray(CHUNK + SecretStream.ABYTES)
        while (true) {
            val n = readFully(input, cbuf)
            if (n < SecretStream.ABYTES) throw WrongPassphraseException()
            val msg = ByteArray(n - SecretStream.ABYTES)
            val tag = ByteArray(1)
            val cipher = if (n == cbuf.size) cbuf else cbuf.copyOf(n)
            if (!sodium.cryptoSecretStreamPull(state, msg, tag, cipher, n.toLong())) throw WrongPassphraseException()
            output.write(msg)
            if (tag[0] == SecretStream.TAG_FINAL) break
            if (n < cbuf.size) throw WrongPassphraseException() // truncated: short chunk without FINAL
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}
