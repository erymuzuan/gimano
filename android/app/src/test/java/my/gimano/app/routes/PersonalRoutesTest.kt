package my.gimano.app.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.gimano.app.backup.JdbcSqlPort
import my.gimano.app.core.routes.BuilderLeg
import my.gimano.app.core.routes.BuilderPoint
import my.gimano.app.core.routes.BuilderRoute
import my.gimano.app.core.routes.DrawGesture
import my.gimano.app.core.routes.Generations
import my.gimano.app.core.routes.LegState
import my.gimano.app.core.routes.PersonalRouteJson
import my.gimano.app.core.routes.Pos
import my.gimano.app.core.routes.RouteAssembly
import my.gimano.app.core.routes.Span
import my.gimano.app.data.events.EventVault
import my.gimano.app.data.events.PersonalRouteDao
import my.gimano.app.data.events.PersonalRouteEntity
import my.gimano.app.data.local.GimanoSchema
import my.gimano.app.data.routes.LegacyPersonalPayload
import my.gimano.app.data.routes.PersonalRouteMove
import my.gimano.app.data.routes.PersonalRouteStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant

/** "Laluan saya" in the main DB: route_json v1 (shared with iOS), the store, schema 2 → 3, and the one-time move. */
class PersonalRoutesTest {
    private val fixture = javaClass.classLoader!!.getResourceAsStream("personal-route-v1.json")!!.bufferedReader().use { it.readText() }
    private val dir: File = Files.createTempDirectory("gimano-routes").toFile()

    private fun leg(state: LegState, geo: List<Pos>, distanceM: Long = 0, durationS: Long? = null, unmatched: List<Span> = emptyList(), drawn: List<Pos>? = null) =
        BuilderLeg(state, Generations.next(), geo, distanceM, durationS, drawn = drawn, unmatched = unmatched)

    private val a = Pos(3.1, 101.6); private val b = Pos(3.2, 101.7); private val c = Pos(3.3, 101.8)
    private val d = Pos(3.4, 101.9); private val e = Pos(3.5, 102.0); private val f = Pos(3.6, 102.1); private val g = Pos(3.7, 102.2)

    /** Every leg state the builder has, incl. a drawn leg with an unmatched stretch and a failed leg. */
    private fun allStates() = BuilderRoute(
        id = "r-all", title = "Semua", mode = "motorcycle",
        points = listOf(a, b, c, d, e, f, g).mapIndexed { i, p -> BuilderPoint("p$i", p.lat, p.lng, stopId = if (i == 0) "stop-1" else null) },
        legs = listOf(
            leg(LegState.ROUTED, listOf(a, Pos(3.15, 101.65), b), 15_000, 900),
            leg(LegState.MATCHED, listOf(b, Pos(3.25, 101.75), c), 14_000, 800, drawn = listOf(b, c)),
            leg(LegState.MATCHED, listOf(c, d), 13_000, 700, unmatched = listOf(Span(0, 1)), drawn = listOf(c, d)),
            leg(LegState.SKETCH, listOf(d, e), 12_000),
            leg(LegState.PENDING, emptyList()),
            leg(LegState.UNRESOLVED, emptyList()).copy(problem = "offline"),
        ),
    )

    // --- route_json v1 ------------------------------------------------------------------------------------------

