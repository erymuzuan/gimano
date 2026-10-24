package my.gimano.app.core.routes

import my.gimano.app.core.events.EventIds

/**
 * Pure editing operations on a [BuilderRoute], ported from desktop/src-tauri/src/editing.rs. Rules:
 * - an edit only invalidates the legs it touches ("recompute affected legs only");
 * - an invalidated leg is PENDING until routed; it is never drawn as a straight line unless "Ikut jalan" is off,
 *   in which case it becomes an explicit SKETCH;
 * - stop order is never changed implicitly.
 * Every function returns a new route, or null when the edit is not possible (the caller keeps the old one).
 */
object RouteEdit {
    private fun newId() = EventIds.newItemId()

    private fun straight(a: Pos, b: Pos, generation: Long) = BuilderLeg(
        state = LegState.SKETCH, generation = generation, geometry = listOf(a, b), distanceM = Math.round(RouteGeo.lengthM(listOf(a, b))),
    )

    /** Leg [i] back to PENDING with a new generation; a drawn line is kept (matched again) unless [dropDrawn]. */
    fun invalidate(r: BuilderRoute, i: Int, dropDrawn: Boolean = false): BuilderRoute {
        if (i !in r.legs.indices) return r
        val drawn = if (dropDrawn) null else r.legs[i].drawn
        return r.copy(legs = r.legs.toMutableList().also { it[i] = BuilderLeg.pending().copy(drawn = drawn) })
    }

    /** Invalidates the legs touching point [i], dropping drawn lines whose ends moved. */
    private fun invalidateAround(r: BuilderRoute, i: Int): BuilderRoute = invalidate(invalidate(r, i - 1, dropDrawn = true), i, dropDrawn = true)

    /** Without "Ikut jalan", pending legs become explicit straight or drawn sketches. */
    fun settle(r: BuilderRoute): BuilderRoute {
        if (r.followRoads) return r
        if (r.legs.none { it.state == LegState.PENDING }) return r
        val legs = r.legs.mapIndexed { i, l ->
            if (l.state != LegState.PENDING) l
            else {
                val d = l.drawn
                if (d != null && d.size >= 2) BuilderLeg(LegState.SKETCH, l.generation, d, Math.round(RouteGeo.lengthM(d)), drawn = d)
                else straight(r.points[i].pos, r.points[i + 1].pos, l.generation)
            }
        }
        return r.copy(legs = legs)
    }

    private fun push(r: BuilderRoute, p: BuilderPoint): BuilderRoute =
        r.copy(points = r.points + p, legs = if (r.points.isEmpty()) r.legs else r.legs + BuilderLeg.pending())

    fun addPoint(r: BuilderRoute, at: Pos, stopId: String? = null): BuilderRoute? {
        if (!at.isValid) return null
        val p = at.rounded6()
        return settle(push(r, BuilderPoint(newId(), p.lat, p.lng, stopId)))
    }

    /** A point on leg [legIndex] (between points legIndex and legIndex + 1): both new legs are calculated again. */
    fun insertPoint(r: BuilderRoute, legIndex: Int, at: Pos): BuilderRoute? {
        if (legIndex !in r.legs.indices || !at.isValid) return null
        val p = at.rounded6()
        val points = r.points.toMutableList().apply { add(legIndex + 1, BuilderPoint(newId(), p.lat, p.lng)) }
        val legs = r.legs.toMutableList().apply { set(legIndex, BuilderLeg.pending()); add(legIndex + 1, BuilderLeg.pending()) }
        return settle(r.copy(points = points, legs = legs))
    }

    fun movePoint(r: BuilderRoute, i: Int, to: Pos): BuilderRoute? {
        if (i !in r.points.indices || !to.isValid) return null
        val p = to.rounded6()
        if (r.points[i].pos == p) return null
        val moved = r.copy(points = r.points.toMutableList().also { it[i] = it[i].copy(lat = p.lat, lng = p.lng) })
        return settle(invalidateAround(moved, i))
    }

