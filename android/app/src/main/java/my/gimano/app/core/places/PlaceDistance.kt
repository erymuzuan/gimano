package my.gimano.app.core.places

import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint

/**
 * Distance to a place, computed on the phone: the user's position goes to the server only when routing
 * (never on GET /places/{id}). Only from a fresh GPS fix; null without one (search distances measured from the
 * map view, or a stale fix, would mislead).
 */
object PlaceDistance {
    fun metres(freshFix: GeoPoint?, placeLat: Double, placeLng: Double): Double? =
        freshFix?.let { GeoMath.distanceM(it, GeoPoint(placeLat, placeLng)) }
}
