package my.gimano.app.core.events

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import my.gimano.app.core.identity.Ed25519
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.Deflater

/*
 * Host (organiser) side of docs/events.md §3–§5, mirroring desktop/crates/event-core (`model.rs` serde layout,
 * `pack.rs` seal_parts / seal, `link.rs`, `file.rs`, `revision.rs`). Nothing here logs, keeps or sends anything.
 */

/**
 * Event JSON v1 writer with the serde layout of event-core: exact field order, optional fields omitted when absent,
 * `parent` always written (`null` on rev 1), `stops` always written, `sessions`/`routes` omitted when empty.
 * Strings are escaped like serde_json (only `"`, `\` and control characters); numbers like ryu (`101.0`, `2.9871`).
 */
object EventJsonWriter {
    private val OFFSET_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")

    fun write(p: EventPlan): ByteArray {
        val o = Obj()
        o.str("id", p.id)
        o.raw("rev", p.rev.toString())
        o.raw("parent", p.parent?.let(::quote) ?: "null")
        o.str("publishedAt", utc(p.publishedAt))
        o.str("template", p.template.name.lowercase())
        o.str("title", p.title)
        p.description?.let { o.str("description", it) }
        o.str("lang", p.lang)
        o.str("tz", p.tz)
        o.str("start", offset(p.start))
        o.str("end", offset(p.end))
        o.str("expiresAt", utc(p.expiresAt))
        p.organizerAlias?.let { a -> o.raw("organizer", Obj().apply { str("alias", a) }.done()) }
        o.raw("stops", p.stops.joinToString(",", "[", "]") { stop(it) })
        if (p.sessions.isNotEmpty()) o.raw("sessions", p.sessions.joinToString(",", "[", "]") { session(it) })
        if (p.routes.isNotEmpty()) o.raw("routes", p.routes.joinToString(",", "[", "]") { route(it) })
        // Stops-only variant (§5.5): always the last field, like the reference writer.
        p.variant?.let { v -> o.raw("variant", Obj().apply { str("kind", v.kind); str("fullHash", v.fullHash) }.done()) }
        return o.done().toByteArray(Charsets.UTF_8)
    }

    private fun stop(s: EventStop): String = Obj().apply {
        str("id", s.id)
        str("kind", s.kind.name.lowercase())
        str("label", s.label)
        raw("lat", num(s.lat))
        raw("lng", num(s.lng))
        s.entrance?.let { e -> raw("entrance", Obj().apply { raw("lat", num(e.lat)); raw("lng", num(e.lng)) }.done()) }
        s.arrive?.let { a -> raw("arrive", Obj().apply { str("from", offset(a.from)); str("to", offset(a.to)) }.done()) }
        s.dwellMin?.let { raw("dwellMin", it.toString()) }
        s.notes?.let { str("notes", it) }
        s.audience?.let { str("audience", it) }
    }.done()

    private fun session(s: EventSession): String = Obj().apply {
        str("id", s.id)
        str("title", s.title)
        str("start", offset(s.start))
        str("end", offset(s.end))
        s.stopId?.let { str("stopId", it) }
    }.done()

    private fun route(r: EventRoute): String = Obj().apply {
        str("id", r.id)
        r.title?.let { str("title", it) }
        str("mode", r.mode.wire)
        r.day?.let { str("day", it) }
        raw("stopIds", r.stopIds.joinToString(",", "[", "]") { quote(it) })
        r.polyline?.let { str("polyline", it) }
        str("source", r.source.wire)
        raw("distanceM", r.distanceM.toString())
        r.durationS?.let { raw("durationS", it.toString()) }
        r.ascentM?.let { raw("ascentM", it.toString()) }
        r.descentM?.let { raw("descentM", it.toString()) }
        raw("legs", r.legs.joinToString(",", "[", "]") { l ->
            Obj().apply {
                str("fromStopId", l.fromStopId)
                str("toStopId", l.toStopId)
                raw("distanceM", l.distanceM.toString())
                l.durationS?.let { raw("durationS", it.toString()) }
                str("source", l.source.wire)
            }.done()
        })
    }.done()

    private class Obj {
        private val sb = StringBuilder("{")
        fun raw(k: String, v: String) { if (sb.length > 1) sb.append(','); sb.append(quote(k)).append(':').append(v) }
        fun str(k: String, v: String) = raw(k, quote(v))
        fun done(): String = sb.append('}').toString()
    }

    /** Local event times keep their offset (`+08:00`, never `Z`), whole seconds, like chrono `DateTime<FixedOffset>`. */
    fun offset(t: OffsetDateTime): String = t.truncatedTo(ChronoUnit.SECONDS).format(OFFSET_TIME)

