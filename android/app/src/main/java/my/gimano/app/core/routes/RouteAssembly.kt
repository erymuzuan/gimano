package my.gimano.app.core.routes

import my.gimano.app.core.events.EventLimits
import my.gimano.app.core.events.EventRoute
import my.gimano.app.core.events.GeometrySource
import my.gimano.app.core.events.RouteLeg
import my.gimano.app.core.geo.GeoPoint

/** Google encoded polyline at precision 6, rounding half away from zero (route-core `encode_polyline`). */
object RoutePolyline {
    fun encode(points: List<GeoPoint>): String {
        val sb = StringBuilder(points.size * 8)
        var plat = 0L
        var plng = 0L
        for (p in points) {
            require(p.lat.isFinite() && p.lng.isFinite() && p.lat in -90.0..90.0 && p.lng in -180.0..180.0) { "invalid coordinate" }
            val lat = scaled(p.lat)
            val lng = scaled(p.lng)
            push(sb, lat - plat)
            push(sb, lng - plng)
            plat = lat
            plng = lng
        }
        return sb.toString()
    }

    private fun scaled(v: Double): Long = (Math.signum(v) * Math.floor(Math.abs(v) * 1e6 + 0.5)).toLong()

    private fun push(sb: StringBuilder, v: Long) {
        var z = (v shl 1) xor (v shr 63)
        while (z >= 0x20) {
            sb.append(((0x20L or (z and 0x1f)) + 63).toInt().toChar())
            z = z ushr 5
        }
        sb.append((z + 63).toInt().toChar())
    }
}

/** Totals for the builder's sheet. Ascent/descent stay null: no elevation source yet ("Tidak tersedia", never 0). */
data class RouteSummary(
    val distanceM: Long,
    /** Movement time; null unless every leg has an estimate (sketches have none). */
    val durationS: Long?,
    val pendingLegs: Int,
    val unresolvedLegs: Int,
    val unmatchedLegs: Int,
    val source: GeometrySource?,
    val ascentM: Long? = null,
    val descentM: Long? = null,
) {
    val navigable: Boolean get() = source == GeometrySource.ROUTED
    /** Legs still without geometry ("perlu dikira"). */
    val openLegs: Int get() = pendingLegs + unresolvedLegs
}

/** Why a route cannot go into the event yet. */
enum class RouteBlock { EMPTY, NOT_READY, UNMATCHED }

sealed interface Assembled {
    data class Ok(val route: EventRoute) : Assembled
    data class Blocked(val why: RouteBlock) : Assembled
}

/** Builder route → the event's `routes[]` entry (desktop assemble.rs `build_route`). */
object RouteAssembly {
    fun legSource(l: BuilderLeg): GeometrySource? = when (l.state) {
        LegState.ROUTED -> GeometrySource.ROUTED
        LegState.MATCHED -> if (l.unmatched.isEmpty()) GeometrySource.ROUTED else GeometrySource.SKETCH
        LegState.SKETCH -> GeometrySource.SKETCH
        LegState.PENDING, LegState.UNRESOLVED -> null
    }

    /** routed < sketch < gpx; null if any part is unknown. */
    fun combine(sources: List<GeometrySource?>): GeometrySource? {
        var out = GeometrySource.ROUTED
        for (s in sources) {
            when (s ?: return null) {
                GeometrySource.GPX -> out = GeometrySource.GPX
                GeometrySource.SKETCH -> if (out != GeometrySource.GPX) out = GeometrySource.SKETCH
                GeometrySource.ROUTED -> Unit
            }
        }
        return out
    }

    /** The resolved legs' geometry joined (shared end points once), rounded to 6 decimals. */
    fun joinedGeometry(legs: List<BuilderLeg>): List<Pos> {
        val pts = ArrayList<Pos>()
        for (l in legs.filter { it.resolved }) {
            val skip = if (pts.isNotEmpty() && l.geometry.firstOrNull()?.rounded6() == pts.last()) 1 else 0
            l.geometry.drop(skip).forEach { pts += it.rounded6() }
        }
        return pts
    }

    fun summary(r: BuilderRoute): RouteSummary {
        val resolved = r.legs.filter { it.resolved }
        val pending = r.legs.count { it.state == LegState.PENDING }
        val unresolved = r.legs.count { it.state == LegState.UNRESOLVED }
        val duration = if (pending + unresolved == 0 && r.legs.isNotEmpty() && r.legs.all { it.durationS != null }) r.legs.sumOf { it.durationS!! } else null
        return RouteSummary(
            distanceM = resolved.sumOf { it.distanceM },
            durationS = duration,
            pendingLegs = pending,
            unresolvedLegs = unresolved,
            unmatchedLegs = r.legs.count { it.hasUnmatched },
            source = if (r.legs.isEmpty()) null else combine(r.legs.map(::legSource)),
        )
    }

