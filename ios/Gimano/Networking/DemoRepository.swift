import Foundation

/// Offline demo backend: Kuala Lumpur places, captured real-road routes, and live alerts/jams/drivers placed on those roads.
/// Everything is generated locally so the whole UI can be demoed without the .NET backend.
actor DemoRepository: GimanoRepository {
    static let defaultLocation = LatLng(lat: 3.152613, lng: 101.703718) // on Jalan Sultan Ismail, KL (road-snapped; origin of DemoRoutes.json)

    private var user = User(id: "demo-user", displayName: "Pemandu Gimano", mood: "😎", vehicle: .car, points: 1280, level: 3, joinedAt: .now.addingTimeInterval(-86400 * 40))
    private var saved: [SavedPlace]
    private var recentList: [Recent]
    private var reports: [Report]
    private var routeDestinations: [String: (dest: RouteEndpoint, vehicle: Vehicle, avoid: AvoidOptions)] = [:]
    private var routeCounter = 0
    private let latency: Duration

    init(latency: Duration = .milliseconds(250)) {
        self.latency = latency
        saved = [
            SavedPlace(id: "home", kind: .home, name: "Home", lat: 3.1466, lng: 101.6297, address: "Taman Tun Dr Ismail, 60000 Kuala Lumpur"),
            SavedPlace(id: "work", kind: .work, name: "Work", lat: 3.1110, lng: 101.6655, address: "Bangsar South, 59200 Kuala Lumpur"),
            SavedPlace(id: "fav-1", kind: .favorite, name: "Mak's house", lat: 3.0738, lng: 101.5183, address: "Seksyen 7, 40000 Shah Alam"),
            SavedPlace(id: "fav-2", kind: .favorite, name: "Gym", lat: 3.1579, lng: 101.7123, address: "Suria KLCC, Kuala Lumpur"),
        ]
        recentList = [
            Recent(id: "r1", name: "Mid Valley Megamall", address: "Lingkaran Syed Putra, Mid Valley City, 59200 Kuala Lumpur", lat: 3.1178, lng: 101.6773, placeId: "demo:midvalley", lastUsedAt: .now.addingTimeInterval(-3600)),
            Recent(id: "r2", name: "Batu Caves", address: "Gombak, 68100 Batu Caves, Selangor", lat: 3.2379, lng: 101.6840, placeId: "demo:batucaves", lastUsedAt: .now.addingTimeInterval(-86400)),
            Recent(id: "r3", name: "KL Sentral", address: "Jalan Stesen Sentral, 50470 Kuala Lumpur", lat: 3.1340, lng: 101.6862, placeId: "demo:klsentral", lastUsedAt: .now.addingTimeInterval(-86400 * 3)),
            Recent(id: "r4", name: "Sunway Pyramid", address: "Jalan PJS 11/15, Bandar Sunway, 47500 Petaling Jaya", lat: 3.0731, lng: 101.6070, placeId: "demo:sunway", lastUsedAt: .now.addingTimeInterval(-86400 * 6)),
        ]
        let now = Date()
        func r(_ id: String, _ type: ReportType, _ sub: String?, _ lat: Double, _ lng: Double, _ street: String, _ minsAgo: Double, conf: Int) -> Report {
            Report(id: id, type: type, subtype: sub, lat: lat, lng: lng, heading: nil, street: street,
                   reportedBy: ReportAuthor(displayName: ["Aina", "Raj", "Mei Ling", "Hafiz", "Siti"][abs(id.hashValue) % 5], mood: "🙂"),
                   createdAt: now.addingTimeInterval(-minsAgo * 60), expiresAt: now.addingTimeInterval(3600), confirmations: conf, thanks: conf / 2)
        }
        // Every demo alert sits on a vertex of a captured real-road route (never off-road).
        let seeds: [(String, ReportType, String?, String, Int, Double, Double, Int)] = [
            ("demo-route-police", .police, "visible", "midvalley", 0, 0.18, 4, 6),
            ("demo-route-hazard", .hazard, "pothole", "midvalley", 0, 0.55, 11, 3),
            ("demo-a1", .police, "visible", "klcc", 0, 0.5, 6, 4),
            ("demo-a2", .hazard, "pothole", "batucaves", 0, 0.35, 18, 2),
            ("demo-a3", .accident, "minor", "klsentral", 0, 0.6, 9, 7),
            ("demo-a4", .traffic, "heavy", "home", 0, 0.45, 3, 12),
            ("demo-a5", .weather, "heavy_rain", "batucaves", 1, 0.7, 25, 5),
            ("demo-a6", .hazard, "flood", "work", 0, 0.7, 40, 9),
            ("demo-a7", .closure, nil, "pavilion", 1, 0.5, 55, 1),
            ("demo-a8", .animal, "on_shoulder", "sunway", 0, 0.8, 12, 1),
        ]
        reports = seeds.compactMap { id, type, sub, key, route, fraction, mins, conf in
            guard let v = CannedRoutes.shared.vertex(key: key, routeIndex: route, fraction: fraction) else { return nil }
            return r(id, type, sub, v.point.lat, v.point.lng, v.street ?? "", mins, conf: conf)
        }
    }

    private func pause() async { try? await Task.sleep(for: latency) }

    // MARK: auth/profile

    func signIn() async throws -> DeviceAuthResponse {
        DeviceAuthResponse(token: "demo-token", userId: user.id, displayName: user.displayName)
    }

    func me() async throws -> User { user }

    func updateMe(_ patch: UserPatch) async throws -> User {
        if let n = patch.displayName { user.displayName = n }
        if let m = patch.mood { user.mood = m }
        if let v = patch.vehicle { user.vehicle = v }
        return user
    }

    // MARK: places

    func savedPlaces() async throws -> [SavedPlace] { saved }

    func setPlace(kind: SavedPlaceKind, _ input: SavedPlaceInput) async throws -> SavedPlace {
        let p = SavedPlace(id: kind.rawValue, kind: kind, name: input.name, lat: input.lat, lng: input.lng, address: input.address, placeId: input.placeId)
        saved.removeAll { $0.kind == kind }
        saved.insert(p, at: kind == .home ? 0 : min(1, saved.count))
        return p
    }

    func addFavorite(_ input: SavedPlaceInput) async throws -> SavedPlace {
        let p = SavedPlace(id: "fav-\(UUID().uuidString.prefix(6))", kind: .favorite, name: input.name, lat: input.lat, lng: input.lng, address: input.address, placeId: input.placeId)
        saved.append(p)
        return p
    }

    func deletePlace(id: String) async throws { saved.removeAll { $0.id == id } }

    func recents() async throws -> [Recent] { recentList }

    func search(query: String, near: LatLng, category: PlaceCategory?, limit: Int) async throws -> [PlaceSummary] {
        await pause()
        let q = query.lowercased().trimmingCharacters(in: .whitespaces)
        let scored: [(PlaceSummary, Double)] = DemoData.places.compactMap { p in
            if let category, p.category != category { return nil }
            let name = p.name.lowercased()
            var score: Double
            if q.isEmpty { score = 1 }
            else if name.hasPrefix(q) { score = 3 }
            else if name.split(separator: " ").contains(where: { $0.hasPrefix(q) }) { score = 2.5 }
            else if name.contains(q) { score = 2 }
            else if (p.brand?.lowercased().hasPrefix(q) ?? false) || p.category.rawValue.replacingOccurrences(of: "_", with: " ").hasPrefix(q) { score = 1.8 }
            else if p.address.lowercased().contains(q) { score = 1 }
            else { return nil }
            var s = p
            s.distanceM = Geo.distance(near, p.location)
            // Proximity decay (~ 10 km scale), but exact matches far away still show.
            score *= 1 / (1 + (s.distanceM ?? 0) / 10_000)
            return (s, score)
        }
        return scored.sorted { $0.1 > $1.1 }.prefix(limit).map(\.0)
    }

    func nearby(near: LatLng, category: PlaceCategory, radiusM: Int) async throws -> [PlaceSummary] {
        await pause()
        return DemoData.places.filter { $0.category == category }
            .map { var s = $0; s.distanceM = Geo.distance(near, $0.location); return s }
            .filter { ($0.distanceM ?? 0) <= Double(radiusM) * 4 }
            .sorted { ($0.distanceM ?? 0) < ($1.distanceM ?? 0) }
    }

    func place(id: String) async throws -> Place {
        guard let s = DemoData.places.first(where: { $0.id == id }) else { throw APIError.http(status: 404, problem: nil) }
        let amenities: [String] = switch s.category {
        case .fuel: ["toilets", "convenience_store", "air_pump", "cards", "touch_n_go"]
        case .restArea: ["toilets", "surau", "food_court", "fuel"]
        case .mall: ["parking", "toilets", "surau", "ev_charging"]
        default: ["toilets", "wheelchair"]
        }
        return Place(id: s.id, name: s.name, category: s.category, address: s.address, lat: s.lat, lng: s.lng, distanceM: nil,
                     brand: s.brand, phone: "+60 3-2123 4567", website: "https://www.openstreetmap.org",
                     openingHours: s.category == .fuel || s.category == .restArea ? "24/7" : "Mo-Su 10:00-22:00",
                     openNow: true, amenities: amenities, tags: [:], osmType: "node", osmId: Int64(abs(s.id.hashValue) % 1_000_000_000))
    }

    func reverseGeocode(_ p: LatLng) async throws -> ReverseGeocode {
        let nearest = DemoData.places.min { Geo.distance(p, $0.location) < Geo.distance(p, $1.location) }
        return ReverseGeocode(name: "Dropped pin", address: "Near \(nearest?.name ?? "Kuala Lumpur")", lat: p.lat, lng: p.lng)
    }

    // MARK: routing

    func routes(_ request: RouteRequest) async throws -> [Route] {
        await pause()
        let o = LatLng(lat: request.origin.lat, lng: request.origin.lng)
        let d = LatLng(lat: request.destination.lat, lng: request.destination.lng)
        var routes: [Route]
        let canned = CannedRoutes.shared
        if request.waypoints.isEmpty, let exact = canned.match(origin: o, destination: d) {
            // Real road geometry (OSM/OSRM fixture) so demo drives stay on roads.
            routes = DemoRouteBuilder.decorate(exact, vehicle: request.vehicle, avoid: request.avoid, departAt: request.departAt,
                                               seed: routeCounter, alerts: reports)
        } else if let nearest = canned.nearest(to: d) {
            // No captured route for this trip: never fabricate geometry — offer the nearest captured route, labelled.
            let place = DemoData.places.min { Geo.distance($0.location, LatLng(lat: nearest.lat, lng: nearest.lng)) < Geo.distance($1.location, LatLng(lat: nearest.lat, lng: nearest.lng)) }
            let name = place?.name ?? nearest.key
            routes = DemoRouteBuilder.decorate(nearest.routes, vehicle: request.vehicle, avoid: request.avoid, departAt: request.departAt,
                                               seed: routeCounter, alerts: reports)
            for i in routes.indices {
                routes[i].demoNote = String(localized: "Demo route: no captured route for this trip. Showing the nearest one, to \(name).")
            }
        } else {
            throw APIError.notAvailable
        }
        routeCounter += 1
        routes = Array(routes.prefix(max(1, request.alternatives + 1)))
        for r in routes { routeDestinations[r.id] = (request.destination, request.vehicle, request.avoid) }
        if let pid = request.destination.placeId, let p = DemoData.places.first(where: { $0.id == pid }) {
            recentList.removeAll { $0.placeId == pid }
            recentList.insert(Recent(id: "r-\(pid)", name: p.name, address: p.address, lat: p.lat, lng: p.lng, placeId: pid, lastUsedAt: .now), at: 0)
            recentList = Array(recentList.prefix(20))
        }
        return routes
    }

    func reroute(routeId: String, _ request: RerouteRequest) async throws -> [Route] {
        guard let ctx = routeDestinations[routeId] else { throw APIError.http(status: 404, problem: nil) }
        let req = RouteRequest(origin: RouteEndpoint(lat: request.lat, lng: request.lng), destination: ctx.dest, vehicle: ctx.vehicle, avoid: ctx.avoid, alternatives: 0)
        return try await routes(req)
    }

    func departureTimes(origin: LatLng, destination: LatLng, date: Date) async throws -> [DepartureSlot] {
        await pause()
        let base = Geo.distance(origin, destination) * 1.3 / (40 / 3.6)
        let start = Calendar.current.date(bySetting: .minute, value: 0, of: date) ?? date
        return (0..<16).map { i in
            let t = start.addingTimeInterval(Double(i) * 1800)
            let hour = Double(Calendar.current.component(.hour, from: t))
            // Rush hours around 08:00 and 18:00.
            let rush = exp(-pow(hour - 8, 2) / 3) + exp(-pow(hour - 18, 2) / 3)
            return DepartureSlot(departAt: t, durationS: base * (1 + 0.8 * rush))
        }
    }

    // MARK: live

    func live(bbox: BBox) async throws -> LiveResponse {
        let now = Date()
        reports.removeAll { $0.expiresAt < now }
        let center = LatLng(lat: (bbox.minLat + bbox.maxLat) / 2, lng: (bbox.minLng + bbox.maxLng) / 2)
        var gen = SeededGenerator(seed: UInt64(abs(Int(center.lat * 100)) * 1000 + abs(Int(center.lng * 100))))
        // Other drivers are placed on real road vertices inside the viewport.
        let onRoad = CannedRoutes.shared.roadVertices.filter { bbox.contains($0.point) }
        let drivers: [Driver] = onRoad.isEmpty ? [] : (0..<min(18, onRoad.count)).map { _ in
            let v = onRoad.randomElement(using: &gen)!
            return Driver(lat: v.point.lat, lng: v.point.lng, heading: v.bearing, mood: ["🙂", "😎", "😴", "🤠", "😇"].randomElement(using: &gen))
        }
        return LiveResponse(alerts: reports.filter { bbox.contains($0.location) }, jams: DemoData.jams, drivers: drivers, serverTime: now)
    }

    func createReport(_ new: NewReport) async throws -> Report {
        await pause()
        let ttl: TimeInterval = switch new.type {
        case .police: 30 * 60
        case .traffic: 20 * 60
        case .closure: 24 * 3600
        case .weather: 120 * 60
        default: 60 * 60
        }
        let r = Report(id: "demo-\(UUID().uuidString.prefix(8))", type: new.type, subtype: new.subtype, lat: new.lat, lng: new.lng,
                       heading: new.heading, street: nil, reportedBy: ReportAuthor(displayName: user.displayName, mood: user.mood),
                       createdAt: .now, expiresAt: .now.addingTimeInterval(ttl), confirmations: 1, thanks: 0)
        reports.append(r)
        user.points = (user.points ?? 0) + 6
        return r
    }

    func vote(reportId: String, stillThere: Bool) async throws -> Report {
        guard let i = reports.firstIndex(where: { $0.id == reportId }) else { throw APIError.http(status: 404, problem: nil) }
        if stillThere {
            reports[i].confirmations += 1
            reports[i].expiresAt = reports[i].expiresAt.addingTimeInterval(15 * 60)
        } else {
            reports[i].expiresAt = reports[i].expiresAt.addingTimeInterval(-20 * 60)
        }
        return reports[i]
    }

    func thank(reportId: String) async throws {
        if let i = reports.firstIndex(where: { $0.id == reportId }) { reports[i].thanks += 1 }
    }

    func sendLocations(_ batch: LocationBatch) async throws {}

    func health() async -> Bool { true }
}

