package my.gimano.app.events

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.core.events.*
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.identity.Ed25519
import my.gimano.app.data.events.*
import my.gimano.app.nav.NavDestination
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.Deflater

/** In-memory [EventDao] so the store runs on the JVM. */
class FakeEventDao : EventDao {
    val pins = MutableStateFlow<Map<String, EventPinEntity>>(emptyMap())
    val revs = MutableStateFlow<Map<String, EventRevisionEntity>>(emptyMap())
    override fun pinsFlow() = pins.map { it.values.sortedBy(EventPinEntity::expiresAt) }
    override suspend fun pin(id: String) = pins.value[id]
    override suspend fun revisions(id: String) = revs.value.values.filter { it.eventId == id }.sortedWith(compareBy({ it.rev }, { it.importedAt }))
    override fun activeFlow() = revs.map { m -> m.values.filter { it.active } }
    override suspend fun insertPin(p: EventPinEntity) { check(p.eventId !in pins.value); pins.value = pins.value + (p.eventId to p) }
    override suspend fun updatePin(p: EventPinEntity) { pins.value = pins.value + (p.eventId to p) }
    override suspend fun insertRevision(r: EventRevisionEntity) { check(r.hash !in revs.value); revs.value = revs.value + (r.hash to r) }
    override suspend fun activate(id: String, hash: String) { revs.value = revs.value.mapValues { (_, r) -> if (r.eventId == id) r.copy(active = r.hash == hash) else r } }
    override suspend fun replacePayload(id: String, hash: String, payload: ByteArray): Int {
        val r = revs.value[hash]?.takeIf { it.eventId == id && it.payload != null } ?: return 0
        revs.value = revs.value + (hash to r.copy(payload = payload, stopsOnly = false)); return 1
    }
    override suspend fun dropPayloadsBelow(id: String, rev: Long) { revs.value = revs.value.mapValues { (_, r) -> if (r.eventId == id && r.rev < rev) r.copy(payload = null) else r } }
    override suspend fun deleteRevisions(id: String) { revs.value = revs.value.filterValues { it.eventId != id } }
    override suspend fun deletePin(id: String) { pins.value = pins.value - id }
    override suspend fun expiredIds(nowMs: Long) = pins.value.values.filter { it.expiresAt <= nowMs }.map { it.eventId }
    override suspend fun nextExpiry() = pins.value.values.minOfOrNull { it.expiresAt }
}

/** XOR "vault": proves the store never writes plaintext payloads. */
class TestVault : EventVault {
    override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() }
    override fun open(sealed: ByteArray) = seal(sealed)
}