    @Test fun fixtureDecodesWithUnknownFieldsIgnored() {
        val r = PersonalRouteJson.decode(fixture, "id-1", "Ke KLCC", "bicycle")
        assertEquals("id-1", r.id); assertEquals("Ke KLCC", r.title); assertEquals("bicycle", r.mode)
        assertTrue(r.isConsistent)
        assertEquals(listOf(Pos(3.139003, 101.686855), Pos(3.149, 101.696), Pos(3.158, 101.712), Pos(3.165, 101.72), Pos(3.17, 101.73)), r.points.map { it.pos })
        assertEquals(listOf(LegState.ROUTED, LegState.MATCHED, LegState.SKETCH, LegState.PENDING), r.legs.map { it.state })
        assertEquals(listOf(Pos(3.139003, 101.686855), Pos(3.144, 101.69), Pos(3.149, 101.696)), r.legs[0].geometry)
        assertEquals(4, r.legs[1].geometry.size)
        assertEquals(listOf(Pos(3.158, 101.712), Pos(3.165, 101.72)), r.legs[2].geometry)
        assertEquals(listOf(1650L, 2100L, 1180L), r.legs.take(3).map { it.distanceM })
        assertEquals(listOf(240L, 300L, null, null), r.legs.map { it.durationS })
        assertTrue(r.legs[3].geometry.isEmpty())
        assertTrue(r.followRoads)
        assertTrue(r.points.all { it.stopId == null } && r.points.map { it.id }.toSet().size == 5)
        val sum = RouteAssembly.summary(r)
        assertEquals(1, sum.pendingLegs)
        assertEquals(4930L, sum.distanceM)
    }

    @Test fun fixtureSurvivesAnotherRoundTripWithoutTheUnknownFields() {
        val r = PersonalRouteJson.decode(fixture, "id-1", "n", "car")
        val out = PersonalRouteJson.encode(r)
        assertFalse(out.contains("note") || out.contains("label") || out.contains("confidence"))
        val again = PersonalRouteJson.decode(out, "id-1", "n", "car")
        assertEquals(r.points.map { it.pos }, again.points.map { it.pos })
        assertEquals(r.legs.map { listOf(it.state, it.geometry, it.distanceM, it.durationS) }, again.legs.map { listOf(it.state, it.geometry, it.distanceM, it.durationS) })
        // Polylines are written exactly as in the fixture (precision 6).
        val src = Json.parseToJsonElement(fixture).jsonObject.getValue("legs").jsonArray.map { it.jsonObject.getValue("polyline").jsonPrimitive.content }
        val dst = Json.parseToJsonElement(out).jsonObject.getValue("legs").jsonArray.map { it.jsonObject.getValue("polyline").jsonPrimitive.content }
        assertEquals(src, dst)
    }

    @Test fun roundTripEveryLegState() {
        val r = allStates()
        val json = PersonalRouteJson.encode(r)
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(1, o.getValue("v").jsonPrimitive.int)
        val legs = o.getValue("legs").jsonArray.map { it.jsonObject }
        assertEquals(r.points.size - 1, legs.size)
        assertEquals(listOf("routed", "matched", "sketch", "sketch", "pending", "pending"), legs.map { it.getValue("source").jsonPrimitive.content })
        // Pending: empty polyline, no numbers ("absent = unknown, never 0").
        legs.takeLast(2).forEach { l ->
            assertEquals("", l.getValue("polyline").jsonPrimitive.content)
            assertFalse("distanceM" in l || "durationS" in l)
        }
        assertFalse("durationS" in legs[3])                              // a sketch has no moving time
        // Only the documented keys are written: no ids, stop links, generations, drawn strokes or problems.
        assertEquals(setOf("v", "points", "legs"), o.keys)
        assertTrue(legs.all { it.keys.all { k -> k in setOf("source", "polyline", "distanceM", "durationS") } })
        assertTrue(o.getValue("points").jsonArray.all { it.jsonObject.keys == setOf("lat", "lng") })

        val back = PersonalRouteJson.decode(json, r.id, r.title, r.mode)
        assertEquals(r.points.map { it.pos }, back.points.map { it.pos })
        assertEquals(listOf(LegState.ROUTED, LegState.MATCHED, LegState.SKETCH, LegState.SKETCH, LegState.PENDING, LegState.PENDING), back.legs.map { it.state })
        r.legs.take(4).zip(back.legs.take(4)).forEach { (x, y) ->
            assertEquals(x.geometry, y.geometry); assertEquals(x.distanceM, y.distanceM); assertEquals(x.durationS, y.durationS)
        }
        assertTrue(back.legs[1].unmatched.isEmpty() && back.legs[1].drawn == null)
        assertTrue(back.legs.map { it.generation }.toSet().size == back.legs.size)
        assertEquals(RouteAssembly.summary(r).distanceM, RouteAssembly.summary(back).distanceM)
    }

