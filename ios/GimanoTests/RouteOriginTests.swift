import Foundation
import Testing
@testable import Gimano

/// Same cases as Android RouteOriginTest (a5b85f0).
@Suite("Route start: only a recent GPS fix is \"Your location\"")
struct RouteOriginTests {
    let klcc = LatLng(lat: 3.157938, lng: 101.712313)
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    @Test("No fix is none")
    func none() {
        #expect(RouteOrigin.classify(fix: nil, at: nil, now: now) == .none)
        #expect(RouteOrigin.classify(fix: klcc, at: nil, now: now) == .none)
        #expect(RouteOrigin.classify(fix: klcc, at: Date(timeIntervalSince1970: 0), now: now) == .none)
    }

    @Test("A fix up to 2 minutes old is fresh")
    func fresh() {
        #expect(RouteOrigin.classify(fix: klcc, at: now - 5, now: now) == .fresh(klcc))
        #expect(RouteOrigin.classify(fix: klcc, at: now - 120, now: now) == .fresh(klcc))
    }

    @Test("Older fixes are stale")
    func stale() {
        #expect(RouteOrigin.classify(fix: klcc, at: now - 121, now: now) == .stale(klcc, age: 121))
        if case .stale = RouteOrigin.classify(fix: klcc, at: now - 3600, now: now) {} else { Issue.record("1 h old must be stale") }
    }

    @Test("A fix from the future beyond clock skew isn't trusted")
    func future() {
        if case .stale = RouteOrigin.classify(fix: klcc, at: now + 600, now: now) {} else { Issue.record("10 min in the future") }
        #expect(RouteOrigin.classify(fix: klcc, at: now + 30, now: now) == .fresh(klcc))
    }

    @Test("Same spot is under 50 m")
    func sameSpot() {
        let suria40m = LatLng(lat: 3.158298, lng: 101.712313)
        let far = LatLng(lat: 3.158938, lng: 101.712313)
        #expect(RouteOrigin.sameSpot(klcc, klcc))
        #expect(RouteOrigin.sameSpot(klcc, suria40m))
        #expect(!RouteOrigin.sameSpot(klcc, far))
    }

    @Test("Only a GPS start can be navigated; unknown and locating have no point")
    func startStates() {
        #expect(RouteStart.gps(klcc).isGPS && RouteStart.gps(klcc).point == klcc)
        let chosen = RouteStart.chosen(name: "Rumah", location: klcc, placeId: nil)
        #expect(!chosen.isGPS && chosen.point == klcc && chosen.chosenName == "Rumah")
        #expect(RouteStart.unknown.point == nil && RouteStart.locating.point == nil)
        #expect(RouteOrigin.waitForFix == 8 && RouteOrigin.maxFixAge == 120)
    }
}
