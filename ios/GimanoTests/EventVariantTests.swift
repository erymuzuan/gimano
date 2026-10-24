import Foundation
import Testing
@testable import Gimano

// docs/events.md §5.4 (Douglas–Peucker at 5 m before signing) and §5.5 (stops-only link): the writer simplifies, the
// share screen picks link / stops-only / file for long routes, and a guest's stops-only copy is upgraded silently by
// the file. Also the route_json writer rule for matched legs with gaps (docs/privacy-and-backup.md).

private enum EV {
    static let now = EventTime.parse("2026-11-01T02:15:00Z")!.date
    static let kl = TimeZone(identifier: "Asia/Kuala_Lumpur")!

    /// Deterministic pseudo-random numbers in [0, 1).
    struct Rng {
        var state: UInt64
        mutating func next() -> Double {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Double(state >> 11) / Double(1 << 53)
        }
    }

    static let mPerDeg = RouteSimplify.earthRadiusM * .pi / 180

    /// A long, road-like line: ~10 m steps, slowly turning, with straights, switchbacks and sub-metre jitter, rounded to
    /// 6 decimals like a decoded polyline (route-core's `winding_road`).
    static func windingRoad(_ n: Int, seed: UInt64 = 0x5eed_cafe) -> [LatLng] {
        var rng = Rng(state: seed)
        var lat = 3.80, lng = 102.40, heading = 0.6, turn = 0.0
        var out: [LatLng] = []
        for i in 0..<n {
            if i % 400 == 0 {
                switch Int(rng.next() * 3) {
                case 0: turn = 0
                case 1: turn = (rng.next() - 0.5) * 0.01
                default: turn = (rng.next() - 0.5) * 0.08
                }
            }
            heading += turn + (rng.next() - 0.5) * 0.004
            let step = 9 + rng.next() * 2
            lat += step * cos(heading) / mPerDeg
            lng += step * sin(heading) / (mPerDeg * cos(lat * .pi / 180))
            let jitter = 0.4 / mPerDeg
            out.append(RouteGeo.round6(LatLng(lat: lat + (rng.next() - 0.5) * jitter, lng: lng + (rng.next() - 0.5) * jitter)))
        }
        return out
    }

    /// A random walk with ~40 m steps in any direction: almost nothing is within 5 m of a chord, so it barely simplifies.
    static func scribble(_ n: Int, from start: LatLng, seed: UInt64) -> [LatLng] {
        var rng = Rng(state: seed)
        var p = start
        var out = [start]
        for _ in 1..<n {
            let a = rng.next() * 2 * .pi
            let d = 25 + rng.next() * 30
            p = RouteGeo.round6(LatLng(lat: p.lat + d * cos(a) / mPerDeg, lng: p.lng + d * sin(a) / (mPerDeg * cos(p.lat * .pi / 180))))
            out.append(p)
        }
        return out
    }

    /// Independent check: distance from `p` to segment a–b on one projection (mean latitude of the whole line).
    static func segmentDistance(_ p: LatLng, _ a: LatLng, _ b: LatLng, cosLat: Double) -> Double {
        func xy(_ q: LatLng) -> (Double, Double) { ((q.lng - a.lng) * mPerDeg * cosLat, (q.lat - a.lat) * mPerDeg) }
        let (px, py) = xy(p), (bx, by) = xy(b)
        let len2 = bx * bx + by * by
        let t = len2 > 0 ? min(1, max(0, (px * bx + py * by) / len2)) : 0
        return ((px - t * bx) * (px - t * bx) + (py - t * by) * (py - t * by)).squareRoot()
    }

    /// The worst distance of an original point from the simplified line (segment between the kept points around it).
    static func worstDistance(_ original: [LatLng], _ simplified: [LatLng]) -> Double {
        let cosLat = cos(original.map(\.lat).reduce(0, +) / Double(original.count) * .pi / 180)
        var worst = 0.0
        var seg = 0
        for p in original {
            while seg + 1 < simplified.count - 1 && p == simplified[seg + 1] { seg += 1 }
            worst = max(worst, segmentDistance(p, simplified[seg], simplified[seg + 1], cosLat: cosLat))
        }
        return worst
    }

    /// A hosted draft with two stops and one calculated route along `geometry`.
    static func draft(geometry: [LatLng], stops extra: [HostedStopDraft] = []) -> HostedEventDraft {
        var d = HostedEventDraft.new(now: now, timeZone: kl, preferredLanguage: "ms")
        d.title = "Kayuh Jauh"
        d.template = .ride
        d.start = EventTime.parse("2026-11-15T07:00:00+08:00")!.date
        d.end = EventTime.parse("2026-11-15T18:00:00+08:00")!.date
        let start = HostedStopDraft(kind: .start, label: "Mula", location: geometry.first!)
        let finish = HostedStopDraft(kind: .finish, label: "Tamat", location: geometry.last!)
        d.stops = [start, finish] + extra
        var r = RouteDraft(title: "Hari 1", modeRaw: "bicycle")
        r.addPoint(geometry.first!, stopId: start.id)
        r.addPoint(geometry.last!, stopId: finish.id)
        r.apply(.routed(geometry: geometry, distanceM: RouteGeo.length(geometry), durationS: 3600), generation: r.legs[0].generation)
        d.routes = [r]
        return d
    }

