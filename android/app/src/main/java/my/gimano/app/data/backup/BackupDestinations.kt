package my.gimano.app.data.backup

/** Where a backup can go. Only moves bytes; [BackupManager] owns the format. */
enum class BackupDestination { DRIVE, FILE, DEMO }

/**
 * The one place that decides which backup destinations exist in this build.
 *
 * `BuildConfig.DRIVE_BACKUP` is false in the Google Play build (owner decision 2026-10-24): no Google Drive
 * destination, no Google sign-in / Drive authorisation, no Drive auto-backup and no Drive restore. The website APK
 * (manotu release) and debug builds keep Drive. File backup/restore and demo storage are in every build.
 *
 * Drive is not merely hidden in the Play build: [drive] returns null there, so the container has no Drive client
 * and the auto-backup worker has nothing to upload to.
 */
object BackupDestinations {
    /** The destinations offered on the Backup screen, in display order. */
    fun available(driveBackup: Boolean, demoMode: Boolean): List<BackupDestination> = buildList {
        if (driveBackup) add(BackupDestination.DRIVE)
        add(BackupDestination.FILE)
        if (demoMode) add(BackupDestination.DEMO)
    }

    /** Automatic backups go only to Drive (never prompts, never a file); null when this build has no Drive. */
    fun autoBackup(driveBackup: Boolean): BackupDestination? = if (driveBackup) BackupDestination.DRIVE else null

    /** The Drive client factory: [make] is never called when [driveBackup] is false. */
    fun <T : Any> drive(driveBackup: Boolean, make: () -> T): T? = if (driveBackup) make() else null
}
