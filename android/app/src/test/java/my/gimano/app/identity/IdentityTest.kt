package my.gimano.app.identity

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.identity.Bip39
import my.gimano.app.core.identity.Ed25519
import my.gimano.app.core.identity.RequestSigning
import my.gimano.app.core.vetting.Geohash
import my.gimano.app.core.vetting.Proximity
import my.gimano.app.core.vetting.VerifyPromptGate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

/** Shared test vectors: docs/identity-and-reputation.md "Test vectors" (cross-checked with OpenSSL). */
class IdentityTest {
    private val ed = Ed25519(LazySodiumJava(SodiumJava()))
    private val seed = ByteArray(32) { it.toByte() }   // 00 01 02 … 1f
    private val bip = Bip39(File("src/main/assets/bip39-english.txt").readLines().filter { it.isNotBlank() })
    private fun b64(s: String) = Base64.getDecoder().decode(s)

    @Test fun seedGivesTheDocumentedPublicKey() {
        assertEquals("A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=", RequestSigning.b64(ed.keyPair(seed).publicKey))
        val other = ed.newSeed()
        assertEquals(32, other.size)
        assertFalse(other.contentEquals(ed.newSeed()))
        assertArrayEquals("deterministic from the seed", ed.keyPair(other).publicKey, ed.keyPair(other.copyOf()).publicKey)
    }