    static func publish(_ d: HostedEventDraft) throws -> (PublishedInvite, EventStore) {
        let store = try EventStore(directory: nil)
        try store.saveDraft(d)
        guard case .success(let inv) = EventPublisher.publish(eventId: d.id, store: store, secrets: InMemoryEventSecretStore(), now: now) else {
            throw CancellationError()
        }
        return (inv, store)
    }
}

@Suite("Route simplification (§5.4): Douglas–Peucker at 5 m")
struct RouteSimplifyTests {
    @Test("A long road: every original point within 5 m, endpoints kept, a subsequence, far fewer points, idempotent")
    func longRoad() throws {
        let road = EV.windingRoad(20_000)
        let simple = RouteSimplify.simplify(road)
        #expect(simple.first == road.first && simple.last == road.last)
        #expect(simple.count * 5 < road.count, "\(road.count) → \(simple.count)")
        let worst = EV.worstDistance(road, simple)
        #expect(worst <= 5.05, "worst \(worst) m")
        #expect(worst > 3, "the tolerance is actually used (\(worst) m)")
        var it = road.makeIterator()
        #expect(simple.allSatisfy { p in while let q = it.next() { if q == p { return true } }; return false })
        #expect(abs(RouteGeo.length(road) - RouteGeo.length(simple)) / RouteGeo.length(road) < 0.01)
        #expect(RouteSimplify.simplify(simple) == simple)
        // The encoded polyline shrinks accordingly.
        let before = Polyline.encode(road, precision: 6)
        let after = try #require(RouteSimplify.polyline(before))
        #expect(after == Polyline.encode(simple, precision: 6))
        #expect(after.count * 4 < before.count)
    }

    @Test("Corners kept, collinear dropped; the 5 m boundary; loops and spikes; tiny inputs")
    func shapes() {
        let pts = (0...10).map { LatLng(lat: 3.0, lng: 101.0 + Double($0) * 0.001) } + (1...10).map { LatLng(lat: 3.0 + Double($0) * 0.001, lng: 101.01) }
        #expect(RouteSimplify.simplify(pts.map(RouteGeo.round6)) == [LatLng(lat: 3.0, lng: 101.0), LatLng(lat: 3.0, lng: 101.01), LatLng(lat: 3.01, lng: 101.01)])
        let off = { (m: Double) in m / EV.mPerDeg }
        let a = LatLng(lat: 3.0, lng: 101.0), b = LatLng(lat: 3.0, lng: 101.009)
        let near = LatLng(lat: 3.0 + off(4), lng: 101.0045), far = LatLng(lat: 3.0 + off(6), lng: 101.0045)
        #expect(RouteSimplify.simplify([a, near, b]) == [a, b])
        #expect(RouteSimplify.simplify([a, far, b]) == [a, far, b])
        let loop = [a, LatLng(lat: 3.001, lng: 101.0), LatLng(lat: 3.001, lng: 101.001), LatLng(lat: 3.0, lng: 101.001), a]
        let s = RouteSimplify.simplify(loop)
        #expect(s.first == a && s.last == a && s.count >= 4 && EV.worstDistance(loop, s) <= 5.05)
        let spike = [a, LatLng(lat: 3.0, lng: 101.002), LatLng(lat: 3.0, lng: 101.001)]
        #expect(RouteSimplify.simplify(spike) == spike)
        #expect(RouteSimplify.simplify([]).isEmpty && RouteSimplify.simplify([a]) == [a] && RouteSimplify.simplify([a, a]) == [a, a])
        #expect(RouteSimplify.simplify([LatLng](repeating: a, count: 5)).count == 2)
        let three = [a, LatLng(lat: 3.0, lng: 101.00001), LatLng(lat: 3.0, lng: 101.00002)]
        #expect(RouteSimplify.simplify(three, toleranceM: 0) == three && RouteSimplify.simplify(three, toleranceM: .nan) == three)
    }