    @Test fun decodeIsLenientWhereItMustBeAndStrictOnTheVersion() {
        assertThrows(IllegalArgumentException::class.java) { PersonalRouteJson.decode("""{"v":2,"points":[],"legs":[]}""", "x", "", "car") }
        assertThrows(IllegalArgumentException::class.java) { PersonalRouteJson.decode("not json", "x", "", "car") }
        // A leg list that does not fit the points is rebuilt as legs to calculate.
        val r = PersonalRouteJson.decode("""{"v":1,"points":[{"lat":1,"lng":2},{"lat":1.1,"lng":2.1},{"lat":1.2,"lng":2.2}],"legs":[]}""", "x", "", "walk")
        assertEquals(listOf(LegState.PENDING, LegState.PENDING), r.legs.map { it.state })
        // Unknown source, or a broken polyline: pending, never a fake road. Distance missing: measured from the line.
        val s = PersonalRouteJson.decode(
            """{"v":1,"points":[{"lat":1,"lng":2},{"lat":1.1,"lng":2.1},{"lat":1.2,"lng":2.2}],"legs":[{"source":"teleport","polyline":"abc"},{"source":"routed","polyline":"_"}]}""",
            "x", "", "walk")
        assertEquals(listOf(LegState.PENDING, LegState.PENDING), s.legs.map { it.state })
        val t = PersonalRouteJson.decode(PersonalRouteJson.encode(allStates().copy(legs = allStates().legs.mapIndexed { i, l -> if (i == 0) l.copy(distanceM = 0) else l })), "x", "", "car")
        assertTrue(t.legs[0].distanceM > 10_000)
        // A route only of straight sketches was built with "Ikut jalan" off.
        val sk = BuilderRoute("s", points = listOf(a, b).mapIndexed { i, p -> BuilderPoint("q$i", p.lat, p.lng) }, legs = listOf(leg(LegState.SKETCH, listOf(a, b), 100)), followRoads = false)
        assertFalse(PersonalRouteJson.decode(PersonalRouteJson.encode(sk), "s", "", "car").followRoads)
    }

    // --- Store (main DB, plain columns) --------------------------------------------------------------------------

    private fun mainDb(name: String): JdbcSqlPort = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:" + File(dir, name).path)).also { p -> GimanoSchema.create { p.exec(it) } }

    private fun store(db: JdbcSqlPort, at: String = "2026-10-24T01:00:00Z") = PersonalRouteStore({ db }, now = { Instant.parse(at) }, io = Dispatchers.Unconfined)

