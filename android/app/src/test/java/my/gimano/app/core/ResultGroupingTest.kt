package my.gimano.app.core

import my.gimano.app.core.search.ResultGrouping
import my.gimano.app.core.search.ResultRow
import my.gimano.app.core.search.ResultSort
import my.gimano.app.data.model.PlaceSummary
import org.junit.Assert.*
import org.junit.Test

class ResultGroupingTest {
    private fun p(id: String, name: String, d: Double, brand: String? = null, open: Boolean? = null, chain: String? = null) =
        PlaceSummary(id, name, "convenience", "", 3.1, 101.6, d, brand, chainGroup = chain?.let { my.gimano.app.data.model.ChainGroup(it, "Petronas", 9) }, openNow = open)

    private val results = listOf(
        p("a", "FamilyMart", 2100.0, "FamilyMart", true), p("b", "Kedai Ah Seng", 300.0), p("c", "FamilyMart", 1400.0, "FamilyMart", true),
        p("d", "Family Mart", 1600.0, "Family Mart", false), p("e", "7-Eleven", 900.0, "7-Eleven", true), p("f", "7-Eleven", 2500.0, "7-Eleven"),
    )

    @Test fun chainsOfThreeOrMoreCollapseNearestFirst() {
        val rows = ResultGrouping.rows(results, ResultSort.RELEVANT)
        assertEquals(4, rows.size)
        val chain = rows[0] as ResultRow.Chain
        assertEquals("familymart", chain.key)
        assertEquals(listOf("c", "d", "a"), chain.branches.map { it.id })   // nearest first; "Family Mart" spelling joins
        assertEquals("b", (rows[1] as ResultRow.Single).place.id)
        assertTrue("two 7-Elevens stay separate rows", rows.drop(2).all { it is ResultRow.Single })
    }

    @Test fun nearestSortsThenGroups() {
        val rows = ResultGrouping.rows(results, ResultSort.NEAREST)
        assertEquals("b", (rows[0] as ResultRow.Single).place.id)
        assertEquals("e", (rows[1] as ResultRow.Single).place.id)
        assertTrue(rows[2] is ResultRow.Chain)
    }

    @Test fun openNowFiltersUnknownAndClosed() {
        val rows = ResultGrouping.rows(results, ResultSort.OPEN_NOW)
        assertEquals(listOf("e", "c", "a"), rows.map { (it as ResultRow.Single).place.id })   // only 2 FamilyMarts open → no group
        assertTrue(ResultGrouping.knowsOpening(results))
        assertFalse(ResultGrouping.knowsOpening(listOf(p("x", "X", 1.0))))
    }

    @Test fun serverChainGroupWinsOverBrand() {
        val r = listOf(p("1", "Petronas SS2", 100.0, "Petronas", chain = "petronas"), p("2", "PETRONAS Damansara", 200.0, null, chain = "petronas"),
            p("3", "Petronas Kayu Ara", 300.0, "PETRONAS Dagangan", chain = "petronas"))
        val rows = ResultGrouping.rows(r, ResultSort.NEAREST)
        assertEquals(1, rows.size); assertEquals(3, (rows[0] as ResultRow.Chain).branches.size)
        assertEquals("server name and outlet count", "Petronas", (rows[0] as ResultRow.Chain).name)
        assertEquals(9, (rows[0] as ResultRow.Chain).countNearby)
        assertTrue(ResultGrouping.isSearchOnly("st-abc")); assertTrue(ResultGrouping.isSearchOnly("pt-3.1,101.6")); assertFalse(ResultGrouping.isSearchOnly("n123"))
    }
}

class AcceptLanguageTest {
    @org.junit.Test fun followsAppLanguage() {
        org.junit.Assert.assertEquals("ms-MY,ms;q=0.9,en;q=0.5", my.gimano.app.data.remote.ApiFactory.acceptLanguage(true))
        org.junit.Assert.assertEquals("en-MY,en;q=0.9,ms;q=0.5", my.gimano.app.data.remote.ApiFactory.acceptLanguage(false))
    }
}

class SearchContractTest {
    @org.junit.Test fun parsesTheDeployedShape() {
        val json = """{"results":[{"id":"n1","name":"FamilyMart","category":"convenience","address":"Jalan SS 2/67","lat":3.12,"lng":101.62,
            "distanceM":347,"brand":"FamilyMart","source":"osm","reviewCount":0,"chainGroup":{"key":"familymart","name":"FamilyMart","countNearby":12},"openNow":true},
            {"id":"st-x","name":"Jalan SS 2/24","category":"street","address":"SS2","lat":3.11,"lng":101.62,"reviewCount":0,"source":"osm"}],
            "correctedQuery":"FamilyMart","noMatch":false,"suggestions":[{"label":"Kedai runcit","query":"kedai runcit","category":"convenience","count":6}],
            "interpretation":{"kind":"text","normalized":"famly mart"},"extra":1}"""
        val r = my.gimano.app.data.remote.GimanoJson.decodeFromString(my.gimano.app.data.model.SearchResponse.serializer(), json)
        org.junit.Assert.assertEquals(12, r.results[0].chainGroup!!.countNearby)
        org.junit.Assert.assertEquals("FamilyMart", r.correctedQuery)
        org.junit.Assert.assertEquals(6, r.suggestions[0].count)
        org.junit.Assert.assertEquals("street", r.results[1].category)
        val rev = my.gimano.app.data.remote.GimanoJson.decodeFromString(my.gimano.app.data.model.ReverseGeocode.serializer(),
            """{"name":"ZUS Coffee","address":"5, Jalan SS 2/67, 47300 Petaling Jaya","lat":3.1,"lng":101.6,"label":"Near 5, Jalan SS 2/67","houseNumber":"5",
               "street":"Jalan SS 2/67","area":"SS2","city":"Petaling Jaya","postcode":"47300","nearby":[{"id":"n9","name":"ZUS Coffee","lat":3.1,"lng":101.6,"distanceM":12,"reviewCount":0}]}""")
        org.junit.Assert.assertEquals("SS2", rev.area); org.junit.Assert.assertEquals(1, rev.nearby.size)
    }
}
