import Foundation
import Testing
@testable import Gimano

@Suite("Polyline precision-6")
struct PolylineTests {
    @Test("Decodes a known precision-6 polyline (Valhalla format)")
    func decodesKnownValue() {
        // Google's canonical example points, encoded at precision 6.
        let points = [LatLng(lat: 38.5, lng: -120.2), LatLng(lat: 40.7, lng: -120.95), LatLng(lat: 43.252, lng: -126.453)]
        let encoded = "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"
        let decoded = Polyline.decode(encoded)
        #expect(decoded.count == 3)
        for (a, b) in zip(decoded, points) {
            #expect(abs(a.lat - b.lat) < 1e-6)
            #expect(abs(a.lng - b.lng) < 1e-6)
        }
        #expect(Polyline.encode(points) == encoded)
    }

    @Test("Precision 5 vs 6 differ by a factor of 10")
    func precisionMatters() {
        let encoded = Polyline.encode([LatLng(lat: 3.1579, lng: 101.7123)], precision: 5)
        let wrong = Polyline.decode(encoded, precision: 6)
        #expect(abs(wrong[0].lat - 0.31579) < 1e-6)
    }

    @Test("Round-trips Kuala Lumpur coordinates including negative deltas", arguments: [
        [LatLng(lat: 3.139003, lng: 101.686855), LatLng(lat: 3.157764, lng: 101.711861), LatLng(lat: 3.149123, lng: 101.713346)],
        [LatLng(lat: 2.745600, lng: 101.707200), LatLng(lat: -1.5, lng: -0.000001), LatLng(lat: 0, lng: 0)],
    ])
    func roundTrip(points: [LatLng]) {
        let decoded = Polyline.decode(Polyline.encode(points))
        #expect(decoded.count == points.count)
        for (a, b) in zip(decoded, points) {
            #expect(abs(a.lat - b.lat) < 1e-6)
            #expect(abs(a.lng - b.lng) < 1e-6)
        }
    }

    @Test("Empty and truncated input are handled")
    func edgeCases() {
        #expect(Polyline.decode("").isEmpty)
        // A truncated string must not crash; it yields only the complete pairs.
        let full = Polyline.encode([LatLng(lat: 3.1, lng: 101.6), LatLng(lat: 3.2, lng: 101.7)])
        let truncated = String(full.dropLast(3))
        #expect(Polyline.decode(truncated).count == 1)
    }
}

@Suite("Geo")
struct GeoTests {
    @Test("Haversine distance KLCC → KL Sentral ≈ 3.5 km")
    func distance() {
        let d = Geo.distance(LatLng(lat: 3.1579, lng: 101.7123), LatLng(lat: 3.1340, lng: 101.6862))
        #expect(d > 3_800 && d < 4_000)
    }

    @Test("Bearing north and east")
    func bearing() {
        let o = LatLng(lat: 3, lng: 101)
        #expect(abs(Geo.bearing(o, LatLng(lat: 3.01, lng: 101)) - 0) < 0.5)
        #expect(abs(Geo.bearing(o, LatLng(lat: 3, lng: 101.01)) - 90) < 0.5)
    }

    @Test("Projection onto a segment clamps and measures perpendicular distance")
    func projection() {
        let a = LatLng(lat: 3, lng: 101), b = LatLng(lat: 3, lng: 101.01)
        let p = Geo.offset(Geo.interpolate(a, b, 0.5), northM: 30, eastM: 0)
        let r = Geo.project(p, onto: a, b)
        #expect(abs(r.t - 0.5) < 0.01)
        #expect(abs(r.distanceM - 30) < 1)
        let before = Geo.offset(a, northM: 0, eastM: -100)
        #expect(Geo.project(before, onto: a, b).t == 0)
    }
}
