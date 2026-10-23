package my.gimano.app.backup

import my.gimano.app.ui.backup.FileBackupGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file backup the user asked to encrypt is never written without its passphrase (e.g. lost to process death). */
class FileBackupGuardTest {
    @Test fun encryptedChoiceNeedsThePassphrase() {
        assertTrue(FileBackupGuard.mayWrite(true, "correct horse"))
        assertFalse("passphrase gone: abort, never write it unencrypted", FileBackupGuard.mayWrite(true, null))
        assertTrue(FileBackupGuard.mayWrite(false, null))
        assertFalse("unknown choice (stale picker result): abort", FileBackupGuard.mayWrite(null, null))
        assertFalse(FileBackupGuard.mayWrite(null, "x"))
    }
}