    @Test("The writer signs simplified routes; distance and time keep the routing values")
    func writerSimplifies() throws {
        // ride-two-day's last three points are collinear: a writer publishes 6 of its 7.
        let v = try #require(Bundle(for: EVToken.self).url(forResource: "ride-two-day", withExtension: "json"))
        let o = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: v)) as? [String: Any])
        let doc = try EventDoc.parse(Array(try #require(o["eventJson"] as? String).utf8))
        #expect(doc.routes[0].points.count == 7 && doc.simplified().routes[0].points.count == 6)
        #expect(doc.simplified().routes[0].distanceM == doc.routes[0].distanceM)

        let road = EV.windingRoad(3000)
        let d = EV.draft(geometry: road)
        let built = try #require(d.event(rev: 1, parent: nil, publishedAt: EV.now).0)
        let points = built.routes[0].points
        #expect(points.count < road.count / 5 && points.first == road.first && points.last == road.last)
        #expect(EV.worstDistance(road, points) <= 5.05)
        #expect(built.routes[0].distanceM == RouteGeo.meters(RouteGeo.length(road)))
        let (inv, _) = try EV.publish(d)
        #expect(try EventPack.openLink(try #require(inv.link)).event.routes[0].points == points)
    }
}

private final class EVToken {}

@Suite("Stops-only link (§5.5): when to offer it, and the guest's upgrade")
struct StopsOnlyShareTests {
    @Test("A short route: the full link, no stops-only link")
    func shortRoute() throws {
        let (inv, _) = try EV.publish(EV.draft(geometry: EV.windingRoad(500)))
        #expect(EventPublisher.shareChoice(inv) == .link && inv.link != nil && inv.stopsOnlyLink == nil)
    }

    @Test("A long route: no full link; the file (full) or the stops-only link, same rev and key, revision hash = full's")
    func longRoute() throws {
        let geometry = EV.scribble(6000, from: LatLng(lat: 3.8, lng: 102.4), seed: 7)
        let (inv, _) = try EV.publish(EV.draft(geometry: geometry))
        #expect(inv.fragmentLength > EventLimits.maxFragmentChars)
        #expect(EventPublisher.shareChoice(inv) == .fileOrStopsOnly)
        let stopsOnly = try EventPack.openLink(try #require(inv.stopsOnlyLink))
        let file = try EventPack.openFile(inv.fileBytes, secretCode: inv.secretCode)
        #expect(file.eventBytes == inv.eventBytes && !file.isStopsOnly && file.event.routes[0].points.count > 1000)
        #expect(stopsOnly.isStopsOnly && stopsOnly.event.routes.allSatisfy { $0.polyline == nil })
        #expect(stopsOnly.event.rev == file.event.rev && stopsOnly.event.id == file.event.id)
        #expect(stopsOnly.event.publishedAt == file.event.publishedAt && stopsOnly.event.parent == file.event.parent)
        #expect(stopsOnly.organizerPub == file.organizerPub)
        #expect(stopsOnly.revisionHash == file.revisionHash && file.revisionHash == EventCodes.bytesHash(inv.eventBytes))
        #expect(stopsOnly.event.routes[0].distanceM == file.event.routes[0].distanceM)
        #expect(EventImport.decide(EventImportCandidate(file), stored: StoredEventRecord(
            organizerPub: stopsOnly.organizerPub, revisions: [StoredEventRevision(rev: 1, hash: stopsOnly.revisionHash, stopsOnly: true)]),
            now: EV.now) == .upgradeToFull(rev: 1))
    }

    @Test("A long route and a plan too big even without lines: only the file")
    func fileOnly() throws {
        var rng = EV.Rng(state: 99)
        let letters = Array("abcdefghijklmnopqrstuvwxyz ")
        let crowd = (0..<150).map { i -> HostedStopDraft in
            var s = HostedStopDraft(kind: .checkpoint, label: "CP \(i)", location: LatLng(lat: 3.8 + Double(i) * 0.001, lng: 102.4))
            s.notes = String((0..<480).map { _ in letters[Int(rng.next() * Double(letters.count))] })
            return s
        }
        let (inv, _) = try EV.publish(EV.draft(geometry: EV.scribble(6000, from: LatLng(lat: 3.8, lng: 102.4), seed: 8), stops: crowd))
        #expect(inv.link == nil && inv.stopsOnlyLink == nil && EventPublisher.shareChoice(inv) == .fileOnly)
        #expect(try EventPack.openFile(inv.fileBytes, secretCode: inv.secretCode).event.stops.count == 152)
    }

    @MainActor
    @Test("Guest: the stops-only link imports; the file later replaces it silently (\"Full route added\"); the link again changes nothing")
    func guestUpgrade() async throws {
        let (inv, _) = try EV.publish(EV.draft(geometry: EV.scribble(6000, from: LatLng(lat: 3.8, lng: 102.4), seed: 7)))
        let guest = EventCenter(store: try EventStore(directory: nil), secrets: InMemoryEventSecretStore())
        guest.now = { EV.now }
        guest.openLink(try #require(inv.stopsOnlyLink))
        #expect(guest.pending?.decision.code == "new")
        let id = try #require(guest.confirmPending())
        #expect(guest.event(id)?.doc.isStopsOnly == true && guest.event(id)?.doc.routes.first?.points.isEmpty == true)

        guest.receiveFile(inv.fileBytes)
        #expect(await guest.openPendingFile(secretCode: inv.secretCode) == nil)
        #expect(!guest.isOpeningFile)
        #expect(guest.pending == nil && guest.fullRouteAdded == id)          // no prompt
        #expect(guest.path.last == .detail(id))
        let full = try #require(guest.event(id))
        #expect(!full.doc.isStopsOnly && full.doc.routes[0].points.count > 1000 && full.forkAlternatives == 0)
        #expect(try guest.store.record(eventId: id)?.revisions == [StoredEventRevision(rev: 1, hash: EventCodes.bytesHash(inv.eventBytes))])

        guest.openLink(try #require(inv.stopsOnlyLink))
        #expect(guest.pending?.decision == .alreadyImported)
        guest.cancelPending()
        #expect(guest.event(id)?.doc.isStopsOnly == false)
    }

    @Test("A later revision over a stored stops-only copy: its parent (fullHash) matches")
    func laterRevision() throws {
        var d = EV.draft(geometry: EV.scribble(6000, from: LatLng(lat: 3.8, lng: 102.4), seed: 7))
        let store = try EventStore(directory: nil)
        let secrets = InMemoryEventSecretStore()
        try store.saveDraft(d)
        guard case .success(let v1) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: EV.now) else {
            Testing.Issue.record("publish failed"); return
        }
        let guest = try EventStore(directory: nil)
        let variant = try EventPack.openLink(try #require(v1.stopsOnlyLink))
        try guest.save(variant, decision: .new(safetyCode: variant.safetyCode), now: EV.now)
        d.title = "Kayuh Jauh (dikemas kini)"
        try store.saveDraft(d)
        guard case .success(let v2) = EventPublisher.publish(eventId: d.id, store: store, secrets: secrets, now: EV.now.addingTimeInterval(60)) else {
            Testing.Issue.record("publish v2 failed"); return
        }
        #expect(v2.doc.rev == 2 && v2.doc.parent == variant.event.variant?.fullHash)
        let rev2 = try EventPack.openFile(v2.fileBytes, secretCode: v2.secretCode)
        #expect(EventImport.decide(EventImportCandidate(rev2), stored: try guest.record(eventId: d.id), now: EV.now)
                == .newer(storedRev: 1, parent: .matches))
    }
}

