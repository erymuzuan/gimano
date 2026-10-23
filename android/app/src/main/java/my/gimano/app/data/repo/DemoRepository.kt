package my.gimano.app.data.repo

import kotlinx.coroutines.delay
import my.gimano.app.core.geo.GeoMath
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.geo.Polyline
import my.gimano.app.data.model.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Offline, in-memory backend used for demos and when the server is unreachable. */
class DemoRepository(private val cannedDocs: () -> List<String> = { emptyList() }) : GimanoRepository {

    /** Real OSM road geometry (assets/demo-routes), loaded on first use. */
    val canned: CannedRoutes by lazy { CannedRoutes.parse(cannedDocs()) }

    private val user = User("demo-user", "Pemandu Gimano", "🙂", "car", 1280, 4, "2026-01-01T00:00:00Z")

    private val catalog = listOf(
        p("klcc", "Suria KLCC", "mall", "Kuala Lumpur City Centre, 50088 Kuala Lumpur", 3.1579, 101.7123),
        p("pavilion", "Pavilion Kuala Lumpur", "mall", "168 Jalan Bukit Bintang, 55100 Kuala Lumpur", 3.1490, 101.7134),
        p("midvalley", "Mid Valley Megamall", "mall", "Lingkaran Syed Putra, 59200 Kuala Lumpur", 3.1176, 101.6774),
        p("klsentral", "KL Sentral", "transit_station", "Jalan Stesen Sentral, 50470 Kuala Lumpur", 3.1340, 101.6865),
        p("masjidnegara", "Masjid Negara", "mosque", "Jalan Perdana, 50480 Kuala Lumpur", 3.1418, 101.6917),
        p("batucaves", "Batu Caves", "tourism", "Gombak, 68100 Batu Caves, Selangor", 3.2379, 101.6840),
        p("klia", "KLIA Terminal 1", "airport", "64000 Sepang, Selangor", 2.7456, 101.7072),
        p("putra", "Masjid Putra", "mosque", "Persiaran Persekutuan, Presint 1, 62502 Putrajaya", 2.9360, 101.6897),
        p("sunway", "Sunway Pyramid", "mall", "3 Jalan PJS 11/15, Bandar Sunway, 47500 Petaling Jaya", 3.0731, 101.6070),
        p("1u", "1 Utama Shopping Centre", "mall", "Lebuh Bandar Utama, 47800 Petaling Jaya", 3.1502, 101.6152),
        p("ikea", "IKEA Damansara", "mall", "2 Jalan PJU 7/2, Mutiara Damansara, 47800 Petaling Jaya", 3.1574, 101.6119),
        p("hkl", "Hospital Kuala Lumpur", "hospital", "Jalan Pahang, 50586 Kuala Lumpur", 3.1716, 101.7020),
        p("petronas-ampang", "PETRONAS Jalan Ampang", "fuel", "Jalan Ampang, 50450 Kuala Lumpur", 3.1600, 101.7300, "PETRONAS"),
        p("petronas-tunrazak", "PETRONAS Jalan Tun Razak", "fuel", "Jalan Tun Razak, 50400 Kuala Lumpur", 3.1650, 101.7180, "PETRONAS"),
        p("petronas-duta", "PETRONAS Jalan Duta", "fuel", "Jalan Duta, 50480 Kuala Lumpur", 3.1720, 101.6700, "PETRONAS"),
        p("shell-bangsar", "Shell Jalan Bangsar", "fuel", "Jalan Bangsar, 59200 Kuala Lumpur", 3.1290, 101.6720, "Shell"),
        p("petron-kuching", "Petron Jalan Kuching", "fuel", "Jalan Kuching, 51200 Kuala Lumpur", 3.1830, 101.6810, "Petron"),
        p("bhp-cheras", "BHPetrol Cheras", "fuel", "Jalan Cheras, 56100 Kuala Lumpur", 3.1000, 101.7400, "BHPetrol"),
        p("caltex-pj", "Caltex SS2", "fuel", "Jalan SS 2/24, 47300 Petaling Jaya", 3.1200, 101.6200, "Caltex"),
        p("rr-dengkil", "R&R Dengkil", "rest_area", "Lebuhraya ELITE, 43800 Dengkil, Selangor", 2.8800, 101.6900),
        p("rr-ayerkeroh", "R&R Ayer Keroh", "rest_area", "Lebuhraya Utara-Selatan, 75450 Ayer Keroh, Melaka", 2.2700, 102.2900),
        p("bangsarvillage", "Bangsar Village", "mall", "1 Jalan Telawi 1, Bangsar Baru, 59100 Kuala Lumpur", 3.1306, 101.6710),
        p("pelita", "Nasi Kandar Pelita Jalan Ampang", "restaurant", "149 Jalan Ampang, 50450 Kuala Lumpur", 3.1600, 101.7240),
        p("villagepark", "Village Park Restaurant", "restaurant", "5 Jalan SS 21/37, Damansara Utama, 47400 Petaling Jaya", 3.1360, 101.6230),
        p("dangwangi", "Balai Polis Dang Wangi", "police", "Jalan Dang Wangi, 50100 Kuala Lumpur", 3.1550, 101.6990),
        p("chargev-mv", "EV charger · Mid Valley", "ev_charging", "Mid Valley City, 59200 Kuala Lumpur", 3.1180, 101.6770),
        p("masjidwilayah", "Masjid Wilayah Persekutuan", "mosque", "Jalan Duta, 50480 Kuala Lumpur", 3.1720, 101.6740),
        p("tol-sgbesi", "Plaza Tol Sungai Besi", "toll_booth", "Lebuhraya Utara-Selatan, 57000 Kuala Lumpur", 3.0560, 101.7080),
        p("penangbridge", "Jambatan Pulau Pinang", "tourism", "Pulau Pinang", 5.3550, 100.3480),
        p("jbciq", "Bangunan Sultan Iskandar CIQ", "government", "Jalan Jim Quee, 80300 Johor Bahru", 1.4630, 103.7650),
    )

