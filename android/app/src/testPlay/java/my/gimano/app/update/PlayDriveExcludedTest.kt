package my.gimano.app.update

import my.gimano.app.BuildConfig
import my.gimano.app.data.backup.BackupDestination
import my.gimano.app.data.backup.BackupDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs only for the Google Play build type (testManotuPlayUnitTest): no Google Drive backup (owner decision 2026-10-24). */
class PlayDriveExcludedTest {
    @Test fun playBuildHasNoDriveBackup() {
        assertFalse(BuildConfig.DRIVE_BACKUP)
        assertTrue(BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isEmpty())
        assertFalse(BackupDestination.DRIVE in BackupDestinations.available(BuildConfig.DRIVE_BACKUP, demoMode = true))
        assertNull(BackupDestinations.autoBackup(BuildConfig.DRIVE_BACKUP))
        assertNull(BackupDestinations.drive(BuildConfig.DRIVE_BACKUP) { error("no Drive client in the Play build") })
    }

    @Test fun playBuildKeepsFileAndDemoBackup() {
        assertEquals(listOf(BackupDestination.FILE, BackupDestination.DEMO), BackupDestinations.available(BuildConfig.DRIVE_BACKUP, demoMode = true))
    }
}
