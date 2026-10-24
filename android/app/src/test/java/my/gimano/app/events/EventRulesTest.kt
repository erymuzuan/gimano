package my.gimano.app.events

import my.gimano.app.core.events.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.Deflater

/** Mirrors event-core `import.rs` unit tests. */
class ImportDecisionTest {
    private val pub = ByteArray(32) { 1 }
    private val otherPub = ByteArray(32) { 2 }
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private fun h(b: Int) = ByteArray(32) { b.toByte() }
    private fun cand(rev: Long, hash: Int, parent: Int?, expires: Instant = Instant.parse("2026-11-16T08:00:00Z")) =
        ImportCandidate(rev, parent?.let(::h), expires, pub, h(hash))
    private fun stored(vararg revs: Pair<Long, Int>) = StoredEvent(pub, revs.map { StoredRevision(it.first, h(it.second)) })

    @Test fun newEventShowsSafetyCode() =
        assertEquals(ImportDecision.New(EventHashes.safetyCode(pub)), EventImport.decide(cand(1, 10, null), null, now))

    @Test fun expiredWinsOverEverything() {
        assertEquals(ImportDecision.Expired, EventImport.decide(cand(3, 10, 9, expires = now), null, now))
        assertEquals(ImportDecision.Expired, EventImport.decide(cand(3, 10, 9, expires = now), stored(2L to 9), now))
        assertNotEquals(ImportDecision.Expired, EventImport.decide(cand(3, 10, 9, expires = now.plusSeconds(1)), null, now))
    }

    @Test fun differentOrganizerRejected() =
        assertEquals(ImportDecision.DifferentOrganizer, EventImport.decide(cand(2, 11, 10), StoredEvent(otherPub, listOf(StoredRevision(1, h(10)))), now))

    @Test fun alreadyImported() = assertEquals(ImportDecision.AlreadyImported, EventImport.decide(cand(2, 11, 10), stored(1L to 10, 2L to 11), now))

    @Test fun olderKeepsStored() = assertEquals(ImportDecision.Older(2), EventImport.decide(cand(1, 9, null), stored(1L to 10, 2L to 11), now))

    @Test fun sameRevDifferentBytesIsFork() = assertEquals(ImportDecision.Fork(2), EventImport.decide(cand(2, 12, 10), stored(1L to 10, 2L to 11), now))

    @Test fun newerWithMatchingParent() =
        assertEquals(ImportDecision.Newer(2, ParentCheck.MATCHES), EventImport.decide(cand(3, 12, 11), stored(1L to 10, 2L to 11), now))

    @Test fun newerParentMatchesEitherForkSide() =
        assertEquals(ImportDecision.Newer(2, ParentCheck.MATCHES), EventImport.decide(cand(3, 13, 12), stored(2L to 11, 2L to 12), now))

    @Test fun newerWithParentMismatchWarns() =
        assertEquals(ImportDecision.Newer(2, ParentCheck.MISMATCH), EventImport.decide(cand(3, 12, 99), stored(2L to 11), now))

    @Test fun newerAfterMissedRevisions() =
        assertEquals(ImportDecision.Newer(2, ParentCheck.MISSED_REVISIONS), EventImport.decide(cand(5, 12, 40), stored(2L to 11), now))

    @Test fun pinnedWithoutRevisions() = assertEquals(ImportDecision.Newer(0, ParentCheck.MATCHES), EventImport.decide(cand(1, 12, null), stored(), now))
}

/** Mirrors event-core `codes.rs` tests plus the reading rules in the vectors README. */
class EventCodesTest {
    @Test fun secretCodeRoundTrip() {
        for (seed in 0..255) {
            val key = ByteArray(32) { i -> (seed * 31 + i * 7).toByte() }
            val code = SecretCode.encode(key)
            assertEquals(52 + 12, code.length)
            assertEquals(13, code.split('-').size)
            assertTrue(code.split('-').all { it.length == 4 })
            assertArrayEquals(key, SecretCode.decode(code))
        }
    }

