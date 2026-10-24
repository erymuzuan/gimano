package my.gimano.app.data.ugc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import my.gimano.app.data.local.SettingDao
import my.gimano.app.data.local.SettingEntity
import my.gimano.app.data.model.Author
import my.gimano.app.data.model.Photo
import my.gimano.app.data.model.Review
import my.gimano.app.data.remote.GimanoJson
import java.time.Instant

/** One blocked author: the server's opaque handle, the name shown when blocked, and when. */
@Serializable
data class BlockedAuthor(val ref: String, val name: String = "", val at: String = "")

/** Pure rules: who is hidden, and the on-device row format. */
object Blocking {
    const val KEY_PREFIX = "blocked_author:"

    fun key(ref: String) = KEY_PREFIX + ref

    fun encode(b: BlockedAuthor): String = GimanoJson.encodeToString(BlockedAuthor.serializer(), b)
    fun decode(key: String, value: String): BlockedAuthor? =
        if (!key.startsWith(KEY_PREFIX)) null
        else runCatching { GimanoJson.decodeFromString(BlockedAuthor.serializer(), value) }.getOrNull()?.copy(ref = key.removePrefix(KEY_PREFIX))

    fun isBlocked(a: Author?, blocked: Set<String>): Boolean = a?.authorRef?.let { it in blocked } == true

    /** A review is hidden when its author is blocked; its photos by blocked authors are dropped too. */
    fun reviews(list: List<Review>, blocked: Set<String>): List<Review> =
        if (blocked.isEmpty()) list
        else list.filterNot { isBlocked(it.author, blocked) }.map { r -> r.copy(photos = photos(r.photos, blocked)) }

    fun photos(list: List<Photo>, blocked: Set<String>): List<Photo> =
        if (blocked.isEmpty()) list else list.filterNot { isBlocked(it.uploadedBy, blocked) }
}

/**
 * "Block this user" (Apple 1.2 / Play UGC): kept ON THIS PHONE ONLY, never sent to the server. One row per author in
 * the local `settings` table (`blocked_author:<ref>`), so it travels in the user's own backup and a restore (merge)
 * unions the lists. Hides that author's reviews and photos everywhere; unblock in Settings → Privacy.
 */
class BlockList(private val dao: SettingDao) {
    private val _blocked = MutableStateFlow<List<BlockedAuthor>>(emptyList())
    val blocked: StateFlow<List<BlockedAuthor>> = _blocked.asStateFlow()
    val refs: Set<String> get() = _blocked.value.mapTo(HashSet()) { it.ref }

    /** Reads the rows (at start, and after a backup restore). */
    suspend fun reload() {
        _blocked.value = dao.all().mapNotNull { Blocking.decode(it.key, it.value) }.sortedByDescending { it.at }
    }

    suspend fun block(a: Author, now: Instant = Instant.now()) {
        val ref = a.authorRef ?: return
        dao.put(SettingEntity(Blocking.key(ref), Blocking.encode(BlockedAuthor(ref, a.displayName, now.toString()))))
        reload()
    }

    suspend fun unblock(ref: String) {
        dao.delete(Blocking.key(ref))
        reload()
    }
}
