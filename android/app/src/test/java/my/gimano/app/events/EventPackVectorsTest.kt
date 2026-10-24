package my.gimano.app.events

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
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

    internal fun positive(name: String) {
        val v = vector(name)
        // Reader: every transport opens to the same signed bytes.
        checkOpened(v, reader.openLink(v.s("linkFragment")))
        checkOpened(v, reader.openLink(v.s("link")))
        checkOpened(v, reader.openFile(v.b("fileB64u"), v.s("secretCode")))
        checkOpened(v, reader.openFile(v.b("fileB64u"), v.s("secretCode").lowercase().replace('-', ' ')))
        if ("fileV2B64u" in v) suite2(v)
        layers(v)
    }

    /** Suite 2 (what writers produce now): Argon2id over the 8-character code and the file salt. */
    private fun suite2(v: JsonObject) {
        val v2 = v.b("fileV2B64u")
        assertEquals(EventTransport.FILE_SUITE_CODE, v2[5])
        checkOpened(v, reader.openFile(v2, v.s("secretCodeV2")))
        checkOpened(v, reader.openFile(v2, " " + v.s("secretCodeV2").lowercase().replace("-", " - ") + " "))
        val parsed = EventTransport.parseFile(v2)
        assertArrayEquals(v.b("fileSaltB64u"), parsed.salt)
        assertArrayEquals(v.b("fileNonceV2B64u"), parsed.sealed.nonce)
        assertArrayEquals(v.b("fileKeyV2B64u"), reader.fileKey(parsed, v.s("secretCodeV2")))
        assertArrayEquals(v.b("deflatedB64u"), reader.decrypt(v.b("fileKeyV2B64u"), parsed.sealed.nonce, parsed.sealed.ciphertext))
        // Each suite takes only its own code shape: invalid_secret_code before any derivation.
        assertCode(EventPackException.INVALID_SECRET_CODE) { reader.openFile(v2, v.s("secretCode")) }
        assertCode(EventPackException.INVALID_SECRET_CODE) { reader.openFile(v.b("fileB64u"), v.s("secretCodeV2")) }
    }

    private fun layers(v: JsonObject) {
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
        assertTrue("suite-2 fields", "fileV2B64u" in vector("kenduri-one-day")); positive("kenduri-one-day")
        val p = reader.openLink(vector("kenduri-one-day").s("linkFragment"))
        val parking = p.event.stops.first()
        assertEquals(StopKind.PARKING, parking.kind)
        assertEquals("navigate to the vehicle entrance when present", 2.9866, parking.navigationTarget.lat, 0.0)
        assertEquals(101.7942, parking.navigationTarget.lng, 0.0)
        assertEquals(EventTemplate.KENDURI, p.event.template)
        assertEquals("Keluarga Aqil", p.event.organizerAlias)
    }

    @Test fun rideTwoDay() {
        assertTrue("suite-2 fields", "fileV2B64u" in vector("ride-two-day")); positive("ride-two-day")
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

    /** Counts Argon2id runs, to prove invalid codes are rejected before deriving. */
    private class CountingSodium : LazySodiumJava(SodiumJava()) {
        var pwhash = 0
        override fun cryptoPwHash(outputHash: ByteArray?, outputHashLen: Int, password: ByteArray?, passwordLen: Int, salt: ByteArray?,
            opsLimit: Long, memLimit: com.sun.jna.NativeLong?, alg: com.goterl.lazysodium.interfaces.PwHash.Alg?): Boolean {
            pwhash++
            return super.cryptoPwHash(outputHash, outputHashLen, password, passwordLen, salt, opsLimit, memLimit, alg)
        }
    }

    @Test fun argon2KnownAnswers() {
        val v = vector("argon2-kat")
        val params = v.getValue("params").jsonObject
        assertEquals("argon2id13", params.s("alg"))
        assertEquals(EventPackReader.FILE_KDF_OPSLIMIT, params.getValue("opslimit").jsonPrimitive.long)
        assertEquals(EventPackReader.FILE_KDF_MEMLIMIT, params.getValue("memlimitBytes").jsonPrimitive.long)
        assertEquals(EventPackReader.FILE_KEY_BYTES, params.getValue("outlen").jsonPrimitive.int)
        val kats = v.getValue("argon2Kat").jsonArray.map { it.jsonObject }
        assertTrue(kats.size >= 5)
        val times = ArrayList<Long>()
        for (k in kats) {
            assertEquals(k.s("password"), String(SecretCodeV2.parse(k.s("code")), Charsets.US_ASCII))
            assertEquals(k.s("saltHex"), k.b("saltB64u").joinToString("") { "%02x".format(it) })
            val t0 = System.nanoTime()
            val key = reader.deriveFileKey(k.s("code"), k.b("saltB64u"))
            times += (System.nanoTime() - t0) / 1_000_000
            assertEquals(k.s("code"), k.s("keyB64u"), B64u.encode(key))
            assertEquals(k.s("keyHex"), key.joinToString("") { "%02x".format(it) })
        }
        println("Argon2id (opslimit 3, 64 MiB) per derivation on this JVM: ${times.joinToString()} ms")
    }

    @Test fun wrongSecretCodeDerivesThenFailsDecrypt() {
        val v = vector("negative-wrong-secret-code")
        assertEquals(EventPackException.DECRYPT, v.s("expectedError"))
        val counting = CountingSodium()
        assertCode(EventPackException.DECRYPT) { EventPackReader(counting).openFile(v.b("fileB64u"), v.s("secretCode")) }
        assertEquals(1, counting.pwhash)
    }

    @Test fun invalidSecretCodesFailBeforeArgon2() {
        val v = vector("negative-invalid-secret-code")
        assertEquals(EventPackException.INVALID_SECRET_CODE, v.s("expectedError"))
        val counting = CountingSodium()
        val r = EventPackReader(counting)
        val codes = v.getValue("secretCodes").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(codes.isNotEmpty())
        val file = v.b("fileB64u")
        for (c in codes) assertCode(EventPackException.INVALID_SECRET_CODE) { r.openFile(file, c) }
        // Wrong lengths, other characters, and the other suite's 52-character code.
        val kenduri = vector("kenduri-one-day")
        for (c in listOf("", "6BJT-PBD", "6BJT-PBDRR", "6BJT_PBDR", "6BJT-PBDÉ", kenduri.s("secretCode"))) {
            assertCode(EventPackException.INVALID_SECRET_CODE) { r.openFile(file, c) }
        }
        assertEquals("no Argon2id run for an invalid code", 0, counting.pwhash)
    }

    @Test fun secretCodeV2Format() {
        assertEquals("2222-2222", SecretCodeV2.encode(ByteArray(5)))
        assertEquals("ZZZZ-ZZZZ", SecretCodeV2.encode(ByteArray(5) { -1 }))
        assertEquals("3456-789A", SecretCodeV2.encode(byteArrayOf(0x08, 0x86.toByte(), 0x42, 0x98.toByte(), 0xe8.toByte())))
        repeat(64) {
            val c = SecretCodeV2.generate()
            assertTrue(c, Regex("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}").matches(c))
            assertEquals(c.replace("-", ""), String(SecretCodeV2.parse(c), Charsets.US_ASCII))
        }
        assertEquals("K7M4QX9P", String(SecretCodeV2.parse("k7m4-qx9p"), Charsets.US_ASCII))
        assertEquals("K7M4QX9P", String(SecretCodeV2.parse(" K7M4 QX9P "), Charsets.US_ASCII))
        assertEquals("K7M4QX9P", String(SecretCodeV2.parse("K-7-M-4QX9P"), Charsets.US_ASCII))
        assertEquals("K7M4-QX9P", SecretCodeV2.format("k7m4qx9p"))
        for (bad in listOf("K7M4-QX9O", "K7M4-QX90", "K7M4-QX9I", "K7M4-QX91", "K7M4_QX9P", "K7M4-QX9É", "K7M4-QX9", "K7M4-QX9PP")) {
            assertCode(EventPackException.INVALID_SECRET_CODE) { SecretCodeV2.parse(bad) }
        }
    }

    @Test fun everyVectorFileIsCovered() {
        val names = listOf("kenduri-one-day", "ride-two-day", "negative-tampered-ciphertext", "negative-bad-signature", "negative-wrong-key",
            "negative-expired", "negative-oversized-fragment", "ride-two-day-stops-only", "negative-variant-with-polyline",
            "negative-missing-polyline", "negative-unknown-variant", "import-stops-only-sequence",
            "argon2-kat", "negative-wrong-secret-code", "negative-invalid-secret-code")
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
        assertCode(EventPackException.UNSUPPORTED_SUITE) { EventTransport.parseFile(ok.copyOf().also { it[5] = 3 }) }
        assertCode(EventPackException.UNSUPPORTED_SUITE) { EventTransport.parseFile(ok.copyOf().also { it[5] = 0 }) }
        // Suite 2: salt(16) before the nonce; too short for salt + nonce + tag is malformed.
        val ok2 = EventTransport.FILE_MAGIC + byteArrayOf(1, 2) + ByteArray(16) { 7 } + ByteArray(40)
        val p2 = EventTransport.parseFile(ok2)
        assertArrayEquals(ByteArray(16) { 7 }, p2.salt)
        assertEquals(16, p2.sealed.ciphertext.size)
        assertNull(EventTransport.parseFile(ok).salt)
        assertCode(EventPackException.MALFORMED_FILE) { EventTransport.parseFile(ok2.copyOf(ok2.size - 1)) }
        assertCode(EventPackException.MALFORMED_FILE) { EventTransport.parseFile(ok2.copyOf(6 + 10)) }
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
