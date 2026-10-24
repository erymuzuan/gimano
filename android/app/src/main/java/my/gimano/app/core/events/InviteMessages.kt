package my.gimano.app.core.events

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ready-to-post texts for "Kongsi…" (WhatsApp etc.), in the event's own language (`lang`), built in one place.
 * The file message never carries the key or the secret code: the code keeps its own copy / share button and the
 * host sends it through another channel (§5.2).
 */
object InviteMessages {
    private fun locale(lang: String) = if (lang == "en") Locale.UK else Locale.forLanguageTag("ms-MY")

    /** "Sab, 15 Nov 2026, 11:00–16:00" or across days "Sab, 21 Nov 2026 07:00 – Ahd, 22 Nov 2026 13:00", in the event's zone. */
    fun whenText(p: EventPlan): String {
        val z = EventSchedule.zone(p)
        val day = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale(p.lang))
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        val s = p.start.atZoneSameInstant(z)
        val e = p.end.atZoneSameInstant(z)
        return if (s.toLocalDate() == e.toLocalDate()) "${s.format(day)}, ${s.format(hm)}–${e.format(hm)}"
        else "${s.format(day)} ${s.format(hm)} – ${e.format(day)} ${e.format(hm)}"
    }

    fun linkMessage(p: EventPlan, link: String, downloadUrl: String): String = if (p.lang == "en") """
        Invitation: ${p.title}
        ${whenText(p)}
        Open the plan, parking & directions: $link
        No Manotu yet? Download: $downloadUrl
    """.trimIndent() else """
        Jemputan: ${p.title}
        ${whenText(p)}
        Buka pelan, tempat parkir & arah: $link
        Belum ada Manotu? Muat turun: $downloadUrl
    """.trimIndent()

    fun fileMessage(p: EventPlan, downloadUrl: String): String = if (p.lang == "en")
        "Invitation: ${p.title} (open with Manotu). The secret code will be sent separately. No Manotu yet? Download: $downloadUrl"
    else
        "Jemputan ${p.title} (buka dengan Manotu). Kod rahsia akan dihantar berasingan. Belum ada Manotu? Muat turun: $downloadUrl"

    /** The code on its own, for its separate share button. */
    fun codeMessage(p: EventPlan, secretCode: String): String = if (p.lang == "en")
        "Secret code for \"${p.title}\": $secretCode"
    else
        "Kod rahsia untuk \"${p.title}\": $secretCode"

    /** File name for the share sheet / Save: the title in safe characters, never anything secret. */
    fun fileName(p: EventPlan): String {
        val base = p.title.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").replace(Regex("-+"), "-").trim('-').take(40)
        return "${base.ifEmpty { "acara" }}.${EventTransport.FILE_EXTENSION}"
    }
}