/// Deterministic RNG so demo drivers don't jump around between polls of the same area.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// Decorates captured real-road demo routes per request. Demo mode never fabricates route geometry.
enum DemoRouteBuilder {
    /// Applies per-request decoration to canned real-road routes: unique ids, traffic factor, avoid-tolls,
    /// localized instructions and alerts projected onto the geometry.
    static func decorate(_ canned: [Route], vehicle: Vehicle, avoid: AvoidOptions, departAt: Date?, seed: Int, alerts: [Report]) -> [Route] {
        let descriptions: [LocalizedStringResource] = ["Typical traffic", "Heavier than usual", "Light traffic"]
        var routes: [Route] = []
        for (i, base) in canned.enumerated() {
            if avoid.tolls && base.hasTolls { continue }
            var r = base
            r.id = "\(base.id)-\(seed)"
            var factor = [1.12, 1.25, 1.35][i % 3] * (vehicle == .motorcycle ? 0.85 : 1)
            if let departAt {
                let hour = Double(Calendar.current.component(.hour, from: departAt))
                factor *= 1 + 0.4 * (exp(-pow(hour - 8, 2) / 3) + exp(-pow(hour - 18, 2) / 3)) - 0.1
            }
            r.trafficDurationS = r.durationS * factor
            r.trafficDescription = String(localized: descriptions[i % 3])
            var legs = r.legs
            for li in legs.indices {
                for mi in legs[li].maneuvers.indices {
                    legs[li].maneuvers[mi].instruction = ManeuverFormatter.instruction(for: legs[li].maneuvers[mi])
                }
            }
            r.legs = legs
            let pts = Polyline.decode(r.polyline)
            r.alerts = alertsOnRoute(pts, alerts: alerts)
            routes.append(r)
        }
        if routes.isEmpty { return decorate(canned, vehicle: vehicle, avoid: AvoidOptions(), departAt: departAt, seed: seed, alerts: alerts) }
        routes.sort { $0.effectiveDurationS < $1.effectiveDurationS }
        for i in routes.indices { routes[i].label = i == 0 ? "best" : "alternative" }
        return routes
    }

