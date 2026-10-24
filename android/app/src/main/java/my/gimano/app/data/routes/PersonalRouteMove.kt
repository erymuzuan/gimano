package my.gimano.app.data.routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import my.gimano.app.core.routes.BuilderRoute
import my.gimano.app.data.events.EventVault
import my.gimano.app.data.events.PersonalRouteDao
import java.time.Instant

/** The sealed payload personal routes had in manotu-events.db (EventsDatabase v3, before schema 3 of the main DB). */
@Serializable
internal data class LegacyPersonalPayload(val name: String, val route: BuilderRoute)

/**
 * One-time move of "Laluan saya" from the events DB (sealed, never backed up) into the main DB's `personal_routes`
 * (docs/privacy-and-backup.md, schema 3). Idempotent: each row is copied unless the main DB already has its id, then
 * deleted from the events DB; a second run finds nothing. Rows the vault cannot open (Keystore key gone) are left
 * where they are and never shown, as before.
 */
class PersonalRouteMove(private val legacy: PersonalRouteDao, private val vault: EventVault, private val store: PersonalRouteStore) {
    private val json = Json { ignoreUnknownKeys = true }

    /** @return how many routes were moved. */
    suspend fun run(): Int {
        var moved = 0
        for (e in legacy.all()) {
            val p = runCatching { json.decodeFromString(LegacyPersonalPayload.serializer(), vault.open(e.payload).toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            val at = Instant.ofEpochMilli(e.updatedAt)
            if (store.insertIfAbsent(p.name, p.route.copy(id = e.id), createdAt = at, updatedAt = at)) moved++
            legacy.delete(e.id)
        }
        if (moved > 0) store.invalidate()
        return moved
    }
}
