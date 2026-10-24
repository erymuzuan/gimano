package my.gimano.app.events

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.core.events.*
import my.gimano.app.data.events.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** In-memory relay: what the server would keep (ciphertext only), with call counts. */
class FakeDropApi : DropApi {
    val blobs = HashMap<String, ByteArray>()
    val tokens = HashMap<String, String>()
    val expires = HashMap<String, Instant>()
    var creates = 0; var updates = 0; var deletes = 0; var fetches = 0
    var offline = false
    private var n = 0

    private fun net() { if (offline) throw DropException(DropException.Kind.NETWORK) }

    override suspend fun create(blob: ByteArray, expiresAt: Instant): DropCreated {
        net(); creates++
        val id = "AbCd${"%06d".format(++n)}".replace('0', 'z')
        blobs[id] = blob; tokens[id] = "tok$n"; expires[id] = expiresAt
        return DropCreated(id, "tok$n", expiresAt.toString())
    }

    override suspend fun update(dropId: String, writeToken: String, blob: ByteArray, expiresAt: Instant) {
        net(); updates++
        if (dropId !in blobs) throw DropException(DropException.Kind.GONE, 404)
        check(tokens[dropId] == writeToken)
        blobs[dropId] = blob; expires[dropId] = expiresAt
    }

    override suspend fun fetch(dropId: String): ByteArray {
        net(); fetches++
        return blobs[dropId] ?: throw DropException(DropException.Kind.GONE, 410)
    }

    override suspend fun delete(dropId: String, writeToken: String) {
        net(); deletes++
        if (dropId in blobs) check(tokens[dropId] == writeToken)
        blobs.remove(dropId)
    }
}

