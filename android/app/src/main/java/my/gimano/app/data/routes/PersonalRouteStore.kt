package my.gimano.app.data.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import my.gimano.app.core.events.EventTravelMode
import my.gimano.app.core.routes.BuilderRoute
import my.gimano.app.core.routes.PersonalRouteJson
import my.gimano.app.core.routes.RouteAssembly
import my.gimano.app.data.backup.SqlPort
import java.time.Instant

/** A personal route as listed. */
data class PersonalRoute(val id: String, val name: String, val route: BuilderRoute, val updatedAt: Long)

/**
 * "Laluan saya": personal routes in the main on-device DB (`personal_routes`, schema 3, docs/privacy-and-backup.md),
 * so they are in the user's own backup like planned drives. Plain columns + `route_json` v1 ([PersonalRouteJson]);
 * at-rest protection is the main DB's. Nothing here touches the network; sharing is the user's own "Kongsi GPX".
 *
 * Raw SQL (the table is not a Room entity, like `identity`), so it runs on Room and on JDBC in tests. Room's
 * invalidation tracker does not know the table, so [routes] re-reads on this store's own writes and on [invalidate]
 * (called after a backup restore).
 */
class PersonalRouteStore(
    private val port: () -> SqlPort,
    private val now: () -> Instant = Instant::now,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val changes = MutableStateFlow(0L)

    /** The table changed outside this store (backup restore, one-time move). */
    fun invalidate() = bump()

    private fun bump() { changes.value = changes.value + 1 }

    fun routes(): Flow<List<PersonalRoute>> = changes.map { all() }.flowOn(io)

    /** Every readable route, newest first. Rows whose `route_json` this version cannot read are skipped (kept). */
    fun all(): List<PersonalRoute> = port().queryStrings("SELECT $COLS FROM personal_routes ORDER BY updated_at DESC").mapNotNull(::row)

    suspend fun get(id: String): PersonalRoute? = withContext(io) {
        port().queryStrings("SELECT $COLS FROM personal_routes WHERE id = ?", id).firstOrNull()?.let(::row)
    }

    suspend fun save(name: String, route: BuilderRoute) = withContext(io) { saveNow(name, route) }

    /** Upsert by the route id; keeps `created_at`, sets `updated_at`. */
    fun saveNow(name: String, route: BuilderRoute, updatedAt: Instant = now()) {
        val sum = RouteAssembly.summary(route)
        val complete = route.legs.isNotEmpty() && sum.openLegs == 0
        val mode = route.mode.takeIf { m -> EventTravelMode.entries.any { it.wire == m } } ?: EventTravelMode.CAR.wire
        val ts = updatedAt.toString()
        val distance = if (complete) sum.distanceM.takeIf { it > 0 } else null
        val duration = if (complete) sum.durationS?.takeIf { it > 0 } else null
        val p = port()
        // INSERT OR IGNORE + UPDATE rather than ON CONFLICT … DO UPDATE (SQLite 3.24, not on every supported Android).
        p.transaction {
            p.exec(
                "INSERT OR IGNORE INTO personal_routes (id, name, mode, route_json, distance_m, duration_s, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                route.id, cleanName(name), mode, PersonalRouteJson.encode(route), distance, duration, ts, ts,
            )
            p.exec(
                "UPDATE personal_routes SET name = ?, mode = ?, route_json = ?, distance_m = ?, duration_s = ?, updated_at = ? WHERE id = ?",
                cleanName(name), mode, PersonalRouteJson.encode(route), distance, duration, ts, route.id,
            )
        }
        bump()
    }

    /**
     * Adds the route unless one with its id is already here (then the row here wins); true when added. Used by the
     * one-time move from the events DB, so running it again changes nothing.
     */
    fun insertIfAbsent(name: String, route: BuilderRoute, createdAt: Instant, updatedAt: Instant): Boolean {
        val sum = RouteAssembly.summary(route)
        val complete = route.legs.isNotEmpty() && sum.openLegs == 0
        val mode = route.mode.takeIf { m -> EventTravelMode.entries.any { it.wire == m } } ?: EventTravelMode.CAR.wire
        val p = port()
        if (p.queryLong("SELECT COUNT(*) FROM personal_routes WHERE id = ?", route.id) > 0) return false
        p.exec(
            "INSERT OR IGNORE INTO personal_routes (id, name, mode, route_json, distance_m, duration_s, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            route.id, cleanName(name), mode, PersonalRouteJson.encode(route),
            if (complete) sum.distanceM.takeIf { it > 0 } else null, if (complete) sum.durationS?.takeIf { it > 0 } else null,
            createdAt.toString(), updatedAt.toString(),
        )
        bump()
        return true
    }

    suspend fun delete(id: String) = withContext(io) {
        port().exec("DELETE FROM personal_routes WHERE id = ?", id)
        bump()
    }

    private fun row(s: String): PersonalRoute? = runCatching {
        val (id, mode, updated, json, name) = s.split(SEP, limit = 5)
        PersonalRoute(id, name, PersonalRouteJson.decode(json, id, name, mode), runCatching { Instant.parse(updated).toEpochMilli() }.getOrDefault(0L))
    }.getOrNull()

    companion object {
        const val NAME_MAX = 80
        private const val SEP = "\u001f"
        // `name` last: the split's remainder, so any character in it is safe. route_json is JSON (control characters
        // are always escaped), so it never contains the separator.
        private const val COLS = "id || '$SEP' || mode || '$SEP' || updated_at || '$SEP' || route_json || '$SEP' || name"

        /** ≤ 80 characters, no control characters. */
        fun cleanName(name: String): String {
            val t = name.filter { !it.isISOControl() }.trim()
            return if (t.codePointCount(0, t.length) > NAME_MAX) t.substring(0, t.offsetByCodePoints(0, NAME_MAX)).trim() else t
        }
    }
}
