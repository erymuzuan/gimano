package my.gimano.app.backup

import my.gimano.app.BuildConfig
import my.gimano.app.data.backup.BackupDestination
import my.gimano.app.data.backup.BackupDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google Drive backup exists only where BuildConfig.DRIVE_BACKUP is true (website APK, debug); never in the Play build. */
class BackupDestinationsTest {
    private val play = false      // BuildConfig.DRIVE_BACKUP in the Play build
    private val website = true    // BuildConfig.DRIVE_BACKUP in the website release and debug builds

    @Test fun playFlagOffersNoDriveDestination() {
        assertEquals(listOf(BackupDestination.FILE), BackupDestinations.available(play, demoMode = false))
        assertEquals(listOf(BackupDestination.FILE, BackupDestination.DEMO), BackupDestinations.available(play, demoMode = true))
        assertFalse(BackupDestination.DRIVE in BackupDestinations.available(play, demoMode = true))
    }

    @Test fun playFlagAutoBackupCannotResolveDrive() {
        assertNull(BackupDestinations.autoBackup(play))
    }

    @Test fun playFlagNeverCreatesADriveClient() {
        var made = 0
        assertNull(BackupDestinations.drive(play) { made++; Any() })
        assertEquals(0, made)
    }

    @Test fun websiteFlagKeepsDrive() {
        assertEquals(listOf(BackupDestination.DRIVE, BackupDestination.FILE), BackupDestinations.available(website, demoMode = false))
        assertEquals(listOf(BackupDestination.DRIVE, BackupDestination.FILE, BackupDestination.DEMO), BackupDestinations.available(website, demoMode = true))
        assertEquals(BackupDestination.DRIVE, BackupDestinations.autoBackup(website))
        val client = Any()
        assertSame(client, BackupDestinations.drive(website) { client })
    }

    @Test fun fileBackupIsInEveryBuild() {
        for (drive in listOf(play, website)) for (demo in listOf(false, true))
            assertTrue(BackupDestination.FILE in BackupDestinations.available(drive, demo))
    }

    /** Runs in every unit-test variant: only the Play distribution turns Drive off. */
    @Test fun buildFlagMatchesDistribution() {
        assertEquals(BuildConfig.DISTRIBUTION != "play", BuildConfig.DRIVE_BACKUP)
        assertEquals(BuildConfig.DRIVE_BACKUP, BackupDestination.DRIVE in BackupDestinations.available(BuildConfig.DRIVE_BACKUP, demoMode = true))
    }
}
