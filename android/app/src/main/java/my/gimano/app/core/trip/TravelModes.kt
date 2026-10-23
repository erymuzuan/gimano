package my.gimano.app.core.trip

/** The four travel modes (docs/api.md "Travel modes"). */
enum class TravelMode(val wire: String, val icon: String) {
    CAR("car", "car"), MOTORCYCLE("motorcycle", "motorcycle"), BICYCLE("bicycle", "bicycle"), WALK("walk", "walk");

    /** Avoid (tolls/highways/ferries) and toll info only make sense for motor vehicles. */
    val isMotorised get() = this == CAR || this == MOTORCYCLE

    /** Typical speed used by the drive simulator. */
    val simSpeedKmh: Double get() = when (this) { CAR -> 55.0; MOTORCYCLE -> 50.0; BICYCLE -> 16.0; WALK -> 5.0 }

    companion object {
        fun from(wire: String?): TravelMode = entries.firstOrNull { it.wire == wire } ?: CAR
    }
}
