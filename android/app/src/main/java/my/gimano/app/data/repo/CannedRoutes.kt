package my.gimano.app.data.repo

import kotlinx.serialization.Serializable
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.data.model.*
import my.gimano.app.data.remote.GimanoJson
import java.time.Instant
import kotlin.math.roundToInt

@Serializable
data class NamedPoint(val lat: Double, val lng: Double, val name: String = "") {
    val point get() = GeoPoint(lat, lng)
}

/** One captured origin→destination set (assets/demo-routes/<id>.json, see tools/capture-demo-routes.py). */
@Serializable
data class CannedRouteSet(
    val id: String,
    val origin: NamedPoint,
    val destination: NamedPoint,
    val attribution: String = "",
    val routes: List<Route>,
)

/**
 * Real road geometry for demo mode, captured once from an OSM router and stored in our Route
 * contract. Demo routing picks the set whose destination is nearest the requested one and
 * decorates it with traffic, labels and sample alerts.
 */
class CannedRoutes(private val sets: List<CannedRouteSet>) {

    val all: List<CannedRouteSet> get() = sets

    companion object {
        fun parse(jsonDocs: List<String>) = CannedRoutes(jsonDocs.map { GimanoJson.decodeFromString(CannedRouteSet.serializer(), it) })
    }

    fun nearestTo(dest: GeoPoint): CannedRouteSet? = sets.minByOrNull { GeoMath.distanceM(it.destination.point, dest) }

    fun plan(set: CannedRouteSet, vehicle: String, avoid: AvoidOptions, departFactor: Double, idPrefix: String = set.id): List<Route> {
        var routes = set.routes
        if (avoid.tolls) routes = routes.filter { !it.hasTolls }.ifEmpty { routes }
        val decorated = routes.mapIndexed { i, r ->
            val pts = Polyline.decode(r.polyline)
            val n = pts.size
            val trafficFactor = listOf(1.12, 1.22, 1.05)[i % 3] * departFactor
            val vehicleFactor = if (vehicle == "motorcycle") 0.88 else 1.0
            val duration = r.durationS * vehicleFactor
            r.copy(
                id = "$idPrefix-$i-${System.nanoTime() % 1_000_000}",
                durationS = duration,
                trafficDurationS = duration * trafficFactor,
                trafficDescription = when {
                    trafficFactor > 1.18 -> "Heavier than usual"
                    trafficFactor < 1.08 -> "Light traffic"
                    else -> "Typical traffic"
                },
                tollEstimateMyr = if (r.hasTolls) ((1.5 + r.distanceM / 40_000.0) * 10).roundToInt() / 10.0 else null,
                trafficSegments = if (n < 20) emptyList() else listOf(
                    TrafficSegment((n * 0.30).toInt(), (n * 0.38).toInt(), 2 + i % 2),
                    TrafficSegment((n * 0.62).toInt(), (n * 0.66).toInt(), 1),
                ),
                alerts = if (i == 0 && r.distanceM > 2_000) listOf(
                    alertAt(pts, r.distanceM * 0.35, "police", "visible"),
                    alertAt(pts, r.distanceM * 0.6, "hazard", "pothole"),
                ) else emptyList(),
            )
        }
        return decorated.sortedBy { it.effectiveDurationS }.mapIndexed { i, r -> r.copy(label = if (i == 0) "best" else "alternative") }
    }

    private fun alertAt(pts: List<GeoPoint>, along: Double, type: String, sub: String): AlertOnRoute {
        var acc = 0.0
        var p = pts.last()
        for (k in 1 until pts.size) {
            acc += GeoMath.distanceM(pts[k - 1], pts[k])
            if (acc >= along) { p = pts[k]; break }
        }
        val now = Instant.now()
        return AlertOnRoute(
            id = "demo-alert-$type-${(p.lat * 1e5).roundToInt()}-${(p.lng * 1e5).roundToInt()}",
            type = type, subtype = sub, lat = p.lat, lng = p.lng,
            reportedBy = ReportedBy("Pemandu Ceria", "😎"),
            createdAt = now.minusSeconds(8 * 60).toString(), expiresAt = now.plusSeconds(22 * 60).toString(),
            confirmations = 3, thanks = 1, distanceAlongRouteM = along,
        )
    }
}