    /** `publishedAt` / `expiresAt`: UTC with `Z`, whole seconds. */
    fun utc(i: Instant): String = DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(ChronoUnit.SECONDS))

    /** serde_json string escaping. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch < ' ' -> sb.append("\\u00").append("%02x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /** Shortest plain decimal with at least one fraction digit (`102.0`, `2.98805`), as ryu prints ≤ 6-decimal values. */
    fun num(v: Double): String {
        require(v.isFinite()) { "not a finite number" }
        val s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
        return if (s.contains('.')) s else "$s.0"
    }

    /** Round half away from zero to 6 decimals (route-core `round6`). */
    fun round6(v: Double): Double = Math.signum(v) * Math.floor(Math.abs(v) * 1e6 + 0.5) / 1e6
}

/** Random ids (§4): event id = b64u(16 bytes), stop / session / route id = b64u(8 bytes). */
object EventIds {
    private val rng = SecureRandom()
    fun newEventId(): String = B64u.encode(ByteArray(EventLimits.EVENT_ID_BYTES).also(rng::nextBytes))
    fun newItemId(): String = B64u.encode(ByteArray(EventLimits.ITEM_ID_BYTES).also(rng::nextBytes))
}

/** Every intermediate layer of one seal (tests and diagnostics). */
class SealedParts(
    val eventBytes: ByteArray,
    val signature: ByteArray,
    val envelopeBytes: ByteArray,
    val deflated: ByteArray,
    val sealed: SealedPack,
)

object RawDeflate {
    /** Raw DEFLATE (RFC 1951, no zlib header), level 9 like the desktop core. */
    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9, true)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }
}

/**
 * Seals event packs (docs/events.md §3): Ed25519 over `"manotu-event-v1\n" ‖ eventBytes` → envelope JSON →
 * raw DEFLATE → XChaCha20-Poly1305-IETF (AD `manotu-event-v1`). Same lazysodium the reader uses; no new SDK.
 */
class EventPackWriter(private val sodium: LazySodium) {
    private val ed = Ed25519(sodium)
    private val reader = EventPackReader(sodium)

    companion object {
        const val KEY_BYTES = 32
        const val SEED_BYTES = 32

        /** `{"v":1,"event":b64u,"pub":b64u(32),"sig":b64u(64)}` (serde field order of `Envelope`). */
        fun envelopeJson(eventBytes: ByteArray, organizerPub: ByteArray, signature: ByteArray): ByteArray =
            """{"v":1,"event":"${B64u.encode(eventBytes)}","pub":"${B64u.encode(organizerPub)}","sig":"${B64u.encode(signature)}"}"""
                .toByteArray(Charsets.US_ASCII)
    }

    /** A new organizer seed: one per event, never reused for another event. */
    fun newSeed(): ByteArray = sodium.randomBytesBuf(SEED_BYTES)

    fun newPackKey(): ByteArray = sodium.randomBytesBuf(KEY_BYTES)

    fun newNonce(): ByteArray = sodium.randomBytesBuf(EventTransport.NONCE_LEN)

    fun organizerPub(seed: ByteArray): ByteArray {
        val kp = ed.keyPair(seed)
        kp.secretKey.fill(0)
        return kp.publicKey
    }

    fun sign(seed: ByteArray, eventBytes: ByteArray): ByteArray {
        val kp = ed.keyPair(seed)
        try {
            return ed.sign(kp, EventPackReader.SIGNATURE_PREFIX + eventBytes)
        } finally {
            kp.secretKey.fill(0)
        }
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES && nonce.size == AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val c = ByteArray(plain.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val ad = EventPackReader.AD
        check(sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(c, LongArray(1), plain, plain.size.toLong(), ad, ad.size.toLong(), null, nonce, key))
        return c
    }

    /** Signs, compresses and encrypts already-serialized event bytes with an explicit nonce (must form a valid event). */
    fun sealParts(eventBytes: ByteArray, seed: ByteArray, key: ByteArray, nonce: ByteArray): SealedParts {
        EventParser.parse(eventBytes)
        val kp = ed.keyPair(seed)
        val sig = try { ed.sign(kp, EventPackReader.SIGNATURE_PREFIX + eventBytes) } finally { kp.secretKey.fill(0) }
        val envelope = envelopeJson(eventBytes, kp.publicKey, sig)
        val deflated = RawDeflate.deflate(envelope)
        val ct = encrypt(key, nonce, deflated)
        return SealedParts(eventBytes, sig, envelope, deflated, SealedPack(nonce.copyOf(), ct))
    }

    /**
     * Seals with a fresh random nonce, then opens the result again to prove it verifies (and gives back the same
     * bytes) before anything is offered to the user.
     */
    fun seal(eventBytes: ByteArray, seed: ByteArray, key: ByteArray): SealedPack {
        val sealed = sealParts(eventBytes, seed, key, newNonce()).sealed
        val check = reader.open(key, sealed)
        check(check.eventBytes.contentEquals(eventBytes)) { "sealed pack does not round-trip" }
        return sealed
    }
}

/** §5.5: the stops-only variant of a published revision. */
object EventVariants {
    /**
     * The stops-only variant of signed full event bytes: the same event (same id, rev, parent, publishedAt) with every
     * route's `polyline` removed and `variant: {kind: "stops-only", fullHash: SHA-256(full bytes)}` as the last field.
     * Deterministic for given full bytes; sign it with the same organiser key. Its revision hash is `fullHash`.
     */
    fun stopsOnly(fullEventBytes: ByteArray): ByteArray {
        val full = EventParser.parse(fullEventBytes)
        if (full.variant != null) fail(EventPackException.INVALID_EVENT, "not a full event")
        val v = full.copy(
            routes = full.routes.map { it.copy(polyline = null, points = emptyList()) },
            variant = EventVariant.stopsOnly(EventHashes.sha256(fullEventBytes)),
        )
        val bytes = EventJsonWriter.write(v)
        EventParser.parse(bytes)
        return bytes
    }
}

/** How the share screen offers an invitation (§5.1, §5.5). */
enum class ShareMode {
    /** The full link fits: link (recommended) and file. */
    LINK,
    /** The full link is too long but the stops-only link fits: file, or "Pautan tanpa laluan" after a confirm. */
    STOPS_ONLY_OR_FILE,
    /** Even the stops-only link is too long (or there are no routes to drop): file only. */
    FILE_ONLY;

