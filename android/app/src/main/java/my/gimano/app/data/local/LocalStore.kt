package my.gimano.app.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.model.Recent
import my.gimano.app.data.model.SavedPlace
import java.time.Instant
import java.util.UUID

/** A local search hit (favourites and private places are matched on the device, never on the server). */
data class LocalHit(val id: String, val name: String, val address: String, val lat: Double, val lng: Double, val icon: String, val kind: String, val placeId: String?)

/**
 * All personal data (docs/privacy-and-backup.md): favourites, Home/Work, recents, private places,
 * planned drives, settings. Lives only in the on-device DB.
 */
class LocalStore(val db: GimanoDatabase, private val now: () -> Instant = Instant::now) {

    private fun ts() = now().toString()

    // --- Saved places -----------------------------------------------------------------------

    val savedPlaces: Flow<List<SavedPlace>> = db.savedPlaces().observeAll().map { l -> l.map { it.toModel() } }

    suspend fun savedPlacesNow(): List<SavedPlace> = db.savedPlaces().all().map { it.toModel() }

    /** Home/Work: one each; setting it again replaces the old one. */
    suspend fun setHomeWork(kind: String, name: String, lat: Double, lng: Double, address: String?, placeId: String?): SavedPlace {
        require(kind == "home" || kind == "work")
        val existing = db.savedPlaces().byKind(kind)
        val e = SavedPlaceEntity(
            id = existing?.id ?: UUID.randomUUID().toString(), kind = kind, name = name.ifBlank { kind }, icon = kind,
            lat = lat, lng = lng, address = address, placeId = placeId,
            createdAt = existing?.createdAt ?: ts(), updatedAt = ts(),
        )
        db.savedPlaces().upsert(e)
        return e.toModel()
    }

    suspend fun addFavorite(label: String, icon: String, lat: Double, lng: Double, address: String?, placeId: String?): SavedPlace {
        val name = Favorites.normalizeLabel(label)
        require(name.isNotEmpty()) { "label required" }
        check(db.savedPlaces().favoriteCount() < Favorites.MAX_FAVORITES) { "max ${Favorites.MAX_FAVORITES} favourites" }
        val e = SavedPlaceEntity(
            id = UUID.randomUUID().toString(), kind = "favorite", name = name, icon = Favorites.validIcon(icon),
            lat = lat, lng = lng, address = address, placeId = placeId,
            sortOrder = db.savedPlaces().maxFavoriteOrder() + 1, createdAt = ts(), updatedAt = ts(),
        )
        db.savedPlaces().upsert(e)
        return e.toModel()
    }

    suspend fun updateFavorite(id: String, label: String, icon: String) {
        val e = db.savedPlaces().byId(id) ?: return
        val name = Favorites.normalizeLabel(label).ifEmpty { e.name }
        db.savedPlaces().upsert(e.copy(name = name, icon = if (e.kind == "favorite") Favorites.validIcon(icon) else e.icon, updatedAt = ts()))
    }

    suspend fun deleteSaved(id: String) = db.savedPlaces().delete(id)

    suspend fun moveFavorite(id: String, delta: Int) {
        val favs = db.savedPlaces().all().filter { it.kind == "favorite" }.map { it.id }
        Favorites.move(favs, id, delta).forEachIndexed { i, fid -> db.savedPlaces().setOrder(fid, i, ts()) }
    }

    // --- Recents ----------------------------------------------------------------------------

    val recents: Flow<List<Recent>> = db.recents().observe(20).map { l -> l.map { it.toModel() } }

    suspend fun recordRecent(name: String, address: String?, lat: Double, lng: Double, placeId: String?) {
        val existing = db.recents().find(placeId, lat, lng)
        db.recents().upsert(
            existing?.copy(name = name, address = address ?: existing.address, lastUsedAt = ts(), useCount = existing.useCount + 1)
                ?: RecentEntity(UUID.randomUUID().toString(), name, address, lat, lng, placeId, ts(), 1),
        )
        db.recents().prune()
    }

    suspend fun deleteRecent(id: String) = db.recents().delete(id)

    // --- Local search -----------------------------------------------------------------------

    /** Local hits come first in search results; nearest first. */
    suspend fun search(q: String, near: GeoPoint?): List<LocalHit> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        val saved = db.savedPlaces().search(t).map { LocalHit(it.id, it.name, it.address ?: "", it.lat, it.lng, it.icon, it.kind, it.placeId) }
        val priv = db.privatePlaces().search(t).map { LocalHit("private:${it.id}", it.name, it.address ?: "", it.lat, it.lng, "lock", "private", null) }
        val all = saved + priv
        return if (near == null) all else all.sortedBy { GeoMath.distanceM(near, GeoPoint(it.lat, it.lng)) }
    }

    // --- Settings (the backed-up, user-facing ones) -------------------------------------------

    suspend fun settings(): Map<String, String> = db.settings().all().associate { it.key to it.value }
    suspend fun putSetting(key: String, value: String) = db.settings().put(SettingEntity(key, value))

    // --- Import from the legacy server copy (one-time migration) ------------------------------

    suspend fun importLegacy(saved: List<SavedPlace>, recents: List<Recent>) {
        saved.forEach { s ->
            when (s.kind) {
                "home", "work" -> if (db.savedPlaces().byKind(s.kind) == null) setHomeWork(s.kind, s.name, s.lat, s.lng, s.address, s.placeId)
                else -> if (db.savedPlaces().byId(s.id) == null) db.savedPlaces().upsert(
                    SavedPlaceEntity(s.id, "favorite", Favorites.normalizeLabel(s.name).ifEmpty { "Favourite" }, Favorites.validIcon(s.icon),
                        s.lat, s.lng, s.address, s.placeId, s.sortOrder, ts(), ts()),
                )
            }
        }
        recents.forEach { r ->
            if (db.recents().find(r.placeId, r.lat, r.lng) == null) {
                db.recents().upsert(RecentEntity(r.id, r.name, r.address, r.lat, r.lng, r.placeId, r.lastUsedAt ?: ts(), 1))
            }
        }
        db.recents().prune()
    }
}

fun SavedPlaceEntity.toModel() = SavedPlace(id, kind, name, lat, lng, address ?: "", placeId, icon, sortOrder)
fun RecentEntity.toModel() = Recent(id, name, address ?: "", lat, lng, placeId, lastUsedAt)