@Suite("route_json writer rules")
struct RouteJSONWriterRuleTests {
    @Test("A matched leg with unmatched parts is stored as sketch (no duration); a failed leg as pending")
    func matchedWithGaps() throws {
        let a = LatLng(lat: 3.1, lng: 101.6), b = LatLng(lat: 3.11, lng: 101.61), c = LatLng(lat: 3.12, lng: 101.6)
        var r = RouteDraft(id: "g", title: "Gaps")
        r.points = [a, b, c].map { BuilderPoint($0) }
        r.legs = [BuilderLeg(state: .matched, geometry: [a, LatLng(lat: 3.105, lng: 101.606), b], distanceM: 1500, durationS: 200,
                             unmatched: [UnmatchedRange(from: 3, to: 9)]),
                  BuilderLeg(state: .unresolved, problem: .noRoute)]
        let o = try #require(try JSONSerialization.jsonObject(with: Data(PersonalRouteJSON.encode(r).utf8)) as? [String: Any])
        let legs = try #require(o["legs"] as? [[String: Any]])
        #expect(legs.map { $0["source"] as? String } == ["sketch", "pending"])
        #expect(legs[0]["durationS"] == nil && legs[0]["distanceM"] as? Int == 1500 && legs[1]["polyline"] as? String == "")
    }

    @Test("Reading: \"Ikut jalan\" opens off only when every leg is a sketch")
    func followRoads() throws {
        let line = Polyline.encode([LatLng(lat: 1, lng: 2), LatLng(lat: 1.1, lng: 2)])
        let allSketch = #"{"v":1,"points":[{"lat":1,"lng":2},{"lat":1.1,"lng":2}],"legs":[{"source":"sketch","polyline":"\#(line)"}]}"#
        let mixed = #"{"v":1,"points":[{"lat":1,"lng":2},{"lat":1.1,"lng":2},{"lat":1.2,"lng":2}],"legs":[{"source":"sketch","polyline":"\#(line)"},{"source":"pending","polyline":""}]}"#
        #expect(PersonalRouteJSON.decode(allSketch, id: "a", name: "", mode: "walk")?.followRoads == false)
        #expect(PersonalRouteJSON.decode(mixed, id: "b", name: "", mode: "walk")?.followRoads == true)
    }
}