    private val saved = CopyOnWriteArrayList(
        listOf(
            SavedPlace("home", "home", "Home", 3.1460, 101.6290, "Taman Tun Dr Ismail, 60000 Kuala Lumpur"),
            SavedPlace("work", "work", "Work", 3.1340, 101.6865, "KL Sentral, 50470 Kuala Lumpur"),
            SavedPlace("fav-mv", "favorite", "Mid Valley", 3.1176, 101.6774, "Lingkaran Syed Putra, 59200 Kuala Lumpur", "midvalley"),
            SavedPlace("fav-mn", "favorite", "Masjid Negara", 3.1418, 101.6917, "Jalan Perdana, 50480 Kuala Lumpur", "masjidnegara"),
        ),
    )

    private val recentList = CopyOnWriteArrayList(
        listOf(
            Recent("r1", "Pavilion Kuala Lumpur", "168 Jalan Bukit Bintang, 55100 Kuala Lumpur", 3.1490, 101.7134, "pavilion"),
            Recent("r2", "1 Utama Shopping Centre", "Lebuh Bandar Utama, 47800 Petaling Jaya", 3.1502, 101.6152, "1u"),
            Recent("r3", "Hospital Kuala Lumpur", "Jalan Pahang, 50586 Kuala Lumpur", 3.1716, 101.7020, "hkl"),
            Recent("r4", "KLIA Terminal 1", "64000 Sepang, Selangor", 2.7456, 101.7072, "klia"),
        ),
    )

    private val reports = CopyOnWriteArrayList<Report>()
    private var seededAround: GeoPoint? = null
    private val jams = CopyOnWriteArrayList<Jam>()
    private val drivers = CopyOnWriteArrayList<Driver>()
    private val routeSets = HashMap<String, Pair<CannedRouteSet, RouteRequest>>()

    private fun p(id: String, name: String, cat: String, addr: String, lat: Double, lng: Double, brand: String? = null) =
        Place(id, name, cat, addr, lat, lng, null, brand,
            phone = if (cat in setOf("restaurant", "hospital", "mall", "police")) "+60 3-2161 ${1000 + id.length * 37}" else null,
            website = if (cat == "mall") "https://example.org/${id}" else null,
            openingHours = when (cat) { "fuel", "hospital", "police", "rest_area", "toll_booth", "ev_charging" -> "24/7"; "mosque" -> "Mo-Su 05:30-22:00"; else -> "Mo-Su 10:00-22:00" },
            openNow = true,
            amenities = when (cat) { "fuel" -> listOf("toilets", "convenience_store", "card_payment", "air_pump"); "rest_area" -> listOf("toilets", "surau", "food_court", "fuel"); "mall" -> listOf("parking", "toilets", "surau", "ev_charging"); else -> listOf("toilets") },
            osmType = "node", osmId = (id.hashCode().toLong() and 0xffffff))

    private suspend fun latency() = delay(120)

    override suspend fun ensureAuth() = user
    override suspend fun updateMe(body: UpdateMeRequest) = user.copy(displayName = body.displayName ?: user.displayName, vehicle = body.vehicle ?: user.vehicle)
    override suspend fun places(): List<SavedPlace> = saved.toList()

