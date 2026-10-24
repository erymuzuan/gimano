package my.gimano.app.ui.common

/**
 * Flat two-tone icons of the driving screens (canvas NavBunyi / NavBunyiKeadaan), same style as [TwoToneIconPaths].
 * "sound_mute"'s cross is drawn in orange by the caller (TwoTone.vector with an orange detail).
 * Kept here (android only) until they join docs/icons/manotu-twotone-icons.json and gen_icons.py.
 */
object NavTwoToneIconPaths {
    val all: Map<String, TwoTonePath> = mapOf(
        "sound_full" to TwoTonePath(
            "M3 9h4l5-4v14l-5-4H3z",
            "M15 8.5a5 5 0 0 1 0 7l-1.4-1.4a3 3 0 0 0 0-4.2zM17.8 5.7a9 9 0 0 1 0 12.6l-1.4-1.4a7 7 0 0 0 0-9.8z", 1f,
        ),
        "bell" to TwoTonePath("M12 3a6 6 0 0 1 6 6v4l2 3H4l2-3V9a6 6 0 0 1 6-6z", "M10 18h4a2 2 0 0 1-4 0zM11 7h2v5h-2z", 1f),
        "sound_mute" to TwoTonePath(
            "M3 9h4l5-4v14l-5-4H3z",
            "M15 9l1.4-1.4 2.1 2.1 2.1-2.1L22 9l-2.1 2.1L22 13.2l-1.4 1.4-2.1-2.1-2.1 2.1-1.4-1.4 2.1-2.1z", 1f,
        ),
    )
}
