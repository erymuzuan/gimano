package my.gimano.app.data.events

import android.content.Context
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
 */
@Entity(tableName = "event_revision", indices = [Index("event_id")])
data class EventRevisionEntity(
    /** b64u(SHA-256(event bytes)). */
    @PrimaryKey val hash: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    val rev: Long,
    val active: Boolean,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    val payload: ByteArray?,
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

@Database(entities = [EventPinEntity::class, EventRevisionEntity::class], version = 1, exportSchema = true)
abstract class EventsDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        const val DB_NAME = "manotu-events.db"

        fun build(context: Context): EventsDatabase =
            Room.databaseBuilder(context.applicationContext, EventsDatabase::class.java, DB_NAME).build()
    }
}

/** Room transaction wrapper, injectable so the store runs on a fake DAO in JVM tests. */
fun EventsDatabase.transactor(): suspend (suspend () -> Unit) -> Unit = { block -> withTransaction { block() } }
