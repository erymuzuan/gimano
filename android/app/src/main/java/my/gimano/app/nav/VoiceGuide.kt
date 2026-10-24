package my.gimano.app.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import my.gimano.app.core.GLog
import my.gimano.app.core.format.Lang
import java.util.Locale

/** TextToSpeech wrapper for English and Bahasa Melayu prompts. */
class VoiceGuide(
    context: Context,
    /** App voice volume 0..1 (sound panel slider), applied per utterance on top of the phone's media volume. */
    private val volume: () -> Float = { 1f },
) : TextToSpeech.OnInitListener, Speaker {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var currentLang: Lang? = null
    private val pending = ArrayList<Pair<String, Lang>>()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            pending.forEach { (t, l) -> speak(t, l) }
        }
        pending.clear()
    }

    private fun applyLang(lang: Lang) {
        if (currentLang == lang) return
        currentLang = lang
        val candidates = if (lang == Lang.MS) listOf(Locale("ms", "MY"), Locale("ms"), Locale("id", "ID")) else listOf(Locale("en", "MY"), Locale("en", "GB"), Locale.US)
        for (loc in candidates) {
            val r = tts.isLanguageAvailable(loc)
            if (r >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = loc
                return
            }
        }
        GLog.w("VoiceGuide", "No TTS voice for $lang, using default")
    }

    override fun speak(text: String, lang: Lang, flush: Boolean) {
        if (!ready) {
            pending += text to lang
            return
        }
        applyLang(lang)
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume().coerceIn(0f, 1f)) }
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "gimano-${text.hashCode()}")
    }

    override fun stop() {
        pending.clear()
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
