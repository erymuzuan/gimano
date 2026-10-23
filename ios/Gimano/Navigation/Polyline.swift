import Foundation

/// Google encoded polyline codec. Gimano/Valhalla use precision 6.
enum Polyline {
    static func decode(_ encoded: String, precision: Int = 6) -> [LatLng] {
        let factor = pow(10.0, Double(precision))
        let bytes = Array(encoded.utf8)
        var index = 0
        var lat = 0
        var lng = 0
        var result: [LatLng] = []
        result.reserveCapacity(bytes.count / 4)

        func next() -> Int? {
            var shift = 0
            var value = 0
            while index < bytes.count {
                let b = Int(bytes[index]) - 63
                index += 1
                value |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20 {
                    return (value & 1) != 0 ? ~(value >> 1) : (value >> 1)
                }
            }
            return nil
        }

        while index < bytes.count {
            guard let dLat = next(), let dLng = next() else { break }
            lat += dLat
            lng += dLng
            result.append(LatLng(lat: Double(lat) / factor, lng: Double(lng) / factor))
        }
        return result
    }

    static func encode(_ points: [LatLng], precision: Int = 6) -> String {
        let factor = pow(10.0, Double(precision))
        var out = [UInt8]()
        var prevLat = 0
        var prevLng = 0

        func append(_ v: Int) {
            var value = v < 0 ? ~(v << 1) : (v << 1)
            while value >= 0x20 {
                out.append(UInt8((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            }
            out.append(UInt8(value + 63))
        }

        for p in points {
            let lat = Int((p.lat * factor).rounded())
            let lng = Int((p.lng * factor).rounded())
            append(lat - prevLat)
            append(lng - prevLng)
            prevLat = lat
            prevLng = lng
        }
        return String(decoding: out, as: UTF8.self)
    }
}
