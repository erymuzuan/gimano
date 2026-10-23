import Foundation
import Testing
import UIKit
@testable import Gimano

private func route(hasTolls: Bool = true, estimate: Double? = nil, complete: Bool? = nil, tolls: [TollPlaza]? = nil,
                   vehicle: Vehicle? = .car, legs: [RouteLeg]? = nil, polyline: String? = nil, duration: Double = 600) -> Route {
    let pts = (0...10).map { LatLng(lat: 3.10 + Double($0) * 0.001, lng: 101.60) }
    let ms = [
        Maneuver(type: .depart, instruction: "", distanceM: 1000, durationS: duration, beginShapeIndex: 0, endShapeIndex: 10),
        Maneuver(type: .arrive, instruction: "", distanceM: 0, durationS: 0, beginShapeIndex: 10, endShapeIndex: 10),
    ]
    return Route(id: "r", label: "best", durationS: duration, distanceM: 1112, trafficDurationS: nil, summary: "", trafficDescription: nil,
                 vehicle: vehicle, hasTolls: hasTolls, tollEstimateMyr: estimate, tollFareComplete: complete, tolls: tolls,
                 polyline: polyline ?? Polyline.encode(pts), bbox: [101.6, 3.1, 101.6, 3.11], legs: legs ?? [RouteLeg(maneuvers: ms)])
}

private func plaza(_ fare: Double?) -> TollPlaza {
    TollPlaza(plazaId: UUID().uuidString, name: "Plaza", highway: "E10", lat: 3.1, lng: 101.6, fareMyr: fare, system: "open", source: "LLM 2025")
}

@Suite("Tolls & fares")
struct TollBadgeTests {
    @Test("Complete fare: RM 2.50")
    func complete() {
        let b = TollBadge.make(for: route(estimate: 2.5, complete: true, tolls: [plaza(1.0), plaza(1.5)]), mode: .car)
        #expect(b == .complete(2.5))
        #expect(b.text == "RM 2.50")
    }

    @Test("Incomplete fare: from RM 1.60")
    func incomplete() {
        let b = TollBadge.make(for: route(estimate: 1.6, complete: false, tolls: [plaza(1.6), plaza(nil)]), mode: .car)
        #expect(b == .from(1.6))
        #expect(b.text == "from RM 1.60")
    }

    @Test("Unknown fare: Tolls (older servers without plazas, or no known fare)")
    func unknown() {
        #expect(TollBadge.make(for: route(estimate: nil, complete: nil, tolls: nil), mode: .car) == .unknown)
        #expect(TollBadge.make(for: route(estimate: nil, complete: false, tolls: [plaza(nil)]), mode: .car).text == "Tolls")
    }

    @Test("No badge without tolls, or for bicycle/walk")
    func none() {
        #expect(TollBadge.make(for: route(hasTolls: false, tolls: []), mode: .car) == .none)
        #expect(TollBadge.make(for: route(estimate: 2.5, complete: true, tolls: [plaza(2.5)]), mode: .bicycle) == .none)
        #expect(TollBadge.make(for: route(estimate: 2.5, complete: true, tolls: [plaza(2.5)], vehicle: .walk), mode: .car) == .none)
    }

    @Test("Fare derived from plazas when the estimate is missing")
    func derived() {
        #expect(TollBadge.make(for: route(estimate: nil, complete: nil, tolls: [plaza(1.0), plaza(0.5)]), mode: .motorcycle) == .complete(1.5))
    }
}

