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

    /** The default share ("Kongsi jemputan", link first): title, date · time, the link, the download page. */
    fun linkMessage(p: EventPlan, link: String, downloadUrl: String): String = if (p.lang == "en")
        "Invitation: ${p.title}\n${whenShort(p)}\nOpen the plan, parking & directions: $link\nNo Manotu yet? $downloadUrl"
    else
        "Jemputan: ${p.title}\n${whenShort(p)}\nBuka pelan, parkir & arah: $link\nBelum ada Manotu? $downloadUrl"

    /** "Ahd, 15 Nov · 11:00–16:00" (one day), else the [whenText] range across days. */
    fun whenShort(p: EventPlan): String {
        val z = EventSchedule.zone(p)
        val s = p.start.atZoneSameInstant(z)
        val e = p.end.atZoneSameInstant(z)
        if (s.toLocalDate() != e.toLocalDate()) return whenText(p)
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        return "${s.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale(p.lang)))} · ${s.format(hm)}–${e.format(hm)}"
    }

    /** §5.2.1 default share, step 1: the text sent on its own before the file (WhatsApp drops text sent with a document). No code, key or link. */
    fun openFileMessage(p: EventPlan, downloadUrl: String): String = if (p.lang == "en")
        "Invitation: ${p.title}\n${whenShort(p)}\nI'm sending the Manotu invitation file next — tap it to open.\nNo Manotu yet? $downloadUrl"
    else
        "Jemputan: ${p.title}\n${whenShort(p)}\nSaya hantar fail jemputan Manotu selepas ini — ketik untuk buka.\nBelum ada Manotu? $downloadUrl"

    /** "Acara sensitif": the text sent with the suite-2 file; the code goes separately ([codeMessage]). */
    fun fileMessage(p: EventPlan, downloadUrl: String): String = if (p.lang == "en")
        "Invitation: ${p.title} (open with Manotu). The secret code will be sent separately. No Manotu yet? Download: $downloadUrl"
    else
        "Jemputan ${p.title} (buka dengan Manotu). Kod rahsia akan dihantar berasingan. Belum ada Manotu? Muat turun: $downloadUrl"

    /** The code on its own, for its separate share button. */
    fun codeMessage(p: EventPlan, secretCode: String): String = if (p.lang == "en")
        "Secret code for \"${p.title}\": $secretCode"
    else
        "Kod rahsia untuk \"${p.title}\": $secretCode"

    private const val UNSAFE = "/\\:*?\"<>|"

    /**
     * File name for the share sheet / Save: "<title>.manotu-event", the title without characters that file systems or
     * chat apps reject (`/ \ : * ? " < > |`, control characters), spaces collapsed, at most 60 characters. Never secret.
     */
    fun fileName(p: EventPlan): String {
        val cleaned = p.title.map { if (it.isISOControl() || it in UNSAFE) ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        val base = if (cleaned.codePointCount(0, cleaned.length) <= 60) cleaned
            else cleaned.substring(0, cleaned.offsetByCodePoints(0, 60)).trimEnd()
        return "${base.ifEmpty { "acara" }}.${EventTransport.FILE_EXTENSION}"
    }
}