    fun deletePoint(r: BuilderRoute, i: Int): BuilderRoute? {
        if (i !in r.points.indices) return null
        val points = r.points.toMutableList().apply { removeAt(i) }
        var legs = r.legs.toMutableList()
        if (legs.isNotEmpty()) {
            when {
                i == 0 -> legs.removeAt(0)
                i > legs.size - 1 -> legs.removeAt(legs.size - 1)
                else -> {
                    // The two legs around the point merge into one.
                    legs.removeAt(i)
                    legs[i - 1] = BuilderLeg.pending()
                }
            }
        }
        if (points.size < 2) legs = mutableListOf()
        return settle(r.copy(points = points, legs = legs))
    }

    fun reorderPoint(r: BuilderRoute, i: Int, delta: Int): BuilderRoute? {
        val j = i + delta
        if (i !in r.points.indices || (delta != 1 && delta != -1) || j !in r.points.indices) return null
        val points = r.points.toMutableList().apply { val t = this[i]; this[i] = this[j]; this[j] = t }
        val lo = minOf(i, j)
        val hi = maxOf(i, j)
        return settle(invalidateAround(invalidateAround(r.copy(points = points), lo), hi))
    }

    /** Snaps point [i] onto an event stop (its pin) and links it, so the route visits that stop. */
    fun snapToStop(r: BuilderRoute, i: Int, stopId: String, stopPos: Pos): BuilderRoute? {
        if (i !in r.points.indices || !stopPos.isValid) return null
        val p = stopPos.rounded6()
        val linked = r.copy(points = r.points.toMutableList().also { it[i] = it[i].copy(lat = p.lat, lng = p.lng, stopId = stopId) })
        return if (r.points[i].pos == p) linked else settle(invalidateAround(linked, i))
    }

    /** The point becomes a shaping point again (the stop itself stays in the event). */
    fun unlinkStop(r: BuilderRoute, i: Int): BuilderRoute? {
        if (i !in r.points.indices || r.points[i].stopId == null) return null
        return r.copy(points = r.points.toMutableList().also { it[i] = it[i].copy(stopId = null) })
    }

    /** "Terbalikkan arah": road legs are routed again (one-way streets); sketches are reversed as drawn. */
    fun reverse(r: BuilderRoute): BuilderRoute? {
        if (r.points.size < 2) return null
        val legs = r.legs.reversed().map { l ->
            when (l.state) {
                LegState.ROUTED, LegState.MATCHED, LegState.PENDING, LegState.UNRESOLVED -> BuilderLeg.pending().copy(drawn = l.drawn?.reversed())
                LegState.SKETCH -> l.copy(geometry = l.geometry.reversed(), drawn = l.drawn?.reversed(), unmatched = emptyList())
            }
        }
        return settle(r.copy(points = r.points.reversed(), legs = legs))
    }

    /** "Kembali ke mula": one more leg back to the first point. */
    fun closeLoop(r: BuilderRoute): BuilderRoute? {
        if (r.points.size < 2) return null
        val first = r.points.first()
        if (r.points.last().pos == first.pos) return null
        return settle(push(r, first.copy(id = newId())))
    }

    /** "Pergi-balik": the same points back to the start; every new leg is routed again. */
    fun outAndBack(r: BuilderRoute): BuilderRoute? {
        val n = r.points.size
        if (n < 2) return null
        var out = r
        for (i in n - 2 downTo 0) out = push(out, r.points[i].copy(id = newId()))
        return settle(out)
    }

