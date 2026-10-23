import Foundation
import Testing
@testable import Gimano

/// A straight 1 km eastbound road with a left turn at 600 m, then 400 m north.
private func makeRoute() -> Route {
    let start = LatLng(lat: 3.15, lng: 101.70)
    let corner = Geo.offset(start, northM: 0, eastM: 600)
    let end = Geo.offset(corner, northM: 400, eastM: 0)
    let pts = DemoRouteBuilder.densify([start, corner, end], stepM: 50)
    let cornerIdx = DemoRouteBuilder.cornerIndices(corners: [start, corner, end], in: pts)[0]
    let maneuvers = [
        Maneuver(type: .depart, instruction: "", streetName: "Jalan A", exitNumber: nil, roadRef: nil, distanceM: 600, durationS: 60, beginShapeIndex: 0, endShapeIndex: cornerIdx, verbalPre: nil, verbalPost: nil),
        Maneuver(type: .left, instruction: "", streetName: "Jalan B", exitNumber: nil, roadRef: nil, distanceM: 400, durationS: 40, beginShapeIndex: cornerIdx, endShapeIndex: pts.count - 1, verbalPre: nil, verbalPost: nil),
        Maneuver(type: .arrive, instruction: "", streetName: nil, exitNumber: nil, roadRef: nil, distanceM: 0, durationS: 0, beginShapeIndex: pts.count - 1, endShapeIndex: pts.count - 1, verbalPre: nil, verbalPost: nil),
    ]
    return Route(id: "r1", label: "best", durationS: 100, distanceM: 1000, trafficDurationS: 120, summary: "Via Jalan A", trafficDescription: nil,
                 hasTolls: false, tollEstimateMyr: nil, polyline: Polyline.encode(pts), bbox: [101.70, 3.15, 101.706, 3.154],
                 legs: [RouteLeg(maneuvers: maneuvers)], trafficSegments: [], alerts: [])
}

@Suite("Route tracking")
struct RouteTrackerTests {
    @Test("Progress along the route: upcoming maneuver, remaining distance and time")
    func progress() throws {
        var t = RouteTracker(route: makeRoute())
        let start = LatLng(lat: 3.15, lng: 101.70)
        let pOpt = t.update(location: Geo.offset(start, northM: 5, eastM: 200))
        let p = try #require(pOpt)
        #expect(abs(p.snap.distanceAlongM - 200) < 3)
        #expect(abs(p.snap.distanceFromRouteM - 5) < 1)
        #expect(p.upcomingIndex == 1)
        #expect(abs(p.distanceToUpcomingM - 400) < 3)
        #expect(abs(p.remainingM - 800) < 5)
        #expect(abs(p.remainingS - 96) < 2) // 120 s traffic duration × 80 %
        #expect(p.thenIndex == 2) // arrival follows 400 m later (< 500 m)
        #expect(!p.arrived)
    }

    @Test("After the turn the upcoming maneuver is arrival; arrival detected near the end")
    func arrival() throws {
        var t = RouteTracker(route: makeRoute())
        let corner = Geo.offset(LatLng(lat: 3.15, lng: 101.70), northM: 0, eastM: 600)
        _ = t.update(location: Geo.offset(corner, northM: 0, eastM: -100))
        let midOpt = t.update(location: Geo.offset(corner, northM: 100, eastM: 0))
        let mid = try #require(midOpt)
        #expect(mid.upcomingIndex == 2)
        let endOpt = t.update(location: Geo.offset(corner, northM: 390, eastM: 0))
        let end = try #require(endOpt)
        #expect(end.arrived)
    }
}

@Suite("Off-route detection")
struct OffRouteDetectorTests {
    let t0 = Date(timeIntervalSince1970: 1_000)

    @Test("Needs several consecutive far fixes")
    func consecutive() {
        var d = OffRouteDetector(thresholdM: 40, requiredConsecutive: 3)
        let r1 = d.evaluate(distanceFromRouteM: 80, accuracyM: 10, at: t0)
        #expect(!r1)
        let r2 = d.evaluate(distanceFromRouteM: 85, accuracyM: 10, at: t0 + 1)
        #expect(!r2)
        let r3 = d.evaluate(distanceFromRouteM: 90, accuracyM: 10, at: t0 + 2)
        #expect(r3)
    }

