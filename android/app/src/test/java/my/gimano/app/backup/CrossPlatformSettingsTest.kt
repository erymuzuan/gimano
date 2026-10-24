package my.gimano.app.backup

import my.gimano.app.data.backup.BackupDb
import my.gimano.app.data.backup.RestoreMode
import my.gimano.app.data.local.GimanoSchema
import my.gimano.app.data.ugc.BlockedAuthor
import my.gimano.app.data.ugc.Blocking
import my.gimano.app.data.ugc.UgcTerms
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/**
 * docs/privacy-and-backup.md "Shared settings keys": the block list and the community rules acceptance use the same
 * keys and JSON as iOS (PR #5), so a backup from either platform restores both, and a merge unions the block list.
 */
class CrossPlatformSettingsTest {
    private val dir: File = Files.createTempDirectory("manotu-xplat").toFile()

    /** Exactly what iOS writes (Codable BlockedAuthor {ref, name, blockedAt}, JSONEncoder .iso8601). */
    private val iosRow = """[{"ref":"h-kancil","name":"Kancil 1204","blockedAt":"2026-10-23T08:15:00Z"},{"ref":"h-badak","name":"Badak (iPhone)","blockedAt":"2026-10-22T01:00:00Z"}]"""

    private fun db(name: String) = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:" + File(dir, name).path)).also { p -> GimanoSchema.create { p.exec(it) } }
    private fun JdbcSqlPort.setting(key: String) = queryStrings("SELECT value FROM settings WHERE `key` = ?", key).firstOrNull()

    @Test fun iosRowsReadOnAndroid() {
        val list = Blocking.decode(iosRow)
        assertEquals(listOf("h-kancil", "h-badak"), list.map { it.ref })
        assertEquals("Kancil 1204", list[0].name)
        assertEquals("2026-10-23T08:15:00Z", list[0].blockedAt)
        // Tolerant: a broken entry is skipped, the rest is kept; extra fields from a newer app are ignored.
        val odd = """[{"name":"no ref"},{"ref":"h-x","name":"X","blockedAt":"2026-10-24T00:00:00Z","note":"future field"}]"""
        assertEquals(listOf("h-x"), Blocking.decode(odd).map { it.ref })
    }

    @Test fun androidRowsHaveTheIosShape() {
        val raw = Blocking.encode(listOf(BlockedAuthor("h-a", "A", "2026-10-24T03:00:00Z")))!!
        assertEquals("""[{"ref":"h-a","name":"A","blockedAt":"2026-10-24T03:00:00Z"}]""", raw)
    }

    @Test fun mergeIsTheUnionByRef() {
        val androidRow = Blocking.encode(listOf(BlockedAuthor("h-badak", "Badak 5880", "2026-10-24T03:00:00Z"), BlockedAuthor("h-helang", "Helang 77", "2026-10-24T04:00:00Z")))
        val merged = Blocking.decode(Blocking.union(androidRow, iosRow))
        assertEquals(listOf("h-badak", "h-helang", "h-kancil"), merged.map { it.ref })
        assertEquals("this phone's entry wins for a shared ref", "Badak 5880", merged.first { it.ref == "h-badak" }.name)
        assertEquals(iosRow.let { Blocking.decode(it) }, Blocking.decode(Blocking.union(null, iosRow)))
        assertNull(Blocking.union(null, null))
    }

    @Test fun aMergeRestoreUnionsTheBlockListAndKeepsTheLaterRulesAcceptance() {
        // The backup: made on an iPhone that blocked two authors and accepted the current rules.
        val ios = db("ios.db")
        ios.exec("INSERT INTO settings VALUES (?, ?)", Blocking.SETTINGS_KEY, iosRow)
        ios.exec("INSERT INTO settings VALUES (?, ?)", UgcTerms.VERSION_KEY, UgcTerms.VERSION)
        ios.exec("INSERT INTO settings VALUES (?, ?)", UgcTerms.ACCEPTED_AT_KEY, "2026-10-24T05:06:07.890Z")
        ios.exec("INSERT INTO settings VALUES (?, ?)", "sound_mode", "alerts")
        ios.conn.close()

        // This Android phone: blocked someone else, accepted an older rules version, its own sound choice.
        val android = db("android.db")
        android.exec("INSERT INTO settings VALUES (?, ?)", Blocking.SETTINGS_KEY, Blocking.encode(listOf(BlockedAuthor("h-helang", "Helang 77", "2026-10-24T04:00:00Z"))))
        android.exec("INSERT INTO settings VALUES (?, ?)", UgcTerms.VERSION_KEY, "2025-01-01")
        android.exec("INSERT INTO settings VALUES (?, ?)", UgcTerms.ACCEPTED_AT_KEY, "2025-01-02T00:00:00Z")
        android.exec("INSERT INTO settings VALUES (?, ?)", "sound_mode", "full")

        BackupDb.restore(android, File(dir, "ios.db"), RestoreMode.MERGE)

        assertEquals(setOf("h-helang", "h-kancil", "h-badak"), Blocking.decode(android.setting(Blocking.SETTINGS_KEY)).map { it.ref }.toSet())
        assertEquals("the later rules version wins", UgcTerms.VERSION, android.setting(UgcTerms.VERSION_KEY))
        assertEquals("…with its date", "2026-10-24T05:06:07.890Z", android.setting(UgcTerms.ACCEPTED_AT_KEY))
        assertEquals("other keys: this phone wins", "full", android.setting("sound_mode"))
    }

    @Test fun aReplaceRestoreTakesTheBackupAsIs() {
        val bk = db("bk-replace.db")
        bk.exec("INSERT INTO settings VALUES (?, ?)", Blocking.SETTINGS_KEY, iosRow)
        bk.conn.close()
        val phone = db("phone-replace.db")
        phone.exec("INSERT INTO settings VALUES (?, ?)", Blocking.SETTINGS_KEY, Blocking.encode(listOf(BlockedAuthor("h-helang", "Helang 77", "2026-10-24T04:00:00Z"))))
        BackupDb.restore(phone, File(dir, "bk-replace.db"), RestoreMode.REPLACE)
        assertEquals(listOf("h-kancil", "h-badak"), Blocking.decode(phone.setting(Blocking.SETTINGS_KEY)).map { it.ref })
    }

    @Test fun newerRulesVersionWins() {
        assertEquals("2026-10-24" to "b", UgcTerms.newer("2025-01-01" to "a", "2026-10-24" to "b"))
        assertEquals("2026-10-24" to "a", UgcTerms.newer("2026-10-24" to "a", "2026-10-24" to "b"))   // tie: this phone
        assertEquals("2026-10-24" to "a", UgcTerms.newer("2026-10-24" to "a", null to null))
    }
}
