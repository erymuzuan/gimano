import Foundation
import Testing
@testable import Gimano

// "Bina laluan": polyline precision 6 (route-core vectors), edit rules (desktop editing.rs), undo/redo, assembly into
// an event route the reader/validator accepts (desktop assemble.rs), GPX writer (route-core gpx.rs), and the routing
// calls (`store: false`, `/routes/match` with unmatched ranges, 404 → sketch fallback) against a mocked network.

private final class RBVectorToken {}

private enum RB {
    static func vectorEvent(_ name: String) throws -> [String: Any] {
        let url = try #require(Bundle(for: RBVectorToken.self).url(forResource: name, withExtension: "json"))
        let v = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let json = try #require(v["eventJson"] as? String)
        return try #require(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
    }

    static func date(_ s: String) -> Date { EventTime.parse(s)!.date }

    static let a = LatLng(lat: 3.1, lng: 101.6)
    static let b = LatLng(lat: 3.11, lng: 101.61)
    static let c = LatLng(lat: 3.12, lng: 101.6)

    static func routed(_ from: LatLng, _ to: LatLng, d: Double = 1000, t: Double? = 100) -> LegResolution {
        .routed(geometry: [from, Geo.interpolate(from, to, 0.5), to], distanceM: d, durationS: t)
    }

    /// Resolves every waiting leg with a three-point road.
    static func resolveAll(_ r: inout RouteDraft, d: Double = 1000, t: Double? = 100) {
        for l in r.pendingLegs { r.apply(routed(l.from, l.to, d: d, t: t), generation: l.generation) }
    }
}

/// A scripted routing server; records what was sent.
final class MockRouteTransport: RouteBuilderTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _routeRequests: [BuilderRouteRequest] = []
    private var _matchRequests: [BuilderMatchRequest] = []
    var routeError: (any Error)?
    var matchError: (any Error)?
    var unmatched: [BuilderMatchResponse.Range] = []

    var routeRequests: [BuilderRouteRequest] { lock.withLock { _routeRequests } }
    var matchRequests: [BuilderMatchRequest] { lock.withLock { _matchRequests } }

    func builderRoute(_ request: BuilderRouteRequest) async throws -> BuilderRoutesResponse {
        lock.withLock { _routeRequests.append(request) }
        if let routeError { throw routeError }
        let a = LatLng(lat: request.origin.lat, lng: request.origin.lng), b = LatLng(lat: request.destination.lat, lng: request.destination.lng)
        let line = Polyline.encode([a, Geo.interpolate(a, b, 0.5), b])
        return BuilderRoutesResponse(routes: [.init(polyline: line, distanceM: Geo.distance(a, b) * 1.3, durationS: 120)])
    }

    func builderMatch(_ request: BuilderMatchRequest) async throws -> BuilderMatchResponse {
        lock.withLock { _matchRequests.append(request) }
        if let matchError { throw matchError }
        let pts = request.points.map { LatLng(lat: $0.lat, lng: $0.lng) }
        return BuilderMatchResponse(polyline: Polyline.encode(pts), distanceM: 2500, durationS: 600, unmatched: unmatched)
    }
}

@Suite("Route builder: polyline precision 6")
struct RoutePolylineTests {
    @Test("route-core vectors encode and decode exactly")
    func routeCoreVectors() {
        let pts = [LatLng(lat: 38.5, lng: -120.2), LatLng(lat: 40.7, lng: -120.95), LatLng(lat: 43.252, lng: -126.453)]
        #expect(Polyline.encode(pts) == "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI")
        #expect(Polyline.encode(Array(pts.prefix(2))) == "_izlhA~rlgdF_{geC~ywl@")
        #expect(Polyline.decode("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI") == pts)
        #expect((try? EventPolyline.decode("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI", maxPoints: 10)) == pts)
    }

