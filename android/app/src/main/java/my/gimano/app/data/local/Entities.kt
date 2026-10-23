package my.gimano.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Column-for-column mirrors of GimanoSchema.DDL (the real tables are created from that DDL).

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    @ColumnInfo(defaultValue = "'star'") val icon: String = "star",
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    @ColumnInfo(name = "place_id") val placeId: String? = null,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "place_id") val placeId: String? = null,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: String,
    @ColumnInfo(name = "use_count", defaultValue = "1") val useCount: Int = 1,
)

@Entity(tableName = "private_places")
data class PrivatePlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val description: String? = null,
    val phone: String? = null,
    val website: String? = null,
    @ColumnInfo(name = "opening_hours") val openingHours: String? = null,
    @ColumnInfo(name = "amenities_json") val amenitiesJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(tableName = "private_place_photos")
data class PrivatePlacePhotoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "place_id") val placeId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(tableName = "planned_drives")
data class PlannedDriveEntity(
    @PrimaryKey val id: String,
    val name: String? = null,
    @ColumnInfo(name = "origin_json") val originJson: String? = null,
    @ColumnInfo(name = "destination_json") val destinationJson: String,
    @ColumnInfo(name = "waypoints_json") val waypointsJson: String? = null,
    val vehicle: String,
    @ColumnInfo(name = "depart_at") val departAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: String,
)
