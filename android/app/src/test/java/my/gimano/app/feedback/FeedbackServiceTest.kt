package my.gimano.app.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import my.gimano.app.data.feedback.FeedbackSendResult
import my.gimano.app.data.feedback.FeedbackService
import my.gimano.app.data.local.LegacyMigration
import my.gimano.app.data.local.OutboxDao
import my.gimano.app.data.local.OutboxEntity
import my.gimano.app.data.model.*
import my.gimano.app.data.repo.DemoRepository
import my.gimano.app.data.repo.GimanoRepository
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Instant

class FakeOutbox : OutboxDao {
    val rows = MutableStateFlow<Map<String, OutboxEntity>>(emptyMap())
    override fun observe(kind: String): Flow<List<OutboxEntity>> = rows.map { m -> m.values.filter { it.kind == kind }.sortedBy { it.nextAttemptAt } }
    override suspend fun due(now: String) = rows.value.values.filter { it.nextAttemptAt <= now }.sortedBy { it.nextAttemptAt }
    override suspend fun all(kind: String) = rows.value.values.filter { it.kind == kind }
    override suspend fun upsert(e: OutboxEntity) { rows.value = rows.value + (e.id to e) }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
}

class FeedbackServiceTest {
    private class FlakyRepo(var fail: Exception?) : GimanoRepository by DemoRepository() {
        val sent = ArrayList<FeedbackRequest>()
        override suspend fun sendFeedback(body: FeedbackRequest): FeedbackCreated {
            fail?.let { throw it }
            sent += body
            return FeedbackCreated("fb-${sent.size}", "2026-10-23T00:00:00Z")
        }
    }

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))
    private val req = FeedbackRequest("bug", "Butang tidak berfungsi", 4, "home")
    private var clock = Instant.parse("2026-10-23T00:00:00Z")

    @Test fun offlineFeedbackIsQueuedThenSentOnRetry() = runBlocking {
        val outbox = FakeOutbox(); val repo = FlakyRepo(IOException("offline"))
        val svc = FeedbackService(outbox, repo) { clock }
        assertEquals(FeedbackSendResult.Queued, svc.submit(req))
        val queued = outbox.all(FeedbackService.KIND).single()
        assertEquals(1, queued.attempts)
        assertEquals("backoff", clock.plusSeconds(60).toString(), queued.nextAttemptAt)
        assertEquals("queued", svc.localItems().first().single().status)

        repo.fail = null
        assertEquals("not due yet", 0, svc.retryDue())
        clock = clock.plusSeconds(61)
        assertEquals(1, svc.retryDue())
        assertTrue(outbox.all(FeedbackService.KIND).isEmpty())
        val items = svc.localItems().first()
        assertEquals("received", items.single().status)
        assertEquals("fb-1", items.single().id)
        assertEquals(req.message, repo.sent.single().message)
    }

    @Test fun serverErrorsAndMissingEndpointKeepItQueued() = runBlocking {
        listOf(503, 404, 429).forEach { code ->
            val outbox = FakeOutbox()
            val svc = FeedbackService(outbox, FlakyRepo(http(code))) { clock }
            assertEquals(FeedbackSendResult.Queued, svc.submit(req))
            assertEquals(1, outbox.all(FeedbackService.KIND).size)
        }
    }

    @Test fun validationErrorsAreNotRetried() = runBlocking {
        val outbox = FakeOutbox()
        val svc = FeedbackService(outbox, FlakyRepo(http(413))) { clock }
        assertEquals(FeedbackSendResult.Rejected(413), svc.submit(req))
        assertTrue(outbox.rows.value.isEmpty())
    }

    @Test fun forcedRetryIgnoresBackoff() = runBlocking {
        val outbox = FakeOutbox(); val repo = FlakyRepo(IOException("offline"))
        val svc = FeedbackService(outbox, repo) { clock }
        svc.submit(req)
        repo.fail = null
        assertEquals(1, svc.retryDue(force = true))
    }
}

class LegacyMigrationTest {
    private val data = MyDataExport(
        listOf(SavedPlace("h", "home", "Rumah", 3.1, 101.6), SavedPlace("f", "favorite", "Kampung", 2.2, 102.2, icon = "kampung")),
        listOf(Recent("r", "Mid Valley", "", 3.11, 101.67)),
    )

    @Test fun importsThenDeletesThenMarksDone() = runBlocking {
        val log = ArrayList<String>()
        val out = LegacyMigration({ data }, { log += "import:${it.savedPlaces.size}" }, { log += "delete" }, { log += "done" }).run()
        assertEquals(LegacyMigration.Outcome.IMPORTED, out)
        assertEquals(listOf("import:2", "delete", "done"), log)
    }

    @Test fun endpointNotShippedYetIsRetriedLater() = runBlocking {
        var done = false
        assertEquals(LegacyMigration.Outcome.NOT_AVAILABLE_YET, LegacyMigration({ null }, {}, {}, { done = true }).run())
        assertFalse(done)
    }

    @Test fun failedDeleteDoesNotMarkDone() = runBlocking {
        var done = false
        val out = LegacyMigration({ data }, {}, { throw IOException("down") }, { done = true }).run()
        assertEquals(LegacyMigration.Outcome.FAILED, out)
        assertFalse("will retry next launch (import is idempotent)", done)
    }

    @Test fun emptyServerCopyIsCleanedUp() = runBlocking {
        val log = ArrayList<String>()
        assertEquals(LegacyMigration.Outcome.NOTHING_ON_SERVER, LegacyMigration({ MyDataExport() }, { log += "import" }, { log += "delete" }, { log += "done" }).run())
        assertEquals(listOf("delete", "done"), log)
    }
}
