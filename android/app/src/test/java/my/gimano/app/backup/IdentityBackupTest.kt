package my.gimano.app.backup

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import my.gimano.app.core.identity.Bip39
import my.gimano.app.core.identity.Ed25519
import my.gimano.app.core.identity.RequestSigning
import my.gimano.app.data.backup.*
import my.gimano.app.data.identity.IdentityService
import my.gimano.app.data.identity.IdentityStore
import my.gimano.app.data.identity.SeedWrapper
import my.gimano.app.data.local.GimanoSchema
import my.gimano.app.data.remote.SigningInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64

/** Test double for the Keystore wrapper: reversible, recognisable, and never plain base64. */
class FakeWrapper : SeedWrapper {
    override fun wrap(seed: ByteArray) = "ks1:" + Base64.getEncoder().encodeToString(seed.reversedArray())
    override fun unwrap(stored: String): ByteArray =
        if (isWrapped(stored)) Base64.getDecoder().decode(stored.removePrefix("ks1:")).reversedArray() else Base64.getDecoder().decode(stored)
    override fun isWrapped(stored: String) = stored.startsWith("ks1:")
}

class IdentityBackupTest {
    private val dir: File = Files.createTempDirectory("gimano-id").toFile()
    private val sodium = LazySodiumJava(SodiumJava())
    private val ed = Ed25519(sodium)
    private val crypto = BackupCrypto(sodium)
    private val wrapper = FakeWrapper()
    private val bip = { Bip39(File("src/main/assets/bip39-english.txt").readLines().filter { it.isNotBlank() }) }

    private fun newDb(name: String) = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:" + File(dir, name).path)).also { p -> GimanoSchema.create { p.exec(it) } }
    private fun service(db: JdbcSqlPort) = IdentityService(IdentityStore({ db }, wrapper), ed, bip)