    @Test fun storeWritesPlainColumnsAndKeepsCreatedAt() = runBlocking<Unit> {
        val db = mainDb("store.db")
        val routed = allStates().let { r -> r.copy(points = r.points.take(3), legs = r.legs.take(2)) }
        store(db, "2026-10-24T01:00:00Z").save("  Ke pejabat\u0007 ", routed)
        assertEquals(listOf("r-all|Ke pejabat|motorcycle|29000|1700|2026-10-24T01:00:00Z|2026-10-24T01:00:00Z"),
            db.queryStrings("SELECT id||'|'||name||'|'||mode||'|'||distance_m||'|'||duration_s||'|'||created_at||'|'||updated_at FROM personal_routes"))
        assertEquals(PersonalRouteJson.encode(routed), db.queryStrings("SELECT route_json FROM personal_routes").single())

        // Edited later with a leg still to calculate: unknown totals are NULL, created_at stays.
        store(db, "2026-10-24T02:00:00Z").save("Ke pejabat", allStates())
        assertEquals(listOf("2026-10-24T01:00:00Z|2026-10-24T02:00:00Z|1|1"),
            db.queryStrings("SELECT created_at||'|'||updated_at||'|'||(distance_m IS NULL)||'|'||(duration_s IS NULL) FROM personal_routes"))

        val s = store(db)
        val p = s.get("r-all")!!
        assertEquals("Ke pejabat", p.name)
        assertEquals(allStates().points.map { it.pos }, p.route.points.map { it.pos })
        assertEquals(Instant.parse("2026-10-24T02:00:00Z").toEpochMilli(), p.updatedAt)
        assertEquals(listOf("r-all"), s.routes().first().map { it.id })
        // An iOS row whose route_json this version can't read is skipped, not deleted.
        db.exec("INSERT INTO personal_routes VALUES ('ios-2','Masa depan','walk','{\"v\":2}',NULL,NULL,'t','t')")
        assertEquals(1, s.all().size)
        s.delete("r-all")
        assertEquals(listOf("ios-2"), db.queryStrings("SELECT id FROM personal_routes"))
        assertThrows(Exception::class.java) { db.exec("INSERT INTO personal_routes VALUES ('bad','x','boat','{}',NULL,NULL,'t','t')") }
    }

    // --- Schema 2 → 3 --------------------------------------------------------------------------------------------

    @Test fun schema2To3MigrationAddsPersonalRoutesAndKeepsData() {
        val db = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite:" + File(dir, "v2.db").path))
        GimanoSchema.DDL.filter { it != GimanoSchema.PERSONAL_ROUTES_DDL }.forEach { db.exec(it) }
        db.exec("PRAGMA user_version = 2")
        db.exec("INSERT INTO saved_places VALUES ('h','home','Rumah','home',3.1,101.6,NULL,NULL,0,'t','t')")
        db.exec("INSERT INTO identity VALUES ('u1',NULL,'seed','t')")
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE name = 'personal_routes'"))

        GimanoSchema.upgrade { db.exec(it) }
        GimanoSchema.upgrade { db.exec(it) }   // runs on every open: idempotent

        val fresh = mainDb("v3.db")
        val info = "SELECT name || ':' || type || ':' || \"notnull\" || ':' || COALESCE(dflt_value,'') || ':' || pk FROM pragma_table_info('personal_routes')"
        assertEquals(fresh.queryStrings(info), db.queryStrings(info))
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM saved_places"))
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM identity"))
        assertEquals(3, GimanoSchema.VERSION)
        assertTrue("personal_routes" in GimanoSchema.BACKED_UP_TABLES && "personal_routes" in GimanoSchema.REQUIRED_TABLES)
        // The CHECK on mode is real after the migration too.
        store(db).saveNow("x", allStates())
        assertThrows(Exception::class.java) { db.exec("INSERT INTO personal_routes VALUES ('b','x','plane','{}',NULL,NULL,'t','t')") }
    }

    // --- One-time move from manotu-events.db ---------------------------------------------------------------------

    private class FakeLegacy : PersonalRouteDao {
        val rows = LinkedHashMap<String, PersonalRouteEntity>()
        override suspend fun all() = rows.values.toList()
        override suspend fun upsert(e: PersonalRouteEntity) { rows[e.id] = e }
        override suspend fun delete(id: String) { rows.remove(id) }
    }

    /** Stand-in for the Keystore vault: XOR, and "lost key" rows that can't be opened. */
    private class FakeVault : EventVault {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun open(sealed: ByteArray): ByteArray {
            if (sealed.isEmpty()) throw javax.crypto.AEADBadTagException()
            return seal(sealed)
        }
    }

    private fun legacyRow(vault: EventVault, name: String, r: BuilderRoute, at: Long) = PersonalRouteEntity(
        r.id, vault.seal(Json.encodeToString(LegacyPersonalPayload.serializer(), LegacyPersonalPayload(name, r)).toByteArray()), at)