    @Test("ride-two-day route polyline: 7 points from the start stop to the finish, re-encodes byte for byte")
    func rideVector() throws {
        let e = try RB.vectorEvent("ride-two-day")
        let route = try #require((e["routes"] as? [[String: Any]])?.first)
        let line = try #require(route["polyline"] as? String)
        let pts = Polyline.decode(line)
        #expect(pts.count == 7)
        #expect(pts.first == LatLng(lat: 2.95, lng: 101.85) && pts.last == LatLng(lat: 3.05, lng: 102.0))
        #expect(Polyline.encode(pts) == line)
    }

    @Test("Rounding is half away from zero (Rust f64::round)")
    func rounding() {
        #expect(RouteGeo.round6(1.0000005) == 1.000001)
        #expect(RouteGeo.round6(-1.0000005) == -1.000001)
        #expect(Polyline.encode([LatLng(lat: 0.0000005, lng: -0.0000005)]) == Polyline.encode([LatLng(lat: 0.000001, lng: -0.000001)]))
    }
}

@Suite("Route builder: edits and undo/redo")
struct RouteEditTests {
    @Test("Undo/redo: one snapshot per edit, a new edit clears redo, 100 kept")
    func history() {
        var h = EditHistory<Int>()
        #expect(!h.canUndo && !h.canRedo)
        h.record(0)            // 0 → 1
        h.record(1)            // 1 → 2
        #expect(h.undo(2) == 1)
        #expect(h.undo(1) == 0)
        #expect(h.undo(0) == nil)
        #expect(h.redo(0) == 1)
        h.record(1)            // a new edit: redo is gone
        #expect(!h.canRedo)
        var big = EditHistory<Int>()
        for i in 0..<150 { big.record(i) }
        #expect(big.undoStack.count == 100 && big.undoStack.first == 50)
    }

    @Test("Adding points: legs wait for the server; with “Ikut jalan” off they are straight sketches")
    func addPoints() {
        var r = RouteDraft()
        r.addPoint(RB.a)
        #expect(r.legs.isEmpty)
        r.addPoint(RB.b)
        #expect(r.legs.count == 1 && r.legs[0].state == .pending && r.pendingLegs.count == 1)
        r.setFollowRoads(false)
        #expect(r.legs[0].state == .sketch && r.legs[0].geometry == [RB.a, RB.b] && r.legs[0].durationS == nil)
        r.addPoint(RB.c)
        #expect(r.legs[1].state == .sketch)
        #expect(r.durationS == nil && abs(r.distanceM - RouteGeo.length([RB.a, RB.b, RB.c])) < 0.001)
        r.setFollowRoads(true)
        #expect(r.legs.allSatisfy { $0.state == .pending })
    }

    @Test("Deleting an inner point merges its legs into one leg to calculate; ends just drop a leg")
    func deletePoint() {
        var r = RouteDraft()
        for p in [RB.a, RB.b, RB.c, RB.a] { r.addPoint(p) }
        RB.resolveAll(&r)
        r.deletePoint(1)
        #expect(r.points.count == 3 && r.legs.count == 2)
        #expect(r.legs[0].state == .pending && r.legs[1].state == .routed)
        r.deletePoint(0)
        #expect(r.points.count == 2 && r.legs.count == 1 && r.legs[0].state == .routed)
        r.deletePoint(1)
        #expect(r.points.count == 1 && r.legs.isEmpty)
    }

    @Test("Reverse: points and legs reversed, road legs recalculated (one-way streets), sketches reversed as they are")
    func reverse() throws {
        var r = RouteDraft()
        for p in [RB.a, RB.b, RB.c] { r.addPoint(p) }
        RB.resolveAll(&r)
        r.legs[1] = BuilderLeg(state: .sketch, geometry: [RB.b, RB.c], distanceM: 5)
        try r.reverse()
        #expect(r.points.map(\.location) == [RB.c, RB.b, RB.a])
        #expect(r.legs[0].state == .sketch && r.legs[0].geometry == [RB.c, RB.b])
        #expect(r.legs[1].state == .pending)
        var one = RouteDraft()
        one.addPoint(RB.a)
        #expect(throws: RouteEditError.needsTwoPoints) { try one.reverse() }
    }

