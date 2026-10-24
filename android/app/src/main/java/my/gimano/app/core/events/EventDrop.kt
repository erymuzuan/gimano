package my.gimano.app.core.events

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Short links through the end-to-end encrypted relay (docs/events.md §5.6), mirroring event-core `drop.rs`:
 *
 *   https://manotu.app/j/#<dropId>.<b64u(secret)>      manotu://event?drop=<dropId>#<b64u(secret)>
 *   key  = HKDF-SHA256(ikm = secret (16 bytes), salt = empty, info = "manotu-drop-v1", L = 32)
 *   blob = nonce (24) ‖ XChaCha20-Poly1305(key, nonce, AD "manotu-event-v1", deflated signed envelope)
 *
 * The secret only ever travels after `#`: never log it, never send it to a server.
 */

/** Which blob to fetch ([dropId], chosen by the server) and the [secret] (16 bytes, made on the organizer's phone). */
class DropRef(val dropId: String, val secret: ByteArray) {
    init {
        require(DropLinks.isDropId(dropId) && secret.size == DropLinks.SECRET_BYTES)
    }

    /** `<dropId>.<b64u(secret)>`, the part after `#`. */
    val fragment: String get() = "$dropId.${B64u.encode(secret)}"

    /** `<site>/j/#<dropId>.<secret>`. */
    fun link(site: String = EventLinks.DEFAULT_SITE): String = "${site.trimEnd('/')}${DropLinks.PATH}#$fragment"

    /** `manotu://event?drop=<dropId>#<secret>`. */
    fun appLink(): String = "manotu://event?drop=$dropId#${B64u.encode(secret)}"

    override fun equals(other: Any?) = other is DropRef && dropId == other.dropId && secret.contentEquals(other.secret)
    override fun hashCode() = 31 * dropId.hashCode() + secret.contentHashCode()
    override fun toString() = "DropRef($dropId, …)"   // never the secret

    companion object {
        /** Stored form (sealed by the events vault): `dropId ‖ secret`. */
        fun decode(bytes: ByteArray): DropRef? = runCatching {
            DropRef(String(bytes, 0, DropLinks.ID_LEN, Charsets.US_ASCII), bytes.copyOfRange(DropLinks.ID_LEN, DropLinks.ID_LEN + DropLinks.SECRET_BYTES))
        }.getOrNull()?.takeIf { bytes.size == DropLinks.ID_LEN + DropLinks.SECRET_BYTES }
    }

    fun encode(): ByteArray = dropId.toByteArray(Charsets.US_ASCII) + secret
}

object DropLinks {
    /** The server's dropId alphabet (base58: no `0`, `O`, `I`, `l`). */
    const val ID_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    const val ID_LEN = 10
    const val SECRET_BYTES = 16
    const val PATH = "/j/"
    /** Largest blob the server accepts (256 KiB). */
    const val MAX_BLOB_BYTES = 256 * 1024

    fun isDropId(id: String) = id.length == ID_LEN && id.all { ID_ALPHABET.indexOf(it) >= 0 }

    /**
     * Accepts `https://<any site>/j/#<dropId>.<secret>`, `manotu://event?drop=<dropId>#<secret>` (also `gimano://`, and
     * other query parameters such as `stop`), or a bare `<dropId>.<secret>` (with or without the `#`). An `/a/` link is
     * never a drop link. Fails with `malformed_drop_link` (event-core `parse_drop_link`).
     */
    fun parse(link: String): DropRef {
        val l = link.trim()
        val hash = l.indexOf('#')
        val before = if (hash >= 0) l.substring(0, hash) else ""
        val fragment = if (hash >= 0) l.substring(hash + 1) else l
        val rest = listOf("manotu://event", "gimano://event").firstOrNull { before.startsWith(it) }?.let { before.substring(it.length) }
        val id: String
        val secret: String
        if (rest != null) {
            if (!rest.startsWith("?")) fail(EventPackException.MALFORMED_DROP_LINK)
            id = rest.substring(1).split('&').firstNotNullOfOrNull { kv -> kv.takeIf { it.startsWith("drop=") }?.substring(5) }
                ?: fail(EventPackException.MALFORMED_DROP_LINK)
            secret = fragment
        } else {
            if (before.isNotEmpty() && !before.endsWith(PATH) && !before.endsWith("/j")) fail(EventPackException.MALFORMED_DROP_LINK)
            val dot = fragment.indexOf('.')
            if (dot < 0) fail(EventPackException.MALFORMED_DROP_LINK)
            id = fragment.substring(0, dot)
            secret = fragment.substring(dot + 1)
        }
        if (!isDropId(id)) fail(EventPackException.MALFORMED_DROP_LINK)
        val s = B64u.decodeExact(secret, SECRET_BYTES) ?: fail(EventPackException.MALFORMED_DROP_LINK)
        return DropRef(id, s)
    }

    fun parseOrNull(link: String): DropRef? = try { parse(link) } catch (_: EventPackException) { null }

    /** Whether [link] is a short link, so it is fetched instead of opened as an `/a/` link. */
    fun isDropLink(link: String) = parseOrNull(link) != null

    /**
     * Deferred link (§5.6): the first `manotu.app/j/#…` link in copied text (a message, the web page's copy). Only the
     * site's own host, so an arbitrary copied text is never fetched. No `[:` inside a character class (ICU on Android
     * reads it as a POSIX class).
     */
    private val IN_TEXT = Regex("""(?:https?://)?(?:www\.)?manotu\.app/j/?#([1-9A-HJ-NP-Za-km-z]{10}\.[A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])""")

