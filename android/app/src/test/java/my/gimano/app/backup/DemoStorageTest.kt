package my.gimano.app.backup

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import my.gimano.app.R
import my.gimano.app.data.backup.*
import my.gimano.app.data.local.GimanoSchema
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** Account-free review flow: demo storage round-trip (real format, passphrase) and plain Drive failure messages. */
class DemoStorageTest {
    private val dir: File = Files.createTempDirectory("manotu-demo").toFile()
    private val crypto = BackupCrypto(LazySodiumJava(SodiumJava()))

    private fun backupWithPlaces(pass: String?): ByteArray {
        val db = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:" + File(dir, "src-${System.nanoTime()}.db").path)).also { p -> GimanoSchema.create { p.exec(it) } }
        db.exec("INSERT INTO saved_places VALUES ('h','home','Rumah','home',3.1,101.6,'TTDI',NULL,0,'t','t')")
        db.exec("INSERT INTO saved_places VALUES ('f','favorite','Kampung','kampung',2.2,102.2,'Melaka',NULL,0,'t','t')")
        val snap = File(dir, "snap-${System.nanoTime()}.sqlite")
        BackupDb.snapshot(db, snap)
        val counts = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:${snap.path}")).let { s -> BackupDb.counts(s).also { s.conn.close() } }
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, BackupManifest(schemaVersion = GimanoSchema.VERSION, appVersion = "t", createdAt = "2026-10-24T00:00:00Z", counts = counts),
            listOf(BackupArchive.DATA to snap), pass, crypto, opslimit = 1, memlimit = 8L * 1024 * 1024)
        return out.toByteArray()
    }

    @Test fun demoStorageRoundTripsTheRealBackupFormatWithAPassphrase() {
        val store = DemoBackupStore(File(dir, DemoBackupStore.DIR))
        val bytes = backupWithPlaces("frasa rahsia demo")
        val e = store.save(bytes, nowMs = 1_761_264_000_000)
        assertTrue(e.name.startsWith("manotu-demo-") && e.name.endsWith(".manotu"))
        assertEquals(listOf(e.name), store.list().map { it.name })
        assertEquals(bytes.size.toLong(), store.list().single().sizeBytes)

        // Restore path: manifest says encrypted; the right passphrase gives the places back, a wrong one doesn't.
        assertNotEquals(BackupManifest.ENCRYPTION_NONE, store.open(e.name).use { BackupArchive.readManifest(it) }.encryption)
        val x = store.open(e.name).use { BackupArchive.extract(it, File(dir, "x"), "frasa rahsia demo", crypto) }
        val restored = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:${x.dbFile.path}"))
        assertEquals(listOf("Kampung", "Rumah"), restored.queryStrings("SELECT name FROM saved_places ORDER BY name"))
        assertThrows(WrongPassphraseException::class.java) { store.open(e.name).use { BackupArchive.extract(it, File(dir, "y"), "salah", crypto) } }
    }

    @Test fun demoStorageKeepsTheNewestFiveAndOpensOnlyItsOwnFiles() {
        val store = DemoBackupStore(File(dir, "keep"))
        val names = (0 until 7).map { i -> store.save(byteArrayOf(i.toByte()), nowMs = 1_761_264_000_000 + i * 60_000L).name }
        assertEquals(names.takeLast(5).reversed(), store.list().map { it.name })   // newest first
        assertThrows(IllegalArgumentException::class.java) { store.open("../databases/gimano.db") }
        assertThrows(IllegalArgumentException::class.java) { store.open("manotu-2026-10-24.manotu") }   // not a demo file
    }

    @Test fun driveFailuresArePlainAndNeverSuccess() {
        // Consent screen closed / back pressed / no account chosen: no token.
        assertEquals(DriveFailure.Kind.CANCELLED, DriveFailure.of(null))
        // Google Play services: CANCELED (16), SIGN_IN_CANCELLED (12501), NETWORK_ERROR (7), others = not available.
        assertEquals(DriveFailure.Kind.CANCELLED, DriveFailure.of(RuntimeException(), 16))
        assertEquals(DriveFailure.Kind.CANCELLED, DriveFailure.of(RuntimeException(), 12501))
        assertEquals(DriveFailure.Kind.OFFLINE, DriveFailure.of(RuntimeException(), 7))
        assertEquals(DriveFailure.Kind.UNAVAILABLE, DriveFailure.of(RuntimeException(), 10))
        assertEquals(DriveFailure.Kind.UNAVAILABLE, DriveFailure.of(RuntimeException(), 8))
        // Network below the HTTP layer, even wrapped.
        assertEquals(DriveFailure.Kind.OFFLINE, DriveFailure.of(java.io.IOException("x", java.net.UnknownHostException("www.googleapis.com"))))
        assertEquals(DriveFailure.Kind.OFFLINE, DriveFailure.of(java.net.SocketTimeoutException()))
        // Drive answered with an error.
        assertEquals(DriveFailure.Kind.REJECTED, DriveFailure.of(java.io.IOException("Drive upload failed: HTTP 403")))
        // Every message says nothing was backed up (never a success string).
        val msgs = DriveFailure.Kind.entries.map(DriveFailure::message)
        assertEquals(listOf(R.string.drive_cancelled, R.string.drive_offline, R.string.drive_unavailable, R.string.drive_rejected), msgs)
        assertFalse(R.string.backup_drive_done in msgs)
    }
}