    @Test fun secretCodeKnownValues() {
        assertEquals("0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000", SecretCode.encode(ByteArray(32)))
        assertEquals("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZG", SecretCode.encode(ByteArray(32) { -1 }))
    }

    @Test fun secretCodeLenientReading() {
        val key = ByteArray(32) { it.toByte() }
        val code = SecretCode.encode(key)
        assertArrayEquals(key, SecretCode.decode(code.lowercase().replace('-', ' ')))
        assertArrayEquals(key, SecretCode.decode(code.replace('0', 'o').replace('1', 'l')))
        assertArrayEquals(key, SecretCode.decode(code.replace('1', 'I')))
    }

    private fun bad(code: String) = try { SecretCode.decode(code); null } catch (e: EventPackException) { e.code }

    @Test fun secretCodeRejectsBadInput() {
        val code = SecretCode.encode(ByteArray(32) { 7 })
        assertEquals(EventPackException.INVALID_SECRET_CODE, bad(code.dropLast(1)))
        assertEquals(EventPackException.INVALID_SECRET_CODE, bad(code + "0"))
        assertEquals(EventPackException.INVALID_SECRET_CODE, bad("U" + code.drop(1)))
        // Last character carries 1 data bit + 4 padding bits: "1" sets a padding bit.
        assertEquals(EventPackException.INVALID_SECRET_CODE, bad(SecretCode.encode(ByteArray(32)).dropLast(1) + "1"))
        // Non-ASCII that upper-cases to a valid letter is still rejected (Rust uses ASCII upper-casing).
        assertEquals(EventPackException.INVALID_SECRET_CODE, bad(code.replaceFirst(Regex("[0-9A-Z]"), "ı")))
    }

    @Test fun safetyCodeFormat() = assertEquals("6668 7AAD F862 BD77", EventHashes.safetyCode(ByteArray(32)))

    @Test fun base64urlIsStrict() {
        assertArrayEquals(byteArrayOf(0x41), B64u.decode("QQ"))
        assertNull("non-canonical trailing bits", B64u.decode("QR"))
        assertNull("padding", B64u.decode("QQ=="))
        assertNull("standard alphabet", B64u.decode("a+/b"))
        assertNull(B64u.decode("Q"))
    }

    private fun deflateRaw(b: ByteArray): ByteArray {
        val d = Deflater(9, true)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test fun inflateIsBounded() {
        val small = "hello".repeat(100).toByteArray()
        assertArrayEquals(small, RawInflate.inflate(deflateRaw(small)))
        assertArrayEquals(small, RawInflate.inflate(deflateRaw(small), max = small.size))
        val e = try { RawInflate.inflate(deflateRaw(small), max = small.size - 1); null } catch (x: EventPackException) { x.code }
        assertEquals(EventPackException.INFLATE_TOO_LARGE, e)
        val bomb = deflateRaw(ByteArray(EventLimits.MAX_INFLATED_BYTES + 1))
        assertEquals(EventPackException.INFLATE_TOO_LARGE, try { RawInflate.inflate(bomb); null } catch (x: EventPackException) { x.code })
        val truncated = deflateRaw(small).let { it.copyOf(it.size / 2) }
        assertEquals(EventPackException.INFLATE, try { RawInflate.inflate(truncated); null } catch (x: EventPackException) { x.code })
        assertEquals(EventPackException.INFLATE, try { RawInflate.inflate(byteArrayOf(0x07, 0x7f)); null } catch (x: EventPackException) { x.code })
    }
}

class EventValidationTest {
    private val base = """{"id":"UwisvVsbNt_u12UlS6sdnQ","rev":1,"parent":null,"publishedAt":"2026-11-01T02:15:00Z","template":"kenduri",
        "title":"Kenduri","lang":"ms","tz":"Asia/Kuala_Lumpur","start":"2026-11-15T11:00:00+08:00","end":"2026-11-15T16:00:00+08:00",
        "expiresAt":"2026-11-16T08:00:00Z","stops":[{"id":"KwBvV0D9LLY","kind":"parking","label":"Parkir","lat":2.9871,"lng":101.793}]}"""