/** Writes packs like the desktop core (sign → envelope → raw deflate → XChaCha20-Poly1305) for revision tests. */
class PackWriter(private val sodium: LazySodiumJava) {
    fun link(eventJson: String, seed: ByteArray, key: ByteArray = ByteArray(32) { 9 }, nonce: ByteArray = ByteArray(24) { 3 }): String {
        val ed = Ed25519(sodium)
        val kp = ed.keyPair(seed)
        val bytes = eventJson.toByteArray()
        val sig = ed.sign(kp, EventPackReader.SIGNATURE_PREFIX + bytes)
        val env = """{"v":1,"event":"${B64u.encode(bytes)}","pub":"${B64u.encode(kp.publicKey)}","sig":"${B64u.encode(sig)}"}""".toByteArray()
        val d = Deflater(9, true).apply { setInput(env); finish() }
        val out = ByteArrayOutputStream(); val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        val m = out.toByteArray()
        val c = ByteArray(m.size + 16)
        check(sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(c, LongArray(1), m, m.size.toLong(), EventPackReader.AD, EventPackReader.AD.size.toLong(), null, nonce, key))
        return "manotu://event#1.${B64u.encode(key)}.${B64u.encode(nonce + c)}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventStoreTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val reader = EventPackReader(sodium)
    private val writer = PackWriter(sodium)
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/kenduri-one-day.json")!!.bufferedReader().readText()).jsonObject
    private val rev1 = v.getValue("eventJson").jsonPrimitive.content
    private val seed = B64u.decode(v.getValue("ed25519SeedB64u").jsonPrimitive.content)!!
    private val rev1Hash = v.getValue("revisionHashB64u").jsonPrimitive.content
    private var now = Instant.parse("2026-11-01T03:00:00Z")

    private fun revN(n: Int, parent: String, title: String = "Kenduri Aqil & Sofea") =
        rev1.replace("\"rev\":1,\"parent\":null", "\"rev\":$n,\"parent\":\"$parent\"").replace("\"Kenduri Aqil & Sofea\"", "\"$title\"")

    private fun store(dao: FakeEventDao = FakeEventDao()) = EventStore(dao, TestVault(), { it() }, clock = { now })
    private fun open(json: String, s: ByteArray = seed) = reader.openLink(writer.link(json, s))
    private suspend fun decide(st: EventStore, p: OpenedPack) = EventImport.decide(ImportCandidate.of(p), st.storedFor(p.event.id), now)

    @Test fun newThenAlreadyThenNewerThenOlder() = runTest {
        val dao = FakeEventDao(); val st = store(dao)
        val p1 = reader.openLink(v.getValue("linkFragment").jsonPrimitive.content)
        val d1 = decide(st, p1)
        assertTrue(d1 is ImportDecision.New)
        assertTrue(st.save(p1, d1))
        assertFalse("payload sealed at rest", dao.revs.value.values.single().payload!!.contentEquals(p1.eventBytes))
        assertEquals(ImportDecision.AlreadyImported, decide(st, p1))
        assertEquals("Kenduri Aqil & Sofea", st.load(p1.event.id)!!.plan.title)
        assertEquals(v.getValue("safetyCode").jsonPrimitive.content, st.load(p1.event.id)!!.safetyCode)

        val p2 = open(revN(2, rev1Hash, "Kenduri (kemas kini)"))
        val d2 = decide(st, p2)
        assertEquals(ImportDecision.Newer(1, ParentCheck.MATCHES), d2)
        st.save(p2, d2)
        assertEquals("Kenduri (kemas kini)", st.load(p1.event.id)!!.plan.title)
        assertNull("superseded payload dropped", dao.revs.value[rev1Hash]!!.payload)
        assertEquals(ImportDecision.AlreadyImported, decide(st, p1))   // its hash is remembered
        assertEquals(1, st.events().first().size)

        // rev 4 whose rev 3 we never saw
        val p4 = open(revN(4, B64u.encode(ByteArray(32))))
        assertEquals(ImportDecision.Newer(2, ParentCheck.MISSED_REVISIONS), decide(st, p4))
        // a rev 3 that doesn't follow our rev 2
        assertEquals(ImportDecision.Newer(2, ParentCheck.MISMATCH), decide(st, open(revN(3, rev1Hash))))
        // older than stored
        st.save(p4, decide(st, p4))
        assertEquals(ImportDecision.Older(4), decide(st, open(revN(3, p2.revisionHashB64u))))
    }

    @Test fun forkKeepsBothAndUserCanSwitch() = runTest {
        val st = store()
        val p1 = open(rev1); st.save(p1, decide(st, p1))
        val a = open(revN(2, rev1Hash, "A")); st.save(a, decide(st, a))
        val b = open(revN(2, rev1Hash, "B"))
        assertEquals(ImportDecision.Fork(2), decide(st, b))
        st.save(b, decide(st, b))
        val shown = st.load(p1.event.id)!!
        assertEquals("B", shown.plan.title)
        assertEquals(listOf(a.revisionHashB64u), shown.alternatives)
        st.choose(p1.event.id, a.revisionHashB64u)
        assertEquals("A", st.load(p1.event.id)!!.plan.title)
    }

    @Test fun differentOrganizerIsRejectedAndNothingStored() = runTest {
        val st = store()
        val p1 = open(rev1); st.save(p1, decide(st, p1))
        val other = open(revN(2, rev1Hash), ByteArray(32) { 42 })
        val d = decide(st, other)
        assertEquals(ImportDecision.DifferentOrganizer, d)
        assertFalse(st.save(other, d))
        assertEquals(1L, st.load(p1.event.id)!!.plan.rev)
    }

    @Test fun purgedAtExpiryAndDeleteRemovesEverything() = runTest {
        val dao = FakeEventDao(); val st = store(dao)
        val p1 = open(rev1); st.save(p1, decide(st, p1))
        assertEquals(Instant.parse("2026-11-16T08:00:00Z"), st.nextExpiry())
        now = Instant.parse("2026-11-16T07:59:59Z")
        assertEquals(0, st.purgeExpired())
        now = Instant.parse("2026-11-16T08:00:00Z")
        assertNull("expired events are never shown", st.load(p1.event.id))
        assertEquals(1, st.purgeExpired())
        assertTrue(dao.pins.value.isEmpty() && dao.revs.value.isEmpty())
        now = Instant.parse("2026-11-01T03:00:00Z")
        st.save(p1, decide(st, p1))
        st.delete(p1.event.id)
        assertTrue(dao.pins.value.isEmpty() && dao.revs.value.isEmpty())
        assertNull(st.storedFor(p1.event.id))
    }

    @Test fun importerFlowWithSuite2File() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val st = store()
        val imp = EventImporter(scope, reader, st, clock = { now }, work = dispatcher)
        val k = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/kenduri-one-day.json")!!.bufferedReader().readText()).jsonObject
        now = Instant.parse(k.getValue("now").jsonPrimitive.content)
        imp.openFile(B64u.decode(k.getValue("fileV2B64u").jsonPrimitive.content)!!)
        assertEquals(EventImportUi.NeedCode(), imp.ui.value)
        // A look-alike: rejected before Argon2id, the dialog stays.
        imp.submitCode("6BJT-PBD0")
        assertEquals("Membuka… while the key is derived", EventImportUi.NeedCode(opening = true), imp.ui.value)
        scope.advanceUntilIdle()
        assertEquals(EventImportUi.NeedCode(invalid = true), imp.ui.value)
        // The old 52-character code is not this file's code.
        imp.submitCode(k.getValue("secretCode").jsonPrimitive.content); scope.advanceUntilIdle()
        assertEquals(EventImportUi.NeedCode(invalid = true), imp.ui.value)
        // A well-formed wrong code: derived, then decrypt fails.
        imp.submitCode("T9H7-WEMG"); scope.advanceUntilIdle()
        assertEquals(EventImportUi.NeedCode(wrong = true), imp.ui.value)
        imp.submitCode(k.getValue("secretCodeV2").jsonPrimitive.content.lowercase()); scope.advanceUntilIdle()
        val review = imp.ui.value as EventImportUi.Review
        assertTrue(review.decision is ImportDecision.New)
        assertEquals(k.getValue("safetyCode").jsonPrimitive.content, review.safetyCode)
    }