    override suspend fun setPlace(kind: String, body: SavePlaceRequest): SavedPlace {
        saved.removeAll { it.kind == kind }
        val sp = SavedPlace(kind, kind, body.name, body.lat, body.lng, body.address, body.placeId)
        saved.add(0, sp)
        return sp
    }

    override suspend fun addFavorite(body: SavePlaceRequest): SavedPlace {
        val sp = SavedPlace(UUID.randomUUID().toString(), "favorite", body.name, body.lat, body.lng, body.address, body.placeId)
        saved += sp
        return sp
    }

    override suspend fun deletePlace(id: String) { saved.removeAll { it.id == id } }
    override suspend fun recents(): List<Recent> = recentList.toList()

    override suspend fun search(q: String, lat: Double?, lng: Double?, limit: Int, category: String?): List<PlaceSummary> {
        latency()
        val tokens = q.lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val me = if (lat != null && lng != null) GeoPoint(lat, lng) else null
        return catalog.asSequence()
            .filter { category == null || it.category == category }
            .mapNotNull { pl ->
                val hay = "${pl.name} ${pl.brand ?: ""} ${pl.category.replace('_', ' ')} ${pl.address}".lowercase()
                val score = tokens.sumOf { t -> when { pl.name.lowercase().startsWith(t) -> 3; hay.split(' ').any { it.startsWith(t) } -> 2; hay.contains(t) -> 1; else -> -100 }.toInt() }
                if (score <= 0) null else pl to score
            }
            .map { (pl, score) ->
                val d = me?.let { GeoMath.distanceM(it, GeoPoint(pl.lat, pl.lng)) }
                Triple(pl, score, d)
            }
            .sortedWith(compareByDescending<Triple<Place, Int, Double?>> { it.second }.thenBy { it.third ?: 0.0 })
            .take(limit)
            .map { (pl, _, d) -> PlaceSummary(pl.id, pl.name, pl.category, pl.address, pl.lat, pl.lng, d, pl.brand) }
            .toList()
    }

    override suspend fun nearby(lat: Double, lng: Double, category: String, radius: Int): List<PlaceSummary> {
        latency()
        val me = GeoPoint(lat, lng)
        val cats = if (category == "restaurant") setOf("restaurant", "cafe", "fast_food") else if (category == "mosque") setOf("mosque", "surau") else setOf(category)
        return catalog.filter { it.category in cats }
            .map { PlaceSummary(it.id, it.name, it.category, it.address, it.lat, it.lng, GeoMath.distanceM(me, GeoPoint(it.lat, it.lng)), it.brand) }
            .sortedBy { it.distanceM }
            .take(15)
    }

    override suspend fun place(id: String): Place {
        latency()
        return catalog.firstOrNull { it.id == id } ?: catalog.first()
    }