@Suite("Multi-stop")
struct StopTests {
    @Test("Up to 5 stops; reorder")
    func planner() {
        var stops: [TripStop] = []
        for i in 0..<5 { #expect(StopPlanner.add(TripStop(name: "S\(i)", location: LatLng(lat: 3, lng: 101)), to: &stops)) }
        #expect(!StopPlanner.add(TripStop(name: "S6", location: LatLng(lat: 3, lng: 101)), to: &stops))
        StopPlanner.move(&stops, from: IndexSet(integer: 4), to: 0)
        #expect(stops.map(\.name) == ["S4", "S0", "S1", "S2", "S3"])
    }

    @Test("ETA per stop from legs (contract shape)")
    func scheduleFromLegs() {
        let m1 = [Maneuver(type: .depart, instruction: "", distanceM: 500, durationS: 120, beginShapeIndex: 0, endShapeIndex: 4),
                  Maneuver(type: .arrive, instruction: "", distanceM: 0, durationS: 0, beginShapeIndex: 4, endShapeIndex: 4)]
        let m2 = [Maneuver(type: .depart, instruction: "", distanceM: 600, durationS: 180, beginShapeIndex: 4, endShapeIndex: 10),
                  Maneuver(type: .arrive, instruction: "", distanceM: 0, durationS: 0, beginShapeIndex: 10, endShapeIndex: 10)]
        let r = route(legs: [RouteLeg(durationS: 120, distanceM: 445, to: LegEnd(lat: 3.104, lng: 101.6, name: "Kedai"), maneuvers: m1),
                             RouteLeg(durationS: 180, distanceM: 667, maneuvers: m2)], duration: 300)
        let s = StopSchedule.make(route: r, stops: [LatLng(lat: 3.104, lng: 101.6)])
        #expect(s.offsetsS == [120, 300])
        #expect(abs(s.alongM[0] - 445) < 3)
    }

    @Test("ETA per stop by projection when the server returns one leg")
    func scheduleByProjection() {
        let r = route(duration: 1000)
        let s = StopSchedule.make(route: r, stops: [LatLng(lat: 3.105, lng: 101.6001)])
        #expect(abs(s.offsetsS[0] - 500) < 5)
        #expect(s.offsetsS.last == 1000)
    }

    @Test("Stop tracker reports arrival once, then moves to the next leg")
    func tracker() {
        var t = StopTracker(schedule: StopSchedule(alongM: [400, 900, 1500], offsetsS: [60, 120, 200]), stopCount: 2)
        #expect(t.update(alongM: 100) == nil)
        #expect(t.update(alongM: 380) == 0)
        #expect(t.update(alongM: 390) == nil)
        #expect(t.nextStopIndex == 1)
        let rem = t.remainingToNext(alongM: 500, totalRemainingS: 100, totalRemainingM: 1000)
        #expect(rem.meters == 400)
        #expect(abs(rem.seconds - 40) < 0.01)
        #expect(t.update(alongM: 880) == 1)
        #expect(!t.hasPendingStop)
        #expect(t.update(alongM: 1500) == nil)
    }

    @Test("Intermediate arrive maneuvers map to stop names")
    @MainActor
    func arrivalLabels() {
        let ms = [Maneuver(type: .depart, instruction: "", distanceM: 0, durationS: 0, beginShapeIndex: 0, endShapeIndex: 1),
                  Maneuver(type: .arrive, instruction: "", distanceM: 0, durationS: 0, beginShapeIndex: 1, endShapeIndex: 1),
                  Maneuver(type: .depart, instruction: "", distanceM: 0, durationS: 0, beginShapeIndex: 1, endShapeIndex: 2),
                  Maneuver(type: .arrive, instruction: "", distanceM: 0, durationS: 0, beginShapeIndex: 2, endShapeIndex: 2)]
        let labels = NavigationSession.arrivalLabels(maneuvers: ms, stops: [TripStop(name: "Kedai Mak", location: LatLng(lat: 0, lng: 0))])
        #expect(labels == [1: "Kedai Mak"])
        #expect(ManeuverFormatter.instruction(for: ms[1], stopName: "Kedai Mak") == "Arrive at Kedai Mak")
    }

    @Test("Reroute sends nextStopIndex; waypoints carry names")
    func encoding() throws {
        let obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(RerouteRequest(lat: 1, lng: 2, heading: 90, nextStopIndex: 1))) as! [String: Any]
        #expect(obj["nextStopIndex"] as? Int == 1)
        let wp = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(TripStop(name: "Kedai Mak", location: LatLng(lat: 3, lng: 101)).endpoint)) as! [String: Any]
        #expect(wp["name"] as? String == "Kedai Mak")
    }

    @Test("Demo repo splits a captured route into legs at on-road stops, and refuses off-road ones")
    func demoLegs() throws {
        let base = try #require(CannedRoutes.shared.route(key: "midvalley", routeIndex: 0))
        let pts = Polyline.decode(base.polyline)
        let stop = pts[pts.count / 3]
        let split = try #require(DemoRouteBuilder.splitIntoLegs(base, stops: [(stop, "Kedai")]))
        #expect(split.legs.count == 2)
        #expect(split.legs[0].to?.name == "Kedai")
        #expect(split.legs[0].maneuvers.last?.type == .arrive)
        #expect(split.legs[1].maneuvers.first?.type == .depart)
        #expect(split.legs[1].maneuvers.last?.beginShapeIndex == pts.count - 1)
        #expect(DemoRouteBuilder.splitIntoLegs(base, stops: [(LatLng(lat: 3.3, lng: 101.9), "Far")]) == nil)
    }
}

