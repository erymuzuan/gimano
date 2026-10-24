package my.gimano.app.ugc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import my.gimano.app.data.feedback.FeedbackSendResult
import my.gimano.app.data.feedback.FeedbackService
import my.gimano.app.data.local.OutboxDao
import my.gimano.app.data.local.OutboxEntity
import my.gimano.app.data.model.*
import my.gimano.app.data.remote.GimanoJson
import my.gimano.app.data.repo.DemoRepository
import my.gimano.app.data.repo.GimanoRepository
import my.gimano.app.data.repo.Moderation
import org.junit.Assert.*
import org.junit.Test

/** Backend PR #4: a report with a contact is unlinked from the account; the app keeps its contactToken to withdraw it. */
class ContactFlowTest {
    private class MemOutbox : OutboxDao {
        val rows = MutableStateFlow<Map<String, OutboxEntity>>(emptyMap())
        override fun observe(kind: String): Flow<List<OutboxEntity>> = rows.map { m -> m.values.filter { it.kind == kind } }
        override suspend fun due(now: String) = rows.value.values.filter { it.nextAttemptAt <= now }
        override suspend fun all(kind: String) = rows.value.values.filter { it.kind == kind }
        override suspend fun upsert(e: OutboxEntity) { rows.value = rows.value + (e.id to e) }
        override suspend fun delete(id: String) { rows.value = rows.value - id }
    }

    /** The server: returns a contactToken for reports with a contact; records how contacts are withdrawn. */
    private class Server(private val d: DemoRepository = DemoRepository()) : GimanoRepository by d {
        val withdrawals = mutableListOf<String>()
        override suspend fun sendFeedback(body: FeedbackRequest) =
            FeedbackCreated("fb-1", "2026-10-24T03:00:00Z", contactToken = if (body.contact != null) "tok-123" else null)
        override suspend fun deleteFeedbackContact(id: String, token: String?) { withdrawals += "feedback $id token=$token" }
        override suspend fun deleteCrashContact(id: String, token: String) { withdrawals += "crash $id token=$token" }
    }

    private val contact = ContactDetails(email = "a@example.com", whatsapp = null, consent = true)

    @Test fun theContactTokenIsKeptLocallyAndUsedToWithdraw() = runTest {
        val out = MemOutbox(); val server = Server()
        val svc = FeedbackService(out, server)
        assertEquals(FeedbackSendResult.Sent, svc.submit(FeedbackRequest(category = "bug", message = "x", screen = "home", contact = contact)))
        // The local record has the token but never the contact details themselves.
        val row = out.all(FeedbackService.KIND_SENT).single()
        assertTrue(row.payloadJson.contains("tok-123"))
        assertFalse(row.payloadJson.contains("a@example.com"))
        val item = svc.localItems().first().single()
        assertTrue(item.hasContact); assertTrue("shown as unlinked (no server status)", item.unlinked)

        svc.deleteContact("fb-1")
        assertEquals(listOf("feedback fb-1 token=tok-123"), server.withdrawals)
        val after = svc.localItems().first().single()
        assertFalse(after.hasContact)
        assertFalse(out.all(FeedbackService.KIND_SENT).single().payloadJson.contains("tok-123"))   // token forgotten
    }

    @Test fun feedbackWithoutAContactStaysLinkedAndHasNoToken() = runTest {
        val out = MemOutbox(); val server = Server()
        val svc = FeedbackService(out, server)
        svc.submit(FeedbackRequest(category = "idea", message = "y", screen = "home"))
        val item = svc.localItems().first().single()
        assertFalse(item.hasContact); assertFalse(item.unlinked)
    }

    @Test fun crashContactsAreWithdrawnWithTheirToken() = runTest {
        val out = MemOutbox(); val server = Server()
        // What CrashReporter records after a crash report with a contact was accepted.
        out.upsert(OutboxEntity("sent-cr-9", FeedbackService.KIND_SENT,
            """{"id":"cr-9","category":"crash","message":"NullPointerException","createdAt":"2026-10-24T03:00:00Z","hasContact":true,"contactToken":"tok-cr","crash":true}""",
            0, "9999-12-31T00:00:00Z"))
        val svc = FeedbackService(out, server)
        val item = svc.localItems().first().single()
        assertTrue(item.crash); assertTrue(item.unlinked)
        svc.deleteContact("cr-9")
        assertEquals(listOf("crash cr-9 token=tok-cr"), server.withdrawals)
    }

    @Test fun createResponsesCarryTheToken() {
        assertEquals("t", GimanoJson.decodeFromString(FeedbackCreated.serializer(), """{"id":"f","createdAt":"x","contactToken":"t"}""").contactToken)
        assertEquals("t2", GimanoJson.decodeFromString(CrashCreated.serializer(), """{"id":"c","signature":"s","contactToken":"t2"}""").contactToken)
        assertNull(GimanoJson.decodeFromString(FeedbackCreated.serializer(), """{"id":"f"}""").contactToken)
    }

    @Test fun demoContactTokensNeverReachTheServer() = runTest {
        val demo = DemoRepository()
        val created = demo.sendFeedback(FeedbackRequest(category = "bug", message = "x", screen = "home", contact = contact))
        assertTrue(created.contactToken!!.startsWith(DemoRepository.DEMO_CONTACT_TOKEN))
        assertTrue("unlinked: not in my feedback", demo.myFeedback().none { it.id == created.id })
    }

    @Test fun authorRefIsReadOnEveryContentType() {
        assertEquals("h1", GimanoJson.decodeFromString(Place.serializer(),
            """{"id":"p","name":"n","lat":1.0,"lng":2.0,"addedBy":{"displayName":"A","authorRef":"h1"}}""").addedBy?.authorRef)
        assertEquals("h2", GimanoJson.decodeFromString(Issue.serializer(),
            """{"id":"i","kind":"road_closed_temp","reportedBy":{"displayName":"B","authorRef":"h2"}}""").reportedBy?.authorRef)
        assertNull("absent when the author deleted their account",
            GimanoJson.decodeFromString(Author.serializer(), """{"displayName":"Pemandu Manotu"}""").authorRef)
    }

    @Test fun a403OnAContributionMeansBanned() {
        val r = retrofit2.Response.error<Any>(403, okhttp3.ResponseBody.create(null, ""))
        assertTrue(Moderation.isBanned(retrofit2.HttpException(r)))
        assertFalse(Moderation.isBanned(retrofit2.HttpException(retrofit2.Response.error<Any>(429, okhttp3.ResponseBody.create(null, "")))))
        assertFalse(Moderation.isBanned(java.io.IOException()))
    }
}
