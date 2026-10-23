import Foundation

enum Geo {
    static let earthRadiusM = 6_371_008.8

    static func distance(_ a: LatLng, _ b: LatLng) -> Double {
        let dLat = (b.lat - a.lat).radians
        let dLng = (b.lng - a.lng).radians
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(a.lat.radians) * cos(b.lat.radians) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * earthRadiusM * asin(min(1, sqrt(h)))
    }

    /// Initial bearing from a to b, degrees 0..<360.
    static func bearing(_ a: LatLng, _ b: LatLng) -> Double {
        let dLng = (b.lng - a.lng).radians
        let y = sin(dLng) * cos(b.lat.radians)
        let x = cos(a.lat.radians) * sin(b.lat.radians) - sin(a.lat.radians) * cos(b.lat.radians) * cos(dLng)
        return (atan2(y, x).degrees + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Signed smallest difference b - a in degrees, range (-180, 180].
    static func angleDelta(_ a: Double, _ b: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d <= -180 { d += 360 }
        return d
    }

    static func interpolate(_ a: LatLng, _ b: LatLng, _ t: Double) -> LatLng {
        LatLng(lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t)
    }

    /// Projects p onto segment ab using a local equirectangular approximation (accurate for short segments).
    /// Returns the clamped fraction t along ab and the distance from p to the projection in metres.
    static func project(_ p: LatLng, onto a: LatLng, _ b: LatLng) -> (t: Double, distanceM: Double, point: LatLng) {
        let k = cos(((a.lat + b.lat) / 2).radians)
        let ax = a.lng * k, ay = a.lat
        let bx = b.lng * k, by = b.lat
        let px = p.lng * k, py = p.lat
        let dx = bx - ax, dy = by - ay
        let len2 = dx * dx + dy * dy
        var t = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0
        t = min(1, max(0, t))
        let q = interpolate(a, b, t)
        return (t, distance(p, q), q)
    }

    /// Cumulative distance (m) at each vertex.
    static func cumulativeDistances(_ points: [LatLng]) -> [Double] {
        guard !points.isEmpty else { return [] }
        var out = [Double](repeating: 0, count: points.count)
        for i in 1..<points.count {
            out[i] = out[i - 1] + distance(points[i - 1], points[i])
        }
        return out
    }

    /// Point at a given distance along a polyline.
    static func point(along points: [LatLng], cumulative: [Double], distance d: Double) -> (point: LatLng, segment: Int) {
        guard points.count > 1, let total = cumulative.last else { return (points.first ?? LatLng(lat: 0, lng: 0), 0) }
        let target = min(max(0, d), total)
        var lo = 0, hi = cumulative.count - 1
        while lo < hi - 1 {
            let mid = (lo + hi) / 2
            if cumulative[mid] <= target { lo = mid } else { hi = mid }
        }
        let segLen = cumulative[hi] - cumulative[lo]
        let t = segLen > 0 ? (target - cumulative[lo]) / segLen : 0
        return (interpolate(points[lo], points[hi], t), lo)
    }

    static func offset(_ p: LatLng, northM: Double, eastM: Double) -> LatLng {
        let dLat = northM / earthRadiusM
        let dLng = eastM / (earthRadiusM * cos(p.lat.radians))
        return LatLng(lat: p.lat + dLat.degrees, lng: p.lng + dLng.degrees)
    }
}

extension Double {
    var radians: Double { self * .pi / 180 }
    var degrees: Double { self * 180 / .pi }
}