/** Short links (docs/events.md §5.6) against the shared vectors, plus the app flows around them. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDropTest {
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

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // --- HKDF -------------------------------------------------------------------------------------------------

    /** RFC 5869 A.1 and A.3 (empty salt and info: the same "salt = empty" rule the drop key uses). */
    @Test fun hkdfRfc5869() {
        val a1 = Hkdf.sha256(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), a1)
        val a3 = Hkdf.sha256(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), ByteArray(0), ByteArray(0), 42)
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), a3)
    }

    // --- Vectors --------------------------------------------------------------------------------------------

    @Test fun kenduriDropVector() {
        val v = vector("kenduri-one-day")
        val secret = v.b("secretB64u")
        assertEquals(16, secret.size)
        assertArrayEquals("HKDF alone", v.b("dropKeyB64u"), DropKeys.key(secret))
        // Every link form parses to the same id and secret, and the writer prints them back.
        for (form in listOf("dropLink", "dropAppLink", "dropFragment")) {
            val ref = DropLinks.parse(v.s(form))
            assertEquals(form, v.s("dropId"), ref.dropId)
            assertArrayEquals(form, secret, ref.secret)
        }
        assertTrue(DropLinks.isDropLink("#" + v.s("dropFragment")))
        val ref = DropRef(v.s("dropId"), secret)
        assertEquals(v.s("dropLink"), ref.link())
        assertEquals(v.s("dropAppLink"), ref.appLink())
        assertEquals(v.s("dropFragment"), ref.fragment)
        assertEquals(ref, DropRef.decode(ref.encode()))
        // Reader: the blob opens to the signed bytes, like the link.
        val blob = v.b("dropBlobB64u")
        val p = reader.openDrop(secret, blob)
        assertEquals(v.s("eventJson"), p.eventBytes.toString(Charsets.UTF_8))
        assertEquals(v.s("safetyCode"), p.safetyCode)
        assertEquals(v.s("revisionHashB64u"), p.revisionHashB64u)
        assertArrayEquals(v.b("dropNonceB64u"), blob.copyOfRange(0, 24))
        // Writer: byte-identical from the vector's deflated envelope (java.util.zip and miniz compress differently, like
        // the link writer test), and a blob from our own deflate opens to the same bytes.
        val key = DropKeys.key(secret)
        assertArrayEquals(blob, v.b("dropNonceB64u") + writer.encrypt(key, v.b("dropNonceB64u"), v.b("deflatedB64u")))
        val ours = EventDrops.sealParts(writer, v.b("eventB64u"), v.b("ed25519SeedB64u"), secret, v.b("dropNonceB64u"))
        assertArrayEquals(v.b("eventB64u"), reader.openDrop(secret, ours).eventBytes)
        // A short link is never read as an /a/ link, and an /a/ link never as a short link.
        assertFalse(DropLinks.isDropLink(v.s("link")))
        assertFalse(DropLinks.isDropLink(v.s("linkFragment")))
        // The writer path (fresh nonce) round-trips.
        val fresh = EventDrops.seal(writer, v.b("eventB64u"), v.b("ed25519SeedB64u"), secret)
        assertArrayEquals(v.b("eventB64u"), reader.openDrop(secret, fresh).eventBytes)
    }

    @Test fun negativeDropLinks() {
        val v = vector("negative-drop-link")
        assertEquals("malformed_drop_link", v.s("expectedError"))
        val links = v.getValue("links").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(links.size >= 10)
        for (l in links) {
            assertCode(EventPackException.MALFORMED_DROP_LINK) { DropLinks.parse(l) }
            assertNull(l, DropLinks.parseOrNull(l))
        }
    }

    @Test fun negativeDropBlobs() {
        val t = vector("negative-drop-blob-truncated")
        val secret = t.b("secretB64u")
        for (b in t.getValue("blobsB64u").jsonArray.map { B64u.decode(it.jsonPrimitive.content)!! }) {
            assertCode(t.s("expectedError")) { reader.openDrop(secret, b) }
        }
        val w = vector("negative-drop-wrong-secret")
        assertArrayEquals(w.b("dropKeyB64u"), DropKeys.key(w.b("secretB64u")))
        assertEquals(w.s("dropId"), DropLinks.parse(w.s("dropLink")).dropId)
        assertCode(w.s("expectedError")) { reader.openDrop(w.b("secretB64u"), w.b("dropBlobB64u")) }
        // Over 256 KiB: refused before decrypting, and never written.
        assertCode(EventPackException.DROP_TOO_LARGE) { reader.openDrop(w.b("secretB64u"), ByteArray(DropLinks.MAX_BLOB_BYTES + 1)) }
        assertCode(EventPackException.DROP_TOO_LARGE) { DropLinks.encodeBlob(SealedPack(ByteArray(24), ByteArray(DropLinks.MAX_BLOB_BYTES))) }
    }

    // --- Link parsing -----------------------------------------------------------------------------------------

    @Test fun linkForms() {
        val s = "pYWCGJTPdGKzy5IgdWuP8w"
        for (l in listOf(
            "https://manotu.app/j/#8jrM9b4bbf.$s", "https://manotu.app/j#8jrM9b4bbf.$s", "https://www.manotu.app/j/#8jrM9b4bbf.$s",
            "manotu://event?drop=8jrM9b4bbf#$s", "gimano://event?drop=8jrM9b4bbf#$s", "manotu://event?stop=AAAAAAAAAAA&drop=8jrM9b4bbf#$s",
            "  8jrM9b4bbf.$s  ", "#8jrM9b4bbf.$s",
        )) assertEquals(l, "8jrM9b4bbf", DropLinks.parse(l).dropId)
        // `/a/` links, other paths and a scheme link without drop= are not short links.
        for (l in listOf("https://manotu.app/a/#1.AAAA.BBBB", "https://manotu.app/x/#8jrM9b4bbf.$s", "manotu://event#1.AAAA.BBBB",
            "manotu://event?stop=x#8jrM9b4bbf.$s", "https://manotu.app/j/#8jrM9b4bbf$s")) assertNull(l, DropLinks.parseOrNull(l))
    }

    // --- Expiry -----------------------------------------------------------------------------------------------

    private fun plan(start: String, end: String, tz: String = "Asia/Kuala_Lumpur"): EventPlan {
        val d = HostDraft(id = EventIds.newEventId(), title = "T", tz = tz, date = start.substring(0, 10), startTime = start.substring(11),
            endTime = end.substring(11), endDate = end.substring(0, 10).takeIf { it != start.substring(0, 10) },
            stops = listOf(HostStop(EventIds.newItemId(), "venue", "Dewan", 3.0, 101.0)))
        return HostDraftRules.toPlan(d, 1, null, Instant.parse("2026-11-01T00:00:00Z"))
    }

    /** Last day + 14 days, to the end of that day in the event's zone. */
    @Test fun expiryIsLastDayPlus14() {
        assertEquals(Instant.parse("2026-11-29T16:00:00Z"), EventDrops.expiresAt(plan("2026-11-15T11:00", "2026-11-15T16:00")))
        // Ends after midnight: the last day is the 16th.
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"), EventDrops.expiresAt(plan("2026-11-15T20:00", "2026-11-15T01:00")))
        // Multi-day ride.
        assertEquals(Instant.parse("2026-12-06T16:00:00Z"), EventDrops.expiresAt(plan("2026-11-21T07:00", "2026-11-22T13:00")))
        // Another zone: midnight there.
        assertEquals(Instant.parse("2026-11-29T15:00:00Z"), EventDrops.expiresAt(plan("2026-11-15T11:00", "2026-11-15T16:00", "Asia/Tokyo")))
        assertEquals("2026-11-29T16:00:00Z", HttpDropApi.expiresHeader(Instant.parse("2026-11-29T16:00:00.123Z")))
    }

    // --- Clipboard (deferred link) ----------------------------------------------------------------------------

    @Test fun clipboardMatcher() {
        val s = "pYWCGJTPdGKzy5IgdWuP8w"
        val msg = "Jemputan: Kenduri Aqil & Sofea\nAhd, 15 Nov · 11:00–16:00\nBuka pelan, parkir & arah: https://manotu.app/j/#8jrM9b4bbf.$s\nBelum ada Manotu? https://manotu.app/download/"
        assertEquals("8jrM9b4bbf", DropLinks.findInText(msg)?.dropId)
        assertEquals("8jrM9b4bbf", DropLinks.findInText("manotu.app/j/#8jrM9b4bbf.$s")?.dropId)
        assertEquals("8jrM9b4bbf", DropLinks.findInText("(www.manotu.app/j/#8jrM9b4bbf.$s)")?.dropId)
        for (t in listOf("", "hello", "https://manotu.app/a/#1.AAAA.BBBB", "https://evil.example/j/#8jrM9b4bbf.$s",
            "https://manotu.app/j/#8jrM9b4bbO.$s", "https://manotu.app/j/#8jrM9b4bbf.${s}x", "https://manotu.app/j/#8jrM9b4bbf.${s.dropLast(1)}",
            "https://manotu.app/j/#8jrM9b4bbf.pYWCGJTPdGKzy5IgdWuP8x")) assertNull(t, DropLinks.findInText(t))
        // Declined links are remembered as hashes, never as the link.
        val ref = DropLinks.findInText(msg)!!
        val key = DeferredLinks.dismissKey(ref)
        assertFalse(key.contains(s))
        assertEquals(ref, DeferredLinks.offer(msg, emptySet()))
        assertNull(DeferredLinks.offer(msg, setOf(key)))
        assertNull(DeferredLinks.offer(null, emptySet()))
        // The window: 7 days from first launch, closed once an event is imported.
        val first = Instant.parse("2026-11-01T00:00:00Z")
        assertTrue(DeferredLinks.inWindow(first, first.plusSeconds(60), anyImported = false))
        assertTrue(DeferredLinks.inWindow(first, first.plusSeconds(7 * 86400L - 1), anyImported = false))
        assertFalse(DeferredLinks.inWindow(first, first.plusSeconds(7 * 86400L), anyImported = false))
        assertFalse(DeferredLinks.inWindow(first, first.plusSeconds(60), anyImported = true))
    }

    // --- Guest flow -------------------------------------------------------------------------------------------

    @Test fun guestImportsKeepsTheLinkAndGetsLaterRevisions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val v = vector("kenduri-one-day")
        val now = Instant.parse(v.s("now"))
        val secret = v.b("secretB64u")
        val seed = v.b("ed25519SeedB64u")
        val api = FakeDropApi()
        val id = v.s("dropId")
        api.blobs[id] = v.b("dropBlobB64u")
        val store = EventStore(FakeEventDao(), TestVault(), { it() }, clock = { now })
        var handled = 0
        val imp = EventImporter(TestScope(dispatcher), reader, store, clock = { now }, work = dispatcher, drops = api, onCopiedHandled = { handled++ })

        imp.openLink(v.s("dropLink"))
        advanceUntilIdle()
        val review = imp.ui.value as EventImportUi.Review
        assertTrue(review.decision is ImportDecision.New)
        imp.confirm()
        advanceUntilIdle()
        val eventId = review.plan.id
        assertEquals(DropRef(id, secret), store.dropFor(eventId))
        assertTrue(store.hasAny())

        // The organizer republishes (rev 2) to the same drop: opening the event fetches it and offers the update.
        val rev2 = v.s("eventJson").replace("\"rev\":1,\"parent\":null", "\"rev\":2,\"parent\":\"${v.s("revisionHashB64u")}\"")
            .replace("\"Kenduri Aqil & Sofea\"", "\"Kenduri Aqil & Sofea (baharu)\"").toByteArray()
        api.blobs[id] = EventDrops.seal(writer, rev2, seed, secret)
        imp.refresh(eventId)
        advanceUntilIdle()
        val upd = imp.ui.value as EventImportUi.Review
        assertTrue(upd.decision is ImportDecision.Newer)
        imp.confirm()
        advanceUntilIdle()
        assertEquals("Kenduri Aqil & Sofea (baharu)", store.load(eventId)!!.plan.title)
        assertEquals(DropRef(id, secret), store.dropFor(eventId))
        // Same revision again: nothing shown.
        imp.refresh(eventId)
        advanceUntilIdle()
        assertEquals(EventImportUi.Idle, imp.ui.value)
        // Offline on refresh: quiet. Ended by the organizer: forgotten, quietly.
        api.offline = true
        imp.refresh(eventId); advanceUntilIdle()
        assertEquals(EventImportUi.Idle, imp.ui.value)
        api.offline = false
        api.blobs.remove(id)
        imp.refresh(eventId); advanceUntilIdle()
        assertEquals(EventImportUi.Idle, imp.ui.value)
        assertNull(store.dropFor(eventId))
        assertNotNull("the stored event stays", store.load(eventId))

        // Opening a link that is gone: "Jemputan ini telah tamat atau dipadam".
        imp.openLink(v.s("dropAppLink"))
        advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.DROP_GONE), imp.ui.value)
        imp.dismiss()
        // Offline: "Cuba lagi" fetches again.
        api.blobs[id] = v.b("dropBlobB64u")
        api.offline = true
        imp.openLink(v.s("dropLink"))
        advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.NETWORK), imp.ui.value)
        api.offline = false
        imp.retry()
        advanceUntilIdle()
        assertEquals("rev 1 is already stored (under rev 2)", EventNotice.ALREADY_IMPORTED, (imp.ui.value as EventImportUi.Notice).kind)
        assertEquals("the same organizer's link is kept again", DropRef(id, secret), store.dropFor(eventId))
        imp.dismiss()
        // Wrong secret: the §7 message.
        val w = vector("negative-drop-wrong-secret")
        api.blobs[id] = w.b("dropBlobB64u")
        imp.openLink(w.s("dropLink"))
        advanceUntilIdle()
        assertEquals(EventImportUi.Notice(EventNotice.CANNOT_OPEN), imp.ui.value)
        imp.dismiss()

        // Deferred link: offered, never opened without the tap; answered links are remembered.
        imp.offerCopied(DropRef(id, secret))
        assertEquals(EventImportUi.OfferCopied(DropRef(id, secret)), imp.ui.value)
        imp.declineCopied()
        assertEquals(EventImportUi.Idle, imp.ui.value)
        assertEquals(1, handled)
        val fetches = api.fetches
        imp.offerCopied(DropRef(id, secret))
        assertEquals("no fetch before the tap", fetches, api.fetches)
        imp.acceptCopied()
        advanceUntilIdle()
        assertEquals(fetches + 1, api.fetches)
        assertEquals(2, handled)
    }

    // --- Organizer flow ---------------------------------------------------------------------------------------

    @Test fun organizerUploadsOnceUpdatesOnRepublishAndEnds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val now = Instant.parse("2026-11-01T03:00:00Z")
        val api = FakeDropApi()
        val host = HostStore(FakeHostDao(), TestVault(), writer, clock = { now }, work = dispatcher)
        val ed = HostEditor(TestScope(dispatcher), host, clock = { now }, drops = api)
        ed.newEvent("ms", ZoneId.of("Asia/Kuala_Lumpur"), LocalDate.parse("2026-11-08"))
        ed.update { HostDraft(id = it.id, title = "Kenduri", date = "2026-11-15", stops = listOf(HostStop(EventIds.newItemId(), "venue", "Dewan", 2.9884, 101.7919))) }
        assertTrue(ed.publish())
        val eventId = ed.draft.value!!.id
        assertEquals(HostEditor.ShortLink.Idle, ed.shortLink.value)

        val link = ed.shortLinkForShare()!!
        val ref = DropLinks.parse(link)
        assertTrue(link.startsWith("https://manotu.app/j/#"))
        assertEquals(1, api.creates)
        assertEquals(Instant.parse("2026-11-29T16:00:00Z"), api.expires[ref.dropId])
        assertEquals("rev 1 on the relay", 1L, reader.openDrop(ref.secret, api.blobs.getValue(ref.dropId)).event.rev)
        assertTrue(ed.shortLinkActive.value)
        // Copy after share: the same link, no second upload.
        assertEquals(link, ed.shortLinkForShare())
        assertEquals(1, api.creates)
        // The secret and token are stored sealed with the keys (TestVault XOR), never in clear.
        val row = (host.drop(eventId))!!
        assertEquals(ref.dropId, row.dropId)

        // Unchanged republish: nothing uploaded.
        ed.closeShare()
        assertTrue(ed.publish())
        advanceUntilIdle()
        assertEquals(link, ed.shortLinkForShare())
        assertEquals(0, api.updates)
        // A change, then republish: PUT right away, same link, rev 2.
        ed.closeShare()
        ed.update { it.copy(title = "Kenduri Aqil") }
        assertTrue(ed.publish())
        advanceUntilIdle()
        assertEquals(1, api.updates)
        assertEquals(link, ed.shortLinkForShare())
        assertEquals(2L, reader.openDrop(ref.secret, api.blobs.getValue(ref.dropId)).event.rev)
        assertEquals(1, api.creates)

        // Offline: falls back (null), state Failed; nothing forgotten.
        ed.closeShare()
        ed.update { it.copy(title = "Kenduri Aqil & Sofea") }
        api.offline = true
        assertTrue(ed.publish())
        advanceUntilIdle()
        assertNull(ed.shortLinkForShare())
        assertTrue(ed.shortLink.value is HostEditor.ShortLink.Failed)
        assertNotNull(host.drop(eventId))
        // "Tamatkan pautan" offline fails and keeps the link; online it is deleted and forgotten.
        assertFalse(ed.endShortLink())
        assertTrue(ed.shortLinkActive.value)
        api.offline = false
        assertTrue(ed.endShortLink())
        assertEquals(1, api.deletes)
        assertNull(host.drop(eventId))
        assertFalse(ed.shortLinkActive.value)
        assertFalse(ref.dropId in api.blobs)

        // The next share makes a new link; deleting the event deletes it too.
        val link2 = ed.shortLinkForShare()!!
        assertNotEquals(link, link2)
        assertEquals(2, api.creates)
        ed.delete()
        assertEquals(2, api.deletes)
        assertTrue(api.blobs.isEmpty())
        assertNull(host.load(eventId))
    }

    /** A link deleted on the server (expired) is replaced by a new one on the next republish. */
    @Test fun goneDropIsReplaced() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val now = Instant.parse("2026-11-01T03:00:00Z")
        val api = FakeDropApi()
        val host = HostStore(FakeHostDao(), TestVault(), writer, clock = { now }, work = dispatcher)
        val ed = HostEditor(TestScope(dispatcher), host, clock = { now }, drops = api)
        ed.newEvent("ms", ZoneId.of("Asia/Kuala_Lumpur"), LocalDate.parse("2026-11-08"))
        ed.update { HostDraft(id = it.id, title = "Kenduri", date = "2026-11-15", stops = listOf(HostStop(EventIds.newItemId(), "venue", "Dewan", 2.9884, 101.7919))) }
        assertTrue(ed.publish())
        val first = DropLinks.parse(ed.shortLinkForShare()!!)
        api.blobs.clear()
        ed.closeShare()
        ed.update { it.copy(title = "Kenduri 2") }
        assertTrue(ed.publish())
        advanceUntilIdle()
        val second = DropLinks.parse(ed.shortLinkForShare()!!)
        assertNotEquals(first.dropId, second.dropId)
        assertFalse("a new secret too", first.secret.contentEquals(second.secret))
        assertEquals(2, api.creates)
    }
}
