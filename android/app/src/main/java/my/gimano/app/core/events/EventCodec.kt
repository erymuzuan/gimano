package my.gimano.app.core.events

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Event pack reader errors (docs/events.md). [code] is the stable code shared with the Rust reference
 * (`event_core::Error::code()`) and the test vectors' `expectedError`.
 */
class EventPackException(val code: String, message: String = code) : Exception(message) {
    companion object {
        const val INVALID_EVENT = "invalid_event"
        const val MALFORMED_EVENT = "malformed_event"
        const val FRAGMENT_TOO_LONG = "fragment_too_long"
        const val MALFORMED_LINK = "malformed_link"
        const val UNSUPPORTED_LINK_VERSION = "unsupported_link_version"
        const val FILE_TOO_LARGE = "file_too_large"
        const val MALFORMED_FILE = "malformed_file"
        const val UNSUPPORTED_FILE_FORMAT = "unsupported_file_format"
        const val UNSUPPORTED_SUITE = "unsupported_suite"
        const val INVALID_SECRET_CODE = "invalid_secret_code"
        /** Suite-2 file key: Argon2id could not run (e.g. the 64 MiB allocation failed). */
        const val KEY_DERIVATION = "key_derivation"
        const val DECRYPT = "decrypt"
        const val INFLATE = "inflate"
        const val INFLATE_TOO_LARGE = "inflate_too_large"
        const val MALFORMED_ENVELOPE = "malformed_envelope"
        const val UNSUPPORTED_ENVELOPE_VERSION = "unsupported_envelope_version"
        const val SIGNATURE = "signature"
        /** §5.5: `variant.kind` other than `stops-only`. */
        const val UNSUPPORTED_VARIANT = "unsupported_variant"
        /** §5.5: a stops-only variant with a route `polyline`. */
        const val VARIANT_HAS_POLYLINE = "variant_has_polyline"
        /** §5.5: a full event with a route lacking `polyline`. */
        const val MISSING_POLYLINE = "missing_polyline"
    }
}

internal fun fail(code: String, message: String = code): Nothing = throw EventPackException(code, message)

/** Limits from docs/events.md §4 (same values as event-core `validate::limits`). */
object EventLimits {
    const val MAX_EVENT_DAYS = 14L
    const val MAX_STOPS = 200
    const val MAX_SESSIONS = 50
    const val MAX_ROUTES = 20
    const val MAX_POLYLINE_POINTS = 50_000
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    const val MAX_FRAGMENT_CHARS = 16_000
    const val MAX_INFLATED_BYTES = 20 * 1024 * 1024

    const val TITLE_CHARS = 120
    const val DESCRIPTION_CHARS = 2000
    const val STOP_LABEL_CHARS = 80
    const val STOP_NOTES_CHARS = 500
    const val AUDIENCE_CHARS = 40
    const val ROUTE_TITLE_CHARS = 80
    const val SESSION_TITLE_CHARS = 120
    const val ALIAS_CHARS = 80
    const val TZ_CHARS = 64
    const val MAX_DWELL_MIN = 14L * 24 * 60

    const val EVENT_ID_BYTES = 16
    const val ITEM_ID_BYTES = 8
}

/** base64url without padding; decoding is strict (alphabet only, no padding, canonical trailing bits). */
object B64u {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    fun encode(b: ByteArray): String = enc.encodeToString(b)

    fun decode(s: String): ByteArray? {
        for (ch in s) {
            val ok = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_'
            if (!ok) return null
        }
        if (s.length % 4 == 1) return null
        val b = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull() ?: return null
        // Re-encoding must give the same text: rejects non-zero trailing bits.
        return if (enc.encodeToString(b) == s) b else null
    }

    fun decodeExact(s: String, size: Int): ByteArray? = decode(s)?.takeIf { it.size == size }
}

object EventHashes {
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /**
     * The revision hash (§7, what the next revision's `parent` refers to): SHA-256 of the exact signed event bytes,
     * except for a stops-only variant (§5.5), whose revision hash is its `variant.fullHash`. For bytes that already
     * passed [EventParser.parse].
     */
    fun revisionHash(eventBytes: ByteArray): ByteArray = variantOf(eventBytes)?.fullHashBytes ?: sha256(eventBytes)

    /** The stops-only variant marker of event bytes, if they are one (cheap probe, no validation). */
    fun variantOf(eventBytes: ByteArray): EventVariant? = runCatching {
        EventJson.decodeFromString(VariantProbe.serializer(), eventBytes.toString(Charsets.UTF_8)).variant
    }.getOrNull()?.takeIf { it.kind == EventVariant.STOPS_ONLY }?.let { EventVariant(it.kind, it.fullHash) }

    @kotlinx.serialization.Serializable
    internal data class VariantProbe(val variant: RawVariant? = null)

