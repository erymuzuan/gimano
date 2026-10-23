package my.gimano.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SavedPlaceEntity::class, RecentEntity::class, PrivatePlaceEntity::class, PrivatePlacePhotoEntity::class,
        PlannedDriveEntity::class, SettingEntity::class, OutboxEntity::class,
    ],
    version = GimanoSchema.VERSION,
    exportSchema = true,
)
abstract class GimanoDatabase : RoomDatabase() {
    abstract fun savedPlaces(): SavedPlaceDao
    abstract fun recents(): RecentDao
    abstract fun privatePlaces(): PrivatePlaceDao
    abstract fun plannedDrives(): PlannedDriveDao
    abstract fun settings(): SettingDao
    abstract fun outbox(): OutboxDao

    companion object {
        /**
         * Room creates its tables first; we immediately replace them with the shared DDL so the
         * file is byte-for-byte the documented schema (CHECK, partial unique index, FK cascade).
         * Room's identity hash in room_master_table keeps its own consistency check happy.
         */
        val sharedSchemaCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                GimanoSchema.ALL_TABLES.reversed().forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
                GimanoSchema.create { db.execSQL(it) }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        /** 0.1.0 → 0.2.0: adds `identity`. It is not a Room entity (raw SQL only), so Room's validation is unaffected. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL(GimanoSchema.IDENTITY_DDL) }
        }

        fun build(context: Context, name: String = GimanoSchema.DB_NAME): GimanoDatabase =
            Room.databaseBuilder(context.applicationContext, GimanoDatabase::class.java, name)
                .addCallback(sharedSchemaCallback)
                // No WAL: backup restore ATTACHes the backup file, which the framework only allows
                // cleanly without WAL; the DB is tiny so this costs nothing measurable.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
