package my.gimano.app.data.share

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import kotlinx.serialization.Serializable
import my.gimano.app.data.remote.GimanoJson
import java.util.Base64

/** What the recipient's browser decrypts (docs/api.md "Share my drive"). */
@Serializable
data class SharePayload(
    val lat: Double,
    val lng: Double,
    val heading: Double? = null,
    val speedKmh: Double? = null,
    val etaIso: String? = null,
    val remainingM: Double? = null,
    val destinationName: String,
    val status: String,   // driving | arrived | ended
    val ts: String,
)

@Serializable
data class SealedShare(val nonce: String, val ciphertext: String)

/**
 * End-to-end encryption for "Share my drive": libsodium crypto_aead_xchacha20poly1305_ietf,
 * a random 24-byte nonce per update and associated data = shareId. The 256-bit key is made on the
 * device and only ever travels in the link's #fragment, which browsers never send to a server.
 */
class ShareCrypto(private val sodium: LazySodium) {
    companion object {
        const val VIEWER = "https://www.bukitbunga.com/gimano/s/"
        private val b64url = Base64.getUrlEncoder().withoutPadding()

        /** `https://www.bukitbunga.com/gimano/s/#<shareId>.<base64url(K), no padding>` */
        fun link(shareId: String, key: ByteArray): String = "$VIEWER#$shareId.${b64url.encodeToString(key)}"

        fun parseLink(link: String): Pair<String, ByteArray>? {
            val frag = link.substringAfter('#', "").takeIf { it.contains('.') } ?: return null
            return frag.substringBefore('.') to runCatching { Base64.getUrlDecoder().decode(frag.substringAfter('.')) }.getOrElse { return null }
        }
    }

    fun newKey(): ByteArray = ByteArray(AEAD.XCHACHA20POLY1305_IETF_KEYBYTES).also { sodium.cryptoAeadXChaCha20Poly1305IetfKeygen(it) }

    fun seal(key: ByteArray, shareId: String, payload: SharePayload): SealedShare {
        val m = GimanoJson.encodeToString(SharePayload.serializer(), payload).toByteArray()
        val ad = shareId.toByteArray()
        val nonce = sodium.randomBytesBuf(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val c = ByteArray(m.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        check(sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(c, LongArray(1), m, m.size.toLong(), ad, ad.size.toLong(), null, nonce, key))
        val enc = Base64.getEncoder()
        return SealedShare(enc.encodeToString(nonce), enc.encodeToString(c))
    }

    /** For tests and the (browser) viewer's logic: null if the key, shareId or data don't match. */
    fun open(key: ByteArray, shareId: String, sealed: SealedShare): SharePayload? {
        val dec = Base64.getDecoder()
        val nonce = dec.decode(sealed.nonce)
        val c = dec.decode(sealed.ciphertext)
        if (c.size < AEAD.XCHACHA20POLY1305_IETF_ABYTES) return null
        val ad = shareId.toByteArray()
        val m = ByteArray(c.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        if (!sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(m, LongArray(1), null, c, c.size.toLong(), ad, ad.size.toLong(), nonce, key)) return null
        return runCatching { GimanoJson.decodeFromString(SharePayload.serializer(), m.decodeToString()) }.getOrNull()
    }
}
