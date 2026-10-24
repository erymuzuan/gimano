package my.gimano.app.data.routes

import kotlinx.coroutines.CancellationException
import my.gimano.app.core.events.EventJsonWriter
import my.gimano.app.core.events.EventPolyline
import my.gimano.app.core.routes.Pos
import my.gimano.app.core.routes.RouteEdit
import my.gimano.app.core.routes.RouteProblem
import my.gimano.app.core.routes.Span
import my.gimano.app.data.model.Coord
import my.gimano.app.data.model.MatchLineRequest
import my.gimano.app.data.model.StatelessRouteRequest
import my.gimano.app.data.remote.GimanoApi
import retrofit2.HttpException
import java.io.IOException

/** Calculates one builder leg. Implementations never send names, ids or notes: coordinates and mode only. */
interface LegRouter {
    suspend fun run(job: RouteEdit.LegJob): RouteEdit.JobResult
}

/**
 * The routing API (docs/api.md): each leg is its own small `POST /routes` with `"store": false` (an older server
 * ignores the flag), drawn lines go to `POST /routes/match`. [api] is a client without the account token: the
 * routing endpoints are anonymous, so these requests are not tied to the user's pseudonym. Never logs coordinates.
 */
class ApiLegRouter(private val api: () -> GimanoApi?) : LegRouter {
    companion object {
        /** Decoding bound for server polylines (route-core `MAX_RESPONSE_POINTS`). */
        const val MAX_RESPONSE_POINTS = 500_000

        private fun c(p: Pos) = Coord(EventJsonWriter.round6(p.lat), EventJsonWriter.round6(p.lng))

        private fun metres(v: Double): Long = if (v.isFinite() && v > 0) Math.round(minOf(v, 4_294_967_295.0)) else 0

        /** HTTP / transport failures → a leg problem code. 404/405/501 on `/routes/match` = not on this server yet. */
        fun problem(e: Throwable, match: Boolean): String = when (e) {
            is HttpException -> when (e.code()) {
                422 -> RouteProblem.NO_ROUTE
                404, 405, 501 -> if (match) RouteProblem.MATCH_UNAVAILABLE else RouteProblem.ERROR
                502, 503, 504 -> RouteProblem.UNAVAILABLE
                else -> RouteProblem.ERROR
            }
            is IOException -> RouteProblem.OFFLINE
            else -> RouteProblem.ERROR
        }
    }

    override suspend fun run(job: RouteEdit.LegJob): RouteEdit.JobResult {
        val api = api() ?: return RouteEdit.JobResult.Failed(RouteProblem.OFFLINE)
        val isMatch = job.kind is RouteEdit.JobKind.Match
        return try {
            when (val k = job.kind) {
                is RouteEdit.JobKind.Route -> {
                    val r = api.routesStateless(StatelessRouteRequest(c(k.from), c(k.to), emptyList(), job.mode)).routes.firstOrNull()
                        ?: return RouteEdit.JobResult.Failed(RouteProblem.NO_ROUTE)
                    val pts = EventPolyline.decode(r.polyline, MAX_RESPONSE_POINTS).map { Pos(it.lat, it.lng) }
                    RouteEdit.JobResult.Routed(pts, metres(r.distanceM), metres(r.durationS))
                }
                is RouteEdit.JobKind.Match -> {
                    val m = api.matchLine(MatchLineRequest(k.points.map(::c), job.mode))
                    val pts = EventPolyline.decode(m.polyline, MAX_RESPONSE_POINTS).map { Pos(it.lat, it.lng) }
                    RouteEdit.JobResult.Matched(pts, metres(m.distanceM), metres(m.durationS),
                        m.unmatched.filter { it.fromIndex >= 0 && it.toIndex >= it.fromIndex }.map { Span(it.fromIndex, it.toIndex) })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EventPolyline.TooManyPoints) {
            RouteEdit.JobResult.Failed(RouteProblem.ERROR)
        } catch (e: Exception) {
            RouteEdit.JobResult.Failed(problem(e, isMatch))
        }
    }
}
