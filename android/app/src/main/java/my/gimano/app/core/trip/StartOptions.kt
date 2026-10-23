package my.gimano.app.core.trip

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.data.model.Recent
import my.gimano.app.data.model.SavedPlace

/** One row of the "Mula dari" sheet (design LaluanPilihMula). */
data class StartOption(val kind: Kind, val name: String, val address: String = "", val point: GeoPoint? = null, val placeId: String? = null) {
    enum class Kind { CURRENT, HOME, WORK, FAVORITE, RECENT }
}

/** What the preview's main button does (design LaluanMulaLain). */
enum class GoAction {
    /** Start turn-by-turn: only ever from the live GPS position. */
    NAVIGATE,
    /** The route starts elsewhere: "Pandu ke {start} dahulu", plus "Simpan perjalanan". */
    DRIVE_TO_START,
    /** No usable start (unknown / locating / already here). */
    NONE,
}

object StartOptions {
    const val MAX_RECENTS = 5
    private const val DUP_M = 15.0

    /**
     * "Lokasi semasa" first, then Home, Work, favourites (in their order) and recent places. Anything within
     * 50 m of the destination is left out (it would be "Anda sudah di sini"), and recents that repeat a saved
     * place are dropped.
     */
    fun build(saved: List<SavedPlace>, recents: List<Recent>, destination: GeoPoint, maxRecents: Int = MAX_RECENTS): List<StartOption> {
        fun near(p: GeoPoint) = RouteOrigin.sameSpot(p, destination)
        val out = mutableListOf(StartOption(StartOption.Kind.CURRENT, ""))
        val ordered = saved.filter { it.kind == "home" } + saved.filter { it.kind == "work" } + saved.filter { it.kind == "favorite" }.sortedBy { it.sortOrder }
        ordered.forEach { s ->
            val p = GeoPoint(s.lat, s.lng)
            if (near(p)) return@forEach
            val kind = when (s.kind) { "home" -> StartOption.Kind.HOME; "work" -> StartOption.Kind.WORK; else -> StartOption.Kind.FAVORITE }
            out += StartOption(kind, s.name, s.address, p, s.placeId)
        }
        val savedPts = ordered.map { GeoPoint(it.lat, it.lng) }
        recents.asSequence()
            .map { StartOption(StartOption.Kind.RECENT, it.name, it.address, GeoPoint(it.lat, it.lng), it.placeId) }
            .filter { o -> !near(o.point!!) && savedPts.none { GeoMath.distanceM(it, o.point) < DUP_M } }
            .distinctBy { it.placeId ?: "${"%.4f".format(it.point!!.lat)},${"%.4f".format(it.point.lng)}" }
            .take(maxRecents)
            .forEach { out += it }
        return out
    }

    fun goAction(source: OriginSource, alreadyHere: Boolean, hasRoute: Boolean): GoAction = when {
        alreadyHere || !hasRoute -> GoAction.NONE
        source == OriginSource.GPS -> GoAction.NAVIGATE
        source == OriginSource.CHOSEN -> GoAction.DRIVE_TO_START
        else -> GoAction.NONE
    }
}
