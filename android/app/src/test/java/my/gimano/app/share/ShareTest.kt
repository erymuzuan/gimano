package my.gimano.app.share

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.share.*
import my.gimano.app.nav.NavDestination
import my.gimano.app.nav.NavState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Base64

class ShareCryptoTest {
    private val crypto = ShareCrypto(LazySodiumJava(SodiumJava()))
    private val payload = SharePayload(3.1180, 101.6210, 90.0, 48.0, "2026-10-23T01:13:00Z", 3700.0, "Rumah Mertua", "driving", "2026-10-23T01:00:00Z")

    @Test fun linkCarriesKeyOnlyInTheFragment() {
        val key = crypto.newKey()
        assertEquals(32, key.size)
        val link = ShareCrypto.link("abc123", key)
        assertTrue(link.startsWith("https://www.bukitbunga.com/gimano/s/#abc123."))
        assertFalse("base64url, no padding", link.substringAfter('#').contains('=') || link.contains('+') || link.substringAfter('#').contains('/'))
        val (id, k) = ShareCrypto.parseLink(link)!!
        assertEquals("abc123", id)
        assertArrayEquals(key, k)
        assertNull(ShareCrypto.parseLink("https://www.bukitbunga.com/gimano/s/"))
    }

    @Test fun sealOpenRoundTripBoundToShareId() {
        val key = crypto.newKey()
        val sealed = crypto.seal(key, "share-1", payload)
        assertEquals(24, Base64.getDecoder().decode(sealed.nonce).size)
        assertTrue("fits the 1 KB limit", sealed.ciphertext.length + sealed.nonce.length < 1024)
        assertEquals(payload, crypto.open(key, "share-1", sealed))
        assertNull("AD = shareId", crypto.open(key, "share-2", sealed))
        assertNull("wrong key", crypto.open(crypto.newKey(), "share-1", sealed))
        assertNotEquals("fresh nonce every update", sealed.nonce, crypto.seal(key, "share-1", payload).nonce)
        assertFalse("server sees no plaintext", String(Base64.getDecoder().decode(sealed.ciphertext), Charsets.ISO_8859_1).contains("Mertua"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareSessionTest {
    private val crypto = ShareCrypto(LazySodiumJava(SodiumJava()))

    private class FakeApi : ShareApi {
        val updates = ArrayList<Pair<String, SealedShare>>()
        val deleted = ArrayList<String>()
        override suspend fun createShare(expiresInMinutes: Int) = ShareCreated("s1", "tok", "2026-10-23T12:00:00Z")
        override suspend fun updateShare(shareId: String, writeToken: String, sealed: SealedShare) { assertEquals("tok", writeToken); updates += shareId to sealed }
        override suspend fun deleteShare(shareId: String, writeToken: String) { deleted += shareId }
    }

    private val driving = NavState(active = true, destination = NavDestination("Kampung", "", GeoPoint(2.2, 102.2), null), puck = GeoPoint(3.1, 101.6), speedKmh = 50)

    private fun statuses(api: FakeApi, link: String): List<String> {
        val (id, key) = ShareCrypto.parseLink(link)!!
        return api.updates.map { crypto.open(key, id, it.second)!!.status }
    }

    @Test fun pushesEvery15sThenEndsWhenNavigationStops() = runTest {
        val nav = MutableStateFlow(driving)
        val api = FakeApi()
        val s = ShareSession(backgroundScope, api, crypto, nav, now = { Instant.parse("2026-10-23T01:00:00Z") })
        val share = s.start()
        runCurrent()
        advanceTimeBy(45_000); runCurrent()
        assertEquals(4, api.updates.size)       // t = 0, 15, 30, 45 s
        nav.value = NavState()                  // Stop
        advanceTimeBy(15_000); runCurrent()
        assertEquals(listOf("driving", "driving", "driving", "driving", "ended"), statuses(api, share.link))
        assertEquals(listOf("s1"), api.deleted)
        assertNull(s.active.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("no more updates", 5, api.updates.size)
    }

    @Test fun arrivalSendsArrivedAndEnds() = runTest {
        val nav = MutableStateFlow(driving)
        val api = FakeApi()
        val s = ShareSession(backgroundScope, api, crypto, nav, now = { Instant.parse("2026-10-23T01:00:00Z") })
        val share = s.start(); runCurrent()
        nav.value = driving.copy(arrived = true)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(listOf("driving", "arrived"), statuses(api, share.link))
        assertEquals(listOf("s1"), api.deleted)
    }

    @Test fun stopSharingEndsImmediately() = runTest {
        val api = FakeApi()
        val s = ShareSession(backgroundScope, api, crypto, MutableStateFlow(driving), now = { Instant.parse("2026-10-23T01:00:00Z") })
        val share = s.start(); runCurrent()
        s.stop()
        assertEquals(listOf("driving", "ended"), statuses(api, share.link))
        assertEquals(listOf("s1"), api.deleted)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, api.updates.size)
    }

    @Test fun expiryEndsTheShare() = runTest {
        var t = Instant.parse("2026-10-23T11:59:50Z")
        val api = FakeApi()
        val s = ShareSession(backgroundScope, api, crypto, MutableStateFlow(driving), now = { t })
        s.start(); runCurrent()
        t = Instant.parse("2026-10-23T12:00:01Z")
        advanceTimeBy(15_000); runCurrent()
        assertNull(s.active.value)
        assertEquals(listOf("s1"), api.deleted)
    }
}
