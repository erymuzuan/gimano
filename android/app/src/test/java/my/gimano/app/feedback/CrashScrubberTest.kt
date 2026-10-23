package my.gimano.app.feedback

import my.gimano.app.core.crash.Scrubber
import my.gimano.app.core.feedback.ContactRules
import org.junit.Assert.*
import org.junit.Test

class CrashScrubberTest {
    // Token-shaped fixtures are built at runtime so no literal JWT / key material sits in the source.
    private val b64url = java.util.Base64.getUrlEncoder().withoutPadding()
    private fun seg(json: String) = b64url.encodeToString(json.toByteArray())
    private val fakeJwt = listOf(seg("{\"alg\":\"none\"}"), seg("{\"sub\":\"test\"}"), "c2lnbmF0dXJl").joinToString(".")
    private val fakeSig = java.util.Base64.getEncoder().encodeToString(ByteArray(64) { (it * 7 + 3).toByte() })
    private val fakeHex = java.util.Random(42).let { r -> (1..32).joinToString("") { "%02x".format(r.nextInt(256)) } }

    private fun s(t: String) = Scrubber.scrub(t)!!
    private fun gone(t: String, vararg secrets: String) {
        val out = s(t)
        secrets.forEach { assertFalse("'$it' survived in: $out", out.contains(it)) }
        assertTrue(out, out.contains("[redacted]"))
    }

    @Test fun coordinatesInEveryShape() {
        gone("GeoPoint(lat=3.13904, lng=101.68692)", "3.13904", "101.68692")
        gone("""{"lat":3.1390,"lng":101.6869}""", "3.1390", "101.6869")
        gone("latitude: -6.2088, longitude: 106.8456", "6.2088", "106.8456")
        gone("snapped to 3.13900, 101.68690 after reroute", "3.13900", "101.68690")
        gone("point (3.139 101.687)", "3.139", "101.687")
        gone("Location[fused 3.139045,101.686924 hAcc=4.0]", "3.139045", "101.686924")
    }

    @Test fun emailsPhonesAndTokens() {
        gone("user ali.bin.abu@example.com.my failed", "ali.bin.abu", "example.com")
        gone("call +60 12-345 6789 now", "345 6789")
        gone("call 012-345 6789", "012-345")
        gone("wa +6591234567", "6591234567")
        gone("Authorization: Bearer abc.def.ghi-123", "abc.def")
        gone("jwt $fakeJwt", fakeJwt.take(12))
        gone("token=s3cr3tValue&x=1", "s3cr3tValue")
        gone("sig: $fakeSig", fakeSig.take(10))
        gone("key $fakeHex", fakeHex.take(12))
    }

    @Test fun queryStringsAndPaths() {
        val u = s("GET https://gimano-api.bukitbunga.com/api/v1/search?q=rumah+mertua&lat=3.1&lng=101.6 failed")
        assertTrue(u, u.contains("https://gimano-api.bukitbunga.com/api/v1/search?[redacted]"))
        assertFalse(u.contains("mertua"))
        gone("open failed: /data/user/0/my.gimano.app/files/photos/abc.webp: ENOENT", "my.gimano.app/files", "abc.webp")
        gone("/storage/emulated/0/Download/gimano-2026-10-23.gimano", "Download/gimano")
    }

    @Test fun stackTracesStayReadable() {
        val trace = """java.lang.IllegalStateException: Route has no legs
	at my.gimano.app.nav.NavigationController.onFix(NavigationController.kt:187)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
	at android.os.Handler.handleCallback(Handler.java:959)
Caused by: java.io.IOException: unexpected end of stream on https://gimano-api.bukitbunga.com/..."""
        assertEquals("a normal stack trace has nothing to redact", trace, s(trace))
        assertEquals("version strings are not coordinates", "Gimano 0.2.0 (2) Android 16 API 36", s("Gimano 0.2.0 (2) Android 16 API 36"))
        assertNull(Scrubber.scrub(null))
        assertEquals(10 + "\n…[truncated]".length, Scrubber.cap("x".repeat(100), 10)!!.length)
    }

    @Test fun contactRules() {
        assertEquals("+60123456789", ContactRules.e164("012-345 6789"))
        assertEquals("+60123456789", ContactRules.e164("60123456789"))
        assertEquals("+60123456789", ContactRules.e164("+60 12 345 6789"))
        assertEquals("+6591234567", ContactRules.e164("+65 9123 4567"))
        assertEquals("+6591234567", ContactRules.e164("006591234567"))
        assertNull(ContactRules.e164("call me"))
        assertNull(ContactRules.e164("123"))
        assertTrue(ContactRules.validEmail("ali@example.com")); assertFalse(ContactRules.validEmail("ali@"))
        assertEquals(ContactRules.State.None, ContactRules.state("", "", false))
        assertEquals("the +60 prefix alone is not a contact", ContactRules.State.None, ContactRules.state("", "+60", false))
        assertEquals(ContactRules.State.NeedsConsent, ContactRules.state("ali@example.com", "", false))
        assertEquals(ContactRules.State.BadEmail, ContactRules.state("ali@", "", true))
        assertEquals(ContactRules.State.BadPhone, ContactRules.state("", "+60abc", true))
        assertEquals(ContactRules.State.Ok("ali@example.com", "+60123456789"), ContactRules.state(" ali@example.com ", "0123456789", true))
    }
}
