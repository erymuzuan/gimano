package my.gimano.app.core.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import my.gimano.app.core.events.EventIds
import my.gimano.app.core.events.EventJsonWriter
import my.gimano.app.core.geo.Polyline

/**
 * `personal_routes.route_json` v1 (docs/privacy-and-backup.md), the same on Android and iOS so backups restore across
 * platforms:
 *
 * `{"v":1,"points":[{"lat":…,"lng":…}],"legs":[{"source":"routed|matched|sketch|pending","polyline":"…","distanceM":…,"durationS":…}]}`
 *
 * What the builder keeps only in memory is not written: point ids and stop links, leg generations, the finger stroke of
 * a drawn leg and its unmatched ranges, and routing problems. Mapping:
 *  - ROUTED → routed; MATCHED → matched, or sketch when part of the stroke matched no road (its geometry is then not a
 *    road all the way, and the stroke that located the gap is not kept); SKETCH → sketch;
 *  - PENDING / UNRESOLVED → pending with `""` (recalculated when the route is opened).
 * `distanceM` / `durationS` are written only when known and positive ("absent = unknown, never 0").
 */
object PersonalRouteJson {
    const val VERSION = 1
    private val parser = Json { ignoreUnknownKeys = true }

    fun encode(r: BuilderRoute): String = buildJsonObject {
        put("v", VERSION)
        put("points", buildJsonArray {
            r.points.forEach { p -> add(buildJsonObject { put("lat", EventJsonWriter.round6(p.lat)); put("lng", EventJsonWriter.round6(p.lng)) }) }
        })
        put("legs", buildJsonArray { r.legs.forEach { add(leg(it)) } })
    }.toString()

    private fun leg(l: BuilderLeg): JsonObject {
        val source = when (l.state) {
            LegState.ROUTED -> SOURCE_ROUTED
            LegState.MATCHED -> if (l.unmatched.isEmpty()) SOURCE_MATCHED else SOURCE_SKETCH
            LegState.SKETCH -> SOURCE_SKETCH
            LegState.PENDING, LegState.UNRESOLVED -> SOURCE_PENDING
        }
        val geometry = l.geometry.takeIf { source != SOURCE_PENDING && it.size >= 2 }
        return buildJsonObject {
            put("source", if (geometry == null) SOURCE_PENDING else source)
            put("polyline", geometry?.let { g -> RoutePolyline.encode(g.map { it.geo }) } ?: "")
            if (geometry != null) {
                l.distanceM.takeIf { it > 0 }?.let { put("distanceM", it) }
                l.durationS?.takeIf { it > 0 }?.let { put("durationS", it) }
            }
        }
    }

    /**
     * The builder route stored under [id] / [name] / [mode]. Unknown fields are ignored; a leg list that does not match
     * the points is rebuilt as pending legs. Throws [IllegalArgumentException] for anything that is not v1.
     */
    fun decode(json: String, id: String, name: String, mode: String): BuilderRoute {
        val o = runCatching { parser.parseToJsonElement(json).jsonObject }.getOrElse { throw IllegalArgumentException("route_json is not a JSON object", it) }
        require((o["v"] as? JsonPrimitive)?.intOrNull == VERSION) { "unsupported route_json version" }
        val points = (o["points"] as? JsonArray).orEmpty().map { e ->
            val p = e.jsonObject
            val lat = p["lat"]?.jsonPrimitive?.doubleOrNull
            val lng = p["lng"]?.jsonPrimitive?.doubleOrNull
            require(lat != null && lng != null && Pos(lat, lng).isValid) { "invalid point" }
            BuilderPoint(EventIds.newItemId(), lat, lng)
        }
        val rawLegs = (o["legs"] as? JsonArray).orEmpty()
        val legCount = maxOf(0, points.size - 1)
        val legs = if (rawLegs.size == legCount) rawLegs.map { decodeLeg(it.jsonObject) } else List(legCount) { BuilderLeg.pending() }
        // "Ikut jalan" is not stored: a route made only of sketches was built with it off.
        val followRoads = legs.isEmpty() || legs.any { it.state != LegState.SKETCH }
        return BuilderRoute(id = id, title = name, mode = mode, followRoads = followRoads, points = points, legs = legs)
    }

    private fun decodeLeg(o: JsonObject): BuilderLeg {
        val source = (o["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val state = when (source) {
            SOURCE_ROUTED -> LegState.ROUTED
            SOURCE_MATCHED -> LegState.MATCHED
            SOURCE_SKETCH -> LegState.SKETCH
            else -> return BuilderLeg.pending()   // pending, or a source this version does not know
        }
        val poly = (o["polyline"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val geometry = runCatching { Polyline.decode(poly).map { Pos(it.lat, it.lng) } }.getOrNull()
        if (geometry == null || geometry.size < 2 || geometry.any { !it.isValid }) return BuilderLeg.pending()
        val distance = (o["distanceM"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: Math.round(RouteGeo.lengthM(geometry))
        val duration = (o["durationS"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        return BuilderLeg(state = state, generation = Generations.next(), geometry = geometry, distanceM = distance, durationS = duration)
    }

    const val SOURCE_ROUTED = "routed"
    const val SOURCE_MATCHED = "matched"
    const val SOURCE_SKETCH = "sketch"
    const val SOURCE_PENDING = "pending"

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