    private fun parse(json: String) = EventParser.parse(json.toByteArray())
    private fun code(json: String) = try { parse(json); null } catch (e: EventPackException) { e.code }

    @Test fun baseIsValid() { assertEquals(1, parse(base).stops.size) }

    @Test fun unknownFieldsIgnoredAndUnknownTemplateOrKindReadAsOther() {
        val e = parse(base.replace("\"kenduri\"", "\"wedding\"").replace("\"parking\"", "\"helipad\"").replace("\"rev\":1", "\"rev\":1,\"future\":{\"x\":1}"))
        assertEquals(EventTemplate.OTHER, e.template)
        assertEquals(StopKind.OTHER, e.stops.single().kind)
    }

    @Test fun optionalFieldsMayBeNull() {
        val e = parse(base.replace("\"lang\"", "\"description\":null,\"organizer\":null,\"lang\""))
        assertNull(e.description); assertNull(e.organizerAlias)
    }

    @Test fun unknownLangIsRejected() = assertEquals(EventPackException.MALFORMED_EVENT, code(base.replace("\"ms\"", "\"fr\"")))

    @Test fun parentRules() {
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("\"rev\":1", "\"rev\":2")))
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("\"parent\":null", "\"parent\":\"${B64u.encode(ByteArray(32))}\"")))
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("\"rev\":1", "\"rev\":0")))
        val r2 = parse(base.replace("\"rev\":1,\"parent\":null", "\"rev\":2,\"parent\":\"${B64u.encode(ByteArray(32))}\""))
        assertEquals(2L, r2.rev)
    }

    @Test fun textLimitsCountCodePoints() {
        val ok = "😀".repeat(120)   // 240 UTF-16 units, 120 code points
        assertEquals(ok, parse(base.replace("\"Kenduri\"", "\"$ok\"")).title)
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("\"Kenduri\"", "\"${ok}x\"")))
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("\"Kenduri\"", "\"   \"")))
    }

    @Test fun coordinatesAndIdsAndRefs() {
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("2.9871", "2.98712345")))
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("101.793", "181.0")))
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("KwBvV0D9LLY", "KwBvV0D9LL")))
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("]}", "],\"sessions\":[{\"id\":\"2rIY4lz076Q\",\"title\":\"J\",\"start\":\"2026-11-15T11:30:00+08:00\",\"end\":\"2026-11-15T12:30:00+08:00\",\"stopId\":\"AAAAAAAAAAA\"}]}")))
    }

    @Test fun durationLimit() {
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("2026-11-15T16:00:00+08:00", "2026-11-29T11:00:01+08:00")))
        assertEquals(EventPackException.INVALID_EVENT, code(base.replace("2026-11-15T16:00:00+08:00", "2026-11-15T10:00:00+08:00")))
    }

    @Test fun routeChecks() {
        fun route(extra: String) = base.replace("]}", "],\"routes\":[{\"id\":\"a-AvMAXaZiw\",\"mode\":\"bicycle\",\"stopIds\":[\"KwBvV0D9LLY\"],$extra}]}")
        val good = "\"polyline\":\"_v`sD_xlgaE_mV_g^\",\"source\":\"routed\",\"distanceM\":100"
        assertEquals(2, parse(route(good)).routes.single().points.size)
        assertEquals(EventPackException.MALFORMED_EVENT, code(route(good.replace("routed", "drawn"))))
        assertEquals(EventPackException.MALFORMED_EVENT, code(route(good.replace("100", "-1"))))
        assertEquals(EventPackException.INVALID_EVENT, code(route(good.replace("_v`sD_xlgaE_mV_g^", "_v`sD_xlgaE"))))
        assertEquals(EventPackException.INVALID_EVENT, code(route(good.replace("_v`sD_xlgaE_mV_g^", "_v`sD"))))
        assertEquals(EventPackException.MALFORMED_EVENT, code(base.replace("]}", "],\"routes\":[{\"id\":\"a-AvMAXaZiw\",\"mode\":\"boat\",$good}]}")))
    }
}
