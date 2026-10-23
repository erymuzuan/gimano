package my.gimano.app.core.feedback

/** "Boleh kami hubungi anda tentang laporan ini?": validation for the optional, volunteered contact. */
object ContactRules {
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}$")

    fun validEmail(s: String) = EMAIL.matches(s.trim())

    /**
     * WhatsApp number → E.164, default country Malaysia (+60): "012-345 6789" → "+60123456789",
     * "60123456789" → "+60123456789", "+6591234567" stays. null when it can't be a phone number.
     */
    fun e164(input: String, defaultCc: String = "60"): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        if (t.any { !it.isDigit() && it !in " +-()." }) return null
        val full = when {
            plus -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith(defaultCc) && digits.length >= 10 -> digits
            digits.startsWith("0") -> defaultCc + digits.drop(1)
            else -> defaultCc + digits
        }
        return if (full.length in 8..15 && !full.startsWith("0")) "+$full" else null
    }

    /** Ready to send: at least one valid channel and the explicit consent tick. Blank = no contact at all. */
    fun state(email: String, whatsapp: String, consent: Boolean): State {
        val e = email.trim(); val w = whatsapp.trim().removePrefix("+60").let { if (it.isBlank()) "" else whatsapp.trim() }
        if (e.isEmpty() && w.isEmpty()) return State.None
        if (e.isNotEmpty() && !validEmail(e)) return State.BadEmail
        if (w.isNotEmpty() && e164(w) == null) return State.BadPhone
        if (!consent) return State.NeedsConsent
        return State.Ok(e.ifEmpty { null }, w.ifEmpty { null }?.let(::e164))
    }

    sealed interface State {
        data object None : State
        data object BadEmail : State
        data object BadPhone : State
        data object NeedsConsent : State
        data class Ok(val email: String?, val whatsapp: String?) : State
    }
}
