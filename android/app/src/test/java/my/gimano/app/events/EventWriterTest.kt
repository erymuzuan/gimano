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
    private val files = EventFileWriter(writer, reader)

    /** Suite-1 framing (read only since suite 2): only the vectors still carry it. */
    private fun suite1File(sealed: SealedPack) =
        EventTransport.FILE_MAGIC + byteArrayOf(EventTransport.FILE_FORMAT_V1, EventTransport.FILE_SUITE_RAW_KEY) + sealed.nonce + sealed.ciphertext

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
        assertEquals(v.s("fileB64u"), B64u.encode(suite1File(sealed)))
        // Suite-2 file: Argon2id(code, salt) key, the vector's own nonce, the same deflated envelope.
        val salt = v.b("fileSaltB64u")
        val fileKey = reader.deriveFileKey(v.s("secretCodeV2"), salt)
        assertEquals(v.s("fileKeyV2B64u"), B64u.encode(fileKey))
        assertFalse("file key independent of the link key", fileKey.contentEquals(key))
        val fileCt = writer.encrypt(fileKey, v.b("fileNonceV2B64u"), v.b("deflatedB64u"))
        assertEquals(v.s("fileV2B64u"), B64u.encode(EventLinks.file(salt, SealedPack(v.b("fileNonceV2B64u"), fileCt))))
        val own = files.sealParts(eventBytes, seed, v.s("secretCodeV2"), salt, v.b("fileNonceV2B64u"))
        assertArrayEquals(fileKey, own.key)
        assertEquals(v.s("signatureB64u"), B64u.encode(own.parts.signature))
        assertEquals(v.s("eventJson"), reader.openFile(own.bytes, v.s("secretCodeV2")).eventBytes.toString(Charsets.UTF_8))
        assertEquals(SecretCodeV2.format(v.s("secretCodeV2")), v.s("secretCodeV2"))
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
        // The writer's file is suite 2: Argon2id over the event's code, fresh salt and nonce each time.
        val code = writer.newSecretCode()
        val f1 = files.seal(bytes, seed, code)
        val f2 = files.seal(bytes, seed, code)
        assertEquals(EventTransport.FILE_SUITE_CODE, f1[5])
        val p1 = EventTransport.parseFile(f1); val p2 = EventTransport.parseFile(f2)
        assertFalse("fresh salt per file", p1.salt!!.contentEquals(p2.salt!!))
        assertFalse("fresh nonce per file", p1.sealed.nonce.contentEquals(p2.sealed.nonce))
        val viaFile = reader.openFile(f1, code)
        assertArrayEquals(bytes, viaFile.eventBytes)
        assertArrayEquals(writer.organizerPub(seed), viaFile.organizerPub)
        assertArrayEquals(bytes, reader.openFile(f2, code.lowercase().replace("-", " ")).eventBytes)
        // The link key is not the file key, and a link-key code (52 chars) is not a suite-2 code.
        try { reader.openFile(f1, SecretCode.encode(key)); fail() } catch (e: EventPackException) { assertEquals(EventPackException.INVALID_SECRET_CODE, e.code) }
    }

    @Test fun oversizedFragmentIsRefusedButFileWorks() {
        val key = ByteArray(32) { 1 }
        val sealed = SealedPack(ByteArray(24), ByteArray(13_000))
        try { EventLinks.fragment(key, sealed); fail() } catch (e: EventPackException) { assertEquals(EventPackException.FRAGMENT_TOO_LONG, e.code) }
        assertTrue(EventLinks.fragmentUnchecked(key, sealed).length > EventLimits.MAX_FRAGMENT_CHARS)
        assertEquals(6 + 16 + 24 + 13_000, EventLinks.file(ByteArray(16), sealed).size)
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
            val code = writer.newSecretCode()
            for (secret in listOf(code, code.replace("-", ""), SecretCode.encode(key), B64u.encode(key), link)) assertFalse("file message leaks $secret", f.contains(secret))
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

        // The file is suite 2 under the event's 8-character code.
        assertTrue(Regex("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}").matches(p1.secretCode))
        assertEquals(EventTransport.FILE_SUITE_CODE, p1.file()[5])
        assertArrayEquals(opened1.eventBytes, reader.openFile(p1.file(), p1.secretCode).eventBytes)

        // Re-share without changes: same rev, new nonce, same key, same secret code (new salt), same signed bytes.
        clock = now.plusSeconds(10)
        val again = host.publish(d.id)
        assertTrue(again.reused)
        assertEquals(p1.secretCode, again.secretCode)
        assertFalse(p1.sealed.nonce.contentEquals(again.sealed.nonce))
        assertFalse(EventTransport.parseFile(p1.file()).salt!!.contentEquals(EventTransport.parseFile(again.file()).salt!!))
        assertArrayEquals(opened1.eventBytes, reader.openLink(again.link(EventLinks.DEFAULT_SITE)!!).eventBytes)

        // Edit → rev 2 with the same id and organizer key.
        clock = now.plusSeconds(3600)
        host.save(d.copy(title = "Kenduri (dikemas kini)"))
        assertTrue(host.load(d.id)!!.changed)
        val p2 = host.publish(d.id)
        assertEquals(2L, p2.plan.rev)
        // A new revision keeps the event's code; the file gets a fresh salt.
        assertEquals(p1.secretCode, p2.secretCode)
        assertFalse(EventTransport.parseFile(p1.file()).salt!!.contentEquals(EventTransport.parseFile(p2.file()).salt!!))
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

    /** Events hosted before file suite 2 (64-byte secrets) get a code on their next file, kept from then on. */
    @Test fun existingHostedEventGetsACodeOnItsNextFile() = runTest {
        var clock = now
        val dao = FakeHostDao()
        val vault = TestVault()
        val host = HostStore(dao, vault, writer, clock = { clock })
        val d = draft()
        host.create(d)
        // Rewind the row to the pre-suite-2 layout: seed ‖ link key only.
        val row = dao.rows.value.getValue(d.id)
        val old = vault.open(row.secrets)
        assertEquals(72, old.size)
        val legacy = old.copyOfRange(0, 64)
        dao.update(row.copy(secrets = vault.seal(legacy)))
        val seedBefore = legacy.copyOfRange(0, 32); val keyBefore = legacy.copyOfRange(32, 64)

        val p1 = host.publish(d.id)
        val upgraded = vault.open(dao.rows.value.getValue(d.id).secrets)
        assertEquals("code stored with the organiser keys", 72, upgraded.size)
        assertArrayEquals("organiser seed kept", seedBefore, upgraded.copyOfRange(0, 32))
        assertArrayEquals("link key kept", keyBefore, upgraded.copyOfRange(32, 64))
        assertEquals(p1.secretCode.replace("-", ""), String(upgraded, 64, 8, Charsets.US_ASCII))
        assertArrayEquals(keyBefore, p1.key)
        assertArrayEquals(reader.openLink(p1.link(EventLinks.DEFAULT_SITE)!!).eventBytes, reader.openFile(p1.file(), p1.secretCode).eventBytes)

        clock = now.plusSeconds(3600)
        host.save(d.copy(title = "Kenduri (dikemas kini)"))
        val p2 = host.publish(d.id)
        assertEquals(2L, p2.plan.rev)
        assertEquals("the code is not regenerated", p1.secretCode, p2.secretCode)
        assertEquals("Kenduri (dikemas kini)", reader.openFile(p2.file(), p1.secretCode).event.title)
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
        val code = writer.newSecretCode()
        val file = files.seal(r.bytes, seed, code)
        assertArrayEquals(r.bytes, reader.openLink(link).eventBytes)
        assertArrayEquals(r.bytes, reader.openFile(file, code).eventBytes)
        val dir = File("/private/tmp/claude-501/-Users-erymuzuan-orca-projects-gimano/4c362b48-62f2-404a-9ad2-ad6fe71abb8c/scratchpad")
        if (!dir.isDirectory) return   // only on the machine that asked for it
        File(dir, "android-sample.manotu-event").writeBytes(file)
        File(dir, "android-sample-link.txt").writeText(buildString {
            appendLine("link: $link")
            appendLine("file: ${File(dir, "android-sample.manotu-event").absolutePath}")
            appendLine("secretCodeV2: $code")
            appendLine("safetyCode: ${EventHashes.safetyCode(writer.organizerPub(seed))}")
            appendLine("revisionHashB64u: ${B64u.encode(EventHashes.revisionHash(r.bytes))}")
            appendLine("eventJson: ${r.bytes.toString(Charsets.UTF_8)}")
        })
    }
}
