package my.gimano.app.data.ugc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.data.local.SettingDao
import my.gimano.app.data.local.SettingEntity
import my.gimano.app.data.model.Author
import my.gimano.app.data.model.Photo
import my.gimano.app.data.model.Review
import my.gimano.app.data.remote.GimanoJson
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One blocked author, exactly as iOS stores it (docs/privacy-and-backup.md, `settings` key `blocked_authors`):
 * [ref] = the server's authorRef, [name] = the name shown when blocked, [blockedAt] = ISO-8601 UTC (no fraction).
 */
@Serializable
data class BlockedAuthor(val ref: String, val name: String = "", val blockedAt: String = "")

/** Pure rules: who is hidden, and the shared row format (one JSON array under one key, as on iOS). */
object Blocking {
    /** Shared `settings` key (iOS and Android): the whole list as ONE JSON array. */
    const val SETTINGS_KEY = "blocked_authors"

    private val listSerializer = ListSerializer(BlockedAuthor.serializer())

    /** ISO-8601 UTC without fractional seconds, like iOS's JSONEncoder .iso8601. */
    fun timestamp(now: Instant): String = now.truncatedTo(ChronoUnit.SECONDS).toString()

    /** Null/empty list = no row (iOS writes nil for an empty list). */
    fun encode(list: List<BlockedAuthor>): String? =
        list.takeIf { it.isNotEmpty() }?.let { GimanoJson.encodeToString(listSerializer, it) }

    /**
     * Tolerant: entries without a ref are skipped one by one (never the whole list), so a row written by either
     * platform, or by a newer version with extra fields, still reads.
     */
    fun decode(raw: String?): List<BlockedAuthor> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { GimanoJson.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val ref = o["ref"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BlockedAuthor(ref, o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["blockedAt"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.distinctBy { it.ref }
    }

    /** Merge restore: the union by ref (this phone's entry wins for a ref on both sides). */
    fun union(local: String?, backup: String?): String? {
        val mine = decode(local)
        val refs = mine.mapTo(HashSet()) { it.ref }
        return encode(mine + decode(backup).filter { it.ref !in refs })
    }

    fun isBlocked(a: Author?, blocked: Set<String>): Boolean = a?.authorRef?.let { it in blocked } == true

    /** A review is hidden when its author is blocked; its photos by blocked authors are dropped too. */
    fun reviews(list: List<Review>, blocked: Set<String>): List<Review> =
        if (blocked.isEmpty()) list
        else list.filterNot { isBlocked(it.author, blocked) }.map { r -> r.copy(photos = photos(r.photos, blocked)) }

    fun photos(list: List<Photo>, blocked: Set<String>): List<Photo> =
        if (blocked.isEmpty()) list else list.filterNot { isBlocked(it.uploadedBy, blocked) }
}

/**
 * "Block this user" (Apple 1.2 / Play UGC): kept ON THIS PHONE ONLY, never sent to the server, in the shared local
 * `settings` table under `blocked_authors` (same key and JSON as iOS), so it travels in the user's own backup and
 * restores on either platform (a merge restore unions by ref). Hides that author's reviews and photos everywhere;
 * unblock in Settings → Privacy.
 */
class BlockList(private val dao: SettingDao) {
    private val _blocked = MutableStateFlow<List<BlockedAuthor>>(emptyList())
    val blocked: StateFlow<List<BlockedAuthor>> = _blocked.asStateFlow()
    val refs: Set<String> get() = _blocked.value.mapTo(HashSet()) { it.ref }

    /** Reads the row (at start, and after a backup restore). */
    suspend fun reload() {
        _blocked.value = Blocking.decode(dao.get(Blocking.SETTINGS_KEY)).sortedByDescending { it.blockedAt }
    }

    suspend fun block(a: Author, now: Instant = Instant.now()) {
        val ref = a.authorRef ?: return
        val list = Blocking.decode(dao.get(Blocking.SETTINGS_KEY)).filterNot { it.ref == ref } +
            BlockedAuthor(ref, a.displayName, Blocking.timestamp(now))
        write(list)
    }

    suspend fun unblock(ref: String) = write(Blocking.decode(dao.get(Blocking.SETTINGS_KEY)).filterNot { it.ref == ref })

    private suspend fun write(list: List<BlockedAuthor>) {
        val v = Blocking.encode(list)
        if (v == null) dao.delete(Blocking.SETTINGS_KEY) else dao.put(SettingEntity(Blocking.SETTINGS_KEY, v))
        reload()
    }
}