@Suite("Travel modes")
struct TravelModeTests {
    @Test("Walking and cycling wording")
    func wording() {
        let dep = Maneuver(type: .depart, instruction: "", streetName: "Jalan Ampang", distanceM: 0, durationS: 0, beginShapeIndex: 0, endShapeIndex: 1)
        #expect(ManeuverFormatter.instruction(for: dep, mode: .walk) == "Walk along Jalan Ampang")
        #expect(ManeuverFormatter.instruction(for: dep, mode: .bicycle) == "Cycle along Jalan Ampang")
        #expect(ManeuverFormatter.instruction(for: dep, mode: .car) == "Head out on Jalan Ampang")
    }

    @Test("Voice prompt distances are shorter on foot")
    func voiceDistances() {
        #expect(VoiceAnnouncer.earlyDistance(speedKmh: 5, mode: .walk) == 120)
        #expect(VoiceAnnouncer.nearDistance(speedKmh: 5, mode: .walk) == 20)
        var a = VoiceAnnouncer(mode: .walk)
        let m = Maneuver(type: .left, instruction: "", streetName: "Lorong Haji Taib", distanceM: 0, durationS: 0, beginShapeIndex: 0, endShapeIndex: 1)
        let early = a.prompt(maneuverIndex: 1, maneuver: m, distanceM: 100, speedKmh: 5)
        #expect(early == "In 100 metres, turn left onto Lorong Haji Taib")
        let near = a.prompt(maneuverIndex: 1, maneuver: m, distanceM: 15, speedKmh: 5)
        #expect(near == "Turn left onto Lorong Haji Taib")
    }

    @Test("Vehicle wire values; unknown decodes to car")
    func vehicleCodable() throws {
        #expect(String(decoding: try JSONEncoder().encode([Vehicle.walk, .bicycle]), as: UTF8.self) == #"["walk","bicycle"]"#)
        #expect(try JSONDecoder().decode([Vehicle].self, from: Data(#"["hovercraft"]"#.utf8)) == [.car])
        #expect(!Vehicle.walk.isMotorized && Vehicle.motorcycle.isMotorized)
    }

    @Test("Route JSON with the round-2 fields decodes; older responses still decode")
    func routeDecoding() throws {
        let new = """
        {"id":"r","label":"best","durationS":600,"distanceM":5000,"trafficDurationS":650,"summary":"Via E10","trafficDescription":null,
         "vehicle":"car","hasTolls":true,"tollEstimateMyr":1.6,"tollFareComplete":false,
         "tolls":[{"plazaId":"p1","name":"Plaza Tol PJS 2","highway":"E10","lat":3.1,"lng":101.6,"fareMyr":1.6,"system":"open","source":"PLUS 2025"},
                  {"plazaId":"p2","name":"Plaza Tol X","highway":null,"lat":3.1,"lng":101.6,"fareMyr":null,"system":"closed_entry","source":null}],
         "polyline":"_izlhA~rlgdF","bbox":[1,2,3,4],
         "legs":[{"durationS":300,"distanceM":2500,"to":{"lat":3.1,"lng":101.6,"name":"Kedai"},"maneuvers":[]},
                 {"durationS":300,"distanceM":2500,"to":{"lat":3.2,"lng":101.7,"name":null},"maneuvers":[]}],
         "trafficSegments":[],"alerts":[]}
        """
        let r = try JSON.makeDecoder().decode(Route.self, from: Data(new.utf8))
        #expect(r.tolls?.count == 2)
        #expect(r.legs[0].to?.name == "Kedai")
        #expect(TollBadge.make(for: r, mode: .car) == .from(1.6))
        let old = #"{"id":"r","label":"best","durationS":1,"distanceM":1,"trafficDurationS":1,"summary":"","trafficDescription":null,"hasTolls":true,"tollEstimateMyr":null,"polyline":"","bbox":[],"legs":[{"maneuvers":[]}],"trafficSegments":[],"alerts":[]}"#
        let o = try JSON.makeDecoder().decode(Route.self, from: Data(old.utf8))
        #expect(o.tolls == nil && o.vehicle == nil)
        #expect(TollBadge.make(for: o, mode: .car) == .unknown)
    }
}

