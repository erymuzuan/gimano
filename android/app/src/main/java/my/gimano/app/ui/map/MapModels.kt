package my.gimano.app.ui.map

import androidx.compose.runtime.Immutable
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.model.Driver
import my.gimano.app.data.model.Jam
import my.gimano.app.data.model.Report
import my.gimano.app.data.model.TrafficSegment

@Immutable
data class RouteDraw(val id: String, val points: List<GeoPoint>, val selected: Boolean, val traffic: List<TrafficSegment>)

/** Everything the map draws, as plain data. */
@Immutable
data class MapRenderState(
    val routes: List<RouteDraw> = emptyList(),
    val alerts: List<Report> = emptyList(),
    val jams: List<Pair<Jam, List<GeoPoint>>> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val puck: GeoPoint? = null,
    val puckBearing: Float = 0f,
    val pin: GeoPoint? = null,
    val stops: List<GeoPoint> = emptyList(),
    val showTraffic: Boolean = true,
    val showAlerts: Boolean = true,
    val showDrivers: Boolean = true,
    val navigating: Boolean = false,
)

/** One-shot camera instructions; [id] makes repeated identical commands re-apply. */
sealed interface CameraCommand {
    val id: Long

    data class MoveTo(val point: GeoPoint, val zoom: Double?, val animate: Boolean = true, override val id: Long = System.nanoTime()) : CameraCommand
    data class Fit(val points: List<GeoPoint>, val topDp: Int, val bottomDp: Int, val sideDp: Int = 48, override val id: Long = System.nanoTime()) : CameraCommand
    data class Follow(val point: GeoPoint, val bearing: Double, val zoom: Double = 17.0, val tilt: Double = 50.0, val bottomDp: Int = 0, val topDp: Int = 0, override val id: Long = System.nanoTime()) : CameraCommand
    data class Reset2D(val point: GeoPoint, val zoom: Double = 15.0, override val id: Long = System.nanoTime()) : CameraCommand
}

data class VisibleBounds(val minLng: Double, val minLat: Double, val maxLng: Double, val maxLat: Double) {
    val center get() = GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2)
}