    static func alertsOnRoute(_ pts: [LatLng], alerts: [Report], within: Double = 120) -> [AlertOnRoute] {
        guard pts.count > 1 else { return [] }
        let cum = Geo.cumulativeDistances(pts)
        return alerts.compactMap { a in
            var best = (d: Double.infinity, along: 0.0)
            for k in 0..<(pts.count - 1) {
                let pr = Geo.project(a.location, onto: pts[k], pts[k + 1])
                if pr.distanceM < best.d { best = (pr.distanceM, cum[k] + (cum[k + 1] - cum[k]) * pr.t) }
            }
            return best.d < within ? AlertOnRoute(report: a, distanceAlongRouteM: best.along) : nil
        }
    }

}

enum DemoData {
    static let places: [PlaceSummary] = [
        p("demo:klcc", "Suria KLCC", .mall, "Jalan Ampang, 50088 Kuala Lumpur", 3.1579, 101.7123),
        p("demo:pavilion", "Pavilion Kuala Lumpur", .mall, "168 Jalan Bukit Bintang, 55100 Kuala Lumpur", 3.1490, 101.7133),
        p("demo:midvalley", "Mid Valley Megamall", .mall, "Lingkaran Syed Putra, Mid Valley City, 59200 Kuala Lumpur", 3.1178, 101.6773),
        p("demo:1u", "1 Utama Shopping Centre", .mall, "Lebuh Bandar Utama, 47800 Petaling Jaya", 3.1502, 101.6152),
        p("demo:sunway", "Sunway Pyramid", .mall, "Jalan PJS 11/15, Bandar Sunway, 47500 Petaling Jaya", 3.0731, 101.6070),
        p("demo:masjidnegara", "Masjid Negara", .mosque, "Jalan Perdana, Tasik Perdana, 50480 Kuala Lumpur", 3.1420, 101.6918),
        p("demo:masjidwilayah", "Masjid Wilayah Persekutuan", .mosque, "Jalan Duta, 50480 Kuala Lumpur", 3.1716, 101.6680),
        p("demo:surau-ttdi", "Surau Al-Ikhlas TTDI", .surau, "Jalan Tun Mohd Fuad, Taman Tun Dr Ismail", 3.1395, 101.6310),
        p("demo:petronas-ampang", "PETRONAS Jalan Ampang", .fuel, "Jalan Ampang, 50450 Kuala Lumpur", 3.1606, 101.7240, brand: "PETRONAS"),
        p("demo:petronas-duta", "PETRONAS Jalan Duta", .fuel, "Jalan Duta, 50480 Kuala Lumpur", 3.1690, 101.6720, brand: "PETRONAS"),
        p("demo:shell-razak", "Shell Jalan Tun Razak", .fuel, "Jalan Tun Razak, 50400 Kuala Lumpur", 3.1640, 101.7190, brand: "Shell"),
        p("demo:petron-bangsar", "Petron Bangsar", .fuel, "Jalan Bangsar, 59200 Kuala Lumpur", 3.1290, 101.6790, brand: "Petron"),
        p("demo:bhp-damansara", "BHPetrol Damansara Heights", .fuel, "Jalan Semantan, 50490 Kuala Lumpur", 3.1470, 101.6590, brand: "BHPetrol"),
        p("demo:caltex-cheras", "Caltex Cheras", .fuel, "Jalan Cheras, 56100 Kuala Lumpur", 3.1050, 101.7300, brand: "Caltex"),
        p("demo:rnr-sgbuloh", "R&R Sungai Buloh", .restArea, "Lebuhraya Utara–Selatan (E1), Sungai Buloh", 3.2085, 101.5794),
        p("demo:rnr-seremban", "R&R Seremban", .restArea, "Lebuhraya Utara–Selatan (E2), Seremban", 2.7700, 101.9100),
        p("demo:hkl", "Hospital Kuala Lumpur", .hospital, "Jalan Pahang, 50586 Kuala Lumpur", 3.1715, 101.7004),
        p("demo:klsentral", "KL Sentral", .transitStation, "Jalan Stesen Sentral, 50470 Kuala Lumpur", 3.1340, 101.6862),
        p("demo:klia", "KLIA Terminal 1", .airport, "64000 Sepang, Selangor", 2.7456, 101.7072),
        p("demo:batucaves", "Batu Caves", .tourism, "Gombak, 68100 Batu Caves, Selangor", 3.2379, 101.6840),
        p("demo:pelita", "Restoran Nasi Kandar Pelita", .restaurant, "149 Jalan Ampang, 50450 Kuala Lumpur", 3.1588, 101.7166),
        p("demo:villagepark", "Village Park Restaurant", .restaurant, "Damansara Uptown, 47400 Petaling Jaya", 3.1356, 101.6237),
        p("demo:mamak-bangsar", "Restoran Rasa Utara Mamak", .restaurant, "Jalan Telawi, Bangsar Baru", 3.1305, 101.6710),
        p("demo:kopitiam", "Old Town Kopitiam Bukit Bintang", .cafe, "Jalan Bukit Bintang, 55100 Kuala Lumpur", 3.1466, 101.7101),
        p("demo:toll-sgbesi", "Plaza Tol Sungai Besi", .tollBooth, "Lebuhraya Utara–Selatan (E2), 57000 Kuala Lumpur", 3.0590, 101.7100),
        p("demo:ev-pavilion", "ChargEV Pavilion KL", .evCharging, "Pavilion KL P3, Jalan Bukit Bintang", 3.1488, 101.7140),
        p("demo:ev-midvalley", "DC Handal Mid Valley", .evCharging, "Mid Valley City, 59200 Kuala Lumpur", 3.1170, 101.6780),
        p("demo:park-klcc", "KLCC Parking", .parking, "Jalan Ampang, 50088 Kuala Lumpur", 3.1569, 101.7140),
        p("demo:police-dangwangi", "Balai Polis Dang Wangi", .police, "Jalan Stesen Sentral 2, 50470 Kuala Lumpur", 3.1554, 101.6981),
        p("demo:um", "Universiti Malaya", .university, "Lembah Pantai, 50603 Kuala Lumpur", 3.1209, 101.6538),
        p("demo:putrajaya", "Dataran Putra, Putrajaya", .government, "Persiaran Perdana, 62000 Putrajaya", 2.9360, 101.6897),
        p("demo:merdeka118", "Merdeka 118", .tourism, "Jalan Hang Jebat, 50150 Kuala Lumpur", 3.1415, 101.7005),
        p("demo:jalanalor", "Jalan Alor Food Street", .restaurant, "Jalan Alor, Bukit Bintang, 50200 Kuala Lumpur", 3.1456, 101.7088),
    ]

