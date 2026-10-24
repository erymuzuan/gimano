package my.gimano.app.data.events

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * The organizer key pinned for one event id (docs/events.md §7.3) and when the event's access ends.
 * Lives in its own file, [EventsDatabase.DB_NAME], which is NOT in data_extraction_rules.xml / backup_rules.xml
 * (both are include-lists) and is never copied by the app's own backups (BackupManager reads gimano.db only): §7.6.
 */
@Entity(tableName = "event_pin")
data class EventPinEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "organizer_pub") val organizerPub: ByteArray,
    /** Epoch ms; the whole event (pin, revisions, payloads) is deleted at this time. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long,
)

/**
 * One imported revision. [payload] is the signed event bytes sealed with the Android Keystore ([EventVault]);
 * null for superseded revisions, whose hash is kept only to recognise them ("sudah diimport", parent checks).
 * [hash] is the revision hash (§7.4): a stops-only variant (§5.5) is stored under its `fullHash`, so the full event
 * of the same revision later replaces its payload in place.
 */
@Entity(tableName = "event_revision", indices = [Index("event_id")])
data class EventRevisionEntity(
    /** b64u(revision hash): SHA-256(event bytes), or `variant.fullHash` for a stops-only variant. */
    @PrimaryKey val hash: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    val rev: Long,
    val active: Boolean,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    val payload: ByteArray?,
    /** The stored bytes are a stops-only variant (§5.5); kept after [payload] is dropped, for the §7.4 decisions. v4. */
    @ColumnInfo(name = "stops_only", defaultValue = "0") val stopsOnly: Boolean = false,
)

@Dao
interface EventDao {
    @Query("SELECT * FROM event_pin ORDER BY expires_at")
    fun pinsFlow(): Flow<List<EventPinEntity>>

    @Query("SELECT * FROM event_pin WHERE event_id = :id")
    suspend fun pin(id: String): EventPinEntity?

    @Query("SELECT * FROM event_revision WHERE event_id = :id ORDER BY rev, imported_at")
    suspend fun revisions(id: String): List<EventRevisionEntity>

    @Query("SELECT * FROM event_revision WHERE active = 1")
    fun activeFlow(): Flow<List<EventRevisionEntity>>

    @Insert
    suspend fun insertPin(p: EventPinEntity)

    @Update
    suspend fun updatePin(p: EventPinEntity)

    @Insert
    suspend fun insertRevision(r: EventRevisionEntity)

    @Query("UPDATE event_revision SET active = (hash = :hash) WHERE event_id = :id")
    suspend fun activate(id: String, hash: String)

    /** "Laluan penuh ditambah": the full event replaces the stored stops-only variant of the same revision. */
    @Query("UPDATE event_revision SET payload = :payload, stops_only = 0 WHERE event_id = :id AND hash = :hash AND payload IS NOT NULL")
    suspend fun replacePayload(id: String, hash: String, payload: ByteArray): Int

    @Query("UPDATE event_revision SET payload = NULL WHERE event_id = :id AND rev < :rev")
    suspend fun dropPayloadsBelow(id: String, rev: Long)

    @Query("DELETE FROM event_revision WHERE event_id = :id")
    suspend fun deleteRevisions(id: String)

    @Query("DELETE FROM event_pin WHERE event_id = :id")
    suspend fun deletePin(id: String)

    @Query("SELECT event_id FROM event_pin WHERE expires_at <= :nowMs")
    suspend fun expiredIds(nowMs: Long): List<String>

    @Query("SELECT MIN(expires_at) FROM event_pin")
    suspend fun nextExpiry(): Long?
}

/**
 * An event this phone organises (host side). Everything is sealed with the events vault: [draft] (incl. organiser-only
 * notes), [secrets] (Ed25519 seed ‖ pack key: one pair per event, never reused) and [published] (the last signed bytes,
 * the next revision's parent). Same file as imported events, so the same backup exclusion applies.
 */
@Entity(tableName = "event_host")
data class HostEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    val draft: ByteArray,
    val secrets: ByteArray,
    val published: ByteArray?,
    @ColumnInfo(name = "published_rev") val publishedRev: Long?,
    /** Epoch ms of the last publication's `expiresAt`; the record (keys included) is deleted then. Null = never published. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface HostEventDao {
    @Query("SELECT * FROM event_host ORDER BY updated_at DESC")
    fun allFlow(): Flow<List<HostEventEntity>>

    @Query("SELECT * FROM event_host WHERE event_id = :id")
    suspend fun get(id: String): HostEventEntity?

    @Insert
    suspend fun insert(e: HostEventEntity)

    @Update
    suspend fun update(e: HostEventEntity)

    @Query("DELETE FROM event_host WHERE event_id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM event_host WHERE expires_at IS NOT NULL AND expires_at <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("SELECT MIN(expires_at) FROM event_host WHERE expires_at IS NOT NULL")
    suspend fun nextExpiry(): Long?
}

/**
 * LEGACY (v3): where personal routes ("Laluan saya") lived before they moved to the main, backed-up DB
 * (`personal_routes`, schema 3). [payload] is the name and the route sealed with the events vault. The table stays in
 * the schema but is emptied once on upgrade by [my.gimano.app.data.routes.PersonalRouteMove]; nothing writes to it.
 * (Not dropped in a v4 migration: Room would run it on open, before the move could read the rows.)
 */
@Entity(tableName = "personal_route")
data class PersonalRouteEntity(
    @PrimaryKey val id: String,
    val payload: ByteArray,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface PersonalRouteDao {
    @Query("SELECT * FROM personal_route")
    suspend fun all(): List<PersonalRouteEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PersonalRouteEntity)

    @Query("DELETE FROM personal_route WHERE id = :id")
    suspend fun delete(id: String)
}

/** Schema changes as plain SQL, so the JVM tests run the exact statements against a v2 database. */
object EventsMigrations {
    /** v2 → v3: personal routes. Must equal Room's `createSql` for [PersonalRouteEntity] (schemas/…/3.json). */
    val V2_TO_V3 = listOf(
        "CREATE TABLE IF NOT EXISTS `personal_route` (`id` TEXT NOT NULL, `payload` BLOB NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )

    val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = V2_TO_V3.forEach(db::execSQL)
    }
}

@Database(
    entities = [EventPinEntity::class, EventRevisionEntity::class, HostEventEntity::class, PersonalRouteEntity::class], version = 4, exportSchema = true,
    // 3 → 4: event_revision.stops_only (default 0: every revision imported before variants existed is a full event).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 3, to = 4)],
)
abstract class EventsDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun hosted(): HostEventDao
    abstract fun personalRoutes(): PersonalRouteDao

    companion object {
        const val DB_NAME = "manotu-events.db"

        fun build(context: Context): EventsDatabase =
            Room.databaseBuilder(context.applicationContext, EventsDatabase::class.java, DB_NAME)
                .addMigrations(EventsMigrations.MIGRATION_2_3)
                .build()
    }
}

/** Room transaction wrapper, injectable so the store runs on a fake DAO in JVM tests. */
fun EventsDatabase.transactor(): suspend (suspend () -> Unit) -> Unit = { block -> withTransaction { block() } }