    /** Digits and upper-case letters without the look-alikes 0/O and 1/I. */
    const val SAFETY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** First 30 bits of SHA-256(organizer pub), 5 bits per character, e.g. "S8EVC7" (§7.3). */
    fun safetyCode(organizerPub: ByteArray): String {
        val h = sha256(organizerPub)
        val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return String(CharArray(6) { i -> SAFETY_ALPHABET[((v shr (27 - 5 * i)) and 31).toInt()] })
    }
}

/**
 * The suite-2 file secret code (§5.2): 8 CSPRNG characters of the safety alphabet (40 bits), shown `XXXX-XXXX`.
 * The code is the Argon2id password, not a key; it stays the same for every revision of an event.
 */
object SecretCodeV2 {
    const val CHARS = 8
    const val BYTES = 5
    private const val ALPHABET = EventHashes.SAFETY_ALPHABET

    /** 40 bits (MSB first) as `XXXX-XXXX`; uniform bits give uniform characters. */
    fun encode(bits: ByteArray): String {
        require(bits.size == BYTES)
        val v = bits.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val sb = StringBuilder(CHARS + 1)
        for (i in 0 until CHARS) {
            if (i == 4) sb.append('-')
            sb.append(ALPHABET[((v shr (35 - 5 * i)) and 31).toInt()])
        }
        return sb.toString()
    }

    /** A fresh code from the CSPRNG, e.g. `K7M4-QX9P`. */
    fun generate(rng: java.security.SecureRandom = java.security.SecureRandom()): String {
        val b = ByteArray(BYTES).also(rng::nextBytes)
        try { return encode(b) } finally { b.fill(0) }
    }

    /**
     * Reads a typed code: case-insensitive, spaces and `-` ignored, any other character outside the alphabet
     * (including `0 O 1 I`, no look-alike mapping) rejected. Returns the 8 upper-case ASCII bytes, the Argon2id
     * password. Fails with `invalid_secret_code`, before any derivation.
     */
    fun parse(code: String): ByteArray {
        val out = ByteArray(CHARS)
        var n = 0
        for (c in code) {
            if (c == '-' || c.isWhitespace()) continue
            val u = if (c in 'a'..'z') c - 32 else c
            if (ALPHABET.indexOf(u) < 0) { out.fill(0); fail(EventPackException.INVALID_SECRET_CODE, "invalid character") }
            if (n == CHARS) { out.fill(0); fail(EventPackException.INVALID_SECRET_CODE, "too long") }
            out[n++] = u.code.toByte()
        }
        if (n != CHARS) { out.fill(0); fail(EventPackException.INVALID_SECRET_CODE, "wrong length") }
        return out
    }

    /** A stored or typed code in its display form `XXXX-XXXX` (fails like [parse]). */
    fun format(code: String): String {
        val p = parse(code)
        try { return String(p, Charsets.US_ASCII).let { it.substring(0, 4) + "-" + it.substring(4) } } finally { p.fill(0) }
    }
}

/** Suite-1 (read only) file key as a Crockford base32 "secret code" (§5.2): 52 chars, MSB first, 4 zero padding bits, groups of 4. */
object SecretCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val KEY_CHARS = 52

    fun encode(key: ByteArray): String {
        require(key.size == 32)
        val sb = StringBuilder(KEY_CHARS)
        var acc = 0
        var bits = 0
        for (b in key) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(acc shr bits) and 31])
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(acc shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    /** Case-insensitive; `I`/`L` read as 1 and `O` as 0; `-` and whitespace ignored; padding bits must be zero. */
    fun decode(code: String): ByteArray {
        val values = ArrayList<Int>(KEY_CHARS)
        for (c in code) {
            if (c == '-' || c.isWhitespace()) continue
            val u = if (c in 'a'..'z') c - 32 else c   // ASCII only, like Rust's to_ascii_uppercase
            val v = when (u) {
                'O' -> 0
                'I', 'L' -> 1
                else -> ALPHABET.indexOf(u).takeIf { it >= 0 } ?: fail(EventPackException.INVALID_SECRET_CODE, "invalid character")
            }
            values += v
            if (values.size > KEY_CHARS) fail(EventPackException.INVALID_SECRET_CODE, "too long")
        }
        if (values.size != KEY_CHARS) fail(EventPackException.INVALID_SECRET_CODE, "wrong length")
        val out = ByteArray(32)
        var acc = 0
        var bits = 0
        var i = 0
        for (v in values) {
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[i++] = (acc shr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        if (acc != 0) fail(EventPackException.INVALID_SECRET_CODE, "non-canonical padding")
        return out
    }
}

object RawInflate {
    /** Raw DEFLATE (RFC 1951) decode; output above [max] bytes is an error, never a truncation. */
    fun inflate(data: ByteArray, max: Int = EventLimits.MAX_INFLATED_BYTES): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(minOf(max, maxOf(1024, data.size * 4)))
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var stalls = 0
            while (!inf.finished()) {
                val n = try { inf.inflate(buf) } catch (_: DataFormatException) { fail(EventPackException.INFLATE) }
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary() || ++stalls > 4) fail(EventPackException.INFLATE)
                    continue
                }
                stalls = 0
                total += n
                if (total > max) fail(EventPackException.INFLATE_TOO_LARGE)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }
}