    @Test("A single good fix resets the counter (GPS jitter)")
    func jitterResets() {
        var d = OffRouteDetector(thresholdM: 40, requiredConsecutive: 3)
        _ = d.evaluate(distanceFromRouteM: 80, accuracyM: 10, at: t0)
        _ = d.evaluate(distanceFromRouteM: 80, accuracyM: 10, at: t0 + 1)
        let r4 = d.evaluate(distanceFromRouteM: 10, accuracyM: 10, at: t0 + 2)
        #expect(!r4)
        #expect(d.consecutive == 0)
        let r5 = d.evaluate(distanceFromRouteM: 80, accuracyM: 10, at: t0 + 3)
        #expect(!r5)
    }

    @Test("Poor accuracy widens tolerance; terrible fixes are ignored")
    func accuracy() {
        var d = OffRouteDetector(thresholdM: 40, requiredConsecutive: 1, maxAccuracyM: 80)
        #expect(d.tolerance(accuracyM: 50) == 60)
        let r6 = d.evaluate(distanceFromRouteM: 55, accuracyM: 50, at: t0)
        #expect(!r6)
        let r7 = d.evaluate(distanceFromRouteM: 500, accuracyM: 200, at: t0)
        #expect(!r7)
        let r8 = d.evaluate(distanceFromRouteM: 500, accuracyM: -1, at: t0)
        #expect(!r8)
        let r9 = d.evaluate(distanceFromRouteM: 65, accuracyM: 50, at: t0)
        #expect(r9)
    }

    @Test("Cooldown prevents reroute storms")
    func cooldown() {
        var d = OffRouteDetector(thresholdM: 40, requiredConsecutive: 1, cooldownS: 10)
        let r10 = d.evaluate(distanceFromRouteM: 100, accuracyM: 5, at: t0)
        #expect(r10)
        d.didReroute(at: t0)
        let r11 = d.evaluate(distanceFromRouteM: 100, accuracyM: 5, at: t0 + 5)
        #expect(!r11)
        let r12 = d.evaluate(distanceFromRouteM: 100, accuracyM: 5, at: t0 + 11)
        #expect(r12)
    }

    @Test("Tracker + detector: driving away from the route triggers a reroute")
    func endToEnd() {
        var t = RouteTracker(route: makeRoute())
        var d = OffRouteDetector()
        let start = LatLng(lat: 3.15, lng: 101.70)
        var triggered = false
        for i in 0..<6 {
            // Drive south off the road from 300 m along.
            let p = Geo.offset(start, northM: -Double(i) * 30, eastM: 300)
            if let prog = t.update(location: p), d.evaluate(distanceFromRouteM: prog.snap.distanceFromRouteM, accuracyM: 8, at: t0 + Double(i)) {
                triggered = true
                #expect(i >= 4) // 120 m+ away, third consecutive bad fix
                break
            }
        }
        #expect(triggered)
    }
}

@Suite("Location ping batching")
struct PingBatcherTests {
    private func point(_ t: TimeInterval) -> LocationPoint {
        LocationPoint(lat: 3.1, lng: 101.6, speedKmh: 40, heading: 90, accuracyM: 5, ts: Date(timeIntervalSince1970: t))
    }

    @Test("One point per 5 s, flushed in batches of 12")
    func batching() {
        var b = LocationPingBatcher()
        var batches: [[LocationPoint]] = []
        for s in stride(from: 0.0, to: 130.0, by: 1.0) {
            if let batch = b.add(point(s)) { batches.append(batch) }
        }
        #expect(batches.count == 2)
        #expect(batches.allSatisfy { $0.count == 12 })
        #expect(batches[0][1].ts.timeIntervalSince(batches[0][0].ts) == 5)
        #expect(b.drain().count == 2) // 120 s and 125 s
    }
}