    @Test fun importerFlowWithFileAndCode() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val st = store()
        val imp = EventImporter(scope, reader, st, clock = { now }, work = dispatcher)
        val ov = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/negative-oversized-fragment.json")!!.bufferedReader().readText()).jsonObject
        imp.openLink(ov.getValue("linkFragment").jsonPrimitive.content); scope.advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.TOO_LONG), imp.ui.value)
        imp.dismiss()
        imp.openFile(ByteArray(10)); assertEquals(EventImportUi.Notice(EventNotice.NOT_A_FILE), imp.ui.value)
        imp.dismiss()
        val file = B64u.decode(ov.getValue("fileB64u").jsonPrimitive.content)!!
        imp.openFile(file); assertEquals(EventImportUi.NeedCode(), imp.ui.value)
        imp.submitCode(SecretCode.encode(ByteArray(32))); scope.advanceUntilIdle()
        assertEquals(EventImportUi.NeedCode(wrong = true), imp.ui.value)
        imp.submitCode(ov.getValue("secretCode").jsonPrimitive.content); scope.advanceUntilIdle()
        val review = imp.ui.value as EventImportUi.Review
        assertTrue(review.decision is ImportDecision.New)
        imp.confirm(); scope.advanceUntilIdle()
        assertEquals(EventImportUi.Idle, imp.ui.value)
        kotlinx.coroutines.runBlocking { assertEquals(ov.getValue("fileEventId").jsonPrimitive.content, st.load(ov.getValue("fileEventId").jsonPrimitive.content)!!.plan.id) }
        // expired invitation: nothing imported
        val ex = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/negative-expired.json")!!.bufferedReader().readText()).jsonObject
        now = Instant.parse(ex.getValue("now").jsonPrimitive.content)
        imp.openLink("https://www.bukitbunga.com/gimano/a/#" + ex.getValue("linkFragment").jsonPrimitive.content); scope.advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.EXPIRED), imp.ui.value)
        imp.dismiss()
        val tampered = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/negative-bad-signature.json")!!.bufferedReader().readText()).jsonObject
        imp.openLink(tampered.getValue("linkFragment").jsonPrimitive.content); scope.advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.CANNOT_OPEN), imp.ui.value)
    }
}

class EventPrivacyTest {
    @Test fun eventStopRoutingCarriesCoordinatesOnly() {
        val dto = NavDestination("Parkir tetamu", "Jalan Rahsia 1", GeoPoint(2.9866, 101.7942), "osm:1", eventStop = true).toDto()
        assertNull(dto.name); assertNull(dto.placeId)
        assertEquals(2.9866, dto.lat, 0.0); assertEquals(101.7942, dto.lng, 0.0)
        assertEquals("Rumah", NavDestination("Rumah", "", GeoPoint(3.0, 101.0), null).toDto().name)
    }

    @Test fun eventsDatabaseIsNotInAnyBackup() {
        for (name in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val f = listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name")).first { it.exists() }
            val includes = Regex("<include[^>]*path=\"([^\"]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toList()
            assertTrue("$name must keep using include-lists", includes.isNotEmpty())
            assertFalse("$name must not back up events", includes.any { it.contains("event") || it == "." || it.isEmpty() })
        }
        assertEquals("manotu-events.db", EventsDatabase.DB_NAME)
    }
}

class EventScheduleTest {
    private val plan = EventParser.parse(
        Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/ride-two-day.json")!!.bufferedReader().readText())
            .jsonObject.getValue("eventJson").jsonPrimitive.content.toByteArray(),
    )

    @Test fun nextStopFollowsTimes() {
        // Before the ride: the flag-off session at the start.
        assertEquals("Mula: Dataran Kayuh", EventSchedule.nextStop(plan, Instant.parse("2026-11-20T00:00:00Z"))!!.label)
        // After CP1's window: the next session's stop (dinner at the homestay).
        assertEquals("Homestay Bukit Fiksyen", EventSchedule.nextStop(plan, Instant.parse("2026-11-21T02:00:00Z"))!!.label)
        // All over: the last stop.
        assertEquals("Tamat: Dataran Kayuh", EventSchedule.nextStop(plan, Instant.parse("2026-11-23T00:00:00Z"))!!.label)
        assertEquals("Asia/Kuala_Lumpur", EventSchedule.zone(plan).id)
        assertTrue(EventSchedule.mapPoints(plan).size >= plan.stops.size + 7)
    }
}
