package my.gimano.app.backup

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import my.gimano.app.data.backup.*
import my.gimano.app.data.local.GimanoSchema
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** SqlPort over plain JDBC SQLite (xerial), so the real backup code runs in unit tests. */
class JdbcSqlPort(val conn: Connection) : SqlPort {
    private fun bind(sql: String, args: Array<out Any?>) = conn.prepareStatement(sql).apply { args.forEachIndexed { i, a -> setObject(i + 1, a) } }
    override fun exec(sql: String, vararg args: Any?) { bind(sql, args).use { it.execute() } }
    override fun queryLong(sql: String, vararg args: Any?): Long = bind(sql, args).use { st -> st.executeQuery().use { if (it.next()) it.getLong(1) else 0L } }
    override fun queryStrings(sql: String, vararg args: Any?): List<String> = bind(sql, args).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) ?: "") } } }
    override fun <T> transaction(block: () -> T): T {
        conn.autoCommit = false
        try { val r = block(); conn.commit(); return r } catch (e: Exception) { conn.rollback(); throw e } finally { conn.autoCommit = true }
    }
}

class BackupTest {
    private val dir: File = Files.createTempDirectory("gimano-backup").toFile()
    private fun newDb(name: String): JdbcSqlPort {
        val p = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:" + File(dir, name).path))
        GimanoSchema.create { p.exec(it) }
        p.exec("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")   // like a Room DB
        return p
    }
    private val crypto by lazy { BackupCrypto(LazySodiumJava(SodiumJava())) }
    private val now = "2026-10-23T00:00:00Z"
    private val later = "2026-10-23T05:00:00Z"

    private fun JdbcSqlPort.seed() {
        exec("INSERT INTO saved_places VALUES ('h','home','Rumah','home',3.1,101.6,'TTDI',NULL,0,?,?)", now, now)
        exec("INSERT INTO saved_places VALUES ('w','work','Pejabat','work',3.13,101.68,'KL Sentral',NULL,0,?,?)", now, now)
        exec("INSERT INTO saved_places VALUES ('f1','favorite','Kampung','kampung',2.2,102.2,'Melaka',NULL,0,?,?)", now, now)
        exec("INSERT INTO saved_places VALUES ('f2','favorite','Rumah Mertua','mertua',3.0,101.5,'Klang',NULL,1,?,?)", now, now)
        exec("INSERT INTO recents VALUES ('r1','Mid Valley','KL',3.11,101.67,'midvalley',?,3)", now)
        exec("INSERT INTO private_places VALUES ('p1','Gerai Pak Abu','restaurant',3.12,101.62,NULL,'nasi lemak',NULL,NULL,'Mo-Sa 07:00-11:00',NULL,?,?)", now, now)
        exec("INSERT INTO private_place_photos VALUES ('ph1','p1','ph1.webp',?)", now)
        exec("INSERT INTO planned_drives VALUES ('d1','Balik kampung',NULL,'{\"lat\":2.2,\"lng\":102.2}',NULL,'car',NULL,?)", now)
        exec("INSERT INTO personal_routes VALUES ('pr1','Ke kampung','car',?,1000,60,?,?)", ROUTE_JSON, now, now)
        exec("INSERT INTO settings VALUES ('voice_lang','ms')")
        exec("INSERT INTO outbox VALUES ('o1','feedback','{}',0,?)", now)
    }

    private fun rows(db: SqlPort, sql: String) = db.queryStrings(sql)

    private companion object {
        const val ROUTE_JSON = """{"v":1,"points":[{"lat":3.1,"lng":101.6},{"lat":3.2,"lng":101.7}],"legs":[{"source":"sketch","polyline":"_mpzD_w~|Q_pR_pR","distanceM":1000}]}"""
    }

    /** A schema-2 DB (before `personal_routes`), as an older Manotu or iOS build has it. */
    private fun newSchema2Db(name: String): JdbcSqlPort {
        val p = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:" + File(dir, name).path))
        GimanoSchema.DDL.filter { it != GimanoSchema.PERSONAL_ROUTES_DDL }.forEach { p.exec(it) }
        p.exec("PRAGMA user_version = 2")
        return p
    }