/** Nonce ‖ ciphertext (with its 16-byte tag), as carried in a link or file. */
class SealedPack(val nonce: ByteArray, val ciphertext: ByteArray)

/** A parsed `.manotu-event` file: [salt] is the suite-2 Argon2id salt, null for a suite-1 (raw key) file. */
class ParsedFile(val salt: ByteArray?, val sealed: SealedPack) {
    val suite: Byte get() = if (salt != null) EventTransport.FILE_SUITE_CODE else EventTransport.FILE_SUITE_RAW_KEY
}

object EventTransport {
    const val NONCE_LEN = 24
    const val TAG_LEN = 16
    val FILE_MAGIC = byteArrayOf('M'.code.toByte(), 'N'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())
    const val FILE_FORMAT_V1: Byte = 0x01
    /** Suite 1: the 52-character code is the raw key. Read only. */
    const val FILE_SUITE_RAW_KEY: Byte = 0x01
    /** Suite 2: the key is Argon2id over the 8-character code and the file's salt. Written. */
    const val FILE_SUITE_CODE: Byte = 0x02
    const val FILE_SALT_LEN = 16
    const val HEADER_LEN = 6
    const val FILE_MIME = "application/vnd.manotu.event"
    const val FILE_EXTENSION = "manotu-event"

    /**
     * A full link (any site, any scheme: https://…/a/#…, manotu://event#…) or a bare fragment.
     * The length is checked before anything is decoded (§4 limits).
     */
    fun parseLink(link: String): Pair<ByteArray, SealedPack> {
        val fragment = (link.indexOf('#').let { if (it >= 0) link.substring(it + 1) else link }).trim()
        if (fragment.codePointCount(0, fragment.length) > EventLimits.MAX_FRAGMENT_CHARS) fail(EventPackException.FRAGMENT_TOO_LONG)
        val parts = fragment.split('.')
        if (parts.size != 3) fail(EventPackException.MALFORMED_LINK)
        if (parts[0] != "1") fail(EventPackException.UNSUPPORTED_LINK_VERSION)
        val key = B64u.decodeExact(parts[1], 32) ?: fail(EventPackException.MALFORMED_LINK)
        val data = B64u.decode(parts[2]) ?: fail(EventPackException.MALFORMED_LINK)
        if (data.size < NONCE_LEN + TAG_LEN) fail(EventPackException.MALFORMED_LINK)
        return key to SealedPack(data.copyOfRange(0, NONCE_LEN), data.copyOfRange(NONCE_LEN, data.size))
    }

    /**
     * §5.2, dispatching on the suite byte:
     * suite 2 `"MNTE" ‖ 0x01 ‖ 0x02 ‖ salt(16) ‖ nonce(24) ‖ ciphertext`, suite 1 `"MNTE" ‖ 0x01 ‖ 0x01 ‖ nonce(24) ‖ ciphertext`.
     */
    fun parseFile(bytes: ByteArray): ParsedFile {
        if (bytes.size > EventLimits.MAX_FILE_BYTES) fail(EventPackException.FILE_TOO_LARGE)
        if (bytes.size < HEADER_LEN || !looksLikeFile(bytes)) fail(EventPackException.MALFORMED_FILE)
        if (bytes[4] != FILE_FORMAT_V1) fail(EventPackException.UNSUPPORTED_FILE_FORMAT)
        val (salt, start) = when (bytes[5]) {
            FILE_SUITE_RAW_KEY -> null to HEADER_LEN
            FILE_SUITE_CODE -> {
                if (bytes.size < HEADER_LEN + FILE_SALT_LEN) fail(EventPackException.MALFORMED_FILE)
                bytes.copyOfRange(HEADER_LEN, HEADER_LEN + FILE_SALT_LEN) to HEADER_LEN + FILE_SALT_LEN
            }
            else -> fail(EventPackException.UNSUPPORTED_SUITE)
        }
        if (bytes.size < start + NONCE_LEN + TAG_LEN) fail(EventPackException.MALFORMED_FILE)
        return ParsedFile(salt, SealedPack(bytes.copyOfRange(start, start + NONCE_LEN), bytes.copyOfRange(start + NONCE_LEN, bytes.size)))
    }

    fun looksLikeFile(bytes: ByteArray): Boolean = bytes.size >= 4 && (0 until 4).all { bytes[it] == FILE_MAGIC[it] }
}
