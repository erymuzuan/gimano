package my.gimano.app.data.events

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import my.gimano.app.core.events.DeferredLinks
import my.gimano.app.core.events.DropRef
import java.time.Instant

/**
 * The deferred link (docs/events.md §5.6) on Android: until an event is imported or 7 days after install, once per
 * launch and only while the app is in the foreground with focus, look at the clipboard for a `manotu.app/j/#…` link
 * and offer it ("Buka jemputan dari pautan yang disalin?"). Never imports by itself. Declined links are remembered as
 * hashes in app-private preferences (not in any backup). The clipboard text is never stored or logged.
 */
class DeferredLinkCheck(
    private val context: Context,
    private val scope: CoroutineScope,
    private val importer: EventImporter,
    private val store: EventStore,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var checked = false

    companion object {
        private const val PREFS = "manotu.deferred"
        private const val KEY_DONE = "done"
        private const val KEY_DISMISSED = "dismissed"
    }

    private fun firstLaunch(): Instant = runCatching {
        Instant.ofEpochMilli(context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime)
    }.getOrElse { Instant.now() }

    /** An answered link (opened or declined) is never offered again. */
    fun remember(ref: DropRef) {
        val set = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty() + DeferredLinks.dismissKey(ref)
        prefs.edit().putStringSet(KEY_DISMISSED, set.toSet()).apply()
    }

    /** Called when the activity gains window focus (Android only lets a focused app read the clipboard). */
    fun onFocus() {
        if (checked) return
        checked = true
        if (prefs.getBoolean(KEY_DONE, false)) return
        scope.launch {
            val imported = runCatching { store.hasAny() }.getOrDefault(false)
            if (!DeferredLinks.inWindow(firstLaunch(), Instant.now(), imported)) {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                return@launch
            }
            if (importer.ui.value != EventImportUi.Idle) return@launch   // opened by a link or file: that one wins
            val text = readClipText() ?: return@launch
            DeferredLinks.offer(text, prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty())?.let(importer::offerCopied)
        }
    }

    /** Plain text only; the description is checked first, so nothing is read when the clip is not text. */
    private fun readClipText(): String? = runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val desc = cm.primaryClipDescription ?: return null
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return null
        val clip = cm.primaryClip ?: return null
        (0 until clip.itemCount).joinToString("\n") { i -> clip.getItemAt(i).text?.toString() ?: clip.getItemAt(i).uri?.toString().orEmpty() }
            .take(4096)
    }.getOrNull()
}