    /** Makes a .gimano from [src] and returns its bytes. */
    private fun backupOf(src: JdbcSqlPort, pass: String?): ByteArray {
        val snap = File(dir, "snap-${System.nanoTime()}.sqlite")
        BackupDb.snapshot(src, snap)
        val counts = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:${snap.path}")).let { s -> BackupDb.cleanSnapshot(s); BackupDb.counts(s).also { s.conn.close() } }
        val photo = File(dir, "ph1.webp").apply { writeBytes(ByteArray(70_000) { (it % 251).toByte() }) }   // > 1 chunk
        val m = BackupManifest(schemaVersion = GimanoSchema.VERSION, appVersion = "test", createdAt = now, counts = counts)
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, m, listOf(BackupArchive.DATA to snap, "photos/ph1.webp" to photo), pass, crypto, opslimit = 1, memlimit = 8L * 1024 * 1024)
        return out.toByteArray()
    }

    @Test fun schemaMatchesTheSharedDocExactly() {
        val doc = File("../../docs/privacy-and-backup.md").readText()
        val sql = doc.substringAfter("```sql").substringBefore("```")
        val fromDoc = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite::memory:"))
        // Strip "--" comments first: they may contain ';'.
        sql.lines().joinToString("\n") { l -> l.substringBefore("--") }.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { fromDoc.exec(it) }
        val ours = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite::memory:"))
        GimanoSchema.create { ours.exec(it) }
        val q = "SELECT type || ' ' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"
        assertEquals(rows(fromDoc, q), rows(ours, q))
        // Column-by-column, incl. types, NOT NULL, defaults and PK.
        GimanoSchema.ALL_TABLES.forEach { t ->
            val info = "SELECT name || ':' || type || ':' || \"notnull\" || ':' || COALESCE(dflt_value,'') || ':' || pk FROM pragma_table_info('$t')"
            assertEquals(t, rows(fromDoc, info), rows(ours, info))
        }
        val idx = "SELECT sql FROM sqlite_master WHERE name = 'ux_saved_home_work'"
        assertEquals(rows(fromDoc, idx).map { it.replace(Regex("\\s+"), " ") }, rows(ours, idx).map { it.replace(Regex("\\s+"), " ") })
        assertEquals(GimanoSchema.VERSION.toLong(), ours.queryLong("PRAGMA user_version"))
        // The CHECK and the partial unique index are real: one home only, many favourites.
        ours.exec("INSERT INTO saved_places VALUES ('a','home','H','home',1,1,NULL,NULL,0,'t','t')")
        assertThrows(Exception::class.java) { ours.exec("INSERT INTO saved_places VALUES ('b','home','H2','home',1,1,NULL,NULL,0,'t','t')") }
        assertThrows(Exception::class.java) { ours.exec("INSERT INTO saved_places VALUES ('c','boss','X','star',1,1,NULL,NULL,0,'t','t')") }
        ours.exec("INSERT INTO saved_places VALUES ('d','favorite','A','star',1,1,NULL,NULL,0,'t','t')")
        ours.exec("INSERT INTO saved_places VALUES ('e','favorite','B','star',1,1,NULL,NULL,0,'t','t')")
    }

    @Test fun roundTripReplaceIntoAFreshPhone() {
        val src = newDb("src.db").apply { seed() }
        val bytes = backupOf(src, null)
        val m = BackupArchive.readManifest(bytes.inputStream())
        assertEquals(BackupManifest.ENCRYPTION_NONE, m.encryption)
        assertEquals(BackupCounts(4, 1, 1, 1, 1, 1), m.counts)
        val manifestJson = String(java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> z.nextEntry; z.readBytes() })
        assertTrue(manifestJson, manifestJson.contains("\"personal_routes\":1"))

        val ex = BackupArchive.extract(bytes.inputStream(), File(dir, "x1"), null, crypto)
        assertEquals(listOf("ph1.webp"), ex.photos.map { it.name })
        assertEquals(70_000L, ex.photos.single().length())
        // The snapshot carries no device-local tables.
        val snap = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:${ex.dbFile.path}"))
        assertEquals(emptyList<String>(), rows(snap, "SELECT name FROM sqlite_master WHERE name IN ('outbox','room_master_table')"))
        snap.conn.close()

        val dst = newDb("dst.db")
        val photos = BackupDb.restore(dst, ex.dbFile, RestoreMode.REPLACE)
        assertEquals(listOf("ph1.webp"), photos)
        GimanoSchema.BACKED_UP_TABLES.forEach { t ->
            assertEquals(t, rows(src, "SELECT * FROM $t ORDER BY 1").size, rows(dst, "SELECT * FROM $t ORDER BY 1").size)
        }
        assertEquals(listOf("Kampung", "Pejabat", "Rumah", "Rumah Mertua"), rows(dst, "SELECT name FROM saved_places ORDER BY name"))
        assertEquals(rows(src, "SELECT * FROM personal_routes"), rows(dst, "SELECT * FROM personal_routes"))
        assertEquals(listOf("pr1|Ke kampung|car|1000|60|$ROUTE_JSON"), rows(dst, "SELECT id||'|'||name||'|'||mode||'|'||distance_m||'|'||duration_s||'|'||route_json FROM personal_routes"))
        assertEquals(0L, dst.queryLong("SELECT COUNT(*) FROM outbox"))
    }

    @Test fun encryptedRoundTripAndWrongPassphrase() {
        val src = newDb("enc-src.db").apply { seed() }
        val bytes = backupOf(src, "kunci rahsia saya")
        val m = BackupArchive.readManifest(bytes.inputStream())
        assertEquals(BackupManifest.ENCRYPTION_PASSPHRASE, m.encryption)
        assertNotNull(m.kdf)
        // Same layout as iOS (ios/Gimano/Storage/Backup.swift): manifest.json + payload.enc only.
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertEquals(listOf("manifest.json", "payload.enc"), names)
        assertEquals("argon2id13", m.kdf!!.alg)
        assertEquals(65_536, m.kdf!!.chunkSize)
        val json = String(java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> z.nextEntry; z.readBytes() })
        assertTrue(json, json.contains("\"chunkSize\":65536") && json.contains("\"counts\""))
        // Encrypted entries don't contain the SQLite header in clear text.
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SQLite format 3"))

        assertThrows(WrongPassphraseException::class.java) { BackupArchive.extract(bytes.inputStream(), File(dir, "bad"), "salah", crypto) }
        assertThrows(WrongPassphraseException::class.java) { BackupArchive.extract(bytes.inputStream(), File(dir, "none"), null, crypto) }

        val ex = BackupArchive.extract(bytes.inputStream(), File(dir, "good"), "kunci rahsia saya", crypto)
        assertEquals(70_000L, ex.photos.single().length())
        val dst = newDb("enc-dst.db")
        BackupDb.restore(dst, ex.dbFile, RestoreMode.REPLACE)
        assertEquals(1L, dst.queryLong("SELECT COUNT(*) FROM private_places"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val src = newDb("tamper.db").apply { seed() }
        val bytes = backupOf(src, "passphrase!")
        val key = crypto.deriveKey("passphrase!", ByteArray(16), 1, 8L * 1024 * 1024)
        val enc = ByteArrayOutputStream().also { crypto.encrypt(key, "hello".toByteArray().inputStream(), it) }.toByteArray()
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 1).toByte()
        assertThrows(WrongPassphraseException::class.java) { crypto.decrypt(key, enc.inputStream(), ByteArrayOutputStream()) }
        assertTrue(bytes.isNotEmpty())
    }

    @Test fun mergeRules() {
        val src = newDb("m-src.db").apply { seed() }
        // Backup is newer for Kampung, has a recent the phone doesn't, and its own Home.
        src.exec("UPDATE saved_places SET name = 'Kampung Halaman', updated_at = ? WHERE id = 'f1'", later)
        src.exec("INSERT INTO recents VALUES ('r2','KLIA','Sepang',2.74,101.70,'klia',?,1)", later)
        // Personal routes: backup newer for pr1, phone newer for pr2, backup-only pr3, phone-only pr9.
        src.exec("UPDATE personal_routes SET name = 'Ke kampung (baru)', updated_at = ? WHERE id = 'pr1'", later)
        src.exec("INSERT INTO personal_routes VALUES ('pr2','Lama dalam sandaran','walk',?,NULL,NULL,?,?)", ROUTE_JSON, now, now)
        src.exec("INSERT INTO personal_routes VALUES ('pr3','Hanya sandaran','bicycle',?,NULL,NULL,?,?)", ROUTE_JSON, now, now)
        val ex = BackupArchive.extract(backupOf(src, null).inputStream(), File(dir, "m"), null, crypto)

        val dst = newDb("m-dst.db")
        dst.exec("INSERT INTO saved_places VALUES ('h-local','home','Rumah Baru','home',3.2,101.7,NULL,NULL,0,?,?)", later, later)
        dst.exec("INSERT INTO saved_places VALUES ('f2','favorite','Mertua (edited)','mertua',3.0,101.5,'Klang',NULL,1,?,?)", later, later)   // phone newer
        dst.exec("INSERT INTO saved_places VALUES ('f9','favorite','Gym','gym',3.0,101.0,NULL,NULL,2,?,?)", now, now)                       // phone only
        dst.exec("INSERT INTO settings VALUES ('voice_lang','en')")
        dst.exec("INSERT INTO personal_routes VALUES ('pr1','Ke kampung','car',?,1000,60,?,?)", ROUTE_JSON, now, now)
        dst.exec("INSERT INTO personal_routes VALUES ('pr2','Disunting di telefon','walk',?,NULL,NULL,?,?)", ROUTE_JSON, now, later)
        dst.exec("INSERT INTO personal_routes VALUES ('pr9','Telefon sahaja','car',?,NULL,NULL,?,?)", ROUTE_JSON, now, now)

        BackupDb.restore(dst, ex.dbFile, RestoreMode.MERGE)
        assertEquals(listOf("pr1:Ke kampung (baru)", "pr2:Disunting di telefon", "pr3:Hanya sandaran", "pr9:Telefon sahaja"),
            rows(dst, "SELECT id || ':' || name FROM personal_routes ORDER BY id"))
        assertEquals("backup's Home wins", listOf("h:Rumah"), rows(dst, "SELECT id || ':' || name FROM saved_places WHERE kind = 'home'"))
        assertEquals("newer backup row wins", "Kampung Halaman", rows(dst, "SELECT name FROM saved_places WHERE id = 'f1'").single())
        assertEquals("newer phone row kept", "Mertua (edited)", rows(dst, "SELECT name FROM saved_places WHERE id = 'f2'").single())
        assertEquals("phone-only favourite kept", 1L, dst.queryLong("SELECT COUNT(*) FROM saved_places WHERE id = 'f9'"))
        assertEquals(2L, dst.queryLong("SELECT COUNT(*) FROM recents"))
        assertEquals("phone's settings win in a merge", "en", rows(dst, "SELECT value FROM settings WHERE key = 'voice_lang'").single())
        assertEquals(1L, dst.queryLong("SELECT COUNT(*) FROM private_place_photos"))

        // Merging the same backup twice changes nothing.
        val before = rows(dst, "SELECT id || updated_at FROM saved_places ORDER BY id") + rows(dst, "SELECT id || updated_at FROM personal_routes ORDER BY id")
        BackupDb.restore(dst, ex.dbFile, RestoreMode.MERGE)
        assertEquals(before, rows(dst, "SELECT id || updated_at FROM saved_places ORDER BY id") + rows(dst, "SELECT id || updated_at FROM personal_routes ORDER BY id"))

        // Replace: the phone's routes are gone, the backup's are there.
        BackupDb.restore(dst, ex.dbFile, RestoreMode.REPLACE)
        assertEquals(listOf("pr1", "pr2", "pr3"), rows(dst, "SELECT id FROM personal_routes ORDER BY id"))
    }

    @Test fun schema2BackupRestoresWithNoRoutes() {
        val old = newSchema2Db("v2-src.db").apply {
            exec("INSERT INTO saved_places VALUES ('f1','favorite','Kampung','kampung',2.2,102.2,'Melaka',NULL,0,?,?)", now, now)
            exec("INSERT INTO identity VALUES ('u-old',NULL,'c2VlZA==',?)", now)
        }
        val snap = File(dir, "v2-snap.sqlite")
        BackupDb.snapshot(old, snap)
        JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:${snap.path}")).let { s -> s.exec("PRAGMA user_version = 2"); s.conn.close() }
        // Merge: this phone's routes stay.
        val dst = newDb("v2-dst.db").apply { seed() }
        BackupDb.restore(dst, snap, RestoreMode.MERGE)
        assertEquals(listOf("pr1"), rows(dst, "SELECT id FROM personal_routes"))
        assertEquals(1L, dst.queryLong("SELECT COUNT(*) FROM saved_places WHERE id = 'f1'"))
        // Replace: everything comes from the backup, which has no routes.
        BackupDb.restore(dst, snap, RestoreMode.REPLACE)
        assertEquals(0L, dst.queryLong("SELECT COUNT(*) FROM personal_routes"))
        assertEquals(listOf("Kampung"), rows(dst, "SELECT name FROM saved_places"))
        assertEquals(listOf("u-old"), rows(dst, "SELECT user_id FROM identity"))
    }

    @Test fun backupFromNewerSchemaIsRefused() {
        val src = newDb("new.db").apply { seed(); exec("PRAGMA user_version = 9") }
        val snap = File(dir, "new-snap.sqlite")
        BackupDb.snapshot(src, snap)
        assertThrows(BackupTooNewException::class.java) { BackupDb.restore(newDb("old.db"), snap, RestoreMode.REPLACE) }
    }

    @Test fun notAGimanoFile() {
        assertThrows(BackupFormatException::class.java) { BackupArchive.readManifest("hello".toByteArray().inputStream()) }
    }

    @Test fun fingerprintChangesOnlyWhenDataChanges() {
        val db = newDb("fp.db").apply { seed() }
        val a = BackupDb.fingerprint(db)
        assertEquals(a, BackupDb.fingerprint(db))
        db.exec("INSERT INTO outbox VALUES ('o2','feedback','{}',0,?)", now)
        assertEquals("outbox is not backed up", a, BackupDb.fingerprint(db))
        db.exec("UPDATE saved_places SET name = 'X', updated_at = ? WHERE id = 'f1'", later)
        val b = BackupDb.fingerprint(db)
        assertNotEquals(a, b)
        db.exec("UPDATE personal_routes SET name = 'Y', updated_at = ? WHERE id = 'pr1'", "2026-10-24T00:00:00Z")
        assertNotEquals("a personal route edit is a change", b, BackupDb.fingerprint(db))
    }
}
