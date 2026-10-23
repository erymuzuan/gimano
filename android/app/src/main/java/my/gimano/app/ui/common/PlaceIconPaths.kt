package my.gimano.app.ui.common

/**
 * Flat two-tone icons of the place card (canvas boards TempatIntai / TempatPenuh), same style as the home panel's
 * [TwoToneIconPaths]: [TwoTonePath.base] in the pale tint (#8FB0E8), [TwoTonePath.detail] in the ink (#0047AB).
 * Kept here (android only) until they join docs/icons/manotu-twotone-icons.json and gen_icons.py.
 */
object PlaceTwoToneIconPaths {
    val all: Map<String, TwoTonePath> = mapOf(
        "bookmark" to TwoTonePath("M6 3h12v18l-6-4-6 4z", "M9 7h6v2H9z", 1f),
        "call" to TwoTonePath(
            "M5 3h4l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2z",
            "M14 3a7 7 0 0 1 7 7h-2a5 5 0 0 0-5-5z", 1f,
        ),
        "share" to TwoTonePath(
            "M8.6 10.7 15 7.4l.9 1.8-6.4 3.3zM9.5 13.5l6.4 3.3-.9 1.8-6.4-3.3z",
            "M18 2a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM6 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM18 16a3 3 0 1 1 0 6 3 3 0 0 1 0-6z", 1f,
        ),
        "website" to TwoTonePath(
            "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z",
            "M2.5 11h19v2h-19zM11 2.2h2c2.6 2.6 2.6 17 0 19.6h-2c-2.6-2.6-2.6-17 0-19.6z", 1f,
        ),
        "route" to TwoTonePath("M12 2 22 12 12 22 2 12z", "M9 15v-3a2 2 0 0 1 2-2h4V8l3 3-3 3v-2h-4v3z", 1f),
        "review" to TwoTonePath(
            "M3 4h18v13H8l-5 4z",
            "M12 6.5l1.4 2.9 3.1.4-2.3 2.2.6 3.1L12 13.6l-2.8 1.5.6-3.1-2.3-2.2 3.1-.4z", 1f,
        ),
        "add_photo" to TwoTonePath("M3 7h4l2-3h6l2 3h4v13H3z", "M12 9a4 4 0 1 1 0 8 4 4 0 0 1 0-8z", 1f),
        "edit" to TwoTonePath("M4 16 15 5l4 4L8 20H4z", "M16 4l2-2 4 4-2 2z", 1f),
    )
}
