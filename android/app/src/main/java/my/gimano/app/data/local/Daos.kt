package my.gimano.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY CASE kind WHEN 'home' THEN 0 WHEN 'work' THEN 1 ELSE 2 END, sort_order, created_at")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places ORDER BY CASE kind WHEN 'home' THEN 0 WHEN 'work' THEN 1 ELSE 2 END, sort_order, created_at")
    suspend fun all(): List<SavedPlaceEntity>

    @Query("SELECT * FROM saved_places WHERE kind = :kind LIMIT 1")
    suspend fun byKind(kind: String): SavedPlaceEntity?

    @Query("SELECT * FROM saved_places WHERE id = :id")
    suspend fun byId(id: String): SavedPlaceEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM saved_places WHERE kind = 'favorite'")
    suspend fun maxFavoriteOrder(): Int

    @Query("SELECT COUNT(*) FROM saved_places WHERE kind = 'favorite'")
    suspend fun favoriteCount(): Int

    @Upsert suspend fun upsert(e: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM saved_places WHERE kind = :kind") suspend fun deleteKind(kind: String)

    @Query("UPDATE saved_places SET sort_order = :order, updated_at = :now WHERE id = :id")
    suspend fun setOrder(id: String, order: Int, now: String)

    @Query("SELECT * FROM saved_places WHERE name LIKE '%' || :q || '%' OR address LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<SavedPlaceEntity>
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY last_used_at DESC LIMIT :limit")
    fun observe(limit: Int = 20): Flow<List<RecentEntity>>

    @Query("SELECT * FROM recents ORDER BY last_used_at DESC")
    suspend fun all(): List<RecentEntity>

    @Query("SELECT * FROM recents WHERE (place_id IS NOT NULL AND place_id = :placeId) OR (ABS(lat - :lat) < 0.0002 AND ABS(lng - :lng) < 0.0002) LIMIT 1")
    suspend fun find(placeId: String?, lat: Double, lng: Double): RecentEntity?

    @Upsert suspend fun upsert(e: RecentEntity)

    /** Keep the last 50. */
    @Query("DELETE FROM recents WHERE id NOT IN (SELECT id FROM recents ORDER BY last_used_at DESC LIMIT 50)")
    suspend fun prune()

    @Query("DELETE FROM recents WHERE id = :id") suspend fun delete(id: String)

    @Query("SELECT * FROM recents WHERE name LIKE '%' || :q || '%' ORDER BY last_used_at DESC LIMIT 5")
    suspend fun search(q: String): List<RecentEntity>
}

@Dao
interface PrivatePlaceDao {
    @Query("SELECT * FROM private_places ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<PrivatePlaceEntity>>

    @Query("SELECT * FROM private_places ORDER BY updated_at DESC")
    suspend fun all(): List<PrivatePlaceEntity>

    @Query("SELECT * FROM private_places WHERE id = :id") suspend fun byId(id: String): PrivatePlaceEntity?

    @Upsert suspend fun upsert(e: PrivatePlaceEntity)

    @Query("SELECT * FROM private_places WHERE name LIKE '%' || :q || '%' OR address LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<PrivatePlaceEntity>

    @Query("SELECT * FROM private_place_photos WHERE place_id = :placeId ORDER BY created_at")
    suspend fun photos(placeId: String): List<PrivatePlacePhotoEntity>

    @Query("SELECT * FROM private_place_photos") suspend fun allPhotos(): List<PrivatePlacePhotoEntity>

    @Upsert suspend fun upsertPhoto(e: PrivatePlacePhotoEntity)

    @Query("DELETE FROM private_place_photos WHERE place_id = :placeId") suspend fun deletePhotosOf(placeId: String)

    @Query("DELETE FROM private_places WHERE id = :id") suspend fun deletePlace(id: String)

    @Transaction
    suspend fun delete(id: String) {
        deletePhotosOf(id)
        deletePlace(id)
    }
}

@Dao
interface PlannedDriveDao {
    @Query("SELECT * FROM planned_drives ORDER BY COALESCE(depart_at, created_at)")
    fun observeAll(): Flow<List<PlannedDriveEntity>>

    @Query("SELECT * FROM planned_drives") suspend fun all(): List<PlannedDriveEntity>

    @Upsert suspend fun upsert(e: PlannedDriveEntity)

    @Query("DELETE FROM planned_drives WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings") suspend fun all(): List<SettingEntity>
    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun get(key: String): String?
    @Upsert suspend fun put(e: SettingEntity)
    @Query("DELETE FROM settings WHERE `key` = :key") suspend fun delete(key: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY next_attempt_at")
    fun observe(kind: String): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE next_attempt_at <= :now ORDER BY next_attempt_at")
    suspend fun due(now: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE kind = :kind") suspend fun all(kind: String): List<OutboxEntity>

    @Upsert suspend fun upsert(e: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: String)
}
