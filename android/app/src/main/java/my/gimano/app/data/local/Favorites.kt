package my.gimano.app.data.local

/** Rules for labelled favourites (pure, unit-tested). */
object Favorites {
    const val MAX_LABEL = 40
    const val MAX_FAVORITES = 50

    /** Icon picker order (docs/api.md: shared favourite icons). */
    val ICONS = listOf("kampung", "mertua", "star", "heart", "school", "surau", "food", "gym", "shop", "hospital")

    /** Quick-label chips → (label, icon). Labels are Malay on purpose: that's how people name them. */
    val QUICK_LABELS = listOf(
        "Kampung" to "kampung",
        "Rumah Mertua" to "mertua",
        "Sekolah" to "school",
        "Surau" to "surau",
        "Gym" to "gym",
        "Kedai" to "shop",
    )

    fun normalizeLabel(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL)

    fun iconForLabel(label: String, fallback: String = "star"): String =
        QUICK_LABELS.firstOrNull { it.first.equals(label.trim(), ignoreCase = true) }?.second ?: fallback

    fun validIcon(icon: String?): String = if (icon != null && icon in ICONS) icon else "star"

    /** New sort orders after moving [id] by [delta] positions within [orderedIds]. */
    fun move(orderedIds: List<String>, id: String, delta: Int): List<String> {
        val i = orderedIds.indexOf(id)
        if (i < 0) return orderedIds
        val j = (i + delta).coerceIn(0, orderedIds.lastIndex)
        if (i == j) return orderedIds
        val out = orderedIds.toMutableList()
        out.add(j, out.removeAt(i))
        return out
    }
}
