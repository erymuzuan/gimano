package my.gimano.app.data.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import my.gimano.app.data.backup.SqlPort
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The single `identity` row. [userId] is "" until the server has accepted the key. */
class StoredIdentity(val userId: String, val displayName: String?, val seed: ByteArray, val createdAt: String) {
    val registered get() = userId.isNotEmpty()
}

/** Wraps the seed at rest. Backups carry the plain (base64) seed, protected by the backup's own passphrase. */
interface SeedWrapper {
    fun wrap(seed: ByteArray): String
    fun unwrap(stored: String): ByteArray
    fun isWrapped(stored: String): Boolean
}

/** AES-256-GCM key in Android Keystore (non-exportable). Stored form: "ks1:" + base64(iv ‖ ciphertext+tag). */
class KeystoreSeedWrapper(private val alias: String = "gimano.identity.v1") : SeedWrapper {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun wrap(seed: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return PREFIX + Base64.getEncoder().encodeToString(c.iv + c.doFinal(seed))
    }

    override fun unwrap(stored: String): ByteArray {
        if (!isWrapped(stored)) return Base64.getDecoder().decode(stored)
        val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12)
    }

    override fun isWrapped(stored: String) = stored.startsWith(PREFIX)

    companion object { const val PREFIX = "ks1:" }
}

/** Raw-SQL access to `identity` (not a Room entity), so it runs on Room and on JDBC in tests. */
class IdentityStore(
    private val port: () -> SqlPort,
    private val wrapper: SeedWrapper,
    /** Told (no secrets) when an unreadable identity was dropped. */
    private val onDropped: (Throwable) -> Unit = {},
) {
    private val sep = "\u001f"

    /**
     * The identity, or null if there is none, or if its seed can no longer be decrypted on this phone.
     *
     * gimano.db is in Android Auto Backup (cloud + device transfer), but the Keystore key that wraps the seed is
     * never backed up and is deleted on uninstall. After a reinstall (restoreAtInstall) or a new-phone restore, the
     * row comes back with a `ks1:` seed and a new, different key: decrypting throws AEADBadTagException on every
     * call, so every signed request failed (search, reports, …) forever. Such a row is dropped and the phone
     * registers as a new device, as data_extraction_rules.xml intends (the user's own Manotu backup or recovery
     * words bring the old pseudonym back). Transient Keystore errors are rethrown, never treated as key loss.
     */
    fun get(): StoredIdentity? {
        val row = port().queryStrings(
            "SELECT user_id || '$sep' || COALESCE(display_name, '') || '$sep' || seed_b64 || '$sep' || created_at FROM identity LIMIT 1",
        ).firstOrNull() ?: return null
        val (id, name, seed, at) = row.split(sep, limit = 4)
        val plain = try {
            wrapper.unwrap(seed)
        } catch (e: java.security.GeneralSecurityException) {
            if (!keyLost(e)) throw e
            port().exec("DELETE FROM identity")
            onDropped(e)
            return null
        }
        return StoredIdentity(id, name.ifEmpty { null }, plain, at)
    }

    companion object {
        /** The wrapping key is gone or replaced for good (not a transient Keystore failure). */
        fun keyLost(e: Throwable): Boolean =
            e is javax.crypto.AEADBadTagException ||
                e is java.security.UnrecoverableKeyException ||
                e.javaClass.simpleName == "KeyPermanentlyInvalidatedException"
    }

    /** Replaces the single row. */
    fun put(userId: String, displayName: String?, seed: ByteArray, createdAt: String = Instant.now().toString()) {
        val p = port()
        p.transaction {
            p.exec("DELETE FROM identity")
            p.exec("INSERT INTO identity (user_id, display_name, seed_b64, created_at) VALUES (?, ?, ?, ?)", userId, displayName, wrapper.wrap(seed), createdAt)
        }
    }

    fun setUser(userId: String, displayName: String?) {
        port().exec("UPDATE identity SET user_id = ?, display_name = ?", userId, displayName)
    }

    /** After a restore: rows arrive with plain seeds; wrap them. */
    fun wrapPlainSeeds() = my.gimano.app.data.backup.BackupDb.transformIdentitySeeds(port()) { s ->
        if (wrapper.isWrapped(s)) s else wrapper.wrap(Base64.getDecoder().decode(s))
    }
}