    /** "Lukis": a finger-drawn line from the last point (or starting the route) to where the finger lifted. */
    fun addDrawnLeg(r: BuilderRoute, line: List<Pos>): BuilderRoute? {
        if (line.size < 2 || line.any { !it.isValid }) return null
        val pts = line.map { it.rounded6() }.toMutableList()
        var out = r
        if (out.points.isNotEmpty()) pts.add(0, out.points.last().pos)
        else out = out.copy(points = listOf(BuilderPoint(newId(), pts[0].lat, pts[0].lng)))
        val end = pts.last()
        out = push(out, BuilderPoint(newId(), end.lat, end.lng))
        val li = out.legs.size - 1
        // Thinned once here, so `unmatched` indexes from the server refer to exactly this list.
        val drawn = RouteGeo.thin(pts, MATCH_MAX_POINTS)
        out = out.copy(legs = out.legs.toMutableList().also { it[li] = it[li].copy(drawn = drawn) })
        return settle(out)
    }

    /** "Simpan sebagai lakaran": the drawn line (or a straight line) as an explicit sketch. */
    fun keepLegAsSketch(r: BuilderRoute, legIndex: Int): BuilderRoute? {
        if (legIndex !in r.legs.indices) return null
        val l = r.legs[legIndex]
        val g = l.drawn?.takeIf { it.size >= 2 } ?: listOf(r.points[legIndex].pos, r.points[legIndex + 1].pos)
        val leg = l.copy(state = LegState.SKETCH, geometry = g, distanceM = Math.round(RouteGeo.lengthM(g)), durationS = null, unmatched = emptyList(), problem = null)
        return r.copy(legs = r.legs.toMutableList().also { it[legIndex] = leg })
    }

    /**
     * "Simpan sebagai lakaran" for every leg still waiting or failed (PENDING or UNRESOLVED), in one edit (one undo
     * step). Routed, matched and sketch legs are left as they are; null when there is nothing to keep.
     */
    fun keepOpenLegsAsSketch(r: BuilderRoute): BuilderRoute? {
        val idx = r.legs.indices.filter { r.legs[it].state == LegState.PENDING || r.legs[it].state == LegState.UNRESOLVED }
        if (idx.isEmpty()) return null
        return idx.fold(r) { acc, i -> keepLegAsSketch(acc, i) ?: acc }
    }

    fun retryLeg(r: BuilderRoute, legIndex: Int): BuilderRoute? {
        if (legIndex !in r.legs.indices) return null
        return settle(invalidate(r, legIndex))
    }

    /** "Cuba lagi" for every leg that failed (offline, server down). */
    fun retryUnresolved(r: BuilderRoute): BuilderRoute? {
        val idx = r.legs.indices.filter { r.legs[it].state == LegState.UNRESOLVED }
        if (idx.isEmpty()) return null
        return settle(idx.fold(r) { acc, i -> invalidate(acc, i) })
    }

    /**
     * Mode and "Ikut jalan" (desktop `UpdateRoute`): a bicycle route is not a motorcycle route, so road legs are routed
     * again when the mode changes; turning "Ikut jalan" off keeps road legs already found and only new legs become
     * sketches; turning it on routes straight sketches again (drawn sketches stay until the user asks).
     */
    fun setOptions(r: BuilderRoute, mode: String = r.mode, followRoads: Boolean = r.followRoads): BuilderRoute {
        val modeChanged = r.mode != mode
        val followChanged = r.followRoads != followRoads
        var out = r.copy(mode = mode, followRoads = followRoads)
        r.legs.forEachIndexed { i, l ->
            val recompute = when (l.state) {
                LegState.ROUTED, LegState.MATCHED -> modeChanged
                LegState.UNRESOLVED -> modeChanged || followChanged
                LegState.SKETCH -> followChanged && followRoads && l.drawn == null
                LegState.PENDING -> true
            }
            if (recompute) out = invalidate(out, i)
        }
        return settle(out)
    }

    /** "Kosongkan". */
    fun clear(r: BuilderRoute): BuilderRoute? = if (r.points.isEmpty()) null else r.copy(points = emptyList(), legs = emptyList())

    /** Keeps every point linked to [stopId] on the stop's pin (the stop was moved in the event editor). */
    fun syncStop(r: BuilderRoute, stopId: String, pos: Pos): BuilderRoute {
        var out = r
        r.points.forEachIndexed { i, p -> if (p.stopId == stopId && p.pos != pos.rounded6()) out = movePoint(out, i, pos) ?: out }
        return out
    }