    @Test fun signedRequestVectors() {
        val kp = ed.keyPair(seed)
        val body = """{"answer":"yes","nameCorrect":true,"locationCorrect":null,"proximity":{"cell":"w283ef","withinM":1000}}"""
        assertEquals("22f8000731d5bee7a1373bc4870155e88e7dcbac1c86492e60af1f687aa3f811", RequestSigning.sha256Hex(body.toByteArray()))
        val c1 = RequestSigning.canonical("post", "/api/v1/places/0b9f3c2e-5d41-4a7e-9a8c-2f1e6d3b7a10/verify", 1761187200, body.toByteArray())
        assertEquals(
            "GIMANO1\nPOST\n/api/v1/places/0b9f3c2e-5d41-4a7e-9a8c-2f1e6d3b7a10/verify\n1761187200\n22f8000731d5bee7a1373bc4870155e88e7dcbac1c86492e60af1f687aa3f811", c1,
        )
        val sig1 = "SM344tg3ihSuOjuD04CgBIgHDy6Ov4zJMkh8gm6PPAtb8BixNMX8wAZ4xhOX160PfhZ3OFfi/u6wRlfPtbzKAA=="
        assertEquals(sig1, RequestSigning.b64(ed.sign(kp, c1.toByteArray())))
        assertTrue(ed.verify(kp.publicKey, c1.toByteArray(), b64(sig1)))
        assertFalse("tampered body", ed.verify(kp.publicKey, c1.replace("POST", "PUT").toByteArray(), b64(sig1)))

        val c2 = RequestSigning.canonical("DELETE", "/api/v1/reviews/r-42", 1761187260, ByteArray(0))
        assertTrue(c2.endsWith("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))   // sha256("")
        assertEquals("YdhF8gQ8AXwchSHCTUXvV3uabu1nCcN4cpQ4C7vUHkbpi9APw7fTqF5xD0jzJC5miQEkPjgdXmJEi8/oEah3AQ==", RequestSigning.b64(ed.sign(kp, c2.toByteArray())))

        val auth = RequestSigning.authMessage("5f2b8c1d-3e4a-4b6c-8d9e-0a1b2c3d4e5f", 1761187200)
        assertEquals("0mFmaaBe+eGTQ53R0rRQSRa5hwlkylIf5Tz0Q4q0A3AYgscqETnxNafYP2pbwS9mgCIMyRGTg2lKsclmLlL7Aw==", RequestSigning.b64(ed.sign(kp, auth.toByteArray())))
    }

    @Test fun onlyTheListedEndpointsAreSigned() {
        listOf(
            "POST" to "/api/v1/places", "PATCH" to "/api/v1/places/p1", "POST" to "/api/v1/places/p1/verify",
            "POST" to "/api/v1/places/p1/suggest-edit", "POST" to "/api/v1/places/p1/reviews", "PATCH" to "/api/v1/reviews/r1",
            "DELETE" to "/api/v1/reviews/r1", "POST" to "/api/v1/photos", "POST" to "/api/v1/reports", "POST" to "/api/v1/reports/x/votes",
            "POST" to "/api/v1/issues", "POST" to "/api/v1/issues/x/votes",
        ).forEach { (m, p) -> assertTrue("$m $p", RequestSigning.isSigned(m, p)) }
        listOf(
            "GET" to "/api/v1/places/p1", "DELETE" to "/api/v1/places/p1", "POST" to "/api/v1/routes", "POST" to "/api/v1/reports/x/thanks",
            "POST" to "/api/v1/feedback", "POST" to "/api/v1/locations", "GET" to "/api/v1/places/pending-nearby", "POST" to "/api/v1/auth/register",
            "GET" to "/api/v1/issues/nearby", "GET" to "/api/v1/issues/x",
        ).forEach { (m, p) -> assertFalse("$m $p", RequestSigning.isSigned(m, p)) }
    }

    @Test fun bip39OfficialVectors() {
        assertEquals(List(23) { "abandon" } + "art", bip.encode(ByteArray(32)))
        assertEquals((List(7) { listOf("legal", "winner", "thank", "year", "wave", "sausage", "worth", "useful") }.flatten().take(23) + "title"),
            bip.encode(ByteArray(32) { 0x7f }))
        assertEquals("letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless",
            bip.encode(ByteArray(32) { 0x80.toByte() }).joinToString(" "))
        assertEquals(List(23) { "zoo" } + "vote", bip.encode(ByteArray(32) { 0xff.toByte() }))
        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", bip.encode(ByteArray(16)).joinToString(" "))
    }

    @Test fun bip39RoundTripAndErrors() {
        val words = bip.encode(seed)
        assertEquals("abandon amount liar amount expire adjust cage candy arch gather drum bullet absurd math era live bid rhythm alien crouch range attend journey unaware", words.joinToString(" "))
        assertArrayEquals(seed, (bip.decode(words.joinToString(" ")) as Bip39.Result.Ok).entropy)
        assertArrayEquals("case, spacing and 4-letter prefixes", seed, (bip.decode("  " + words.joinToString("\n") { it.take(4).uppercase() } + " ") as Bip39.Result.Ok).entropy)
        repeat(50) {
            val s = ed.newSeed()
            assertArrayEquals(s, (bip.decode(bip.encode(s).joinToString(" ")) as Bip39.Result.Ok).entropy)
        }
        assertEquals(Bip39.Result.WrongLength, bip.decode(words.dropLast(1).joinToString(" ")))
        assertEquals(Bip39.Result.UnknownWord(3, "durian"), bip.decode(words.toMutableList().also { it[2] = "durian" }.joinToString(" ")))
        assertEquals(Bip39.Result.BadChecksum, bip.decode(words.toMutableList().also { it[0] = "zoo" }.joinToString(" ")))
    }

    @Test fun geohash() {
        assertEquals("u4pruy", Geohash.encode(57.64911, 10.40744))   // the Wikipedia example (u4pruydqqvj)
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
        assertEquals("w283", Geohash.encode(3.139, 101.687, 4))         // Kuala Lumpur
    }

    @Test fun proximityIsComputedOnThePhone() {
        val me = GeoPoint(3.1390, 101.6869)
        assertEquals(1000, Proximity.withinM(me, GeoPoint(3.1440, 101.6869), rural = false))   // ~560 m
        assertNull(Proximity.withinM(me, GeoPoint(3.1550, 101.6869), rural = false))           // ~1.8 km
        assertEquals(5000, Proximity.withinM(me, GeoPoint(3.1550, 101.6869), rural = true))
        assertNull(Proximity.withinM(me, GeoPoint(3.2000, 101.6869), rural = true))            // ~6.8 km
    }

    @Test fun promptOnlyWhenParkedForAMinuteAndAtMostEvery30Minutes() {
        val g = VerifyPromptGate()
        val t0 = 1_000_000L
        assertFalse(g.onFix(2.0, t0, navigating = false, appInForeground = true))
        assertFalse(g.onFix(3.0, t0 + 59_000, false, true))
        assertTrue(g.onFix(1.0, t0 + 60_000, false, true))
        g.markPrompted(t0 + 60_000)
        assertFalse("30-min gap", g.onFix(0.0, t0 + 20 * 60_000, false, true))
        assertTrue(g.onFix(0.0, t0 + 31 * 60_000, false, true))
        g.markPrompted(t0 + 31 * 60_000)

        val h = VerifyPromptGate()
        assertFalse(h.onFix(0.0, 0, false, true))
        assertFalse("moving resets the timer", h.onFix(12.0, 40_000, false, true))
        assertFalse(h.onFix(0.0, 70_000, false, true))
        assertFalse(h.onFix(0.0, 120_000, false, true))
        assertTrue(h.onFix(0.0, 130_000, false, true))
        val n = VerifyPromptGate()
        n.onFix(0.0, 0, true, true)
        assertFalse("never while navigating", n.onFix(0.0, 120_000, navigating = true, appInForeground = true))
        assertFalse("never in the background", n.onFix(0.0, 240_000, navigating = false, appInForeground = false))
        assertFalse("unknown speed is not 'parked'", n.onFix(null, 400_000, false, true))
    }
}

/** The backend's published vectors (docs/identity-and-reputation.md §8): both sides must agree byte for byte. */
class ServerVectorsTest {
    private val ed = Ed25519(LazySodiumJava(SodiumJava()))
    private val kp = ed.keyPair(ByteArray(32) { it.toByte() })
    private fun sig(m: String, pq: String, ts: Long, body: String) = RequestSigning.b64(ed.sign(kp, RequestSigning.canonical(m, pq, ts, body.toByteArray()).toByteArray()))

    @Test fun publicKeyHex() = assertEquals("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8", kp.publicKey.joinToString("") { "%02x".format(it) })

    @Test fun request1Verify() {
        val body = """{"answer":"yes","nameCorrect":true,"locationCorrect":true,"proximity":{"cell":"w283f8","withinM":420}}"""
        assertEquals("6c091ee7b6a05d273cedfd0ada1a4a1c113a2ef99a1ed14c35f8d87f36019821", RequestSigning.sha256Hex(body.toByteArray()))
        assertEquals("CPm5nQ7WU1z/tiBHSciXop7u2uc/M6Rt4/AgExSarydCkuW1iUQ99XT+gFyonROf7816WI+u2h901BueYupVAQ==",
            sig("POST", "/api/v1/places/c01a166b7f9c67924976aff1beb56c2cd/verify", 1792713600, body))
    }

    @Test fun request2EmptyBody() = assertEquals("62H79zlWTyZDvgEzOFxiHLBA29cFklC0eto7hkBYoWdsgHaoXp54+ygVAvS7hyS/38WD2XYwOK+XQr6RE4B+Cw==",
        sig("DELETE", "/api/v1/reviews/01a16676-ce9e-78db-842e-55ffa03ef190", 1792713600, ""))

    @Test fun request3QueryString() {
        val body = """{"type":"hazard","subtype":"pothole","lat":3.118,"lng":101.619,"heading":90}"""
        assertEquals("e7880fafa70c2dcc331adee63b54a5c4362d39a02d0ec08341d7ac06b9713980", RequestSigning.sha256Hex(body.toByteArray()))
        assertEquals("CVlQVQXZSUEbSZDJ3pEkhmeAKHgPbZh2MyAkEwcTXo9C5d+drAlcNC2X7zdrBBXfSAJN9jq3VQo+ukoTiRLJDw==", sig("POST", "/api/v1/reports?source=app", 1792713601, body))
    }

    @Test fun signatureLogin() = assertEquals("aApOheYmDSVKSLICJI8tuRW9Cp06hzkHJQKwdLab1N5sYCPAwvKFum8s+fnw6+6QsktcXji/+OLZzEQqijW+Cw==",
        RequestSigning.b64(ed.sign(kp, RequestSigning.authMessage("01a16676-0000-7000-8000-000000000001", 1792713600).toByteArray())))

    @Test fun signInByPublicKey() {
        val pk = RequestSigning.b64(kp.publicKey)
        assertEquals("GIMANO1-LOGIN\nA6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=\n1792713600", RequestSigning.keyLoginMessage(pk, 1792713600))
        assertEquals("61uR1jjsZqzZb7I0Oq3t0ZgVkyxeyutovBXfKWH9GvedLaFb2TzIXtKuotj9CgnRf6njJJDUBOwf6yy3ZkfFDQ==",
            RequestSigning.b64(ed.sign(kp, RequestSigning.keyLoginMessage(pk, 1792713600).toByteArray())))
    }

    @Test fun recoveryWordsSignTheSameLogin() {
        // 24 words → seed → key → the exact vector signature: the full recovery path.
        val bip = Bip39(File("src/main/assets/bip39-english.txt").readLines().filter { it.isNotBlank() })
        val seed = (bip.decode(bip.encode(ByteArray(32) { it.toByte() }).joinToString(" ")) as Bip39.Result.Ok).entropy
        val k = ed.keyPair(seed)
        val pk = RequestSigning.b64(k.publicKey)
        assertEquals("61uR1jjsZqzZb7I0Oq3t0ZgVkyxeyutovBXfKWH9GvedLaFb2TzIXtKuotj9CgnRf6njJJDUBOwf6yy3ZkfFDQ==",
            RequestSigning.b64(ed.sign(k, RequestSigning.keyLoginMessage(pk, 1792713600).toByteArray())))
    }

    @Test fun attestationClientDataHash() = assertEquals("ff664bac6820f2ce7bb5823ae9a2b33b103e47bca5bb6aa4ec11af46a9aa78be",
        RequestSigning.sha256Hex(RequestSigning.attestClientData("c01a166b7f9c67924976aff1beb56c2cd", 1792713600).toByteArray()))

    @Test fun geohashCells() {
        assertEquals("w2838r", Geohash.encode(3.1180, 101.6190)); assertEquals("w283fw", Geohash.encode(3.1579, 101.7116))
        assertEquals("w21zdq", Geohash.encode(1.3521, 103.8198)); assertEquals("w8cezs", Geohash.encode(4.9031, 114.9398))
    }

    @Test fun signedRequestWithPinnedTsUsesIt() {
        val req = okhttp3.Request.Builder().url("https://x/api/v1/places/p1/verify").header(RequestSigning.HEADER_TS, "1792713600")
            .post(okhttp3.RequestBody.Companion.run { "{}".toRequestBody(null) }).build()
        var tsSeen = 0L
        my.gimano.app.data.remote.SigningInterceptor.signed(req, req.header(RequestSigning.HEADER_TS)!!.toLong()) { _, _, ts, _ -> tsSeen = ts; "s" }
        assertEquals(1792713600L, tsSeen)
    }
}
