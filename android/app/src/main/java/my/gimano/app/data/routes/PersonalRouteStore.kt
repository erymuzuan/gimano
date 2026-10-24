package my.gimano.app.data.routes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import my.gimano.app.core.routes.BuilderRoute
import my.gimano.app.data.events.EventVault
import my.gimano.app.data.events.PersonalRouteDao
import my.gimano.app.data.events.PersonalRouteEntity

/** A personal route as listed: its name is private and lives only in the sealed payload. */
data class PersonalRoute(val id: String, val name: String, val route: BuilderRoute, val updatedAt: Long)

@Serializable
internal data class PersonalPayload(val name: String, val route: BuilderRoute)

/**
 * "Laluan saya": personal routes on this phone only (manotu-events.db, excluded from backups, sealed with the events
 * vault). Nothing here touches the network; sharing is the user's own "Kongsi GPX".
 */
class PersonalRouteStore(private val dao: PersonalRouteDao, private val vault: EventVault, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun view(e: PersonalRouteEntity): PersonalRoute? = runCatching {
        val p = json.decodeFromString(PersonalPayload.serializer(), vault.open(e.payload).toString(Charsets.UTF_8))
        PersonalRoute(e.id, p.name, p.route, e.updatedAt)
    }.getOrNull()

    fun routes(): Flow<List<PersonalRoute>> = dao.allFlow().map { rows -> rows.mapNotNull(::view) }

    suspend fun get(id: String): PersonalRoute? = dao.get(id)?.let(::view)

    suspend fun save(name: String, route: BuilderRoute) {
        val bytes = json.encodeToString(PersonalPayload.serializer(), PersonalPayload(name.trim(), route)).toByteArray()
        dao.upsert(PersonalRouteEntity(route.id, vault.seal(bytes), clock()))
    }

    suspend fun delete(id: String) = dao.delete(id)
}