    private static func p(_ id: String, _ name: String, _ cat: PlaceCategory, _ addr: String, _ lat: Double, _ lng: Double, brand: String? = nil) -> PlaceSummary {
        PlaceSummary(id: id, name: name, category: cat, address: addr, lat: lat, lng: lng, distanceM: nil, brand: brand)
    }

    /// Demo jams are 300–800 m slices of captured real-road routes.
    static let jams: [Jam] = {
        let specs: [(String, Int, String, Int, Double, Double)] = [
            ("demo-j1", 3, "midvalley", 0, 0.30, 600),
            ("demo-j2", 2, "klcc", 0, 0.35, 500),
            ("demo-j3", 4, "home", 0, 0.50, 800),
            ("demo-j4", 1, "batucaves", 0, 0.40, 700),
            ("demo-j5", 2, "sunway", 0, 0.55, 800),
        ]
        return specs.compactMap { id, level, key, route, from, len in
            guard let slice = CannedRoutes.shared.slice(key: key, routeIndex: route, fromFraction: from, lengthM: len) else { return nil }
            return Jam(id: id, level: level, speedKmh: [0, 25, 15, 8, 2][level], delayS: Double(level) * 120,
                       lengthM: Geo.cumulativeDistances(slice.points).last ?? 0, polyline: Polyline.encode(slice.points), street: slice.street, updatedAt: .now)
        }
    }()
}

