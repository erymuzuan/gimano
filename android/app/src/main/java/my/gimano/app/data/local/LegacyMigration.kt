package my.gimano.app.data.local

import my.gimano.app.data.model.MyDataExport

/**
 * One-time move of any server-side favourites/recents into the local DB, then deletion on the
 * server (docs/privacy-and-backup.md). Marked done only when the server copy is gone for good.
 */
class LegacyMigration(
    private val export: suspend () -> MyDataExport?,
    private val import: suspend (MyDataExport) -> Unit,
    private val deleteRemote: suspend () -> Unit,
    private val markDone: suspend () -> Unit,
) {
    enum class Outcome { IMPORTED, NOTHING_ON_SERVER, NOT_AVAILABLE_YET, FAILED }

    suspend fun run(): Outcome = try {
        val data = export()
        when {
            data == null -> Outcome.NOT_AVAILABLE_YET            // 404/405/410: endpoint not shipped (or gone): try again later
            data.savedPlaces.isEmpty() && data.recents.isEmpty() -> {
                deleteRemote(); markDone(); Outcome.NOTHING_ON_SERVER
            }
            else -> {
                import(data)          // idempotent: re-running after a failed delete doesn't duplicate
                deleteRemote()
                markDone()
                Outcome.IMPORTED
            }
        }
    } catch (e: Exception) {
        Outcome.FAILED
    }
}
