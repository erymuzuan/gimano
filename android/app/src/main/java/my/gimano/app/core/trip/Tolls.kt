package my.gimano.app.core.trip

import my.gimano.app.data.model.Route
import java.util.Locale

/** What the route card's toll badge says (docs/api.md "Tolls & fares"). */
sealed interface TollBadge {
    data object None : TollBadge
    /** Every plaza priced: "RM 2.50". */
    data class Exact(val myr: Double) : TollBadge
    /** Some plazas priced: "from RM 1.60". */
    data class From(val myr: Double) : TollBadge
    /** Tolls, but no fare known: "Tolls". */
    data object Unknown : TollBadge

    companion object {
        fun of(route: Route): TollBadge {
            if (!TravelMode.from(route.vehicle).isMotorised && route.vehicle != null) return None
            val hasTolls = route.hasTolls || route.tolls.isNotEmpty()
            if (!hasTolls) return None
            val est = route.tollEstimateMyr
            return when {
                est == null -> Unknown
                route.tollFareComplete -> Exact(est)
                est > 0 -> From(est)
                else -> Unknown
            }
        }

        fun rm(myr: Double): String = String.format(Locale.US, "RM %.2f", myr)
    }
}