    @Test("Out and back appends points n-2 … 0 with their stops; back to start closes once")
    func outAndBackAndLoop() throws {
        var r = RouteDraft()
        r.addPoint(RB.a, stopId: "S1")
        r.addPoint(RB.b)
        r.addPoint(RB.c, stopId: "S3")
        try r.outAndBack()
        #expect(r.points.map(\.location) == [RB.a, RB.b, RB.c, RB.b, RB.a])
        #expect(r.points.map(\.stopId) == ["S1", nil, "S3", nil, "S1"])
        #expect(r.legs.count == 4 && Set(r.points.map(\.id)).count == 5)
        #expect(throws: RouteEditError.alreadyClosed) { try r.closeLoop() }

        var loop = RouteDraft()
        for p in [RB.a, RB.b, RB.c] { loop.addPoint(p) }
        try loop.closeLoop()
        #expect(loop.points.count == 4 && loop.points.last?.location == RB.a && loop.legs.count == 3)
        #expect(throws: RouteEditError.alreadyClosed) { try loop.closeLoop() }
    }

    @Test("A late answer for a leg that changed meanwhile is dropped")
    func staleAnswer() {
        var r = RouteDraft()
        r.addPoint(RB.a)
        r.addPoint(RB.b)
        let old = r.pendingLegs[0].generation
        r.movePoint(1, to: RB.c)
        let stale = r.apply(RB.routed(RB.a, RB.b), generation: old)
        #expect(!stale)
        #expect(r.legs[0].state == .pending)
        let fresh = r.apply(RB.routed(RB.a, RB.c), generation: r.pendingLegs[0].generation)
        #expect(fresh)
    }

    @Test("A draft with routes round-trips through JSON storage (geometry as precision-6 polylines)")
    func codable() throws {
        var r = RouteDraft(title: "Hari 1")
        for p in [RB.a, RB.b, RB.c] { r.addPoint(p) }
        RB.resolveAll(&r)
        let back = try JSONDecoder().decode(RouteDraft.self, from: JSONEncoder().encode(r))
        #expect(back == r)
        // Drafts saved before routes existed still load.
        var d = HostedEventDraft.new()
        d.routes = []
        let old = try JSONEncoder().encode(d)
        #expect(try JSONDecoder().decode(HostedEventDraft.self, from: old).routes.isEmpty)
    }
}

