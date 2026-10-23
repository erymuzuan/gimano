package my.gimano.app.trip

import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.trip.GoAction
import my.gimano.app.core.trip.OriginSource
import my.gimano.app.core.trip.StartOption.Kind
import my.gimano.app.core.trip.StartOptions
import my.gimano.app.data.model.Recent
import my.gimano.app.data.model.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Test

class StartOptionsTest {
    private val dest = GeoPoint(3.1579, 101.7123)           // KLCC
    private val home = SavedPlace("h", "home", "Rumah", 3.0738, 101.5183, "Shah Alam")
    private val work = SavedPlace("w", "work", "Pejabat", 3.1390, 101.6869, "KL Sentral")
    private val favB = SavedPlace("f2", "favorite", "Kampung", 2.0, 102.5, sortOrder = 2)
    private val favA = SavedPlace("f1", "favorite", "Mak", 2.5, 102.0, sortOrder = 1)
    private val favAtDest = SavedPlace("f3", "favorite", "Suria KLCC", 3.15805, 101.71235, sortOrder = 0)

    @Test fun orderIsCurrentHomeWorkFavouritesRecents() {
        val recents = listOf(Recent("r1", "Econsave", "", 3.0, 101.4), Recent("r2", "IKEA", "", 3.15, 101.6))
        val o = StartOptions.build(listOf(favB, work, favA, home), recents, dest)
        assertEquals(listOf(Kind.CURRENT, Kind.HOME, Kind.WORK, Kind.FAVORITE, Kind.FAVORITE, Kind.RECENT, Kind.RECENT), o.map { it.kind })
        assertEquals(listOf("Mak", "Kampung"), o.filter { it.kind == Kind.FAVORITE }.map { it.name })
    }

    @Test fun placesAtTheDestinationAreLeftOut() {
        val recents = listOf(Recent("r1", "KLCC", "", 3.15795, 101.71232))
        val o = StartOptions.build(listOf(favAtDest, home), recents, dest)
        assertEquals(listOf(Kind.CURRENT, Kind.HOME), o.map { it.kind })
    }

    @Test fun recentsRepeatingSavedOrEachOtherAreDroppedAndCapped() {
        val recents = listOf(Recent("r0", "Rumah", "", 3.07381, 101.51831)) +
            (1..8).map { Recent("r$it", "R$it", "", 3.0 + it * 0.01, 101.0, placeId = if (it == 2) "p" else null) } +
            Recent("r9", "R2 again", "", 3.5, 101.5, placeId = "p")
        val o = StartOptions.build(listOf(home), recents, dest)
        val names = o.filter { it.kind == Kind.RECENT }.map { it.name }
        assertEquals(listOf("R1", "R2", "R3", "R4", "R5"), names)
    }

    @Test fun onlyAGpsStartCanNavigate() {
        assertEquals(GoAction.NAVIGATE, StartOptions.goAction(OriginSource.GPS, alreadyHere = false, hasRoute = true))
        assertEquals(GoAction.DRIVE_TO_START, StartOptions.goAction(OriginSource.CHOSEN, alreadyHere = false, hasRoute = true))
        assertEquals(GoAction.NONE, StartOptions.goAction(OriginSource.UNKNOWN, alreadyHere = false, hasRoute = true))
        assertEquals(GoAction.NONE, StartOptions.goAction(OriginSource.LOCATING, alreadyHere = false, hasRoute = true))
        assertEquals(GoAction.NONE, StartOptions.goAction(OriginSource.GPS, alreadyHere = true, hasRoute = true))
        assertEquals(GoAction.NONE, StartOptions.goAction(OriginSource.GPS, alreadyHere = false, hasRoute = false))
    }
}
