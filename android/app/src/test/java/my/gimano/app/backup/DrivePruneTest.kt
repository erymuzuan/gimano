package my.gimano.app.backup

import my.gimano.app.data.backup.DriveFile
import my.gimano.app.data.backup.DrivePrune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePruneTest {
    private fun f(id: String, day: Int, install: String?) =
        DriveFile(id, "b-$id", "2026-10-%02dT10:00:00Z".format(day), appProperties = install?.let { mapOf(DrivePrune.INSTALL_ID to it) })

    @Test fun onlyThisInstallsOldFilesAreDeleted() {
        val mine = (1..8).map { f("m$it", it, "me") }
        val others = listOf(f("o1", 1, "other-phone"), f("o2", 2, "ios-install"), f("u1", 1, null), f("u2", 3, null),
            DriveFile("g1", "gimano-2026-10-01.gimano", "2026-10-01T00:00:00Z", appProperties = mapOf("app" to "android")))
        val del = DrivePrune.toDelete((others + mine).shuffled(java.util.Random(7)), "me")
        assertEquals(listOf("m3", "m2", "m1"), del.map { it.id })
        assertTrue(del.none { it.installId != "me" })
    }

    @Test fun untaggedFolderIsNeverPruned() {
        val legacy = (1..20).map { f("g$it", it, null) }   // e.g. Gimano's pre-tagging backups
        assertTrue(DrivePrune.toDelete(legacy, "me").isEmpty())
        assertTrue(DrivePrune.toDelete(legacy + (1..3).map { f("m$it", it, "me") }, "me").isEmpty())
    }

    @Test fun blankInstallIdDeletesNothing() {
        assertTrue(DrivePrune.toDelete((1..9).map { f("x$it", it, "") }, "").isEmpty())
    }
}