    fun findInText(text: String): DropRef? = IN_TEXT.find(text)?.let { parseOrNull(it.groupValues[1]) }

    /** Splits a blob into nonce and ciphertext: over 256 KiB is `drop_too_large`, shorter than nonce + tag `malformed_drop`. */
    fun parseBlob(blob: ByteArray): SealedPack {
        if (blob.size > MAX_BLOB_BYTES) fail(EventPackException.DROP_TOO_LARGE)
        if (blob.size < EventTransport.NONCE_LEN + EventTransport.TAG_LEN) fail(EventPackException.MALFORMED_DROP)
        return SealedPack(blob.copyOfRange(0, EventTransport.NONCE_LEN), blob.copyOfRange(EventTransport.NONCE_LEN, blob.size))
    }

    /** `nonce ‖ ciphertext`, refusing a blob over 256 KiB (`drop_too_large`). */
    fun encodeBlob(sealed: SealedPack): ByteArray {
        if (sealed.nonce.size + sealed.ciphertext.size > MAX_BLOB_BYTES) fail(EventPackException.DROP_TOO_LARGE)
        return sealed.nonce + sealed.ciphertext
    }
}

object DropKeys {
    val INFO = "manotu-drop-v1".toByteArray(Charsets.US_ASCII)

    /** `HKDF-SHA256(ikm = secret, salt = empty, info = "manotu-drop-v1", L = 32)`. */
    fun key(secret: ByteArray): ByteArray = Hkdf.sha256(secret, ByteArray(0), INFO, 32)
}

/** HKDF (RFC 5869) with HMAC-SHA256 from javax.crypto. */
object Hkdf {
    private const val HASH_LEN = 32

    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN)
        // An empty salt is HashLen zero bytes (RFC 5869 §2.2; HMAC pads the key with zeros anyway), and SecretKeySpec
        // refuses an empty key.
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)
        try {
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var i = 1
            while (pos < length) {
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
                mac.update(t); mac.update(info); mac.update(i.toByte())
                t.fill(0)
                t = mac.doFinal()
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n; i++
            }
            t.fill(0)
            return out
        } finally {
            prk.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

/** Drop sealing on top of the pack writer / reader (same XChaCha20-Poly1305 and signature rules as a link). */
object EventDrops {
    /** Deterministic blob for test vectors; [seal] picks a fresh nonce. Refuses a stops-only variant (always the full event). */
    fun sealParts(writer: EventPackWriter, eventBytes: ByteArray, seed: ByteArray, secret: ByteArray, nonce: ByteArray): ByteArray {
        if (EventParser.parse(eventBytes).variant != null) fail(EventPackException.INVALID_EVENT, "not a full event")
        val key = DropKeys.key(secret)
        try {
            return DropLinks.encodeBlob(writer.sealParts(eventBytes, seed, key, nonce).sealed)
        } finally {
            key.fill(0)
        }
    }

    /** The upload: fresh nonce, then opened again to prove it verifies before it leaves the phone. */
    fun seal(writer: EventPackWriter, eventBytes: ByteArray, seed: ByteArray, secret: ByteArray): ByteArray {
        val blob = sealParts(writer, eventBytes, seed, secret, writer.newNonce())
        check(EventPackReader(writer.sodium).openDrop(secret, blob).eventBytes.contentEquals(eventBytes)) { "drop does not round-trip" }
        return blob
    }

    /**
     * `X-Drop-Expires`: the event's last day + 14 days (§5.6). Read as the end of that day in the event's zone, i.e. the
     * start of the following day; the server clamps it to 1 … 400 days from now.
     */
    fun expiresAt(plan: EventPlan): Instant {
        val zone = runCatching { ZoneId.of(plan.tz) }.getOrElse { plan.end.offset }
        val lastDay = plan.end.atZoneSameInstant(zone).toLocalDate()
        return ZonedDateTime.of(lastDay.plusDays(15), java.time.LocalTime.MIDNIGHT, zone).toInstant()
    }
}

/**
 * Deferred link after install (§5.6): no tracking SDK, no install referrer. On the first launches the app looks once
 * per launch, in the foreground, for a copied short link and offers it. Pure rules; the Android side is
 * `DeferredLinkCheck`.
 */
object DeferredLinks {
    const val WINDOW_DAYS = 7L

    /** Still in the window: no event imported yet and less than 7 days since the first launch (install). */
    fun inWindow(firstLaunch: Instant, now: Instant, anyImported: Boolean): Boolean =
        !anyImported && now.isBefore(firstLaunch.plus(java.time.Duration.ofDays(WINDOW_DAYS)))

    /** What a declined offer remembers: a hash of the link, never the link (it holds the secret). */
    fun dismissKey(ref: DropRef): String =
        B64u.encode(EventHashes.sha256("manotu-deferred-v1\n${ref.fragment}".toByteArray(Charsets.US_ASCII)).copyOf(16))

    /** The link to offer from copied [text], unless it was declined before. */
    fun offer(text: String?, dismissed: Set<String>): DropRef? {
        val ref = text?.let(DropLinks::findInText) ?: return null
        return ref.takeIf { dismissKey(it) !in dismissed }
    }
}