    /**
     * The event route, or why not: fewer than 2 points, a leg still to calculate, or a drawn part that matched no road
     * (the host fixes it or keeps it as a sketch). [knownStops] are the event's stop ids; links to anything else are
     * left out.
     */
    fun toEventRoute(r: BuilderRoute, knownStops: Set<String>): Assembled {
        if (r.points.size < 2 || r.legs.isEmpty()) return Assembled.Blocked(RouteBlock.EMPTY)
        if (r.legs.any { !it.resolved }) return Assembled.Blocked(RouteBlock.NOT_READY)
        if (r.legs.any { it.hasUnmatched }) return Assembled.Blocked(RouteBlock.UNMATCHED)
        val s = summary(r)
        val pts = joinedGeometry(r.legs).map { it.geo }.let { if (it.size == 1) it + it else it }
        val stopIds = ArrayList<String>()
        val legs = ArrayList<RouteLeg>()
        var spanFrom: String? = null
        var span = ArrayList<BuilderLeg>()
        r.points.forEachIndexed { j, p ->
            val sid = p.stopId?.takeIf { it in knownStops }
            if (sid != null) {
                if (stopIds.lastOrNull() != sid) stopIds += sid
                val from = spanFrom
                if (from != null && span.isNotEmpty()) {
                    legs += RouteLeg(
                        fromStopId = from, toStopId = sid,
                        distanceM = span.sumOf { it.distanceM },
                        durationS = if (span.all { it.durationS != null }) span.sumOf { it.durationS!! } else null,
                        source = combine(span.map(::legSource)) ?: GeometrySource.SKETCH,
                    )
                }
                spanFrom = sid
                span = ArrayList()
            }
            if (spanFrom != null) r.legs.getOrNull(j)?.let { span += it }
        }
        val title = r.title.trim().takeIf { it.isNotEmpty() }?.let { t ->
            if (t.codePointCount(0, t.length) > EventLimits.ROUTE_TITLE_CHARS) t.substring(0, t.offsetByCodePoints(0, EventLimits.ROUTE_TITLE_CHARS)) else t
        }
        return Assembled.Ok(EventRoute(
            id = r.id, title = title, mode = r.travelMode, day = r.day?.takeIf { it.isNotBlank() }, stopIds = stopIds,
            polyline = RoutePolyline.encode(pts), points = pts, source = s.source ?: GeometrySource.SKETCH,
            distanceM = s.distanceM, durationS = s.durationS, ascentM = null, descentM = null, legs = legs,
        ))
    }

    /** Polyline points of the route as it would be published (for the 50 000-point limit). */
    fun pointCount(r: BuilderRoute): Int = joinedGeometry(r.legs).size
}

/** GPX 1.1 track writer (route-core `export_gpx`: no timestamps, no metadata, coordinates to 6 decimals). */
object GpxWriter {
    fun track(name: String?, points: List<GeoPoint>): String {
        val x = StringBuilder()
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        x.append("<gpx version=\"1.1\" creator=\"Manotu\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        x.append("  <trk>\n")
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { x.append("    <name>").append(escape(it)).append("</name>\n") }
        x.append("    <trkseg>\n")
        for (p in points) x.append("      <trkpt lat=\"").append(fmt6(p.lat)).append("\" lon=\"").append(fmt6(p.lng)).append("\"/>\n")
        x.append("    </trkseg>\n")
        x.append("  </trk>\n")
        x.append("</gpx>\n")
        return x.toString()
    }

    fun fmt6(v: Double): String {
        val s = java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_EVEN).toPlainString().trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    fun escape(s: String): String {
        val o = StringBuilder(s.length)
        for (c in s) when {
            c == '&' -> o.append("&amp;")
            c == '<' -> o.append("&lt;")
            c == '>' -> o.append("&gt;")
            c == '"' -> o.append("&quot;")
            c == '\'' -> o.append("&apos;")
            c == '\t' || c == '\n' || c == '\r' -> o.append(c)
            Character.isISOControl(c) -> o.append(' ')
            else -> o.append(c)
        }
        return o.toString()
    }

    /** "Kayuhan pagi" → "Kayuhan-pagi.gpx" (letters, digits, - and _ only). */
    fun fileName(name: String): String {
        val base = name.trim().replace(Regex("\\s+"), "-").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(60)
        return (base.ifBlank { "laluan" }) + ".gpx"
    }
}