@Suite("API contract decoding")
struct DecodingTests {
    @Test("Route JSON per docs/api.md decodes, including AlertOnRoute and .NET 7-digit fractions")
    func route() throws {
        let json = """
        {"routes":[{"id":"r1","label":"best","durationS":600,"distanceM":3700,"trafficDurationS":660,
          "summary":"Via E11 LDP","trafficDescription":"Typical traffic","hasTolls":true,"tollEstimateMyr":2.3,
          "polyline":"_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI","bbox":[101.6,3.1,101.7,3.2],
          "legs":[{"maneuvers":[{"type":"ramp_right","instruction":"Take exit","exitNumber":"1107","distanceM":100,"durationS":10,"beginShapeIndex":0,"endShapeIndex":1}]}],
          "trafficSegments":[{"fromIndex":0,"toIndex":1,"level":3}],
          "alerts":[{"id":"a1","type":"police","subtype":"hidden","lat":3.1,"lng":101.6,"reportedBy":{"displayName":"Aina","mood":"🙂"},
                     "createdAt":"2026-10-23T01:02:03.1234567Z","expiresAt":"2026-10-23T02:02:03Z","confirmations":2,"thanks":1,"distanceAlongRouteM":850.5}]}]}
        """
        let res = try JSON.makeDecoder().decode(RoutesResponse.self, from: Data(json.utf8))
        let r = try #require(res.routes.first)
        #expect(r.isBest)
        #expect(r.effectiveDurationS == 660)
        #expect(r.maneuvers.first?.type == .rampRight)
        #expect(r.alerts?.first?.report.type == .police)
        #expect(r.alerts?.first?.distanceAlongRouteM == 850.5)
        #expect(Polyline.decode(r.polyline).count == 3)
    }

    @Test("Unknown enum values degrade gracefully")
    func unknownEnums() throws {
        let json = #"{"id":"p","name":"X","category":"spaceport","address":"","lat":0,"lng":0}"#
        let p = try JSON.makeDecoder().decode(PlaceSummary.self, from: Data(json.utf8))
        #expect(p.category == .other)
    }

    @Test("Route request encodes departAt as explicit null and vehicle raw values")
    func requestEncoding() throws {
        let req = RouteRequest(origin: RouteEndpoint(lat: 1, lng: 2), destination: RouteEndpoint(lat: 3, lng: 4, placeId: "x"), vehicle: .motorcycle)
        let obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(req)) as! [String: Any]
        #expect(obj["departAt"] is NSNull)
        #expect(obj["vehicle"] as? String == "motorcycle")
        #expect((obj["avoid"] as? [String: Bool])?["tolls"] == false)
    }

    @Test("Hub URL derives from API base URL")
    func hubURL() {
        #expect(AppConfig.hubURL(fromAPIBase: URL(string: "http://100.101.150.9:5080/api/v1/")!).absoluteString == "ws://100.101.150.9:5080/hubs/live")
        #expect(AppConfig.hubURL(fromAPIBase: URL(string: "https://gimano.example/api/v1/")!).absoluteString == "wss://gimano.example/hubs/live")
    }
}

@Suite("SignalR JSON protocol")
struct SignalRTests {
    @Test("Parses handshake, invocations and pings from one frame")
    func parse() {
        let rs = "\u{1e}"
        let frame = "{}" + rs
            + #"{"type":1,"target":"AlertExpired","arguments":["abc"]}"# + rs
            + #"{"type":6}"# + rs
        let msgs = SignalRProtocol.parse(frame)
        #expect(msgs.count == 3)
        #expect(msgs[0] == .handshakeResponse(error: nil))
        #expect(msgs[2] == .ping)
        guard case .invocation(let target, let args) = msgs[1] else { Issue.record("not an invocation"); return }
        #expect(target == "AlertExpired")
        guard case .alertExpired(let id)? = SignalRProtocol.event(target: target, arguments: args, decoder: JSON.makeDecoder()) else {
            Issue.record("not decoded"); return
        }
        #expect(id == "abc")
    }

    @Test("Subscribe invocation is framed with the record separator")
    func subscribe() throws {
        let s = try #require(SignalRProtocol.invocation(target: "Subscribe", arguments: [[101.6, 3.1, 101.7, 3.2]]))
        #expect(s.hasSuffix("\u{1e}"))
        #expect(s.contains(#""target":"Subscribe""#))
    }
}

@Suite("Demo repository")
struct DemoRepositoryTests {
    @Test("Produces 2–3 alternatives with exactly one best, decodable polylines and arrive last")
    func routes() async throws {
        let repo = DemoRepository(latency: .zero)
        let routes = try await repo.routes(RouteRequest(origin: RouteEndpoint(lat: 3.1528, lng: 101.7038), destination: RouteEndpoint(lat: 3.1178, lng: 101.6773)))
        #expect((2...3).contains(routes.count))
        #expect(routes.filter(\.isBest).count == 1)
        #expect(routes.first?.isBest == true)
        for r in routes {
            let pts = Polyline.decode(r.polyline)
            #expect(pts.count > 10)
            #expect(r.maneuvers.last?.type == .arrive)
            #expect(r.maneuvers.last?.beginShapeIndex == pts.count - 1)
        }
    }