/// Real-road demo routes captured once from OpenStreetMap data (OSRM demo server, ODbL) into
/// `Resources/DemoRoutes.json`, in the Gimano `Route` contract shape (precision-6 polylines).
struct CannedRoutes: Sendable {
    struct Destination: Decodable, Sendable {
        var key: String
        var lat: Double
        var lng: Double
        var routes: [Route]
    }

    struct File: Decodable, Sendable {
        var attribution: String
        var origin: LatLng
        var destinations: [Destination]
    }

    let file: File?

    static let shared = CannedRoutes(bundle: .main)

    init(bundle: Bundle) {
        if let url = bundle.url(forResource: "DemoRoutes", withExtension: "json"), let data = try? Data(contentsOf: url) {
            file = try? JSON.makeDecoder().decode(File.self, from: data)
        } else {
            file = nil
        }
    }

    /// Captured destination closest to `destination` (used when no exact fixture matches).
    func nearest(to destination: LatLng) -> Destination? {
        file?.destinations.min { Geo.distance(destination, LatLng(lat: $0.lat, lng: $0.lng)) < Geo.distance(destination, LatLng(lat: $1.lat, lng: $1.lng)) }
    }

    func route(key: String, routeIndex: Int) -> Route? {
        guard let d = file?.destinations.first(where: { $0.key == key }), !d.routes.isEmpty else { return nil }
        return d.routes[min(routeIndex, d.routes.count - 1)]
    }

