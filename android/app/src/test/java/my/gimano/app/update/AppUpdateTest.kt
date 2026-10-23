package my.gimano.app.update

import my.gimano.app.core.update.*
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val sha = "51f9ae66d2866390a23406696589d1619e01624e4c6bae14151b5d70a702721b"
    private val full = """{"versionCode":3,"versionName":"0.2.1","apkUrl":"https://www.bukitbunga.com/gimano/download/gimano-0.2.1.apk",
        "minSupported":1,"sha256":"${sha.uppercase()}","notes":{"ms":"Pembetulan kecil","en":"Small fixes"},"publishedUtc":"2026-10-23T08:00:00Z","sizeBytes":41522687}"""

    @Test fun parsesAFullManifest() {
        val m = UpdateManifest.parse(full)!!
        assertEquals(3, m.versionCode); assertEquals("0.2.1", m.versionName); assertEquals(1, m.minSupported)
        assertEquals(sha, m.sha256)   // normalised to lower case
        assertEquals("Pembetulan kecil", m.notes("ms")); assertEquals("Small fixes", m.notes("en"))
        assertEquals(41522687L, m.sizeBytes)
    }

    @Test fun lenientAboutExtraMissingAndNullFields() {
        val m = UpdateManifest.parse("""{"versionCode":"4","apkUrl":"https://x.example/a.apk","sha256":"$sha","channel":"beta","extra":{"a":[1,2]},
            "minSupported":null,"notes":null,"publishedUtc":null,"versionName":null}""")!!
        assertEquals(4, m.versionCode); assertEquals("4", m.versionName); assertEquals(0, m.minSupported)
        assertNull(m.notes("ms")); assertNull(m.publishedUtc); assertNull(m.sizeBytes)
        assertEquals("plain-string notes serve both languages", "Baharu",
            UpdateManifest.parse("""{"versionCode":4,"apkUrl":"https://x/a.apk","sha256":"$sha","notes":"Baharu"}""")!!.notes("en"))
        assertEquals("only one language → fall back to the other", "Small fixes",
            UpdateManifest.parse("""{"versionCode":4,"apkUrl":"https://x/a.apk","sha256":"$sha","notes":{"en":"Small fixes"}}""")!!.notes("ms"))
    }

    @Test fun rejectsUnusableManifests() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse("[]"))
        assertNull("no versionCode", UpdateManifest.parse("""{"apkUrl":"https://x/a.apk","sha256":"$sha"}"""))
        assertNull("http only", UpdateManifest.parse("""{"versionCode":3,"apkUrl":"http://x/a.apk","sha256":"$sha"}"""))
        assertNull("bad hash", UpdateManifest.parse("""{"versionCode":3,"apkUrl":"https://x/a.apk","sha256":"abc"}"""))
    }

    @Test fun versionComparisonSnoozeAndMinSupportedGate() {
        val m = UpdateManifest.parse(full)!!   // versionCode 3, minSupported 1
        val now = 1_000_000_000L
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(3, m, 0, now))
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(4, m, 0, now))
        assertEquals(UpdateDecision.Available(m, snoozed = false), UpdatePolicy.decide(2, m, 0, now))
        assertEquals(UpdateDecision.Available(m, snoozed = true), UpdatePolicy.decide(2, m, now + UpdatePolicy.SNOOZE_MS, now))
        assertEquals(UpdateDecision.Available(m, snoozed = false), UpdatePolicy.decide(2, m, now - 1, now))
        val strict = m.copy(minSupported = 3)
        assertEquals("below minSupported → blocking gate, snooze ignored", UpdateDecision.Required(strict), UpdatePolicy.decide(2, strict, now + 99_999, now))
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(2, null, 0, now))
        assertTrue(UpdatePolicy.shouldCheck(0, now, false)); assertFalse(UpdatePolicy.shouldCheck(now - 3_600_000, now, false))
        assertTrue(UpdatePolicy.shouldCheck(now - 1, now, force = true))
    }

    @Test fun verifierRejectsHashSignerPackageAndVersionMismatches() {
        val m = UpdateManifest.parse(full)!!
        val mine = setOf("b9baddcd63b3d74b8a374cb93df3278579ba4faeae121c35827d377e210bbec9")
        val good = ArchiveInfo("my.gimano.app", 3, mine)
        assertEquals(VerifyOutcome.Ok, UpdateVerifier.verify(sha.uppercase(), m, good, "my.gimano.app", mine))
        assertTrue(UpdateVerifier.verify("0".repeat(64), m, good, "my.gimano.app", mine) is VerifyOutcome.HashMismatch)
        assertEquals(VerifyOutcome.SignerMismatch, UpdateVerifier.verify(sha, m, good.copy(signerSha256 = setOf("ff".repeat(32))), "my.gimano.app", mine))
        assertEquals(VerifyOutcome.SignerMismatch, UpdateVerifier.verify(sha, m, good, "my.gimano.app", emptySet()))
        assertEquals(VerifyOutcome.WrongPackage("com.evil.app"), UpdateVerifier.verify(sha, m, good.copy(packageName = "com.evil.app"), "my.gimano.app", mine))
        assertEquals(VerifyOutcome.WrongVersion(3, 2), UpdateVerifier.verify(sha, m, good.copy(versionCode = 2), "my.gimano.app", mine))
        assertEquals(VerifyOutcome.Unreadable, UpdateVerifier.verify(sha, m, null, "my.gimano.app", mine))
    }
}
