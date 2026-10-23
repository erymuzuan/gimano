package my.gimano.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.gimano.app.BuildConfig
import my.gimano.app.data.local.GimanoDatabase
import my.gimano.app.data.local.GimanoSchema
import my.gimano.app.data.local.SettingEntity
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

data class RestoreResult(val manifest: BackupManifest, val mode: RestoreMode, val photosRestored: Int)

/**
 * Creates and restores `.gimano` backups of the on-device DB + private-place photos.
 * Destinations (file, Google Drive) only move the bytes; this class owns the format.
 */
class BackupManager(
    private val context: Context,
    private val db: GimanoDatabase,
    private val crypto: BackupCrypto,
    /** Copies user-facing settings into the DB `settings` table before a backup. */
    private val exportSettings: suspend () -> Map<String, String>,
    /** Applies the DB `settings` table to the app after a restore. */
    private val importSettings: suspend (Map<String, String>) -> Unit,
    /** Keystore-wrapped seed → plain base64 (the backup's own passphrase encryption protects it). */
    private val seedToPlain: (String) -> String = { it },
    /** After a restore: re-wrap the identity seed and sign in as the restored pseudonym. */
    private val afterRestore: suspend () -> Unit = {},
) {
    val photosDir: File get() = File(context.filesDir, "photos").also { it.mkdirs() }
    private fun port() = AndroidSqlPort(db.openHelper.writableDatabase)

    suspend fun fingerprint(): String = withContext(Dispatchers.IO) { BackupDb.fingerprint(port()) }

    suspend fun createBackup(out: OutputStream, passphrase: String?): BackupManifest = withContext(Dispatchers.IO) {
        exportSettings().forEach { (k, v) -> db.settings().put(SettingEntity(k, v)) }
        val work = File(context.cacheDir, "backup-out").apply { deleteRecursively(); mkdirs() }
        try {
            val snap = File(work, BackupArchive.DATA)
            if (Build.VERSION.SDK_INT >= 30) {
                BackupDb.snapshot(port(), snap)
            } else {
                // SQLite < 3.27 has no VACUUM INTO: the DB uses a rollback journal, so a copy taken
                // inside a read transaction is consistent.
                port().transaction { context.getDatabasePath(GimanoSchema.DB_NAME).copyTo(snap, overwrite = true) }
            }
            val counts = SQLiteDatabase.openDatabase(snap.path, null, SQLiteDatabase.OPEN_READWRITE).use { s ->
                val p = FrameworkSqlPort(s)
                BackupDb.cleanSnapshot(p)
                BackupDb.transformIdentitySeeds(p, seedToPlain)
                BackupDb.counts(p)
            }
            val photoNames = db.privatePlaces().allPhotos().map { it.fileName }
            val files = listOf(BackupArchive.DATA to snap) +
                photoNames.map { File(photosDir, it) }.filter { it.exists() }.map { "${BackupArchive.PHOTOS}${it.name}" to it }
            val manifest = BackupManifest(
                schemaVersion = GimanoSchema.VERSION,
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                createdAt = Instant.now().toString(),
                counts = counts,
            )
            BackupArchive.write(out, manifest, files, passphrase, crypto)
            manifest
        } finally {
            work.deleteRecursively()
        }
    }

    suspend fun readManifest(input: InputStream): BackupManifest = withContext(Dispatchers.IO) { BackupArchive.readManifest(input) }

    suspend fun restore(input: InputStream, passphrase: String?, mode: RestoreMode): RestoreResult = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "backup-in").apply { deleteRecursively(); mkdirs() }
        try {
            val ex = BackupArchive.extract(input, work, passphrase, crypto)
            val names = BackupDb.restore(port(), ex.dbFile, mode)
            if (mode == RestoreMode.REPLACE) photosDir.listFiles()?.forEach { it.delete() }
            var n = 0
            ex.photos.filter { it.name in names }.forEach { f -> f.copyTo(File(photosDir, f.name), overwrite = true); n++ }
            db.invalidationTracker.refreshVersionsAsync()
            importSettings(db.settings().all().associate { it.key to it.value })
            runCatching { afterRestore() }
            RestoreResult(ex.manifest, mode, n)
        } finally {
            work.deleteRecursively()
        }
    }
}

class FrameworkSqlPort(private val db: SQLiteDatabase) : SqlPort {
    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun queryLong(sql: String, vararg args: Any?): Long =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    override fun queryStrings(sql: String, vararg args: Any?): List<String> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) ?: "") } }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block(); db.setTransactionSuccessful(); return r
        } finally {
            db.endTransaction()
        }
    }
}