    private static func street(of route: Route, atVertex i: Int) -> String? {
        let m = route.maneuvers.last { $0.beginShapeIndex <= i && $0.type != .arrive }
        return m.flatMap { ManeuverFormatter.roadName($0) }
    }

    /// The route vertex at a fraction of the route length, with the street it is on.
    func vertex(key: String, routeIndex: Int, fraction: Double) -> (point: LatLng, street: String?)? {
        guard let r = route(key: key, routeIndex: routeIndex) else { return nil }
        let pts = Polyline.decode(r.polyline)
        let cum = Geo.cumulativeDistances(pts)
        guard let total = cum.last, total > 0 else { return nil }
        let target = total * fraction
        let i = cum.indices.min { abs(cum[$0] - target) < abs(cum[$1] - target) } ?? 0
        return (pts[i], Self.street(of: r, atVertex: i))
    }

    /// A stretch of a captured route between `fromFraction` and `fromFraction + lengthM` (points on the polyline).
    func slice(key: String, routeIndex: Int, fromFraction: Double, lengthM: Double) -> (points: [LatLng], street: String?)? {
        guard let r = route(key: key, routeIndex: routeIndex) else { return nil }
        let pts = Polyline.decode(r.polyline)
        let cum = Geo.cumulativeDistances(pts)
        guard let total = cum.last, total > lengthM else { return nil }
        let d0 = total * fromFraction, d1 = min(total, d0 + lengthM)
        let start = Geo.point(along: pts, cumulative: cum, distance: d0)
        let end = Geo.point(along: pts, cumulative: cum, distance: d1)
        var out = [start.point]
        for i in pts.indices where cum[i] > d0 && cum[i] < d1 { out.append(pts[i]) }
        out.append(end.point)
        return (out, Self.street(of: r, atVertex: start.segment))
    }