    @Test fun oneTimeMoveFromTheEventsDbIsIdempotent() = runBlocking<Unit> {
        val db = mainDb("move.db")
        val s = store(db)
        val vault = FakeVault()
        val legacy = FakeLegacy()
        val t = Instant.parse("2026-10-20T08:00:00Z").toEpochMilli()
        legacy.upsert(legacyRow(vault, "Lama", allStates(), t))
        legacy.upsert(legacyRow(vault, "Kedua", allStates().copy(id = "r-2", mode = "walk"), t + 1000))
        legacy.upsert(PersonalRouteEntity("lost", ByteArray(0), t))                 // the vault key is gone: left alone
        // Already in the main DB (e.g. a crash after the copy, before the delete): the main DB's row wins.
        s.saveNow("Sudah di sini", allStates().copy(id = "r-2"))
        legacy.upsert(legacyRow(vault, "Kedua", allStates().copy(id = "r-2", mode = "walk"), t + 1000))

        assertEquals(1, PersonalRouteMove(legacy, vault, s).run())
        assertEquals(listOf("lost"), legacy.rows.keys.toList())
        assertEquals(listOf("r-2|Sudah di sini|motorcycle", "r-all|Lama|motorcycle"), db.queryStrings("SELECT id||'|'||name||'|'||mode FROM personal_routes ORDER BY id"))
        assertEquals(listOf("2026-10-20T08:00:00Z|2026-10-20T08:00:00Z"), db.queryStrings("SELECT created_at||'|'||updated_at FROM personal_routes WHERE id = 'r-all'"))
        assertEquals(allStates().points.map { it.pos }, s.get("r-all")!!.route.points.map { it.pos })

        val before = db.queryStrings("SELECT id||route_json||updated_at FROM personal_routes ORDER BY id")
        assertEquals(0, PersonalRouteMove(legacy, vault, s).run())
        assertEquals(before, db.queryStrings("SELECT id||route_json||updated_at FROM personal_routes ORDER BY id"))
    }

    // --- "Lukis": one finger draws, two fingers move the map ----------------------------------------------------

    @Test fun oneFingerDrawsAndLiftEndsTheStroke() {
        val g = DrawGesture(stepPx = 10f)
        g.down(0f, 0f)
        assertFalse(g.move(5f, 0f, 1))                  // under one step
        assertTrue(g.move(12f, 0f, 1)); assertTrue(g.move(24f, 0f, 1))
        val end = g.up(30f, 0f)!!
        assertEquals(listOf(0f, 12f, 24f, 30f), end.points!!.map { it.x })
        assertEquals(DrawGesture.Phase.IDLE, g.phase)
        // A tap is not a line.
        g.down(1f, 1f)
        assertNull(g.up(1f, 1f)!!.points)
    }

    @Test fun secondFingerEndsTheStrokeCleanlyAndHandsTheGestureToTheMap() {
        val g = DrawGesture(stepPx = 10f)
        g.down(0f, 0f); g.move(12f, 0f, 1); g.move(24f, 0f, 1)
        val end = g.secondFinger()!!
        // Kept as drawn so far; the second finger's position never joins it (no zig-zag).
        assertEquals(listOf(0f, 12f, 24f), end.points!!.map { it.x })
        assertEquals(DrawGesture.Phase.MAP, g.phase)
        // Pinch / pan moves (two fingers, then the one left) are the map's, never drawn.
        assertFalse(g.move(300f, 300f, 2)); assertFalse(g.move(320f, 310f, 1))
        assertTrue(g.points.isEmpty())
        assertNull(g.up(320f, 310f))
        assertEquals(DrawGesture.Phase.IDLE, g.phase)
        // A pinch that starts (almost) together: the stray dot is dropped.
        g.down(5f, 5f)
        assertNull(g.secondFinger()!!.points)
        assertNull(g.secondFinger())                   // a third finger: still the map's
        g.cancel()
        assertEquals(DrawGesture.Phase.IDLE, g.phase)
    }
}