@Suite("Route builder: event route assembly")
struct RouteAssemblyTests {
    /// The ride-two-day vector's route, rebuilt in the builder: three stop points and two routed legs.
    @Test("Rebuilding the ride-two-day route gives its polyline, stopIds, legs and totals")
    func rideVectorRoute() throws {
        let e = try RB.vectorEvent("ride-two-day")
        let v = try #require((e["routes"] as? [[String: Any]])?.first)
        let geometry = Polyline.decode(try #require(v["polyline"] as? String))
        let stops = try #require(e["stops"] as? [[String: Any]])
        func stop(_ id: String) -> LatLng {
            let s = stops.first { $0["id"] as? String == id }!
            return LatLng(lat: s["lat"] as! Double, lng: s["lng"] as! Double)
        }
        let ids = try #require(v["stopIds"] as? [String])
        var r = RouteDraft(id: v["id"] as! String, title: "Hari 1", modeRaw: "bicycle", day: "2026-11-21")
        for id in ids { r.addPoint(stop(id), stopId: id) }
        let legs = r.pendingLegs
        r.apply(.routed(geometry: Array(geometry[0...3]), distanceM: 7050, durationS: 1590), generation: legs[0].generation)
        r.apply(.routed(geometry: Array(geometry[3...6]), distanceM: 13050, durationS: 2930), generation: legs[1].generation)

        let er = try r.eventRoute(knownStops: Set(stops.map { $0["id"] as! String })).get()
        #expect(er.polyline == v["polyline"] as? String)
        #expect(er.stopIds == ids)
        #expect(er.source == .routed && er.mode == .bicycle && er.day == "2026-11-21" && er.title == "Hari 1")
        #expect(er.distanceM == 20100 && er.durationS == 4520)
        #expect(er.ascentM == nil && er.descentM == nil)   // no elevation service: absent, never 0
        let vl = try #require(v["legs"] as? [[String: Any]])
        #expect(er.legs.map(\.fromStopId) == vl.map { $0["fromStopId"] as! String })
        #expect(er.legs.map(\.toStopId) == vl.map { $0["toStopId"] as! String })
        #expect(er.legs.map(\.distanceM) == [7050, 13050] && er.legs.map(\.durationS) == [1590, 2930])
    }

    @Test("Shaping points merge into the stop-to-stop leg; sketch legs make the route a sketch without a time; the reader accepts it")
    func assembleIntoEvent() throws {
        var d = HostedEventDraft.new(now: RB.date("2026-11-01T02:15:00Z"), timeZone: TimeZone(identifier: "Asia/Kuala_Lumpur")!)
        d.title = "Konvoi"
        d.template = .convoy
        let s1 = HostedStopDraft(kind: .start, label: "Mula", location: RB.a)
        let s2 = HostedStopDraft(kind: .finish, label: "Tamat", location: RB.c)
        d.stops = [s1, s2]
        var r = RouteDraft(title: "  Hari 1  ")
        r.addPoint(RB.a, stopId: s1.id)
        r.addPoint(RB.b)                     // shaping point
        r.addPoint(RB.c, stopId: s2.id)
        // Waiting legs block publishing.
        d.routes = [r]
        #expect(d.event(rev: 1, parent: nil, publishedAt: RB.date("2026-11-01T02:15:00Z")).1 == [.routeIncomplete(r.id)])

        RB.resolveAll(&r, d: 1000.4, t: 60.6)
        d.routes = [r]
        let (doc0, issues) = d.event(rev: 1, parent: nil, publishedAt: RB.date("2026-11-01T02:15:00Z"))
        #expect(issues.isEmpty)
        let doc = try #require(doc0)
        let parsed = try EventDoc.parse(try doc.bytes())
        let er = try #require(parsed.routes.first)
        #expect(er.stopIds == [s1.id, s2.id] && er.title == "Hari 1")
        #expect(er.legs.count == 1 && er.legs[0].distanceM == 2001 && er.legs[0].durationS == 121 && er.legs[0].source == .routed)
        #expect(r.joinedGeometry.count == 5)  // 3 + 3 points, the joint once
        // Published simplified at 5 m (§5.4): the legs' straight midpoints go, the shaping point stays.
        #expect(er.points == RouteSimplify.simplify(r.joinedGeometry) && er.points == [RB.a, RB.b, RB.c])

        // One sketch leg: route and that leg are sketches; no total time.
        r.setFollowRoads(false)
        r.movePoint(1, to: LatLng(lat: 3.111, lng: 101.611))
        d.routes = [r]
        let sketch = try #require(d.event(rev: 1, parent: nil, publishedAt: RB.date("2026-11-01T02:15:00Z")).0?.routes.first)
        #expect(sketch.source == .sketch && sketch.durationS == nil && sketch.legs[0].source == .sketch && sketch.legs[0].durationS == nil)

        // Removing a stop unlinks its points; a single point route is left out.
        d.stops = [s1]
        d.syncRoutesWithStops()
        #expect(d.routes[0].points.compactMap(\.stopId) == [s1.id])
        var tiny = RouteDraft()
        tiny.addPoint(RB.a)
        d.routes = [tiny]
        #expect(d.event(rev: 1, parent: nil, publishedAt: RB.date("2026-11-01T02:15:00Z")).0?.routes.isEmpty == true)
    }

    @Test("A drawing with unmatched stretches blocks publishing until it's redrawn, split or kept as a sketch")
    func unmatched() throws {
        var r = RouteDraft()
        r.addPoint(RB.a)
        try? r.draw([RB.b, Geo.interpolate(RB.b, RB.c, 0.5), RB.c])   // drawn line a, b, mid, c
        #expect(r.points.count == 2 && r.legs.count == 1 && r.legs[0].drawn?.count == 4)
        let g = r.pendingLegs[0].generation
        r.apply(.matched(geometry: [RB.a, RB.c], distanceM: 900, durationS: 90, unmatched: [UnmatchedRange(from: 2, to: 2)]), generation: g)
        #expect(r.hasUnmatched)
        #expect(r.eventRoute(knownStops: []) == .failure(.unmatched))
        var split = r
        split.splitAtUnmatched(0)
        #expect(split.points.count == 3 && split.legs.count == 2 && !split.hasUnmatched && split.points[1].location == RB.b)
        r.keepAsSketch(0)
        #expect(r.legs[0].state == .sketch && r.legs[0].geometry == r.legs[0].drawn)
        #expect(try r.eventRoute(knownStops: []).get().source == .sketch)
    }
}

@Suite("Route builder: GPX")
struct RouteGPXTests {
    @Test("GPX 1.1 track like route-core export_gpx: header, waypoints with names, escaping, 6 decimals trimmed")
    func writer() {
        let out = GPXWriter.write(trackName: "Hari 1 & <2>", track: [LatLng(lat: 3.1, lng: 101.600001), LatLng(lat: -0.0000001, lng: 1.5)],
                                  waypoints: [.init(location: LatLng(lat: 3.1, lng: 101.6), name: "Mula \"A\"")])
        #expect(out == """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Manotu" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="3.1" lon="101.6">
                <name>Mula &quot;A&quot;</name>
              </wpt>
              <trk>
                <name>Hari 1 &amp; &lt;2&gt;</name>
                <trkseg>
                  <trkpt lat="3.1" lon="101.600001"/>
                  <trkpt lat="0" lon="1.5"/>
                </trkseg>
              </trk>
            </gpx>

            """)
    }

    @Test("A route's GPX holds the calculated legs only, its linked stops as waypoints")
    func fromRoute() {
        var r = RouteDraft()
        r.addPoint(RB.a, stopId: "S1")
        r.addPoint(RB.b)
        r.addPoint(RB.c)
        r.apply(RB.routed(RB.a, RB.b), generation: r.pendingLegs[0].generation)
        let stop = HostedStopDraft(id: "S1", kind: .start, label: "Mula", location: RB.a)
        let out = GPXWriter.gpx(r, name: "Laluan", stops: [stop])
        #expect(out.components(separatedBy: "<trkpt").count - 1 == 3)
        #expect(out.contains("<name>Mula</name>") && out.contains("<name>Laluan</name>"))
    }
}

@Suite("Route builder: routing calls (mocked network)")
struct RouteRoutingTests {
    @Test("A leg request carries coordinates and mode only, with store:false")
    func requestBody() async throws {
        let t = MockRouteTransport()
        let r = await RouteBuilderService.resolve(from: RB.a, to: RB.b, drawn: nil, mode: .motorcycle, transport: t)
        guard case .routed(let g, _, let dur) = r else { Issue.record("not routed: \(r)"); return }
        #expect(g.count == 3 && dur == 120)
        let body = String(decoding: try JSONEncoder().encode(t.routeRequests[0]), as: UTF8.self)
        #expect(body.contains("\"store\":false") && body.contains("\"vehicle\":\"motorcycle\"") && body.contains("\"waypoints\":[]"))
        for absent in ["name", "placeId", "title", "event"] { #expect(!body.contains(absent)) }
    }

    @Test("Errors: offline, 422 no road, 503, other statuses; no transport reads as offline")
    func errors() async {
        let t = MockRouteTransport()
        t.routeError = APIError.transport(URLError(.notConnectedToInternet))
        #expect(await RouteBuilderService.resolve(from: RB.a, to: RB.b, drawn: nil, mode: .car, transport: t) == .failed(.offline))
        t.routeError = APIError.http(status: 422, problem: nil)
        #expect(await RouteBuilderService.resolve(from: RB.a, to: RB.b, drawn: nil, mode: .car, transport: t) == .failed(.noRoute))
        t.routeError = APIError.http(status: 503, problem: nil)
        #expect(await RouteBuilderService.resolve(from: RB.a, to: RB.b, drawn: nil, mode: .car, transport: t) == .failed(.unavailable))
        t.routeError = APIError.http(status: 404, problem: nil)
        #expect(await RouteBuilderService.resolve(from: RB.a, to: RB.b, drawn: nil, mode: .car, transport: t) == .failed(.failed))
        #expect(await RouteBuilderService.resolve(from: RB.a, to: RB.b, drawn: nil, mode: .car, transport: nil) == .failed(.offline))
    }

    @Test("Match: unmatched ranges are kept (clamped to the points sent); nothing matched reads as no road")
    func match() async {
        let t = MockRouteTransport()
        t.unmatched = [.init(fromIndex: 1, toIndex: 2), .init(fromIndex: 5, toIndex: 99)]
        let line = (0..<8).map { LatLng(lat: 3.1 + Double($0) * 0.001, lng: 101.6) }
        let r = await RouteBuilderService.resolve(from: line[0], to: line[7], drawn: line, mode: .bicycle, transport: t)
        guard case .matched(_, let d, _, let u) = r else { Issue.record("not matched: \(r)"); return }
        #expect(d == 2500 && u == [UnmatchedRange(from: 1, to: 2), UnmatchedRange(from: 5, to: 7)])
        #expect(t.matchRequests[0].points.count == 8 && t.matchRequests[0].vehicle == "bicycle" && t.routeRequests.isEmpty)
        #expect(RouteBuilderService.matched(BuilderMatchResponse(polyline: "", distanceM: 0, durationS: nil, unmatched: []), pointCount: 2) == .failed(.noRoute))
    }

    @Test("A server without /routes/match (404/405): the drawing is kept as a labelled sketch, never as a road")
    func matchUnavailable() async {
        for status in [404, 405] {
            let t = MockRouteTransport()
            t.matchError = APIError.http(status: status, problem: nil)
            var r = RouteDraft()
            r.addPoint(RB.a)
            try? r.draw([RB.b, RB.c])
            let leg = r.pendingLegs[0]
            let res = await RouteBuilderService.resolve(from: leg.from, to: leg.to, drawn: leg.drawn, mode: .car, transport: t)
            #expect(res == .failed(.matchUnavailable))
            r.apply(res, generation: leg.generation)
            #expect(r.legs[0].state == .sketch && r.legs[0].problem == .matchUnavailable && r.legs[0].geometry == [RB.a, RB.b, RB.c])
            #expect(r.legs[0].source == .sketch)
        }
    }

    @Test("The builder model routes each tap's leg, and undo/redo reuse the answers instead of asking again")
    @MainActor
    func model() async {
        let t = MockRouteTransport()
        let m = RouteBuilderModel(route: RouteDraft(), context: .personal, transport: t)
        m.tap(RB.a); m.tap(RB.b); m.tap(RB.c)
        await m.settle()
        #expect(m.route.points.count == 3 && m.route.legs.allSatisfy { $0.state == .routed } && m.route.isComplete)
        #expect(t.routeRequests.count == 2)
        m.undo()
        #expect(m.route.points.count == 2)
        m.redo()
        await m.settle()
        #expect(m.route.points.count == 3 && m.route.isComplete && t.routeRequests.count == 2)
        m.setMode(.walk)
        await m.settle()
        #expect(t.routeRequests.count == 4 && t.routeRequests.last?.vehicle == "walk")

        // Offline: legs wait as "to calculate", and the straight line is not shown as a road.
        let off = RouteBuilderModel(route: RouteDraft(), context: .personal, transport: nil)
        off.tap(RB.a); off.tap(RB.b)
        await off.settle()
        #expect(off.route.legs[0].state == .unresolved && off.route.legs[0].problem == .offline && off.route.needsCalculation == 1)
        #expect(off.route.joinedGeometry.isEmpty)
    }

    @Test("A personal route is stored in the on-device personal store (schema 3), not the events database")
    func personalStore() throws {
        let store = try LocalStore(path: nil, photosDirectory: FileManager.default.temporaryDirectory.appendingPathComponent("rb-\(UUID().uuidString)"))
        var r = RouteDraft(title: "Kayuh pagi")
        r.addPoint(RB.a); r.addPoint(RB.b)
        try store.savePersonalRoute(r)
        let back = try store.personalRoutes()
        #expect(back.map(\.id) == [r.id] && back.first?.title == "Kayuh pagi")
        #expect(try back.map(PersonalRouteJSON.encode) == [PersonalRouteJSON.encode(r)])
        try store.deletePersonalRoute(id: r.id)
        #expect(try store.personalRoutes().isEmpty)
        let events = try EventStore(directory: nil)
        #expect(try events.legacyPersonalRoutes().isEmpty)
    }
}

@Suite("Route builder: demo mode, keep as sketch, blockers and plurals")
struct RouteBuilderFixesTests {
    @Test("Keep as sketch turns every waiting or failed leg into a sketch at once; road legs stay")
    func keepOpenLegsAsSketch() {
        var r = RouteDraft()
        r.addPoint(RB.a); r.addPoint(RB.b); r.addPoint(RB.c); r.addPoint(RB.a)
        let legs = r.pendingLegs
        r.apply(RB.routed(legs[0].from, legs[0].to), generation: legs[0].generation)
        r.apply(.failed(.demo), generation: legs[1].generation)
        #expect(r.legs.map(\.state) == [.routed, .unresolved, .pending] && r.needsCalculation == 2)
        let road = r.legs[0]
        r.keepOpenLegsAsSketch()
        #expect(r.legs.map(\.state) == [.routed, .sketch, .sketch] && r.needsCalculation == 0)
        #expect(r.legs[0] == road && r.legs[1].geometry == [RB.b, RB.c])
    }

    @Test("Demo data mode: legs fail with .demo (not .offline), and keep-as-sketch for all is one undo step")
    @MainActor
    func demoModel() async {
        let t = MockRouteTransport()
        let m = RouteBuilderModel(route: RouteDraft(), context: .personal, transport: t, isDemo: true)
        m.tap(RB.a); m.tap(RB.b); m.tap(RB.c); m.tap(RB.a)
        await m.settle()
        #expect(m.route.legs.map(\.state) == [.unresolved, .unresolved, .unresolved])
        #expect(m.route.legs.allSatisfy { $0.problem == .demo })
        #expect(t.routeRequests.isEmpty)   // the server is never asked
        m.keepOpenLegsAsSketch()
        #expect(m.route.legs.allSatisfy { $0.state == .sketch } && m.route.needsCalculation == 0)
        m.undo()
        #expect(m.route.legs.allSatisfy { $0.state == .unresolved } && m.route.points.count == 4)
    }

    @Test("A blocked \"Share invitation\" scrolls to the first problem's field, place or route")
    func issueAnchors() {
        #expect(EventDraftIssue.noStops.anchor == EventDraftIssue.Anchor.addPlace)
        #expect(EventDraftIssue.titleMissing.anchor == EventDraftIssue.Anchor.title)
        #expect(EventDraftIssue.stopLabel("s1").anchor == EventDraftIssue.Anchor.stop("s1"))
        #expect(EventDraftIssue.routeIncomplete("r1").anchor == EventDraftIssue.Anchor.route("r1"))
        #expect(EventDraftIssue.invalid(.malformedLink).anchor == EventDraftIssue.Anchor.problems)
    }

    @Test("Counts use plural variations: 1 place / 3 places, BM 1 tempat / 3 tempat")
    func plurals() {
        func s(_ r: LocalizedStringResource, _ lang: String) -> String {
            var r = r
            r.locale = Locale(identifier: lang)
            return String(localized: r)
        }
        #expect(s("\(1) places", "en") == "1 place")
        #expect(s("\(3) places", "en") == "3 places")
        #expect(s("\(1) routes", "en") == "1 route")
        #expect(s("\(2) routes", "en") == "2 routes")
        #expect(s("Point list, \(1) points", "en") == "Point list, 1 point")
        #expect(s("\(1) places", "ms") == "1 tempat")
        #expect(s("\(3) places", "ms") == "3 tempat")
    }
}