    /** The stop was deleted from the event: its points become shaping points. */
    fun dropStop(r: BuilderRoute, stopId: String): BuilderRoute =
        if (r.points.none { it.stopId == stopId }) r else r.copy(points = r.points.map { if (it.stopId == stopId) it.copy(stopId = null) else it })

    // ---- routing jobs ---------------------------------------------------------------------------------------

    sealed interface JobKind {
        data class Route(val from: Pos, val to: Pos) : JobKind
        data class Match(val points: List<Pos>) : JobKind
    }

    data class LegJob(val legIndex: Int, val generation: Long, val mode: String, val kind: JobKind)

    sealed interface JobResult {
        data class Routed(val points: List<Pos>, val distanceM: Long, val durationS: Long) : JobResult
        data class Matched(val points: List<Pos>, val distanceM: Long, val durationS: Long, val unmatched: List<Span>) : JobResult
        data class Failed(val problem: String) : JobResult
    }

    /** Up to 5000 points per `/routes/match` request (docs/api.md). */
    const val MATCH_MAX_POINTS = 5000

    /** Every pending leg, when the route follows roads. */
    fun pendingJobs(r: BuilderRoute): List<LegJob> {
        if (!r.followRoads) return emptyList()
        return r.legs.indices.filter { r.legs[it].state == LegState.PENDING }.map { i ->
            val l = r.legs[i]
            val d = l.drawn
            val kind = if (d != null && d.size >= 2) JobKind.Match(RouteGeo.thin(d, MATCH_MAX_POINTS)) else JobKind.Route(r.points[i].pos, r.points[i + 1].pos)
            LegJob(i, l.generation, r.mode, kind)
        }
    }

    /** Applies a routing result if the leg has not been edited since the job was created; null when stale. */
    fun applyJob(r: BuilderRoute, job: LegJob, result: JobResult): BuilderRoute? {
        val l = r.legs.getOrNull(job.legIndex) ?: return null
        if (l.generation != job.generation || l.state != LegState.PENDING) return null
        val leg = when (result) {
            is JobResult.Routed -> if (result.points.size >= 2)
                l.copy(state = LegState.ROUTED, geometry = result.points, distanceM = result.distanceM, durationS = result.durationS, unmatched = emptyList(), problem = null)
            else l.copy(state = LegState.UNRESOLVED, problem = RouteProblem.NO_ROUTE)
            is JobResult.Matched -> if (result.points.size >= 2) {
                val n = (l.drawn?.size ?: 0)
                // Ranges refer to the thinned input; clamp them to the drawn line so they can be drawn.
                val spans = result.unmatched.mapNotNull { s -> if (n == 0) null else Span(s.from.coerceIn(0, n - 1), s.to.coerceIn(0, n - 1)).takeIf { it.from <= it.to } }
                l.copy(state = LegState.MATCHED, geometry = result.points, distanceM = result.distanceM, durationS = result.durationS, unmatched = spans, problem = null)
            } else l.copy(state = LegState.UNRESOLVED, problem = RouteProblem.NO_ROUTE)
            is JobResult.Failed -> l.copy(state = LegState.UNRESOLVED, problem = result.problem)
        }
        return r.copy(legs = r.legs.toMutableList().also { it[job.legIndex] = leg })
    }
}

/** Undo / redo by whole snapshots (routes are small), like the desktop `History`. */
class EditHistory<T>(private val limit: Int = 100) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()

    /** Call with the value as it was *before* an edit. */
    fun record(before: T) {
        undo.addLast(before)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun undo(current: T): T? = undo.removeLastOrNull()?.also { redo.addLast(current) }
    fun redo(current: T): T? = redo.removeLastOrNull()?.also { undo.addLast(current) }
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    fun clear() { undo.clear(); redo.clear() }
}