    /** Mirrors BackupManager.createBackup: snapshot → clean → unwrap seeds → archive. */
    private fun backup(src: JdbcSqlPort, pass: String?): ByteArray {
        val snap = File(dir, "snap-${System.nanoTime()}.sqlite")
        BackupDb.snapshot(src, snap)
        val counts = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:${snap.path}")).let { s ->
            BackupDb.cleanSnapshot(s)
            BackupDb.transformIdentitySeeds(s) { v -> if (wrapper.isWrapped(v)) Base64.getEncoder().encodeToString(wrapper.unwrap(v)) else v }
            BackupDb.counts(s).also { s.conn.close() }
        }
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, BackupManifest(schemaVersion = GimanoSchema.VERSION, appVersion = "t", createdAt = "2026-10-23T00:00:00Z", counts = counts),
            listOf(BackupArchive.DATA to snap), pass, crypto, opslimit = 1, memlimit = 8L * 1024 * 1024)
        return out.toByteArray()
    }

    @Test fun seedIsWrappedAtRestAndKeysAreStable() {
        val db = newDb("a.db")
        val id = service(db)
        val first = id.ensure()
        assertFalse(first.registered)
        assertTrue("at rest the seed is wrapped", db.queryStrings("SELECT seed_b64 FROM identity").single().startsWith("ks1:"))
        val pk = id.publicKeyB64()
        assertEquals("ensure() is idempotent", pk, service(db).publicKeyB64())
        id.markRegistered("user-1", "Rusa 2368")
        assertEquals("user-1", service(db).current()!!.userId)
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM identity"))
        assertEquals(24, id.recoveryWords().size)
        assertArrayEquals(first.seed, (bip().decode(id.recoveryWords().joinToString(" ")) as Bip39.Result.Ok).entropy)
    }

    @Test fun backupCarriesThePlainSeedAndRestoreAdoptsAndRewrapsIt() {
        val src = newDb("src.db")
        val a = service(src); a.ensure(); a.markRegistered("user-A", "Rusa 2368")
        val seedA = a.current()!!.seed
        for (pass in listOf(null, "frasa rahsia")) {
            val bytes = backup(src, pass)
            val ex = BackupArchive.extract(bytes.inputStream(), File(dir, "x-${pass != null}"), pass, crypto)
            val inBackup = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:${ex.dbFile.path}")).queryStrings("SELECT seed_b64 FROM identity").single()
            assertArrayEquals("plain inside the backup (protected by the backup passphrase when set)", seedA, Base64.getDecoder().decode(inBackup))

            // A different phone with its own fresh identity restores (Merge too): it adopts A.
            val dst = newDb("dst-${pass != null}.db")
            val b = service(dst); b.ensure()
            BackupDb.restore(dst, ex.dbFile, RestoreMode.MERGE)
            b.reload()
            val now = b.current()!!
            assertEquals("user-A", now.userId)
            assertArrayEquals(seedA, now.seed)
            assertTrue("re-wrapped after restore", dst.queryStrings("SELECT seed_b64 FROM identity").single().startsWith("ks1:"))
            assertEquals(a.publicKeyB64(), b.publicKeyB64())
        }
    }

    @Test fun schema1BackupWithoutIdentityKeepsThisPhonesKey() {
        val old = newDb("v1.db")
        old.exec("DROP TABLE identity"); old.exec("PRAGMA user_version = 1")
        old.exec("INSERT INTO saved_places VALUES ('h','home','Rumah','home',3.1,101.6,NULL,NULL,0,'t','t')")
        val v1 = File(dir, "v1-snap.sqlite"); BackupDb.snapshot(old, v1)
        val dst = newDb("keep.db")
        val me = service(dst); me.ensure(); me.markRegistered("user-B", "Kenyalang 8483")
        BackupDb.restore(dst, v1, RestoreMode.REPLACE)
        me.reload()
        assertEquals("user-B", me.current()!!.userId)
        assertEquals(listOf("Rumah"), dst.queryStrings("SELECT name FROM saved_places"))
    }

    @Test fun upgradingA010DatabaseAddsIdentity() {
        val db = newDb("upgrade.db")
        db.exec("DROP TABLE identity"); db.exec("PRAGMA user_version = 1")
        db.exec(GimanoSchema.IDENTITY_DDL)   // what GimanoDatabase.MIGRATION_1_2 runs
        val info = "SELECT name || ':' || type || ':' || \"notnull\" || ':' || pk FROM pragma_table_info('identity')"
        val fresh = newDb("fresh.db")
        assertEquals(fresh.queryStrings(info), db.queryStrings(info))
    }

    @Test fun interceptorSignsTheDocVector() {
        val seed = ByteArray(32) { it.toByte() }
        val kp = ed.keyPair(seed)
        val body = """{"answer":"yes","nameCorrect":true,"locationCorrect":null,"proximity":{"cell":"w283ef","withinM":1000}}"""
        val req = Request.Builder().url("https://gimano-api.bukitbunga.com/api/v1/places/0b9f3c2e-5d41-4a7e-9a8c-2f1e6d3b7a10/verify")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val signed = SigningInterceptor.signed(req, 1761187200) { m, pq, ts, b -> RequestSigning.b64(ed.sign(kp, RequestSigning.canonical(m, pq, ts, b).toByteArray())) }!!
        assertEquals("1761187200", signed.header("X-Gimano-Ts"))
        assertEquals("SM344tg3ihSuOjuD04CgBIgHDy6Ov4zJMkh8gm6PPAtb8BixNMX8wAZ4xhOX160PfhZ3OFfi/u6wRlfPtbzKAA==", signed.header("X-Gimano-Sig"))
        val get = Request.Builder().url("https://x/api/v1/places/p1?lat=3.1&lng=101.6").build()
        assertNull("GET is not signed", SigningInterceptor.signed(get, 1) { _, _, _, _ -> "x" })
        val withQuery = Request.Builder().url("https://x/api/v1/reports?src=app").post("{}".toRequestBody("application/json".toMediaType())).build()
        var seen = ""
        SigningInterceptor.signed(withQuery, 1) { _, pq, _, _ -> seen = pq; "sig" }
        assertEquals("/api/v1/reports?src=app", seen)
    }
}