    companion object {
        /** [stopsOnlyFragment] null = no variant was made (the event has no routes). */
        fun choose(fullFragment: Int, stopsOnlyFragment: Int?): ShareMode = when {
            fullFragment <= EventLimits.MAX_FRAGMENT_CHARS -> LINK
            stopsOnlyFragment != null && stopsOnlyFragment <= EventLimits.MAX_FRAGMENT_CHARS -> STOPS_ONLY_OR_FILE
            else -> FILE_ONLY
        }
    }
}

/**
 * One sealed invitation: always the full event (link when it fits, and the file), plus the stops-only variant sealed
 * with the same organiser key and pack key when the full link is too long (§5.5).
 */
class SealedInvitation(val full: SealedPack, val fullFragmentLength: Int, val stopsOnly: SealedPack?, val stopsOnlyFragmentLength: Int?) {
    val mode: ShareMode get() = ShareMode.choose(fullFragmentLength, stopsOnlyFragmentLength)

    companion object {
        /** Seals [fullEventBytes] and, only when its link is too long and the event has routes, the stops-only variant. */
        fun seal(writer: EventPackWriter, fullEventBytes: ByteArray, seed: ByteArray, key: ByteArray): SealedInvitation {
            val full = writer.seal(fullEventBytes, seed, key)
            val fullLen = EventLinks.fragmentUnchecked(key, full).length
            if (fullLen <= EventLimits.MAX_FRAGMENT_CHARS || EventParser.parse(fullEventBytes).routes.isEmpty()) {
                return SealedInvitation(full, fullLen, null, null)
            }
            val variant = writer.seal(EventVariants.stopsOnly(fullEventBytes), seed, key)
            return SealedInvitation(full, fullLen, variant, EventLinks.fragmentUnchecked(key, variant).length)
        }
    }
}

/** Writer side of §5.1 / §5.2 framing. */
object EventLinks {
    /** Where the static viewer lives (`site/manotu/a/`): the same base as share-my-drive's `/s/` viewer. */
    const val DEFAULT_SITE = "https://manotu.app"

    /** `1.<b64u(key)>.<b64u(nonce ‖ ciphertext)>`, whatever its length. */
    fun fragmentUnchecked(key: ByteArray, sealed: SealedPack): String =
        "1.${B64u.encode(key)}.${B64u.encode(sealed.nonce + sealed.ciphertext)}"

    /** The fragment (no `#`); fails with `fragment_too_long` above 16 000 characters (offer the file instead). */
    fun fragment(key: ByteArray, sealed: SealedPack): String {
        val f = fragmentUnchecked(key, sealed)
        if (f.length > EventLimits.MAX_FRAGMENT_CHARS) fail(EventPackException.FRAGMENT_TOO_LONG)
        return f
    }

    fun link(site: String, key: ByteArray, sealed: SealedPack): String = "${site.trimEnd('/')}/a/#${fragment(key, sealed)}"

    /** `"MNTE" ‖ 0x01 ‖ 0x01 ‖ nonce(24) ‖ ciphertext`. */
    fun file(sealed: SealedPack): ByteArray {
        val size = 6 + sealed.nonce.size + sealed.ciphertext.size
        if (size > EventLimits.MAX_FILE_BYTES) fail(EventPackException.FILE_TOO_LARGE)
        return EventTransport.FILE_MAGIC + byteArrayOf(EventTransport.FILE_FORMAT_V1, EventTransport.FILE_SUITE_RAW_KEY) + sealed.nonce + sealed.ciphertext
    }
}
