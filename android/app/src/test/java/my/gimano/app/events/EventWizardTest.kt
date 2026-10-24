package my.gimano.app.events

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.core.events.*
import my.gimano.app.data.events.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Suite-3 open files, guest info blocks, new templates, and the wizard's model (docs/events.md §4.1, §5.2, §5.2.1). */
class EventWizardTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val reader = EventPackReader(sodium)
    private val writer = EventPackWriter(sodium)
    private val now = Instant.parse("2026-11-01T03:00:00Z")

    private fun vector(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("event-vectors/$name.json")!!.bufferedReader().use { it.readText() }).jsonObject
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    private fun draft() = HostDraft(
        id = EventIds.newEventId(), template = "kenduri", title = "Kenduri Aqil & Sofea", lang = "ms", tz = "Asia/Kuala_Lumpur",
        date = "2026-11-15", startTime = "11:00", endTime = "16:00",
        stops = listOf(HostStop(EventIds.newItemId(), "venue", "Dewan", 2.9884, 101.7919)),
    )

    // --- Suite 3 --------------------------------------------------------------------------------------------

    /** The open file built from the kenduri link's key and nonce ‖ ciphertext opens with no code to the link's bytes. */
    @Test fun suite3FromTheKenduriLink() {
        val v = vector("kenduri-one-day")
        val fragment = v.s("linkFragment")
        val parts = fragment.split('.')
        val key = B64u.decode(parts[1])!!
        val data = B64u.decode(parts[2])!!
        val file = "MNTE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01, 0x03) + key + data
        assertEquals(6 + 32 + data.size, file.size)
        assertFalse(EventTransport.fileNeedsCode(file))
        val viaFile = reader.openFileWithoutCode(file)
        val viaLink = reader.openLink(fragment)
        assertArrayEquals(viaLink.eventBytes, viaFile.eventBytes)
        assertEquals(v.s("eventJson"), viaFile.eventBytes.toString(Charsets.UTF_8))
        assertEquals(v.s("safetyCode"), viaFile.safetyCode)
        if ("fileV3B64u" in v) assertArrayEquals("byte-identical to the vector", B64u.decode(v.s("fileV3B64u")), file)
        // The writer frames the same bytes.
        val (k, sealed) = EventTransport.parseLink(fragment)
        assertArrayEquals(file, EventLinks.openFile(k, sealed))
        // Tampering with the embedded key fails like a wrong link key.
        val bad = file.copyOf().also { it[6] = (it[6] + 1).toByte() }
        try { reader.openFileWithoutCode(bad); fail() } catch (e: EventPackException) { assertEquals(EventPackException.DECRYPT, e.code) }
    }

    @Test fun suiteDecidesWhetherToAskForACode() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val store = EventStore(FakeEventDao(), TestVault(), { it() }, clock = { now })
        val imp = EventImporter(scope, reader, store, clock = { now }, work = dispatcher)
        val v = vector("kenduri-one-day")
        val (k, sealed) = EventTransport.parseLink(v.s("linkFragment"))
        // Suite 3: straight to the review sheet, never the code dialog.
        imp.openFile(EventLinks.openFile(k, sealed))
        assertNotEquals(EventImportUi.NeedCode(), imp.ui.value)
        scope.advanceUntilIdle()
        val review = imp.ui.value as EventImportUi.Review
        assertTrue(review.decision is ImportDecision.New)
        assertEquals(2, review.plan.info.size)
        imp.dismiss()
        // Suite 2 and suite 1: the code dialog.
        imp.openFile(B64u.decode(v.s("fileV2B64u"))!!)
        assertEquals(EventImportUi.NeedCode(), imp.ui.value)
        imp.dismiss()
        imp.openFile(B64u.decode(v.s("fileB64u"))!!)
        assertEquals(EventImportUi.NeedCode(), imp.ui.value)
        imp.dismiss()
        // Anything else shared as octet-stream: graceful notices, nothing asked.
        imp.openFile("%PDF-1.7 not an event".toByteArray())
        assertEquals(EventImportUi.Notice(EventNotice.NOT_A_FILE), imp.ui.value)
        imp.openFile(EventTransport.FILE_MAGIC + byteArrayOf(1, 9) + ByteArray(80))
        assertEquals(EventImportUi.Notice(EventNotice.NEEDS_UPDATE), imp.ui.value)
        imp.openFile(EventTransport.FILE_MAGIC + byteArrayOf(1, 3) + ByteArray(40))
        assertEquals(EventImportUi.Notice(EventNotice.NOT_A_FILE), imp.ui.value)
    }

    @Test fun defaultPublicationIsTheOpenFileAndSensitiveAddsTheCodeFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = HostStore(FakeHostDao(), TestVault(), writer, clock = { now }, work = dispatcher)
        val ed = HostEditor(TestScope(dispatcher), host, clock = { now })
        ed.newEvent("ms", ZoneId.of("Asia/Kuala_Lumpur"), LocalDate.parse("2026-11-08"))
        ed.update { draft().copy(id = it.id, info = listOf(HostInfo("a", "dress", "Tema pastel"))) }
        assertTrue(ed.publish())
        val p = ed.publication.value!!
        assertNull("no Argon2id by default", p.codeFile())
        val file = p.file()
        assertEquals(EventTransport.FILE_SUITE_OPEN, file[5])
        val link = reader.openLink(p.link(EventLinks.DEFAULT_SITE)!!)
        assertArrayEquals(link.eventBytes, reader.openFileWithoutCode(file).eventBytes)
        // Same key, nonce and ciphertext as the link, after the 6 header bytes.
        assertArrayEquals(file.copyOfRange(6, file.size), p.key + p.sealed.nonce + p.sealed.ciphertext)
        assertEquals(listOf(InfoBlock(InfoKind.DRESS, "Tema pastel")), link.event.info)
        // "Acara sensitif" at the share screen: a suite-2 file of the same revision under the event's code.
        ed.setSensitive(true)
        advanceUntilIdle()
        val code = ed.publication.value!!.codeFile()!!
        assertEquals(EventTransport.FILE_SUITE_CODE, code[5])
        assertArrayEquals(link.eventBytes, reader.openFile(code, p.secretCode).eventBytes)
        assertTrue(ed.draft.value!!.sensitive)
        // The default message carries neither key, link nor code.
        val msg = InviteMessages.openFileMessage(p.plan, "https://manotu.app/download/")
        assertEquals("Jemputan: Kenduri Aqil & Sofea\nAhd, 15 Nov · 11:00–16:00\nKetik fail untuk buka di Manotu.\nBelum ada Manotu? https://manotu.app/download/", msg)
        for (secret in listOf(p.secretCode, p.link(EventLinks.DEFAULT_SITE)!!, B64u.encode(p.key))) assertFalse(msg.contains(secret))
        assertTrue(InviteMessages.openFileMessage(p.plan.copy(lang = "en"), "u").startsWith("Invitation: Kenduri Aqil & Sofea\nSun, 15 Nov · 11:00–16:00\nTap the file"))
    }

    // --- Info blocks and templates --------------------------------------------------------------------------

    @Test fun infoBlocksAndAgendaAreWrittenInOrder() {
        val d = draft().copy(info = listOf(
            HostInfo("1", HostInfo.AGENDA, "11:00 Ketibaan · 12:30 Jamuan\n14:00–15:00 Persandingan"),
            HostInfo("2", "dress", "  Tema pastel.\nBaju kurung.  "),
            HostInfo("3", "weather", "Bawa payung"),
            HostInfo("4", "bring", "   "),
        ))
        assertTrue(HostDraftRules.issues(d, now).isEmpty())
        val r = EventRevisions.prepare(d, null, now)
        val json = r.bytes.toString(Charsets.UTF_8)
        assertTrue(json, json.contains(""""info":[{"kind":"dress","text":"Tema pastel.\nBaju kurung."},{"kind":"other","text":"Bawa payung"}]"""))
        assertTrue("info after sessions", json.indexOf("\"sessions\"") < json.indexOf("\"info\""))
        val p = EventParser.parse(r.bytes)
        assertEquals(listOf("Ketibaan", "Jamuan", "Persandingan"), p.sessions.map { it.title })
        assertEquals("2026-11-15T12:30+08:00", p.sessions[0].end.toString())
        assertEquals("2026-11-15T15:00+08:00", p.sessions[2].end.toString())
        // Unchanged agenda: the same bytes (session ids are stable), so a re-share reuses the revision.
        assertTrue(EventRevisions.prepare(d, r.bytes, now.plusSeconds(60)).reused)
        assertEquals(json, EventJsonWriter.write(p).toString(Charsets.UTF_8))
    }

    @Test fun infoAndAgendaLimits() {
        val long = draft().copy(info = listOf(HostInfo("1", "dress", "x".repeat(1001))))
        assertTrue(HostDraftRules.issues(long, now).any { it.code == DraftIssue.Code.INFO_TOO_LONG && it.info == 0 })
        val many = draft().copy(info = List(13) { HostInfo("$it", "other", "t$it") })
        assertTrue(HostDraftRules.issues(many, now).any { it.code == DraftIssue.Code.TOO_MANY_INFO })
        val agenda = draft().copy(info = listOf(HostInfo("1", HostInfo.AGENDA, "11:00 Ketibaan\nJamuan tanpa masa")))
        assertTrue(HostDraftRules.issues(agenda, now).any { it.code == DraftIssue.Code.AGENDA_INVALID })
        assertEquals(listOf("Jamuan tanpa masa"), HostDraftRules.agendaBadLines("11:00 Ketibaan\nJamuan tanpa masa"))
        assertEquals(EventWizard.Step.INFO, EventWizard.stepFor(DraftIssue(DraftIssue.Code.AGENDA_INVALID)))
        // Parser: 13 blocks / 1001 characters rejected; templates new and unknown.
        val base = EventRevisions.prepare(draft(), null, now).bytes.toString(Charsets.UTF_8)
        for (t in listOf("meeting", "seminar", "picnic", "hike")) {
            assertEquals(EventTemplate.valueOf(t.uppercase()), EventParser.parse(base.replace("\"kenduri\"", "\"$t\"").toByteArray()).template)
        }
        assertEquals(EventTemplate.OTHER, EventParser.parse(base.replace("\"kenduri\"", "\"gala\"").toByteArray()).template)
    }

    @Test fun multiDayJourneyWithTimedStops() {
        val d = draft().copy(template = "ride", endDate = "2026-11-16", startTime = "07:00", endTime = "13:00",
            stops = listOf(
                HostStop("AAAAAAAAAAA", "start", "Dataran Putrajaya", 2.9264, 101.6964, arrive = "7:00"),
                HostStop("AAAAAAAAAAE", "water", "Stesen air", 2.8, 101.8, arrive = "08:10", dwellMin = 10),
                HostStop("AAAAAAAAAAI", "finish", "Port Dickson", 2.5, 101.8, arrive = "12:30", day = 1),
            ))
        assertEquals(2, d.dayCount())
        assertTrue(HostDraftRules.issues(d, now).isEmpty())
        val p = EventParser.parse(EventRevisions.prepare(d, null, now).bytes)
        assertEquals(EventTemplate.RIDE, p.template)
        assertEquals("2026-11-16T13:00+08:00", p.end.toString())
        assertEquals("2026-11-15T08:10+08:00", p.stops[1].arrive!!.from.toString())
        assertEquals("2026-11-15T08:20+08:00", p.stops[1].arrive!!.to.toString())
        assertEquals(10L, p.stops[1].dwellMin)
        assertEquals("2026-11-16T12:30+08:00", p.stops[2].arrive!!.from.toString())
        assertTrue(HostDraftRules.issues(d.copy(stops = d.stops.map { it.copy(arrive = "25:99") }), now).any { it.code == DraftIssue.Code.STOP_TIME })
    }

    // --- Wizard model ---------------------------------------------------------------------------------------

    @Test fun templateDefaultsFollowTheFlowTable() {
        assertEquals(EventWizard.PLACE_TEMPLATES + EventWizard.JOURNEY_TEMPLATES, HostDraft.TEMPLATES)
        for (t in HostDraft.TEMPLATES) assertEquals(t, EventWizard.defaults(t).template)
        assertEquals("11:00", EventWizard.hm(EventWizard.defaults("kenduri").times.first().start))
        assertEquals("19:30", EventWizard.hm(EventWizard.defaults("dinner").times.first().start))
        assertEquals("bicycle", EventWizard.defaults("ride").mode)
        assertEquals("walk", EventWizard.defaults("hike").mode)
        assertEquals("motorcycle", EventWizard.defaults("convoy").mode)
        assertTrue(EventWizard.isJourney("company") && !EventWizard.isJourney("picnic"))
        assertEquals(listOf(HostInfo.AGENDA, "dress"), EventWizard.infoChips("kenduri", emptySet()).take(2))
        assertFalse("added kinds are not offered again", EventWizard.infoChips("kenduri", setOf("dress")).contains("dress"))
        assertTrue("other may repeat", EventWizard.infoChips("kenduri", setOf("other")).contains("other"))
        val prefixes = mapOf("kenduri" to "Kenduri ", "meeting" to "Mesyuarat ", "other" to "")
        val d0 = HostDraft.new("x", "ms", ZoneId.of("Asia/Kuala_Lumpur"), LocalDate.parse("2026-11-08"))
        val d1 = EventWizard.chooseTemplate(d0, "kenduri", { prefixes[it].orEmpty() }, untouchedTime = true)
        assertEquals("Kenduri ", d1.title)
        val d2 = EventWizard.chooseTemplate(d1, "meeting", { prefixes[it].orEmpty() }, untouchedTime = true)
        assertEquals("Mesyuarat ", d2.title)
        assertEquals("09:00" to "12:00", d2.startTime to d2.endTime)
        val typed = EventWizard.chooseTemplate(d2.copy(title = "Mesyuarat AGM", startTime = "10:00"), "kenduri", { prefixes[it].orEmpty() }, untouchedTime = false)
        assertEquals("Mesyuarat AGM", typed.title)
        assertEquals("10:00", typed.startTime)
        assertEquals(DraftIssue.Code.TITLE_REQUIRED, EventWizard.blocking(d0, EventWizard.Step.WHEN))
        assertEquals(DraftIssue.Code.NO_STOPS, EventWizard.blocking(d1, EventWizard.Step.PLACE))
    }

    @Test fun wizardStepsAndJourneyStopOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val host = HostStore(FakeHostDao(), TestVault(), writer, clock = { now }, work = dispatcher)
        val ed = HostEditor(TestScope(dispatcher), host, clock = { now })
        ed.newEvent("ms", ZoneId.of("Asia/Kuala_Lumpur"), LocalDate.parse("2026-11-08"))
        assertEquals(EventWizard.Step.TYPE, ed.step.value)
        assertFalse("back on the first step leaves", ed.back())
        ed.next(); ed.next()
        assertEquals(EventWizard.Step.PLACE, ed.step.value)
        ed.update { it.copy(template = "ride", title = "Kayuhan PD") }
        ed.addStop(2.9, 101.7, "Putrajaya")          // first stop of a journey: start
        ed.addKind = "finish"; ed.addStop(2.5, 101.8, "Port Dickson")
        ed.addKind = "water"; ed.addLabel = "Air"; ed.addStop(2.8, 101.8, "")
        assertEquals(listOf("start", "water", "finish"), ed.draft.value!!.stops.map { it.kind })
        assertEquals("Air", ed.draft.value!!.stops[1].label)
        // "Tukar" moves the stop instead of adding one.
        val start = ed.draft.value!!.stops[0].id
        ed.relocate = start; ed.addStop(2.95, 101.69, "Dataran")
        assertEquals(3, ed.draft.value!!.stops.size)
        assertEquals(2.95, ed.draft.value!!.stops[0].lat, 0.0)
        assertEquals("Putrajaya", ed.draft.value!!.stops[0].label)
        // Mode: new routes use it.
        ed.setMode("walk", null)
        assertEquals("walk", ed.defaultMode())
        // A Semak row opens a step; "Seterusnya" goes straight back to Semak.
        ed.goTo(EventWizard.Step.REVIEW)
        ed.goTo(EventWizard.Step.WHEN, fromReview = true)
        ed.next()
        assertEquals(EventWizard.Step.REVIEW, ed.step.value)
        // Multi-day: removing days moves stops to the last day left.
        ed.setDays(3)
        ed.updateStop(ed.draft.value!!.stops[2].id) { it.copy(day = 2) }
        ed.setDays(2)
        assertEquals("2026-11-16", ed.draft.value!!.endDate)
        assertEquals(1, ed.draft.value!!.stops[2].day)
        ed.setDays(1)
        assertNull(ed.draft.value!!.endDate)
        // An existing event opens on Semak.
        advanceUntilIdle()
        assertTrue(ed.publish())
        val id = ed.draft.value!!.id
        ed.close(); advanceUntilIdle()
        assertTrue(ed.open(id))
        assertEquals(EventWizard.Step.REVIEW, ed.step.value)
        assertFalse("back on Semak of a published event leaves", ed.back())
        assertNotNull(ed.safetyCode())
    }
}
