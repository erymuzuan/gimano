package my.gimano.app.data.places

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import my.gimano.app.data.local.OutboxDao
import my.gimano.app.data.local.OutboxEntity

/**
 * A device-local log of what this user contributed publicly (reviews, photos), so "My
 * contributions" can show them; public places come from GET /me/places/contributed.
 * Lives in the device-local outbox table (never backed up, never sent anywhere).
 */
class ContributionLog(private val outbox: OutboxDao) {
    companion object {
        const val REVIEW = "contrib_review"
        const val PHOTO = "contrib_photo"
        private const val NEVER = "9999-12-31T00:00:00Z"
    }

    suspend fun review(reviewId: String, placeName: String) = outbox.upsert(OutboxEntity("r-$reviewId", REVIEW, placeName, 0, NEVER))
    suspend fun photo(photoId: String, url: String) = outbox.upsert(OutboxEntity("p-$photoId", PHOTO, url, 0, NEVER))

    /** (reviews, photos) */
    val counts: Flow<Pair<Int, List<String>>> = combine(outbox.observe(REVIEW), outbox.observe(PHOTO)) { r, p -> r.size to p.map { it.payloadJson } }
}