@Suite("Favourites (labels & icons)")
struct FavoriteLabelTests {
    @Test("Free-text labels suggest icons", arguments: [
        ("Kampung", FavoriteIcon.kampung), ("Rumah Mertua", .mertua), ("Sekolah Aina", .school), ("Surau Al-Ikhlas", .surau),
        ("Gym", .gym), ("Kedai Mak", .shop), ("Klinik Dr Lim", .hospital),
    ])
    func suggest(label: String, icon: FavoriteIcon) {
        #expect(QuickLabel.suggestedIcon(for: label) == icon)
    }

    @Test("Unknown label has no suggestion; quick chips match the spec")
    func chips() {
        #expect(QuickLabel.suggestedIcon(for: "Pejabat Abah") == nil)
        #expect(QuickLabel.all.map(\.label) == ["Kampung", "Rumah Mertua", "Sekolah", "Surau", "Gym", "Kedai"])
        #expect(!FavoriteIcon.pickable.contains(.home) && !FavoriteIcon.pickable.contains(.work))
    }

    @Test("Ordering: home, work, then favourites by sortOrder")
    func ordering() {
        let p = [SavedPlace(id: "f2", kind: .favorite, name: "B", lat: 0, lng: 0, address: "", sortOrder: 1),
                 SavedPlace(id: "w", kind: .work, name: "Work", lat: 0, lng: 0, address: ""),
                 SavedPlace(id: "f1", kind: .favorite, name: "A", lat: 0, lng: 0, address: "", sortOrder: 0),
                 SavedPlace(id: "h", kind: .home, name: "Home", lat: 0, lng: 0, address: "")]
        #expect(SavedPlaceOrdering.sorted(p).map(\.id) == ["h", "w", "f1", "f2"])
    }
}

@Suite("Feedback")
struct FeedbackTests {
    private func req(_ m: String = "Jalan ditutup") -> FeedbackRequest {
        FeedbackRequest(category: .mapData, message: m, rating: 4, screen: .navigation, screenshot: nil)
    }

    @Test("Validation and the driving rule")
    func rules() {
        #expect(FeedbackRules.validate(message: "ok"))
        #expect(!FeedbackRules.validate(message: "   \n"))
        #expect(!FeedbackRules.validate(message: String(repeating: "a", count: 2001)))
        #expect(FeedbackRules.allowedWhileDriving(speedKmh: 3, isPassenger: false))
        #expect(!FeedbackRules.allowedWhileDriving(speedKmh: 40, isPassenger: false))
        #expect(FeedbackRules.allowedWhileDriving(speedKmh: 40, isPassenger: true))
    }

    @Test("Retry policy: 400/413 fail, everything else retries with capped backoff")
    func policy() {
        #expect(OutboxPolicy.classify(APIError.http(status: 400, problem: nil)) == .fail)
        #expect(OutboxPolicy.classify(APIError.http(status: 413, problem: nil)) == .fail)
        #expect(OutboxPolicy.classify(APIError.http(status: 404, problem: nil)) == .retry)
        #expect(OutboxPolicy.classify(APIError.http(status: 429, problem: nil)) == .retry)
        #expect(OutboxPolicy.classify(APIError.transport(URLError(.notConnectedToInternet))) == .retry)
        #expect(OutboxPolicy.backoff(attempts: 1) == 30)
        #expect(OutboxPolicy.backoff(attempts: 3) == 120)
        #expect(OutboxPolicy.backoff(attempts: 20) == 3600)
    }

