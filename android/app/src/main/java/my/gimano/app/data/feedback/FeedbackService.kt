package my.gimano.app.data.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import my.gimano.app.core.feedback.FeedbackRules
import my.gimano.app.data.local.OutboxDao
import my.gimano.app.data.local.OutboxEntity
import my.gimano.app.data.model.FeedbackRequest
import my.gimano.app.data.model.FeedbackSummary
import my.gimano.app.data.remote.GimanoJson
import my.gimano.app.data.repo.GimanoRepository
import retrofit2.HttpException
import java.time.Instant
import java.util.UUID

/**
 * One row in "My feedback": sent (with server status) or still queued on the device. [unlinked]: it carries a
 * volunteered contact, so the server stored it WITHOUT the user id: no server status, replies come by that contact.
 */
data class FeedbackItem(
    val id: String, val category: String, val message: String, val status: String, val createdAt: String, val queued: Boolean,
    val hasContact: Boolean = false, val unlinked: Boolean = false, val crash: Boolean = false,
)

/**
 * Kept after sending, on this phone only (outbox table, never in a backup), WITHOUT the contact details or device
 * block: only whether a contact was given and the report's [contactToken] (the only way to withdraw it later, since the
 * report isn't linked to the account). [crash]: a crash report (DELETE /crash-reports/{id}/contact).
 */
@Serializable
internal data class SentRecord(
    val id: String, val category: String, val message: String, val createdAt: String, val hasContact: Boolean = false,
    val contactToken: String? = null, val crash: Boolean = false,
)

sealed interface FeedbackSendResult {
    data object Sent : FeedbackSendResult
    data object Queued : FeedbackSendResult
    data class Rejected(val httpCode: Int) : FeedbackSendResult
}

/**
 * Sends feedback, or keeps it in the device outbox (table `outbox`, kind "feedback") and retries
 * with backoff when offline / the server is down. A local "sent" record (kind "feedback_sent") lets
 * "My feedback" work even before GET /me/feedback exists.
 */
class FeedbackService(private val outbox: OutboxDao, private val repo: GimanoRepository, private val now: () -> Instant = Instant::now) {
    companion object {
        const val KIND = "feedback"
        const val KIND_SENT = "feedback_sent"
        private const val NEVER = "9999-12-31T00:00:00Z"

        /** The local "sent" row (also used by the crash reporter for crash reports that carried a contact). */
        internal suspend fun recordSent(outbox: OutboxDao, rec: SentRecord) =
            outbox.upsert(OutboxEntity("sent-${rec.id}", KIND_SENT, GimanoJson.encodeToString(SentRecord.serializer(), rec), 0, NEVER))
    }

    suspend fun submit(req: FeedbackRequest): FeedbackSendResult {
        val id = UUID.randomUUID().toString()
        val row = OutboxEntity(id, KIND, GimanoJson.encodeToString(FeedbackRequest.serializer(), req), 0, now().toString())
        outbox.upsert(row)
        return attempt(row)
    }

    private suspend fun attempt(row: OutboxEntity): FeedbackSendResult {
        val req = GimanoJson.decodeFromString(FeedbackRequest.serializer(), row.payloadJson)
        return try {
            val created = repo.sendFeedback(req)
            outbox.delete(row.id)
            val rec = SentRecord(created.id, req.category, req.message, created.createdAt.ifBlank { now().toString() },
                hasContact = req.contact != null, contactToken = created.contactToken)
            recordSent(outbox, rec)
            FeedbackSendResult.Sent
        } catch (e: HttpException) {
            if (FeedbackRules.isPermanentFailure(e.code())) {
                outbox.delete(row.id)
                FeedbackSendResult.Rejected(e.code())
            } else reschedule(row)
        } catch (e: Exception) {
            reschedule(row)
        }
    }

    private suspend fun reschedule(row: OutboxEntity): FeedbackSendResult {
        val attempts = row.attempts + 1
        outbox.upsert(row.copy(attempts = attempts, nextAttemptAt = now().plusSeconds(FeedbackRules.backoffSeconds(attempts)).toString()))
        return FeedbackSendResult.Queued
    }

    /** Retries everything that is due; [force] ignores the backoff (e.g. when "My feedback" opens). */
    suspend fun retryDue(force: Boolean = false): Int {
        val rows = if (force) outbox.all(KIND) else outbox.due(now().toString()).filter { it.kind == KIND }
        return rows.count { attempt(it) == FeedbackSendResult.Sent }
    }

    /** Local view: queued items first, then sent ones (status refreshed from the server when available). */
    fun localItems(): Flow<List<FeedbackItem>> = combine(outbox.observe(KIND), outbox.observe(KIND_SENT)) { queued, sent ->
        queued.map { r ->
            val q = GimanoJson.decodeFromString(FeedbackRequest.serializer(), r.payloadJson)
            FeedbackItem(r.id, q.category, q.message, "queued", r.nextAttemptAt, queued = true)
        } + sent.map { r ->
            val s = GimanoJson.decodeFromString(SentRecord.serializer(), r.payloadJson)
            FeedbackItem(s.id, s.category, s.message, "received", s.createdAt, queued = false, hasContact = s.hasContact,
                unlinked = s.contactToken != null, crash = s.crash)
        }.sortedByDescending { it.createdAt }
    }

    /**
     * Withdraw the volunteered contact for one report: with its contactToken (X-Contact-Token; feedback or crash report),
     * or, for older feedback sent before tokens, as its author. Then the local record forgets the contact and the token.
     */
    suspend fun deleteContact(id: String) {
        val row = outbox.all(KIND_SENT).firstOrNull { it.id == "sent-$id" }
        val s = row?.let { GimanoJson.decodeFromString(SentRecord.serializer(), it.payloadJson) }
        when {
            s?.crash == true -> repo.deleteCrashContact(id, s.contactToken ?: return)
            else -> repo.deleteFeedbackContact(id, s?.contactToken)
        }
        if (row != null && s != null) outbox.upsert(row.copy(payloadJson = GimanoJson.encodeToString(SentRecord.serializer(), s.copy(hasContact = false, contactToken = null))))
    }

    suspend fun serverStatuses(): Map<String, FeedbackSummary> =
        runCatching { repo.myFeedback() }.getOrDefault(emptyList()).associateBy { it.id }
}