    /// Every vertex of every captured route with its travel bearing (for placing demo drivers on roads).
    var roadVertices: [(point: LatLng, bearing: Double)] {
        guard let file else { return [] }
        return file.destinations.flatMap(\.routes).flatMap { r -> [(point: LatLng, bearing: Double)] in
            let pts = Polyline.decode(r.polyline)
            guard pts.count > 1 else { return [] }
            return stride(from: 0, to: pts.count - 1, by: 3).map { (pts[$0], Geo.bearing(pts[$0], pts[$0 + 1])) }
        }
    }

    /// All captured route polylines, decoded.
    var allPolylines: [[LatLng]] {
        file?.destinations.flatMap(\.routes).map { Polyline.decode($0.polyline) } ?? []
    }

    /// Canned alternatives when the request starts near the fixture origin and ends near a fixture destination.
    func match(origin: LatLng, destination: LatLng, originToleranceM: Double = 500, destinationToleranceM: Double = 400) -> [Route]? {
        guard let file, Geo.distance(origin, file.origin) <= originToleranceM else { return nil }
        let best = file.destinations.min { Geo.distance(destination, LatLng(lat: $0.lat, lng: $0.lng)) < Geo.distance(destination, LatLng(lat: $1.lat, lng: $1.lng)) }
        guard let best, Geo.distance(destination, LatLng(lat: best.lat, lng: best.lng)) <= destinationToleranceM else { return nil }
        return best.routes
    }
}
