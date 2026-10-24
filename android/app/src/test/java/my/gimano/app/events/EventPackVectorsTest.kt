package my.gimano.app.events

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.core.events.*
import my.gimano.app.core.identity.Ed25519
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The shared event-pack vectors (desktop/crates/event-core/tests/vectors, copied to
 * src/test/resources/event-vectors; [VectorCopyInSyncTest] fails when the copy drifts).
 */
class EventPackVectorsTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val reader = EventPackReader(sodium)

    private fun vector(name: String): JsonObject {
        val text = javaClass.classLoader!!.getResourceAsStream("event-vectors/$name.json")!!.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.b(k: String) = B64u.decode(s(k)) ?: error("$k is not strict b64u")

    private fun assertCode(code: String, block: () -> Unit) {
        try {
            block(); fail("expected $code")
        } catch (e: EventPackException) {
            assertEquals(code, e.code)
        }
    }

    private fun checkOpened(v: JsonObject, p: OpenedPack) {
        assertEquals(v.s("eventJson"), p.eventBytes.toString(Charsets.UTF_8))
        assertArrayEquals(v.b("eventB64u"), p.eventBytes)
        assertEquals(v.s("organizerPubB64u"), B64u.encode(p.organizerPub))
        assertEquals(v.s("safetyCode"), p.safetyCode)
        assertEquals(v.s("revisionHashB64u"), p.revisionHashB64u)
        val exp = v.getValue("expected").jsonObject
        assertEquals(exp.s("id"), p.event.id)
        assertEquals(exp.getValue("rev").jsonPrimitive.int.toLong(), p.event.rev)
        assertEquals(exp.s("title"), p.event.title)
        assertEquals(exp.getValue("stops").jsonPrimitive.int, p.event.stops.size)
        assertEquals(exp.getValue("sessions").jsonPrimitive.int, p.event.sessions.size)
        assertEquals(exp.getValue("routes").jsonPrimitive.int, p.event.routes.size)
        assertEquals(exp.getValue("polylinePoints").jsonPrimitive.int, p.event.polylinePoints)
        val d = EventImport.decide(ImportCandidate.of(p), null, Instant.parse(v.s("now")))
        assertEquals(exp.s("decision"), d.wire)
        assertEquals(ImportDecision.New(v.s("safetyCode")), d)
    }

    private fun positive(name: String) {
        val v = vector(name)
        // Reader: every transport opens to the same signed bytes.
        checkOpened(v, reader.openLink(v.s("linkFragment")))
        checkOpened(v, reader.openLink(v.s("link")))
        checkOpened(v, reader.openFile(v.b("fileB64u"), v.s("secretCode")))
        checkOpened(v, reader.openFile(v.b("fileB64u"), v.s("secretCode").lowercase().replace('-', ' ')))
        // Every layer as committed.
        val key = v.b("keyB64u")
        val nonce = v.b("nonceB64u")
        assertArrayEquals(v.b("deflatedB64u"), reader.decrypt(key, nonce, v.b("ciphertextB64u")))
        assertEquals(v.s("envelopeJson"), RawInflate.inflate(v.b("deflatedB64u")).toString(Charsets.UTF_8))
        assertArrayEquals(v.b("envelopeB64u"), v.s("envelopeJson").toByteArray())
        // Deterministic writer layers reproduce byte for byte.
        assertEquals(v.s("secretCode"), SecretCode.encode(key))
        assertArrayEquals(key, SecretCode.decode(v.s("secretCode")))
        val ed = Ed25519(sodium)
        val kp = ed.keyPair(v.b("ed25519SeedB64u"))
        assertEquals(v.s("organizerPubB64u"), B64u.encode(kp.publicKey))
        val sig = ed.sign(kp, EventPackReader.SIGNATURE_PREFIX + v.b("eventB64u"))
        assertEquals(v.s("signatureB64u"), B64u.encode(sig))
        val m = v.b("deflatedB64u")
        val c = ByteArray(m.size + 16)
        assertTrue(sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(c, LongArray(1), m, m.size.toLong(), EventPackReader.AD, EventPackReader.AD.size.toLong(), null, nonce, key))
        assertEquals(v.s("ciphertextB64u"), B64u.encode(c))
        val file = EventTransport.FILE_MAGIC + byteArrayOf(1, 1) + nonce + c
        assertEquals(v.s("fileB64u"), B64u.encode(file))
        assertEquals(v.s("linkFragment"), "1.${B64u.encode(key)}.${B64u.encode(nonce + c)}")
        assertEquals(v.s("link"), "${v.s("site")}/a/#${v.s("linkFragment")}")
    }

    @Test fun kenduriOneDay() {
        positive("kenduri-one-day")
        val p = reader.openLink(vector("kenduri-one-day").s("linkFragment"))
        val parking = p.event.stops.first()
        assertEquals(StopKind.PARKING, parking.kind)
        assertEquals("navigate to the vehicle entrance when present", 2.9866, parking.navigationTarget.lat, 0.0)
        assertEquals(101.7942, parking.navigationTarget.lng, 0.0)
        assertEquals(EventTemplate.KENDURI, p.event.template)
        assertEquals("Keluarga Aqil", p.event.organizerAlias)
    }

    @Test fun rideTwoDay() {
        positive("ride-two-day")
        val r = reader.openLink(vector("ride-two-day").s("linkFragment")).event.routes.single()
        assertEquals(EventTravelMode.BICYCLE, r.mode)
        assertEquals(7, r.points.size)
        assertEquals(2, r.legs.size)
        assertEquals(420L, r.ascentM)
        assertEquals(310L, r.descentM)
        assertTrue(r.source.navigable)
    }

    @Test fun tamperedCiphertextFailsDecrypt() {
        assertCode(EventPackException.DECRYPT) { reader.openLink(vector("negative-tampered-ciphertext").s("linkFragment")) }
    }

    @Test fun badSignatureFailsAfterDecryptAndInflate() {
        val v = vector("negative-bad-signature")
        assertEquals(v.s("envelopeJson"), RawInflate.inflate(v.b("deflatedB64u")).toString(Charsets.UTF_8))
        assertCode(EventPackException.SIGNATURE) { reader.openLink(v.s("linkFragment")) }
    }

    @Test fun wrongKeyFailsDecryptForLinkAndFile() {
        val v = vector("negative-wrong-key")
        assertCode(EventPackException.DECRYPT) { reader.openLink(v.s("linkFragment")) }
        assertCode(EventPackException.DECRYPT) { reader.openFile(v.b("fileB64u"), v.s("secretCode")) }
    }

    @Test fun expiredOpensButIsNotImported() {
        val v = vector("negative-expired")
        val p = reader.openLink(v.s("linkFragment"))
        assertEquals(v.s("expectedError"), EventImport.decide(ImportCandidate.of(p), null, Instant.parse(v.s("now"))).wire)
        assertTrue(p.event.isExpired(Instant.parse(v.s("now"))))
    }

    @Test fun oversizedFragmentRejectedBeforeDecodingButFileOpens() {
        val v = vector("negative-oversized-fragment")
        assertEquals(v.getValue("fragmentLength").jsonPrimitive.int, v.s("linkFragment").length)
        assertCode(EventPackException.FRAGMENT_TOO_LONG) { EventTransport.parseLink(v.s("linkFragment")) }
        assertCode(EventPackException.FRAGMENT_TOO_LONG) { reader.openLink("https://www.bukitbunga.com/gimano/a/#" + v.s("linkFragment")) }
        // Even a garbage fragment that long is rejected for its length, not its content.
        assertCode(EventPackException.FRAGMENT_TOO_LONG) { EventTransport.parseLink("#" + "!".repeat(16_001)) }
        val p = reader.openFile(v.b("fileB64u"), v.s("secretCode"))
        assertEquals(v.s("fileEventId"), p.event.id)
        assertEquals(ImportDecision.New(p.safetyCode), EventImport.decide(ImportCandidate.of(p), null, Instant.parse(v.s("now"))))
    }

    @Test fun everyVectorFileIsCovered() {
        val names = listOf("kenduri-one-day", "ride-two-day", "negative-tampered-ciphertext", "negative-bad-signature", "negative-wrong-key",
            "negative-expired", "negative-oversized-fragment")
        val dir = javaClass.classLoader!!.getResource("event-vectors")!!.let { File(it.toURI()) }
        assertEquals(names.sorted(), dir.listFiles()!!.filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted())
    }

    @Test fun linkAndFileFramingErrors() {
        assertCode(EventPackException.MALFORMED_LINK) { EventTransport.parseLink("https://x/a/#1.abc") }
        assertCode(EventPackException.UNSUPPORTED_LINK_VERSION) { EventTransport.parseLink("2.a.b") }
        assertCode(EventPackException.MALFORMED_LINK) { EventTransport.parseLink("1.${B64u.encode(ByteArray(31))}.${B64u.encode(ByteArray(40))}") }
        assertCode(EventPackException.MALFORMED_LINK) { EventTransport.parseLink("1.${B64u.encode(ByteArray(32))}.${B64u.encode(ByteArray(39))}") }
        assertCode(EventPackException.MALFORMED_LINK) { EventTransport.parseLink("1.${B64u.encode(ByteArray(32))}=.${B64u.encode(ByteArray(40))}") }
        val ok = EventTransport.FILE_MAGIC + byteArrayOf(1, 1) + ByteArray(40)
        EventTransport.parseFile(ok)
        assertCode(EventPackException.MALFORMED_FILE) { EventTransport.parseFile(ok.copyOf(45)) }
        assertCode(EventPackException.MALFORMED_FILE) { EventTransport.parseFile(byteArrayOf(0) + ok.copyOfRange(1, ok.size)) }
        assertCode(EventPackException.UNSUPPORTED_FILE_FORMAT) { EventTransport.parseFile(ok.copyOf().also { it[4] = 2 }) }
        assertCode(EventPackException.UNSUPPORTED_SUITE) { EventTransport.parseFile(ok.copyOf().also { it[5] = 2 }) }
        assertCode(EventPackException.FILE_TOO_LARGE) { EventTransport.parseFile(ByteArray(EventLimits.MAX_FILE_BYTES + 1)) }
    }
}

/** The test copy must match the vectors the Rust core generates (when the desktop tree is checked out). */
class VectorCopyInSyncTest {
    @Test fun copyMatchesDesktopVectors() {
        val src = listOf("../../desktop/crates/event-core/tests/vectors", "../desktop/crates/event-core/tests/vectors").map(::File).firstOrNull { it.isDirectory }
            ?: return   // Android-only checkout: nothing to compare with
        val copy = listOf("src/test/resources/event-vectors", "app/src/test/resources/event-vectors").map(::File).first { it.isDirectory }
        val want = src.listFiles()!!.filter { it.extension == "json" }.associate { it.name to it.readBytes() }
        val have = copy.listFiles()!!.filter { it.extension == "json" }.associate { it.name to it.readBytes() }
        assertEquals("copy src/test/resources/event-vectors from ${src.path}", want.keys, have.keys)
        want.forEach { (n, b) -> assertArrayEquals("$n differs from ${src.path}: copy it again", b, have.getValue(n)) }
    }
}