    @Test("Search ranks name prefix matches and reports distance")
    func search() async throws {
        let repo = DemoRepository(latency: .zero)
        let res = try await repo.search(query: "petronas", near: DemoRepository.defaultLocation, category: nil, limit: 10)
        #expect(!res.isEmpty)
        #expect(res.allSatisfy { $0.name.lowercased().contains("petronas") })
        #expect(res.allSatisfy { $0.distanceM != nil })
    }
}

@Suite("Canned real-road demo routes")
struct CannedRouteTests {
    let canned = CannedRoutes(bundle: .main)

    @Test("Fixture loads with 2–3 alternatives per destination")
    func loads() throws {
        let file = try #require(canned.file)
        #expect(file.destinations.count >= 8)
        #expect(file.destinations.allSatisfy { (2...3).contains($0.routes.count) })
        #expect(Geo.distance(file.origin, DemoRepository.defaultLocation) < 5)
    }

    @Test("Precision-1e6 decoding yields dense road geometry in the Klang Valley matching the reported length")
    func geometry() throws {
        let file = try #require(canned.file)
        for dest in file.destinations {
            for r in dest.routes {
                let pts = Polyline.decode(r.polyline, precision: 6)
                #expect(pts.count > 20)
                #expect(pts.allSatisfy { (2.9...3.35).contains($0.lat) && (101.5...101.85).contains($0.lng) })
                let cum = Geo.cumulativeDistances(pts)
                #expect(abs((cum.last ?? 0) - r.distanceM) / r.distanceM < 0.03)
                // Real roads: no long straight "teleports" between vertices.
                let maxGap = zip(pts, pts.dropFirst()).map { Geo.distance($0, $1) }.max() ?? 0
                #expect(maxGap < 1_500)
                #expect(Geo.distance(pts[0], file.origin) < 30)
                #expect(Geo.distance(pts.last!, LatLng(lat: dest.lat, lng: dest.lng)) < 30)
                // Wrong precision would put KL near the equator/meridian.
                #expect(Polyline.decode(r.polyline, precision: 5).first!.lat > 30)
                let begins = r.maneuvers.map(\.beginShapeIndex)
                #expect(begins == begins.sorted())
                #expect(r.maneuvers.first?.type == .depart)
                #expect(r.maneuvers.last?.type == .arrive)
                #expect(begins.last == pts.count - 1)
            }
        }
    }

    @Test("Driving along a canned route never triggers off-route and progress is monotonic")
    func driveAlong() throws {
        let file = try #require(canned.file)
        let route = try #require(file.destinations.first { $0.key == "midvalley" }?.routes.first)
        var tracker = RouteTracker(route: route)
        var detector = OffRouteDetector()
        let pts = tracker.geometry.points
        let cum = tracker.geometry.cumulative
        var last = -1.0
        var t = Date(timeIntervalSince1970: 0)
        for d in stride(from: 0.0, through: cum.last!, by: 15) {
            let p = Geo.point(along: pts, cumulative: cum, distance: d).point
            let progOpt = tracker.update(location: p)
            let prog = try #require(progOpt)
            #expect(prog.snap.distanceFromRouteM < 1)
            #expect(prog.snap.distanceAlongM >= last - 0.5)
            last = prog.snap.distanceAlongM
            t += 1
            let reroute = detector.evaluate(distanceFromRouteM: prog.snap.distanceFromRouteM, accuracyM: 5, at: t)
            #expect(!reroute)
        }
        let endOpt = tracker.update(location: pts.last!)
        let end = try #require(endOpt)
        #expect(end.arrived)
    }

    @Test("Demo repository serves canned routes for fixture trips")
    func repositoryUsesCanned() async throws {
        let repo = DemoRepository(latency: .zero)
        let routes = try await repo.routes(RouteRequest(origin: RouteEndpoint(lat: DemoRepository.defaultLocation.lat, lng: DemoRepository.defaultLocation.lng),
                                                        destination: RouteEndpoint(lat: 3.1178, lng: 101.6773)))
        #expect(routes.count == 3)
        #expect(routes.allSatisfy { $0.id.hasPrefix("demo-midvalley-") })
        #expect(routes.first?.isBest == true)
        let alertCount = routes.first?.alerts?.count ?? 0
        #expect(alertCount > 0)
    }
}
