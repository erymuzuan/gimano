package my.gimano.app.core.events

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.Sign

/** A decrypted pack whose signature verified and whose event passed validation. */
class OpenedPack(
    val event: EventPlan,
    /** The exact signed bytes: store and hash these, never a re-serialization. */
    val eventBytes: ByteArray,
    val organizerPub: ByteArray,
    val revisionHash: ByteArray,
) {
    val safetyCode: String get() = EventHashes.safetyCode(organizerPub)
    val revisionHashB64u: String get() = B64u.encode(revisionHash)
}

/**
 * Opens Manotu event packs (docs/events.md §3, §5, §7 steps 1–2):
 * decrypt (XChaCha20-Poly1305-IETF, AD `manotu-event-v1`) → bounded raw inflate (20 MiB) → envelope →
 * Ed25519 signature over `"manotu-event-v1\n" ‖ eventBytes` → only then parse and validate the event.
 * Nothing here logs or keeps the key or the plaintext.
 */
class EventPackReader(private val sodium: LazySodium) {
    companion object {
        val AD = "manotu-event-v1".toByteArray(Charsets.US_ASCII)
        val SIGNATURE_PREFIX = "manotu-event-v1\n".toByteArray(Charsets.US_ASCII)

        /** §5.2 suite 2: libsodium `crypto_pwhash`, ALG_ARGON2ID13, opslimit 3, memlimit 64 MiB, 32-byte key. */
        const val FILE_KDF_OPSLIMIT = 3L
        const val FILE_KDF_MEMLIMIT = 67_108_864L
        const val FILE_KEY_BYTES = 32
    }

    fun openLink(link: String): OpenedPack {
        val (key, sealed) = EventTransport.parseLink(link)
        return open(key, sealed)
    }

    /**
     * Opens a file of either suite with the code the host sent through another channel: 8 characters for suite 2
     * (Argon2id, 64 MiB, a fraction of a second: never on the main thread), 52 for suite 1. A code of the other
     * suite's shape is `invalid_secret_code`, checked before deriving.
     */
    fun openFile(bytes: ByteArray, secretCode: String): OpenedPack {
        val parsed = EventTransport.parseFile(bytes)
        val key = fileKey(parsed, secretCode)
        try { return open(key, parsed.sealed) } finally { key.fill(0) }
    }

    fun fileKey(parsed: ParsedFile, secretCode: String): ByteArray =
        parsed.salt?.let { deriveFileKey(secretCode, it) } ?: SecretCode.decode(secretCode)

    /**
     * The suite-2 file key: Argon2id13 over the code's 8 upper-case characters (no separator) with the file salt.
     * The code is checked first, so a typo fails as `invalid_secret_code` without the deliberately slow derivation;
     * a failed derivation (allocation) is `key_derivation`.
     */
    fun deriveFileKey(secretCode: String, salt: ByteArray): ByteArray {
        val password = SecretCodeV2.parse(secretCode)
        try {
            if (salt.size != EventTransport.FILE_SALT_LEN) fail(EventPackException.KEY_DERIVATION)
            val out = ByteArray(FILE_KEY_BYTES)
            val ok = try {
                sodium.cryptoPwHash(out, out.size, password, password.size, salt, FILE_KDF_OPSLIMIT,
                    com.sun.jna.NativeLong(FILE_KDF_MEMLIMIT), PwHash.Alg.PWHASH_ALG_ARGON2ID13)
            } catch (e: Exception) {
                false
            } catch (e: OutOfMemoryError) {
                false
            }
            if (!ok) { out.fill(0); fail(EventPackException.KEY_DERIVATION) }
            return out
        } finally {
            password.fill(0)
        }
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        if (key.size != AEAD.XCHACHA20POLY1305_IETF_KEYBYTES || nonce.size != AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES) fail(EventPackException.DECRYPT)
        if (ciphertext.size < AEAD.XCHACHA20POLY1305_IETF_ABYTES) fail(EventPackException.DECRYPT)
        val m = ByteArray(ciphertext.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(m, LongArray(1), null, ciphertext, ciphertext.size.toLong(), AD, AD.size.toLong(), nonce, key)
        if (!ok) fail(EventPackException.DECRYPT)
        return m
    }

    fun open(key: ByteArray, sealed: SealedPack): OpenedPack {
        val deflated = decrypt(key, sealed.nonce, sealed.ciphertext)
        val envelopeBytes = RawInflate.inflate(deflated, EventLimits.MAX_INFLATED_BYTES)
        val env = try {
            EventJson.decodeFromString(RawEnvelope.serializer(), envelopeBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            fail(EventPackException.MALFORMED_ENVELOPE)
        }
        if (env.v != 1L) fail(EventPackException.UNSUPPORTED_ENVELOPE_VERSION)
        val pub = B64u.decodeExact(env.pub, Sign.ED25519_PUBLICKEYBYTES) ?: fail(EventPackException.MALFORMED_ENVELOPE)
        val sig = B64u.decodeExact(env.sig, Sign.ED25519_BYTES) ?: fail(EventPackException.MALFORMED_ENVELOPE)
        val eventBytes = B64u.decode(env.event) ?: fail(EventPackException.MALFORMED_ENVELOPE)

        val msg = SIGNATURE_PREFIX + eventBytes
        if (!sodium.cryptoSignVerifyDetached(sig, msg, msg.size, pub)) fail(EventPackException.SIGNATURE)

        val event = EventParser.parse(eventBytes)
        return OpenedPack(event, eventBytes, pub, EventHashes.revisionHash(eventBytes))
    }
}
