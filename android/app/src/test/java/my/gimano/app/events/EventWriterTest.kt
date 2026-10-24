package my.gimano.app.events

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.core.events.*
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.events.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** In-memory [HostEventDao]. */
class FakeHostDao : HostEventDao {
    val rows = MutableStateFlow<Map<String, HostEventEntity>>(emptyMap())
    override fun allFlow() = rows.map { it.values.sortedByDescending(HostEventEntity::updatedAt) }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun insert(e: HostEventEntity) { check(e.eventId !in rows.value); rows.value = rows.value + (e.eventId to e) }
    override suspend fun update(e: HostEventEntity) { rows.value = rows.value + (e.eventId to e) }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun deleteExpired(nowMs: Long): Int {
        val gone = rows.value.values.filter { it.expiresAt != null && it.expiresAt <= nowMs }.map { it.eventId }
        rows.value = rows.value - gone.toSet(); return gone.size
    }
    override suspend fun nextExpiry() = rows.value.values.mapNotNull { it.expiresAt }.minOrNull()
}

/** The host (writer) side against the shared vectors and the existing reader. */
class EventWriterTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val reader = EventPackReader(sodium)
    private val writer = EventPackWriter(sodium)

    private fun vector(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/$name.json")!!.bufferedReader().use { it.readText() }).jsonObject
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.b(k: String) = B64u.decode(s(k)) ?: error("$k is not strict b64u")

    private fun writerLayers(name: String) {
        val v = vector(name)
        val seed = v.b("ed25519SeedB64u")
        val key = v.b("keyB64u")
        val nonce = v.b("nonceB64u")
        val eventBytes = v.b("eventB64u")
        // Ed25519 public key and signature from the seed.
        assertEquals(v.s("organizerPubB64u"), B64u.encode(writer.organizerPub(seed)))
        assertEquals(v.s("signatureB64u"), B64u.encode(writer.sign(seed, eventBytes)))
        // Envelope JSON from the event bytes.
        val env = EventPackWriter.envelopeJson(eventBytes, v.b("organizerPubB64u"), v.b("signatureB64u"))
        assertEquals(v.s("envelopeJson"), env.toString(Charsets.UTF_8))
        assertArrayEquals(v.b("envelopeB64u"), env)
        // Encrypting the vector's deflated bytes with its key + nonce gives its ciphertext.
        val ct = writer.encrypt(key, nonce, v.b("deflatedB64u"))
        assertEquals(v.s("ciphertextB64u"), B64u.encode(ct))
        // Link fragment / link / file framing.
        val sealed = SealedPack(nonce, ct)
        assertEquals(v.s("linkFragment"), EventLinks.fragment(key, sealed))
        assertEquals(v.s("link"), EventLinks.link(v.s("site"), key, sealed))
        assertEquals(v.s("fileB64u"), B64u.encode(EventLinks.file(sealed)))
        // Codes and revision hash.
        assertEquals(v.s("secretCode"), SecretCode.encode(key))
        assertEquals(v.s("safetyCode"), EventHashes.safetyCode(writer.organizerPub(seed)))
        assertEquals(v.s("revisionHashB64u"), B64u.encode(EventHashes.revisionHash(eventBytes)))
        // Own seal with the vector nonce: deterministic layers equal, own DEFLATE round-trips through the reader.
        val parts = writer.sealParts(eventBytes, seed, key, nonce)
        assertEquals(v.s("signatureB64u"), B64u.encode(parts.signature))
        assertEquals(v.s("envelopeJson"), parts.envelopeBytes.toString(Charsets.UTF_8))
        assertArrayEquals(parts.envelopeBytes, RawInflate.inflate(parts.deflated))
        val opened = reader.open(key, parts.sealed)
        assertEquals(v.s("eventJson"), opened.eventBytes.toString(Charsets.UTF_8))
        // The JSON writer reproduces the reference event bytes from the parsed model, byte for byte.
        assertEquals(v.s("eventJson"), EventJsonWriter.write(EventParser.parse(eventBytes)).toString(Charsets.UTF_8))
    }

    @Test fun kenduriVectorWriterLayers() = writerLayers("kenduri-one-day")
    @Test fun rideVectorWriterLayers() = writerLayers("ride-two-day")

    @Test fun freshSealOpensAsLinkAndFile() {
        val v = vector("ride-two-day")
        val seed = writer.newSeed(); val key = writer.newPackKey()
        val bytes = v.b("eventB64u")
        val a = writer.seal(bytes, seed, key)
        val b = writer.seal(bytes, seed, key)
        assertFalse("fresh nonce per seal", a.nonce.contentEquals(b.nonce))
        val viaLink = reader.openLink(EventLinks.link(EventLinks.DEFAULT_SITE, key, a))
        assertArrayEquals(bytes, viaLink.eventBytes)
        assertArrayEquals(writer.organizerPub(seed), viaLink.organizerPub)
        val viaFile = reader.openFile(EventLinks.file(a), SecretCode.encode(key))
        assertArrayEquals(bytes, viaFile.eventBytes)
    }

    @Test fun oversizedFragmentIsRefusedButFileWorks() {
        val key = ByteArray(32) { 1 }
        val sealed = SealedPack(ByteArray(24), ByteArray(13_000))
        try { EventLinks.fragment(key, sealed); fail() } catch (e: EventPackException) { assertEquals(EventPackException.FRAGMENT_TOO_LONG, e.code) }
        assertTrue(EventLinks.fragmentUnchecked(key, sealed).length > EventLimits.MAX_FRAGMENT_CHARS)
        assertEquals(6 + 24 + 13_000, EventLinks.file(sealed).size)
    }

    // --- Event JSON writer --------------------------------------------------------------------------------

    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private val now = Instant.parse("2026-11-01T03:00:00Z")

    private fun draft() = HostDraft(
        id = EventIds.newEventId(), template = "kenduri", title = "Kenduri \"Aqil\" & Sofea\nRumah 2", lang = "ms", tz = "Asia/Kuala_Lumpur",
        date = "2026-11-15", startTime = "11:00", endTime = "16:00",
        stops = listOf(
            HostStop(EventIds.newItemId(), "parking", "Parkir tetamu", 2.98712345, 101.793, entranceLat = 2.9866, entranceLng = 101.7942,
                notes = "Padang sekolah", audience = "Semua", organizerNote = "RAHSIA-PENGANJUR"),
            HostStop(EventIds.newItemId(), "venue", "Dewan", 2.9884, 101.7919),
        ),
        organizerNotes = "NOTA-PERIBADI",
    )

    @Test fun jsonWriterOmitsOptionalsAndWritesNullParent() {
        val d = draft()
        val p = EventRevisions.prepare(d, null, now)
        val json = p.bytes.toString(Charsets.UTF_8)
        assertEquals(1L, p.plan.rev)
        assertTrue(json.startsWith("""{"id":"${d.id}","rev":1,"parent":null,"publishedAt":"2026-11-01T03:00:00Z","template":"kenduri","""))
        assertTrue(json.contains(""""start":"2026-11-15T11:00:00+08:00","end":"2026-11-15T16:00:00+08:00","expiresAt":"2026-11-16T08:00:00Z""""))
        assertTrue("serde escaping", json.contains(""""title":"Kenduri \"Aqil\" & Sofea\nRumah 2""""))
        assertTrue("6 decimals", json.contains(""""lat":2.987123,"lng":101.793,"entrance":{"lat":2.9866,"lng":101.7942}"""))
        for (absent in listOf("description", "organizer", "sessions", "routes", "arrive", "dwellMin")) assertFalse(absent, json.contains("\"$absent\""))
        assertFalse("second stop has no notes", json.substringAfter("\"Dewan\"").contains("notes"))
        for (secret in listOf("RAHSIA-PENGANJUR", "NOTA-PERIBADI", "organizerNote")) assertFalse("organizer-only $secret", json.contains(secret))
        val parsed = EventParser.parse(p.bytes)
        assertNull(parsed.parent)
        assertEquals(2, parsed.stops.size)
        assertEquals(GeoPoint(2.9866, 101.7942), parsed.stops[0].navigationTarget)
        assertEquals(json, EventJsonWriter.write(parsed).toString(Charsets.UTF_8))
        // Alias written only when given.
        val withAlias = EventRevisions.prepare(d.copy(alias = " Keluarga Aqil "), null, now).bytes.toString(Charsets.UTF_8)
        assertTrue(withAlias.contains(""""expiresAt":"2026-11-16T08:00:00Z","organizer":{"alias":"Keluarga Aqil"},"stops":["""))
    }

    @Test fun draftRulesCatchLimits() {
        val bad = draft().copy(title = " ", stops = listOf(HostStop("x", label = "", lat = 1.0, lng = 1.0, notes = "n".repeat(501))))
        val codes = HostDraftRules.issues(bad, now).map { it.code }
        assertTrue(codes.containsAll(listOf(DraftIssue.Code.TITLE_REQUIRED, DraftIssue.Code.STOP_LABEL_REQUIRED, DraftIssue.Code.STOP_NOTES_TOO_LONG)))
        assertTrue(HostDraftRules.issues(draft().copy(stops = emptyList()), now).any { it.code == DraftIssue.Code.NO_STOPS })
        assertTrue(HostDraftRules.issues(draft(), Instant.parse("2026-11-17T00:00:00Z")).any { it.code == DraftIssue.Code.EXPIRED })
        assertTrue(HostDraftRules.issues(draft(), now).isEmpty())
        // Overnight: end before start means the next day.
        assertEquals("2026-11-16T01:00+08:00", draft().copy(startTime = "20:00", endTime = "01:00").end().toString())
        assertEquals("Asia/Kuala_Lumpur", HostDraft.zoneName(ZoneId.of("GMT+08:00")))
    }

    @Test fun revisionsBumpRevAndChainParent() {
        val d = draft()
        val r1 = EventRevisions.prepare(d, null, now)
        // Nothing guest-visible changed (organizer notes don't count): the same bytes are sealed again.
        val same = EventRevisions.prepare(d.copy(organizerNotes = "lain"), r1.bytes, now.plusSeconds(60))
        assertTrue(same.reused)
        assertArrayEquals(r1.bytes, same.bytes)
        val later = now.plusSeconds(3600)
        val r2 = EventRevisions.prepare(d.copy(stops = d.stops.map { if (it.kind == "parking") it.copy(lat = 2.99) else it }), r1.bytes, later)
        assertFalse(r2.reused)
        assertEquals(2L, r2.plan.rev)
        assertEquals(d.id, r2.plan.id)
        assertEquals(B64u.encode(EventHashes.revisionHash(r1.bytes)), r2.plan.parent)
        assertEquals(later, r2.plan.publishedAt)
        // A guest holding rev 1 sees it as the next revision with a matching parent.
        val c2 = ImportCandidate(r2.plan.rev, B64u.decode(r2.plan.parent!!), r2.plan.expiresAt, ByteArray(32), EventHashes.revisionHash(r2.bytes))
        val stored = StoredEvent(ByteArray(32), listOf(StoredRevision(1, EventHashes.revisionHash(r1.bytes))))
        assertEquals(ImportDecision.Newer(1, ParentCheck.MATCHES), EventImport.decide(c2, stored, now))
        // What the guest is shown.
        val diff = EventDiff.diff(r1.plan, r2.plan)
        assertFalse(diff.timeChanged)
        assertEquals(1, diff.stops.size)
        assertTrue(diff.stops.single().moved)
        assertNotNull(diff.targetChanged(d.stops[0].id))
        assertNull(diff.targetChanged(d.stops[1].id))
    }

    @Test fun diffFindsAddedRemovedRenamedAndTime() {
        val d = draft()
        val a = HostDraftRules.toPlan(d, 1, null, now)
        val b = HostDraftRules.toPlan(d.copy(startTime = "12:00",
            stops = listOf(d.stops[1].copy(label = "Dewan Besar"), HostStop("bmV3c3RvcA", "meal", "Makan", 2.9, 101.7))), 2, "x", now)
        val diff = EventDiff.diff(a, b)
        assertTrue(diff.timeChanged)
        assertTrue(diff.stops.any { it.removed && it.before!!.id == d.stops[0].id })
        assertTrue(diff.stops.any { it.added && it.after!!.label == "Makan" })
        assertTrue(diff.stops.any { it.renamed && !it.moved && it.after!!.label == "Dewan Besar" })
        assertNotNull("removed stop counts as a changed target", diff.targetChanged(d.stops[0].id))
        assertTrue(EventDiff.diff(a, a).isEmpty)
    }

    // --- Share messages -----------------------------------------------------------------------------------

    @Test fun inviteMessagesCarryLinkAndDownloadButNeverTheCode() {
        val download = "https://manotu.app/download/"
        val key = writer.newPackKey()
        val r = EventRevisions.prepare(draft().copy(title = "Kenduri Aqil & Sofea"), null, now)
        val sealed = writer.seal(r.bytes, writer.newSeed(), key)
        val link = EventLinks.link(EventLinks.DEFAULT_SITE, key, sealed)
        val ms = InviteMessages.linkMessage(r.plan, link, download)
        assertTrue(ms.contains(link)); assertTrue(ms.contains(download))
        assertTrue(ms.startsWith("Jemputan: Kenduri Aqil & Sofea\n"))
        assertTrue(ms.contains("11:00–16:00"))
        val en = InviteMessages.linkMessage(r.plan.copy(lang = "en"), link, download)
        assertTrue(en.startsWith("Invitation: ") && en.contains(link) && en.contains(download))
        for (p in listOf(r.plan, r.plan.copy(lang = "en"))) {
            val f = InviteMessages.fileMessage(p, download)
            assertTrue(f.contains(download))
            val code = SecretCode.encode(key)
            for (secret in listOf(code, code.replace("-", ""), B64u.encode(key), link)) assertFalse("file message leaks $secret", f.contains(secret))
            assertTrue(InviteMessages.codeMessage(p, code).contains(code))
        }
        assertEquals("Kenduri-Aqil-Sofea.manotu-event", InviteMessages.fileName(r.plan))
    }

    // --- Host store, own link, guest update ---------------------------------------------------------------

    @Test fun hostStorePublishesRevisionsAndOwnLinkIsNotAnImport() = runTest {
        var clock = now
        val dao = FakeHostDao()
        val host = HostStore(dao, TestVault(), writer, clock = { clock })
        val d = draft()
        host.create(d)
        assertEquals(1, dao.rows.value.size)
        assertFalse("secrets sealed", dao.rows.value.values.single().secrets.size == 0)
        val p1 = host.publish(d.id)
        assertEquals(1L, p1.plan.rev)
        val opened1 = reader.openLink(p1.link(EventLinks.DEFAULT_SITE)!!)
        assertTrue(host.isOwn(d.id, opened1.organizerPub))
        assertFalse(host.isOwn(d.id, ByteArray(32)))
        assertEquals(p1.plan.expiresAt.toEpochMilli(), dao.rows.value[d.id]!!.expiresAt)

        // Re-share without changes: same rev, new nonce, same key (same secret code), same signed bytes.
        clock = now.plusSeconds(10)
        val again = host.publish(d.id)
        assertTrue(again.reused)
        assertEquals(p1.secretCode, again.secretCode)
        assertFalse(p1.sealed.nonce.contentEquals(again.sealed.nonce))
        assertArrayEquals(opened1.eventBytes, reader.openLink(again.link(EventLinks.DEFAULT_SITE)!!).eventBytes)

        // Edit → rev 2 with the same id and organizer key.
        clock = now.plusSeconds(3600)
        host.save(d.copy(title = "Kenduri (dikemas kini)"))
        assertTrue(host.load(d.id)!!.changed)
        val p2 = host.publish(d.id)
        assertEquals(2L, p2.plan.rev)
        val opened2 = reader.openFile(p2.file(), p2.secretCode)
        assertArrayEquals(opened1.organizerPub, opened2.organizerPub)
        assertEquals(opened1.revisionHashB64u, opened2.event.parent)
        assertFalse(host.load(d.id)!!.changed)

        // The host opening their own link: "this is your event", never an import.
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val guestStore = EventStore(FakeEventDao(), TestVault(), { it() }, clock = { clock })
        val imp = EventImporter(scope, reader, guestStore, clock = { clock }, work = dispatcher, isOwn = { id, pub -> host.isOwn(id, pub) })
        imp.openLink(p2.link(EventLinks.DEFAULT_SITE)!!); scope.advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.OWN_EVENT, d.id), imp.ui.value)
        assertNull(guestStore.storedFor(d.id))

        // A guest (no host record): rev 1, then rev 2 shows what changed against rev 1.
        val guest = EventImporter(scope, reader, guestStore, clock = { clock }, work = dispatcher)
        guest.openLink(p1.link(EventLinks.DEFAULT_SITE)!!); scope.advanceUntilIdle()
        assertTrue((guest.ui.value as EventImportUi.Review).decision is ImportDecision.New)
        guest.confirm(); scope.advanceUntilIdle()
        guest.openFile(p2.file()); guest.submitCode(p2.secretCode); scope.advanceUntilIdle()
        val review = guest.ui.value as EventImportUi.Review
        assertEquals(ImportDecision.Newer(1, ParentCheck.MATCHES), review.decision)
        assertEquals("Kenduri \"Aqil\" & Sofea\nRumah 2", review.previous!!.title)
        assertTrue(EventDiff.diff(review.previous!!, review.plan).infoChanged)
        guest.confirm(openAfter = false); scope.advanceUntilIdle()
        assertEquals("Kenduri (dikemas kini)", guestStore.load(d.id)!!.plan.title)

        // Keys and draft go at the published expiresAt.
        clock = p2.plan.expiresAt
        assertEquals(1, host.purgeExpired())
        assertNull(host.load(d.id))
    }

    @Test fun editorValidatesBeforePublishing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = HostStore(FakeHostDao(), TestVault(), writer, clock = { now })
        val ed = HostEditor(TestScope(dispatcher), host, clock = { now })
        ed.newEvent("ms", ZoneId.of("Asia/Kuala_Lumpur"), LocalDate.parse("2026-11-08"))
        assertFalse(ed.publish())
        assertTrue(ed.issues.value.any { it.code == DraftIssue.Code.TITLE_REQUIRED })
        ed.update { it.copy(title = "Majlis") }
        ed.addStop(3.1, 101.6, "Rumah")
        assertNotNull(ed.editingStop.value)
        assertTrue(ed.publish())
        assertEquals(1L, ed.publication.value!!.plan.rev)
        assertEquals("2026-11-15", ed.draft.value!!.date)
    }

    /** A freshly sealed sample (link + file + code) for cross-checking with the Rust core and the web viewer. */
    @Test fun writeSampleForCrossCheck() {
        val today = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur"))
        val d = draft().copy(title = "Contoh Android: Kenduri Aqil & Sofea", alias = "Keluarga Aqil", date = today.plusDays(14).toString(),
            description = "Sampel daripada penulis Android (EventWriterTest).")
        val seed = writer.newSeed(); val key = writer.newPackKey()
        val r = EventRevisions.prepare(d, null, Instant.now())
        val sealed = writer.seal(r.bytes, seed, key)
        val link = EventLinks.link(EventLinks.DEFAULT_SITE, key, sealed)
        val file = EventLinks.file(sealed)
        assertArrayEquals(r.bytes, reader.openLink(link).eventBytes)
        assertArrayEquals(r.bytes, reader.openFile(file, SecretCode.encode(key)).eventBytes)
        val dir = File("/private/tmp/claude-501/-Users-erymuzuan-orca-projects-gimano/4c362b48-62f2-404a-9ad2-ad6fe71abb8c/scratchpad")
        if (!dir.isDirectory) return   // only on the machine that asked for it
        File(dir, "android-sample.manotu-event").writeBytes(file)
        File(dir, "android-sample-link.txt").writeText(buildString {
            appendLine("link: $link")
            appendLine("file: ${File(dir, "android-sample.manotu-event").absolutePath}")
            appendLine("secretCode: ${SecretCode.encode(key)}")
            appendLine("safetyCode: ${EventHashes.safetyCode(writer.organizerPub(seed))}")
            appendLine("revisionHashB64u: ${B64u.encode(EventHashes.revisionHash(r.bytes))}")
            appendLine("eventJson: ${r.bytes.toString(Charsets.UTF_8)}")
        })
    }
}