    override suspend fun reverse(lat: Double, lng: Double): ReverseGeocode {
        val near = catalog.minByOrNull { GeoMath.distanceM(GeoPoint(lat, lng), GeoPoint(it.lat, it.lng)) }
        return if (near != null && GeoMath.distanceM(GeoPoint(lat, lng), GeoPoint(near.lat, near.lng)) < 300)
            ReverseGeocode(near.name, near.address, lat, lng)
        else ReverseGeocode(String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng), "Dropped pin", lat, lng)
    }

    override suspend fun routes(req: RouteRequest): List<Route> {
        delay(350)
        // Add-a-stop: route to the stop first (demo keeps it simple: one leg).
        val target = (req.waypoints.firstOrNull() ?: req.destination).let { GeoPoint(it.lat, it.lng) }
        val set = canned.nearestTo(target) ?: return emptyList()
        val departFactor = if (req.departAt != null) 0.92 else 1.0
        val routes = canned.plan(set, req.vehicle, req.avoid, departFactor)
        routes.forEach { routeSets[it.id] = set to req }
        routes.flatMap { it.alerts }.forEach { a -> if (reports.none { it.id == a.id }) reports += a.toReport() }
        addRecent(req.destination)
        return routes
    }

    private fun addRecent(d: LatLngDto) {
        val pl = d.placeId?.let { id -> catalog.firstOrNull { it.id == id } } ?: return
        recentList.removeAll { it.placeId == pl.id }
        recentList.add(0, Recent(UUID.randomUUID().toString(), pl.name, pl.address, pl.lat, pl.lng, pl.id, Instant.now().toString()))
    }

    override suspend fun reroute(routeId: String, req: RerouteRequest): List<Route> {
        delay(300)
        // Demo routes are canned, so a reroute returns the same real-road route again.
        val (set, orig) = routeSets[routeId] ?: return emptyList()
        val routes = canned.plan(set, orig.vehicle, orig.avoid, 1.0, "reroute").take(1)
        routes.forEach { routeSets[it.id] = set to orig }
        return routes
    }

    override suspend fun departureTimes(oLat: Double, oLng: Double, dLat: Double, dLng: Double): List<DepartureSlot> {
        latency()
        val base = GeoMath.distanceM(GeoPoint(oLat, oLng), GeoPoint(dLat, dLng)) * 1.3 / (40 / 3.6)
        val start = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(15, ChronoUnit.MINUTES)
        return (0 until 10).map { i ->
            val f = 1.0 + 0.35 * kotlin.math.sin(i / 2.0)
            DepartureSlot(start.plus(15L * i, ChronoUnit.MINUTES).toString(), base * f)
        }
    }

    private fun seed(center: GeoPoint) {
        val s = seededAround
        if (s != null && GeoMath.distanceM(s, center) < 15_000) return
        seededAround = center
        val rnd = Random((center.lat * 100).toInt())
        val now = Instant.now()
        val types = listOf(
            "police" to "visible", "accident" to "minor", "hazard" to "pothole", "traffic" to "heavy",
            "hazard" to "flood", "closure" to null, "blocked_lane" to "left", "weather" to "heavy_rain", "animal" to "on_shoulder",
        )
        reports.removeAll { it.id.startsWith("seed-") }
        types.forEachIndexed { i, (t, sub) ->
            val pt = GeoMath.offset(center, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(400.0, 3500.0))
            val age = rnd.nextLong(2, 40)
            reports += Report(
                id = "seed-$i", type = t, subtype = sub, lat = pt.lat, lng = pt.lng, heading = rnd.nextDouble(0.0, 360.0),
                street = listOf("Jalan Ampang", "Jalan Tun Razak", "Jalan Duta", "Jalan Bangsar", "Jalan Cheras")[i % 5],
                reportedBy = ReportedBy(listOf("Aiman", "Mei Ling", "Kavitha", "Hafiz", "Jason")[i % 5], listOf("😎", "🙂", "😴", "🤠", "😇")[i % 5]),
                createdAt = now.minusSeconds(age * 60).toString(), expiresAt = now.plusSeconds((60 - age) * 60).toString(),
                confirmations = rnd.nextInt(0, 9), thanks = rnd.nextInt(0, 5),
            )
        }
        jams.clear()
        repeat(4) { j ->
            val a = GeoMath.offset(center, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(500.0, 3000.0))
            val brg = rnd.nextDouble(0.0, 360.0)
            val pts = (0..8).map { k -> GeoMath.offset(a, brg + 8 * kotlin.math.sin(k.toDouble()), k * 90.0) }
            jams += Jam("jam-$j", 1 + j % 4, 12.0 + j * 4, 120.0 + j * 60, 720.0, Polyline.encode(pts), "Jalan ${listOf("Ampang", "Pahang", "Raja Laut", "Travers")[j]}", now.toString())
        }
        drivers.clear()
        repeat(14) {
            val pt = GeoMath.offset(center, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(200.0, 2500.0))
            drivers += Driver(pt.lat, pt.lng, rnd.nextDouble(0.0, 360.0), listOf("🙂", "😎", "🤠")[it % 3])
        }
    }

    override suspend fun live(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double): LiveResponse {
        seed(GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2))
        val now = Instant.now()
        reports.removeAll { Instant.parse(it.expiresAt).isBefore(now) }
        return LiveResponse(reports.toList(), jams.toList(), drivers.toList(), now.toString())
    }

    override suspend fun createReport(body: CreateReportRequest): Report {
        latency()
        val now = Instant.now()
        val ttlMin = when (body.type) { "police" -> 30L; "traffic" -> 20L; "closure" -> 24 * 60L; "weather" -> 120L; else -> 60L }
        val r = Report(UUID.randomUUID().toString(), body.type, body.subtype, body.lat, body.lng, body.heading, null,
            ReportedBy(user.displayName, user.mood), now.toString(), now.plusSeconds(ttlMin * 60).toString(), 0, 0, body.comment)
        reports += r
        return r
    }

    override suspend fun vote(reportId: String, stillThere: Boolean): Report {
        latency()
        val i = reports.indexOfFirst { it.id == reportId }
        if (i < 0) throw NoSuchElementException(reportId)
        val r = reports[i]
        val upd = if (stillThere) r.copy(confirmations = r.confirmations + 1, expiresAt = Instant.parse(r.expiresAt).plusSeconds(15 * 60).toString())
        else r.copy(expiresAt = Instant.parse(r.expiresAt).minusSeconds(10 * 60).toString())
        reports[i] = upd
        return upd
    }

    override suspend fun thank(reportId: String) {}
    override suspend fun postLocations(batch: LocationBatch) {}
}
