package my.gimano.app.ui.common

/**
 * Flat two-tone icons of the events wizard and guest card (canvas boards Ew*), same style as [TwoToneIconPaths]:
 * [TwoTonePath.base] in the pale cobalt tint, [TwoTonePath.detail] (even-odd) in the cobalt ink. 24-unit grid.
 * Kept here (android only) until they join docs/icons/manotu-twotone-icons.json.
 */
object EventTwoToneIconPaths {
    val all: Map<String, TwoTonePath> = mapOf(
        // Templates
        "ev_kenduri" to TwoTonePath(
            "M9 8a6 6 0 1 1 0 12A6 6 0 0 1 9 8zm0 2.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z",
            "M15 8a6 6 0 1 1 0 12 6 6 0 0 1 0-12zm0 2.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zM13 2h4l-2 4z", 1f,
        ),
        "ev_meeting" to TwoTonePath(
            "M2 13h20v3H2zM4 16h2v5H4zM18 16h2v5h-2z",
            "M7 4a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5zM17 4a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5zM4 13a3 3 0 0 1 6 0zM14 13a3 3 0 0 1 6 0z", 1f,
        ),
        "ev_seminar" to TwoTonePath(
            "M3 3h18v11H3z",
            "M6 6h8v2H6zM6 9.5h12v2H6zM9 16h6l1 6H8z", 1f,
        ),
        "ev_picnic" to TwoTonePath(
            "M12 2a7 7 0 0 1 7 7c0 3-2 5-4 5.6V14H9v.6C7 14 5 12 5 9a7 7 0 0 1 7-7z",
            "M11 13h2v9h-2zM7 20h10v2H7z", 1f,
        ),
        "ev_bus" to TwoTonePath(
            "M5 3h14a2 2 0 0 1 2 2v12H3V5a2 2 0 0 1 2-2z",
            "M5 6h14v5H5zM6 13a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM18 13a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM5 17h3v3H5zM16 17h3v3h-3z", 1f,
        ),
        "ev_add" to TwoTonePath(
            "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z",
            "M11 7h2v4h4v2h-4v4h-2v-4H7v-2h4z", 1f,
        ),
        "ev_place" to TwoTonePath(
            "M3 11 12 3l9 8v10H3z",
            "M10.5 14h3v7h-3zM11 8h2v2h-2z", 1f,
        ),
        "ev_journey" to TwoTonePath(
            "M6.6 15.8 11 8.6l1.7 1-4.4 7.2zM13.6 9.4l4 6.4-1.7 1-4-6.4z",
            "M12 3.5a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM5 14.5a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM19 14.5a3 3 0 1 1 0 6 3 3 0 0 1 0-6z", 1f,
        ),
        // Stop kinds
        "ev_entrance" to TwoTonePath(
            "M4 21V9a8 8 0 0 1 16 0v12z",
            "M8 21v-9a4 4 0 0 1 8 0v9z", 1f,
        ),
        "ev_dropoff" to TwoTonePath(
            "M3 19h18v2.5H3z",
            "M11 3h2v9.2l3-3 1.4 1.4L12 16l-5.4-5.4L8 9.2l3 3z", 1f,
        ),
        "ev_water" to TwoTonePath(
            "M12 2.5c3.5 4.5 6.5 8.2 6.5 12a6.5 6.5 0 0 1-13 0c0-3.8 3-7.5 6.5-12z",
            "M9 14.5h2a2 2 0 0 0 2 2v2a4 4 0 0 1-4-4z", 1f,
        ),
        "ev_rest" to TwoTonePath(
            "M3 10h18v4H3z",
            "M4 6h16v3H4zM5 14h2.5v6H5zM16.5 14H19v6h-2.5z", 1f,
        ),
        "ev_bed" to TwoTonePath(
            "M2 12h20v6H2z",
            "M2 5h2.5v15H2zM19.5 15H22v5h-2.5zM5 8.5h6v3.5H5z", 1f,
        ),
        "ev_flag" to TwoTonePath(
            "M6 3h13l-3 4.5 3 4.5H6z",
            "M4 2h2.5v20H4z", 1f,
        ),
        "ev_start" to TwoTonePath(
            "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z",
            "M9.5 7.5 17 12l-7.5 4.5z", 1f,
        ),
        "ev_checkpoint" to TwoTonePath(
            "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z",
            "M10.5 15.8 6.7 12l1.4-1.4 2.4 2.4 5.4-5.4 1.4 1.4z", 1f,
        ),
        // Info blocks
        "ev_agenda" to TwoTonePath(
            "M8 4.5h13v3H8zM8 10.5h13v3H8zM8 16.5h13v3H8z",
            "M4.5 4.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM4.5 10.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM4.5 16.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z", 1f,
        ),
        "ev_dress" to TwoTonePath(
            "M8 3 2 7l2.5 4L7 9.8V21h10V9.8l2.5 1.2L22 7l-6-4a4 4 0 0 1-8 0z",
            "M10 3h4a2 2 0 0 1-4 0z", 1f,
        ),
        "ev_safety" to TwoTonePath(
            "M12 2 20 5v6c0 5-3.4 9.3-8 11-4.6-1.7-8-6-8-11V5z",
            "M10.8 15.4 7.4 12l1.4-1.4 2 2 4.4-4.4 1.4 1.4z", 1f,
        ),
        "ev_documents" to TwoTonePath(
            "M5 2h9l5 5v15H5z",
            "M14 2v5h5zM8 11h8v2H8zM8 15h8v2H8z", 1f,
        ),
        "ev_bring" to TwoTonePath(
            "M4 8h16l-1.2 14H5.2z",
            "M8 9V7a4 4 0 0 1 8 0v2h-2V7a2 2 0 0 0-4 0v2z", 1f,
        ),
        "ev_contact" to TwoTonePath(
            "M7 2h10a1.5 1.5 0 0 1 1.5 1.5v17A1.5 1.5 0 0 1 17 22H7a1.5 1.5 0 0 1-1.5-1.5v-17A1.5 1.5 0 0 1 7 2z",
            "M8 5h8v11H8zM11 18h2v2h-2z", 1f,
        ),
        // Misc
        "ev_calendar" to TwoTonePath(
            "M3 5h18v16H3z",
            "M3 5h18v4H3zM7 2h2v5H7zM15 2h2v5h-2zM6 12h3v3H6z", 1f,
        ),
        "ev_clock" to TwoTonePath(
            "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z",
            "M11 6h2v5.6l3.6 2.1-1 1.7L11 12.8z", 1f,
        ),
        "ev_person" to TwoTonePath(
            "M3.5 21a8.5 8.5 0 0 1 17 0z",
            "M12 3a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z", 1f,
        ),
        "ev_key" to TwoTonePath(
            "M10 11h12v3h-2v3h-3v-3h-7z",
            "M7 7.5a5 5 0 1 1 0 10 5 5 0 0 1 0-10zm0 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4z", 1f,
        ),
        "ev_note" to TwoTonePath(
            "M4 3h16v18H4z",
            "M7 7h10v2H7zM7 11h10v2H7zM7 15h6v2H7z", 1f,
        ),
        "ev_globe" to TwoTonePath(
            "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z",
            "M2.5 11h19v2h-19zM11 2.2h2c2.6 2.6 2.6 17 0 19.6h-2c-2.6-2.6-2.6-17 0-19.6z", 1f,
        ),
        "ev_lock" to TwoTonePath(
            "M4 10h16v12H4z",
            "M7 10V7a5 5 0 0 1 10 0v3h-2.5V7a2.5 2.5 0 0 0-5 0v3zM11 14h2v4h-2z", 1f,
        ),
        "ev_file" to TwoTonePath(
            "M5 2h9l5 5v15H5z",
            "M14 2v5h5zM8 18l3-4 2 2.5 1.5-2 2.5 3.5z", 1f,
        ),
        "ev_sliders" to TwoTonePath(
            "M3 5h18v2H3zM3 11h18v2H3zM3 17h18v2H3z",
            "M8 3.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5zM16 9.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5zM10 15.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z", 1f,
        ),
        "ev_eye" to TwoTonePath(
            "M12 5c5 0 9 4.5 10 7-1 2.5-5 7-10 7S3 14.5 2 12c1-2.5 5-7 10-7z",
            "M12 8.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z", 1f,
        ),
        "ev_verified" to TwoTonePath(
            "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z",
            "M10.5 16.2 6.3 12l1.5-1.5 2.7 2.7 5.7-5.7 1.5 1.5z", 1f,
        ),
    )
}