    @Test("Offline queue: queued while failing, sent later, persisted across restarts")
    @MainActor
    func outbox() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("outbox-\(UUID()).json")
        let box = FeedbackOutbox(storage: FileOutboxStorage(url: url))
        _ = box.enqueue(req())
        let t0 = Date()
        let sent0 = await box.flush(now: t0) { _ in throw APIError.transport(URLError(.notConnectedToInternet)) }
        #expect(sent0 == 0)
        #expect(box.items[0].state == .queued)
        #expect(box.items[0].nextAttemptAt == t0.addingTimeInterval(30))
        // Not due yet → not attempted.
        let notDue = await box.flush(now: t0.addingTimeInterval(10)) { _ in Issue.record("should not send"); throw URLError(.unknown) }
        #expect(notDue == 0)
        // Restart: reload from storage.
        let reloaded = FeedbackOutbox(storage: FileOutboxStorage(url: url))
        #expect(reloaded.pendingCount == 1)
        let sent = await reloaded.flush(now: t0.addingTimeInterval(60)) { r in
            #expect(r.message == "Jalan ditutup")
            return FeedbackCreated(id: "fb1", createdAt: .now)
        }
        #expect(sent == 1)
        #expect(reloaded.items[0].state == .sent && reloaded.items[0].serverId == "fb1")
        reloaded.applyServerStatuses([FeedbackSummary(id: "fb1", category: .mapData, message: "", status: .planned, createdAt: .now)])
        #expect(reloaded.items[0].serverStatus == .planned)
    }

    @Test("Validation errors are not retried")
    @MainActor
    func permanentFailure() async {
        let box = FeedbackOutbox(storage: FileOutboxStorage(url: FileManager.default.temporaryDirectory.appendingPathComponent("ob-\(UUID()).json")))
        _ = box.enqueue(req())
        await box.flush { _ in throw APIError.http(status: 400, problem: nil) }
        #expect(box.items[0].state == .failed)
        #expect(box.pendingCount == 0)
    }

    @Test("Screenshots are compressed to ≤ 1.5 MB")
    func screenshotSize() {
        let big = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 6000)).image { ctx in
            for i in 0..<400 {
                UIColor(hue: CGFloat(i % 37) / 37, saturation: 1, brightness: 1, alpha: 1).setFill()
                ctx.fill(CGRect(x: CGFloat(i * 7 % 3000), y: CGFloat(i * 15), width: 300, height: 15))
            }
        }
        let data = FeedbackAttachments.compress(big)
        #expect(data != nil)
        #expect((data?.count ?? .max) <= FeedbackRules.maxScreenshotBytes)
    }

    @Test("Feedback request encodes the contract shape")
    func encoding() throws {
        let device = DeviceInfo(appVersion: "0.1.0", appBuild: 1, osVersion: "iOS 26.1", deviceModel: "iPhone17,3 (iPhone 16)",
                                screen: .init(widthPx: 1179, heightPx: 2556, density: 3, widthDp: 393, heightDp: 852),
                                locale: "ms", theme: "light", dataMode: "live")
        let r = FeedbackRequest(category: .mapData, message: "x", rating: nil, screen: .routePreview,
                                device: device, location: CoarseLocation(LatLng(lat: 3.152613, lng: 101.703718)),
                                contact: ContactDetails(email: "ali@example.com", whatsapp: "+60123456789", consent: true),
                                routeId: "r1", placeId: nil, screenshot: nil)
        let obj = try JSONSerialization.jsonObject(with: JSON.makeEncoder().encode(r)) as! [String: Any]
        #expect(obj["category"] as? String == "map_data")
        #expect(obj["screen"] as? String == "route_preview")
        let d = try #require(obj["device"] as? [String: Any])
        #expect(d["platform"] as? String == "ios" && d["appBuild"] as? Int == 1 && d["osVersion"] as? String == "iOS 26.1")
        let screen = try #require(d["screen"] as? [String: Any])
        #expect(screen["widthPx"] as? Int == 1179 && screen["density"] as? Double == 3 && screen["widthDp"] as? Int == 393)
        let loc = try #require(obj["location"] as? [String: Any])
        #expect(loc["lat"] as? Double == 3.15 && loc["lng"] as? Double == 101.7)
        #expect((obj["contact"] as? [String: Any])?["consent"] as? Bool == true)
        #expect(obj["context"] == nil)
    }
}
