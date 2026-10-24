package my.gimano.app.core.events

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
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
    }

    fun openLink(link: String): OpenedPack {
        val (key, sealed) = EventTransport.parseLink(link)
        return open(key, sealed)
    }

    fun openFile(bytes: ByteArray, secretCode: String): OpenedPack {
        val sealed = EventTransport.parseFile(bytes)
        val key = SecretCode.decode(secretCode)
        return open(key, sealed)
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
