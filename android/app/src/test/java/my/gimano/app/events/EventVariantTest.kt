package my.gimano.app.events

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.core.events.*
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.routes.RoutePolyline
import my.gimano.app.core.routes.RouteSimplify
import my.gimano.app.data.events.EventImportUi
import my.gimano.app.data.events.EventImporter
import my.gimano.app.data.events.EventNotice
import my.gimano.app.data.events.EventStore
import my.gimano.app.data.events.Publication
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/** docs/events.md §5.4 simplification, §5.5 stops-only variant, §7.4 revision hashes (vectors from the Rust core). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventVariantTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val reader = EventPackReader(sodium)
    private val writer = EventPackWriter(sodium)

    private fun vector(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/$name.json")!!.bufferedReader().use { it.readText() }).jsonObject

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.b(k: String) = B64u.decode(s(k)) ?: error("$k is not strict b64u")

    private fun assertCode(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: EventPackException) { assertEquals(code, e.code) }
    }

    // --- Reader -------------------------------------------------------------------------------------------------

    @Test fun stopsOnlyVectorOpensWithFullHashAsRevisionHash() {
        EventPackVectorsTest().positive("ride-two-day-stops-only")
        val v = vector("ride-two-day-stops-only")
        val p = reader.openLink(v.s("linkFragment"))
        assertTrue(v.getValue("expected").jsonObject.getValue("stopsOnly").jsonPrimitive.boolean)
        assertTrue(p.event.stopsOnly)
        assertEquals(v.s("fullHashB64u"), p.event.variant!!.fullHash)
        assertEquals(v.s("fullHashB64u"), p.revisionHashB64u)
        assertEquals(vector("ride-two-day").s("revisionHashB64u"), p.revisionHashB64u)
        assertEquals(v.s("eventBytesHashB64u"), B64u.encode(EventHashes.sha256(p.eventBytes)))
        assertNotEquals(v.s("eventBytesHashB64u"), p.revisionHashB64u)
        // The routes are still listed (distance, time, stops), with no line.
        val r = p.event.routes.single()
        assertNull(r.polyline)
        assertTrue(r.points.isEmpty())
        assertEquals(20100L, r.distanceM)
        assertEquals(2, r.legs.size)
        // Same organiser key as the full event; the full event is not a variant.
        val full = reader.openLink(vector("ride-two-day").s("linkFragment"))
        assertArrayEquals(full.organizerPub, p.organizerPub)
        assertFalse(full.event.stopsOnly)
    }

    @Test fun writerReproducesTheReferenceVariantAndRoundTripsIt() {
        val full = vector("ride-two-day").b("eventB64u")
        val ref = vector("ride-two-day-stops-only").s("eventJson")
        // Not required by the contract (the revision hash is fullHash), but our serde-layout writer matches it.
        assertEquals(ref, EventVariants.stopsOnly(full).toString(Charsets.UTF_8))
        assertEquals(ref, EventJsonWriter.write(EventParser.parse(ref.toByteArray())).toString(Charsets.UTF_8))
        assertArrayEquals(EventHashes.sha256(full), EventHashes.revisionHash(EventVariants.stopsOnly(full)))
        // A variant can't be made from a variant.
        assertCode(EventPackException.INVALID_EVENT) { EventVariants.stopsOnly(ref.toByteArray()) }
    }

    @Test fun variantNegativesFailWithTheirCodes() {
        for (name in listOf("negative-variant-with-polyline", "negative-missing-polyline", "negative-unknown-variant")) {
            val v = vector(name)
            // Through every layer (decrypts and verifies, then the event is rejected) and on the signed JSON alone.
            assertCode(v.s("expectedError")) { reader.openLink(v.s("linkFragment")) }
            assertCode(v.s("expectedError")) { EventParser.parse(v.s("eventJson").toByteArray()) }
        }
        assertEquals("variant_has_polyline", EventPackException.VARIANT_HAS_POLYLINE)
        assertEquals("missing_polyline", EventPackException.MISSING_POLYLINE)
        assertEquals("unsupported_variant", EventPackException.UNSUPPORTED_VARIANT)
        // variant: null is a full event; a malformed fullHash is invalid_event.
        val full = vector("ride-two-day").s("eventJson")
        assertFalse(EventParser.parse(full.dropLast(1).plus(",\"variant\":null}").toByteArray()).stopsOnly)
        val variant = vector("ride-two-day-stops-only").s("eventJson")
        val badHash = variant.replace(Regex("\"fullHash\":\"[^\"]+\""), "\"fullHash\":\"abc\"")
        assertCode(EventPackException.INVALID_EVENT) { EventParser.parse(badHash.toByteArray()) }
    }

    // --- §7.4 import sequence -------------------------------------------------------------------------------

    @Test fun importSequenceVector() = runTest {
        val v = vector("import-stops-only-sequence")
        val now = Instant.parse(v.s("now"))
        val packs = v.getValue("packs").jsonObject.mapValues { (_, p) -> p.jsonObject }
        // The packs carry what they claim.
        packs.forEach { (_, p) ->
            val o = reader.openLink(p.s("linkFragment"))
            assertEquals(p.s("revisionHashB64u"), o.revisionHashB64u)
            assertEquals(p.getValue("stopsOnly").jsonPrimitive.boolean, o.event.stopsOnly)
            assertEquals(p.getValue("rev").jsonPrimitive.int.toLong(), o.event.rev)
        }
        for (seq in v.getValue("sequences").jsonArray.map { it.jsonObject }) {
            val name = seq.s("name")
            val st = EventStore(FakeEventDao(), TestVault(), { it() }, clock = { now })
            for (step in seq.getValue("steps").jsonArray.map { it.jsonObject }) {
                val p = reader.openLink(packs.getValue(step.s("import")).s("linkFragment"))
                val d = EventImport.decide(ImportCandidate.of(p), st.storedFor(p.event.id), now)
                assertEquals(name, step.s("decision"), d.wire)
                val parent = (step["parentCheck"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                assertEquals(name, parent, (d as? ImportDecision.Newer)?.parent?.name?.lowercase())
                if (d is ImportDecision.New || d is ImportDecision.Newer || d is ImportDecision.Fork || d is ImportDecision.UpgradeToFull) assertTrue(st.save(p, d))
                val want = step.getValue("storedAfter").jsonArray.map { it.jsonObject }
                    .map { Triple(it.getValue("rev").jsonPrimitive.int.toLong(), it.s("revisionHashB64u"), it.getValue("stopsOnly").jsonPrimitive.boolean) }
                val have = st.storedFor(p.event.id)!!.revisions.map { Triple(it.rev, B64u.encode(it.hash), it.stopsOnly) }.sortedBy { it.first }
                assertEquals("$name after ${step.s("import")}", want, have)
            }
        }
    }

    @Test fun pureDecisionsMirrorTheRustCases() {
        val pub = ByteArray(32) { 1 }
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val exp = Instant.parse("2026-11-16T08:00:00Z")
        fun h(b: Int) = ByteArray(32) { b.toByte() }
        fun cand(rev: Long, hash: Int, parent: Int?, stopsOnly: Boolean = false) = ImportCandidate(rev, parent?.let(::h), exp, pub, h(hash), stopsOnly)
        fun stored(vararg r: StoredRevision) = StoredEvent(pub, r.toList())
        val full2 = StoredRevision(2, h(11)); val var2 = StoredRevision(2, h(11), stopsOnly = true)
        assertEquals(ImportDecision.UpgradeToFull(2), EventImport.decide(cand(2, 11, 10), stored(StoredRevision(1, h(10)), var2), now))
        assertEquals(ImportDecision.AlreadyImported, EventImport.decide(cand(2, 11, 10, true), stored(full2), now))
        assertEquals(ImportDecision.AlreadyImported, EventImport.decide(cand(2, 11, 10, true), stored(var2), now))
        assertEquals(ImportDecision.Fork(2), EventImport.decide(cand(2, 12, 10, true), stored(full2), now))
        assertEquals(ImportDecision.Fork(2), EventImport.decide(cand(2, 12, 10), stored(var2), now))
        assertEquals(ImportDecision.Newer(2, ParentCheck.MATCHES), EventImport.decide(cand(3, 11, 11), stored(var2), now))
        assertEquals(ImportDecision.Newer(2, ParentCheck.MATCHES), EventImport.decide(cand(3, 12, 11, true), stored(StoredRevision(1, h(10)), var2), now))
        assertEquals(ImportDecision.Older(2), EventImport.decide(cand(1, 10, null, true), stored(full2), now))
        assertEquals(ImportDecision.DifferentOrganizer,
            EventImport.decide(ImportCandidate(1, null, exp, ByteArray(32) { 2 }, h(10), true), stored(StoredRevision(1, h(10))), now))
    }

    @Test fun importerUpgradesSilentlyWithLaluanPenuhDitambah() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val now = Instant.parse("2026-11-01T03:00:00Z")
        val st = EventStore(FakeEventDao(), TestVault(), { it() }, clock = { now })
        val imp = EventImporter(scope, reader, st, clock = { now }, work = dispatcher)
        val variant = vector("ride-two-day-stops-only")
        val full = vector("ride-two-day")
        imp.openLink(variant.s("link")); scope.advanceUntilIdle()
        assertTrue((imp.ui.value as EventImportUi.Review).decision is ImportDecision.New)
        imp.confirm(); scope.advanceUntilIdle()
        val id = variant.getValue("expected").jsonObject.s("id")
        kotlinx.coroutines.runBlocking { assertTrue(st.load(id)!!.plan.stopsOnly) }
        // The event file (always full) later: no review sheet, the route lines are simply there.
        imp.openFile(full.b("fileB64u")); imp.submitCode(full.s("secretCode")); scope.advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.FULL_ROUTE_ADDED, id), imp.ui.value)
        kotlinx.coroutines.runBlocking {
            val saved = st.load(id)!!
            assertFalse(saved.plan.stopsOnly)
            assertEquals(7, saved.plan.routes.single().points.size)
            assertEquals(full.s("revisionHashB64u"), saved.hash)
        }
        imp.dismiss()
        // The stops-only link again: already imported, the full route stays.
        imp.openLink(variant.s("linkFragment")); scope.advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.ALREADY_IMPORTED, id), imp.ui.value)
        kotlinx.coroutines.runBlocking { assertFalse(st.load(id)!!.plan.stopsOnly) }
    }

    // --- §5.4 simplification --------------------------------------------------------------------------------

    private val mPerDeg = Math.toRadians(6_371_008.8)

    private fun r6(v: Double) = EventJsonWriter.round6(v)

    /** A long, road-like line: ~10 m steps, slowly turning, switchbacks and straights, sub-metre jitter. */
    private fun windingRoad(n: Int, seed: Int = 7): List<GeoPoint> {
        val rnd = Random(seed)
        var lat = 3.80; var lng = 102.40; var heading = 0.6; var turn = 0.0
        return List(n) { i ->
            if (i % 400 == 0) turn = when (rnd.nextInt(3)) { 0 -> 0.0; 1 -> (rnd.nextDouble() - 0.5) * 0.01; else -> (rnd.nextDouble() - 0.5) * 0.08 }
            heading += turn + (rnd.nextDouble() - 0.5) * 0.004
            val step = 9.0 + rnd.nextDouble() * 2.0
            lat += step * Math.cos(heading) / mPerDeg
            lng += step * Math.sin(heading) / (mPerDeg * Math.cos(Math.toRadians(lat)))
            val j = 0.4 / mPerDeg
            GeoPoint(r6(lat + (rnd.nextDouble() - 0.5) * j), r6(lng + (rnd.nextDouble() - 0.5) * j))
        }
    }

    /** Independent check: one projection for the whole line (mean latitude), distance to the bracketing kept segment. */
    private fun worstDistance(original: List<GeoPoint>, simplified: List<GeoPoint>, tol: Double): Double {
        val cos = Math.cos(Math.toRadians(original.map { it.lat }.average()))
        fun seg(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
            fun xy(q: GeoPoint) = (q.lng - a.lng) * mPerDeg * cos to (q.lat - a.lat) * mPerDeg
            val (px, py) = xy(p); val (bx, by) = xy(b)
            val len2 = bx * bx + by * by
            val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
            return Math.hypot(px - t * bx, py - t * by)
        }
        var worst = 0.0
        var s = 0
        for (p in original) {
            while (s + 1 < simplified.size - 1 && p == simplified[s + 1]) s++
            val d = seg(p, simplified[s], simplified[s + 1])
            assertTrue("point $p is $d m from the line", d <= tol + 0.05)
            worst = maxOf(worst, d)
        }
        return worst
    }

    @Test fun simplificationKeepsEveryPointWithin5mAndTheEnds() {
        val road = windingRoad(20_000)
        val simple = RouteSimplify.simplify(road)
        assertEquals(road.first(), simple.first())
        assertEquals(road.last(), simple.last())
        assertTrue("${road.size} → ${simple.size}", simple.size * 5 < road.size)
        assertTrue("the tolerance is used", worstDistance(road, simple, RouteSimplify.EVENT_TOLERANCE_M) > 3.0)
        val it = road.iterator()
        assertTrue("kept points are an ordered subsequence", simple.all { p -> it.asSequence().any { it == p } })
        assertEquals("idempotent", simple, RouteSimplify.simplify(simple))
        // Boundary: a middle point 4 m off a 1 km chord goes, 6 m stays.
        val a = GeoPoint(3.0, 101.0); val b = GeoPoint(3.0, 101.009)
        val near = GeoPoint(3.0 + 4.0 / mPerDeg, 101.0045); val far = GeoPoint(3.0 + 6.0 / mPerDeg, 101.0045)
        assertEquals(listOf(a, b), RouteSimplify.simplify(listOf(a, near, b)))
        assertEquals(listOf(a, far, b), RouteSimplify.simplify(listOf(a, far, b)))
        // Closed loop keeps its far side; an out-and-back spike past the chord end is kept.
        val loop = listOf(a, GeoPoint(3.001, 101.0), GeoPoint(3.001, 101.001), GeoPoint(3.0, 101.001), a)
        val sl = RouteSimplify.simplify(loop)
        assertTrue(sl.size >= 4 && sl.first() == a && sl.last() == a)
        worstDistance(loop, sl, 5.0)
        val spike = listOf(GeoPoint(3.0, 101.0), GeoPoint(3.0, 101.002), GeoPoint(3.0, 101.001))
        assertEquals(spike, RouteSimplify.simplify(spike))
        assertEquals(2, RouteSimplify.simplify(List(5) { a }).size)
        assertEquals(listOf(a), RouteSimplify.simplify(listOf(a)))
    }

    // --- Writer: which share options for a long route --------------------------------------------------------

    private val seed = ByteArray(32) { 5 }
    private val key = ByteArray(32) { 6 }

    private fun fullPlan(): EventPlan = EventParser.parse(vector("ride-two-day").b("eventB64u"))

    /** The ride with its route replaced by [pts], simplified like the writer does (RouteAssembly.toEventRoute). */
    private fun withRoute(p: EventPlan, pts: List<GeoPoint>): ByteArray {
        val s = RouteSimplify.simplify(pts)
        val r = p.routes.single().copy(polyline = RoutePolyline.encode(s), points = s)
        return EventJsonWriter.write(p.copy(routes = listOf(r)))
    }

    /** A route that does not simplify or compress much: 30–90 m random sidesteps. */
    private fun jaggedRoute(n: Int): List<GeoPoint> {
        val rnd = Random(11)
        var lat = 2.95; var lng = 101.85
        return List(n) {
            lat += (rnd.nextDouble() * 60 + 30) * (if (rnd.nextBoolean()) 1 else -1) / mPerDeg
            lng += (rnd.nextDouble() * 60 + 30) / mPerDeg
            GeoPoint(r6(lat), r6(lng))
        }
    }

    @Test fun shareOffersLinkStopsOnlyOrFileCorrectly() {
        val plan = fullPlan()
        // 1. The ride as it is: the full link fits.
        val small = SealedInvitation.seal(writer, withRoute(plan, plan.routes.single().points), seed, key)
        assertEquals(ShareMode.LINK, small.mode)
        assertNull(small.stopsOnly)
        val pubSmall = Publication(plan, key, small, reused = false, previous = null)
        assertNotNull(pubSmall.link(EventLinks.DEFAULT_SITE))
        assertNull(pubSmall.stopsOnlyLink(EventLinks.DEFAULT_SITE))

        // 2. A long route (3000 jagged points even after simplification): full link too long, stops-only fits.
        val longBytes = withRoute(plan, jaggedRoute(3000))
        val long = SealedInvitation.seal(writer, longBytes, seed, key)
        assertTrue(long.fullFragmentLength > EventLimits.MAX_FRAGMENT_CHARS)
        assertEquals(ShareMode.STOPS_ONLY_OR_FILE, long.mode)
        val pub = Publication(EventParser.parse(longBytes), key, long, reused = false, previous = null)
        assertNull(pub.link(EventLinks.DEFAULT_SITE))
        val stopsLink = pub.stopsOnlyLink(EventLinks.DEFAULT_SITE)!!
        val opened = reader.openLink(stopsLink)
        assertTrue(opened.event.stopsOnly)
        assertEquals(plan.rev, opened.event.rev)
        assertArrayEquals(EventHashes.sha256(longBytes), opened.revisionHash)
        // The file stays full, same organiser key.
        val file = reader.openFile(pub.file(), pub.secretCode)
        assertFalse(file.event.stopsOnly)
        assertArrayEquals(longBytes, file.eventBytes)
        assertArrayEquals(file.organizerPub, opened.organizerPub)
        assertEquals(file.revisionHashB64u, opened.revisionHashB64u)

        // 3. So many stop notes that even the stops-only link is too long: the file only.
        val rnd = Random(3)
        val stops = List(EventLimits.MAX_STOPS) { i ->
            EventStop(EventIds.newItemId(), StopKind.CHECKPOINT, "CP $i", r6(3.0 + i * 0.001), 101.8, null, null, null,
                B64u.encode(ByteArray(370).also(rnd::nextBytes)), null)
        }
        val huge = plan.copy(stops = plan.stops + stops.take(EventLimits.MAX_STOPS - plan.stops.size))
        val hugeBytes = withRoute(huge, jaggedRoute(3000))
        val fileOnly = SealedInvitation.seal(writer, hugeBytes, seed, key)
        assertTrue(fileOnly.stopsOnlyFragmentLength!! > EventLimits.MAX_FRAGMENT_CHARS)
        assertEquals(ShareMode.FILE_ONLY, fileOnly.mode)
        assertNull(Publication(huge, key, fileOnly, reused = false, previous = null).stopsOnlyLink(EventLinks.DEFAULT_SITE))
        // No routes at all and too long: nothing to drop, the file only.
        val noRoutes = EventJsonWriter.write(huge.copy(routes = emptyList()))
        val nr = SealedInvitation.seal(writer, noRoutes, seed, key)
        assertNull(nr.stopsOnly)
        assertEquals(ShareMode.FILE_ONLY, nr.mode)

        assertEquals(ShareMode.LINK, ShareMode.choose(16_000, null))
        assertEquals(ShareMode.STOPS_ONLY_OR_FILE, ShareMode.choose(16_001, 16_000))
        assertEquals(ShareMode.FILE_ONLY, ShareMode.choose(16_001, 16_001))
    }
}
