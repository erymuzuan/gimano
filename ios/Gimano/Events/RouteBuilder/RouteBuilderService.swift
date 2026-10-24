import Foundation

// Routing for "Bina laluan" (docs/events.md §6, docs/api.md "POST /routes" + "POST /routes/match").
// Each leg is its own small, stateless request: the two ends' coordinates (or the drawn line) and the travel mode.
// Never a stop label, event id, alias or note. `"store": false` asks the server to keep nothing; servers that don't
// know the flag yet ignore it.

struct BuilderLatLng: Codable, Sendable, Equatable {
    var lat: Double
    var lng: Double
    init(_ p: LatLng) { lat = RouteGeo.round6(p.lat); lng = RouteGeo.round6(p.lng) }
}

struct BuilderRouteRequest: Encodable, Sendable, Equatable {
    var origin: BuilderLatLng
    var destination: BuilderLatLng
    var waypoints: [BuilderLatLng] = []
    var vehicle: String
    var store = false
}

struct BuilderRoutesResponse: Decodable, Sendable {
    struct Route: Decodable, Sendable {
        var polyline: String
        var distanceM: Double
        var durationS: Double?
    }
    var routes: [Route]
}

struct BuilderMatchRequest: Encodable, Sendable, Equatable {
    var points: [BuilderLatLng]
    var vehicle: String
}

struct BuilderMatchResponse: Decodable, Sendable {
    struct Range: Decodable, Sendable {
        var fromIndex: Int
        var toIndex: Int
    }
    var polyline: String
    var distanceM: Double
    var durationS: Double?
    var unmatched: [Range]?
}

/// The two calls the builder makes. `LiveRepository` sends them with the app's API client; tests mock it.
protocol RouteBuilderTransport: Sendable {
    func builderRoute(_ request: BuilderRouteRequest) async throws -> BuilderRoutesResponse
    func builderMatch(_ request: BuilderMatchRequest) async throws -> BuilderMatchResponse
}

enum RouteBuilderService {
    /// `POST /routes/match` takes 2–5000 points.
    static let maxMatchPoints = 5000

    /// Calculates one leg: a drawn line is matched to roads, otherwise the two ends are routed.
    static func resolve(from: LatLng, to: LatLng, drawn: [LatLng]?, mode: EventTravelMode,
                        transport: (any RouteBuilderTransport)?) async -> LegResolution {
        guard let transport else { return .failed(.offline) }
        if let drawn, drawn.count >= 2 {
            let line = RouteGeo.thin(drawn, max: maxMatchPoints)
            do {
                let r = try await transport.builderMatch(BuilderMatchRequest(points: line.map(BuilderLatLng.init), vehicle: mode.rawValue))
                return matched(r, pointCount: line.count)
            } catch {
                return .failed(problem(error, matching: true))
            }
        }
        do {
            let r = try await transport.builderRoute(BuilderRouteRequest(origin: BuilderLatLng(from), destination: BuilderLatLng(to),
                                                                         vehicle: mode.rawValue))
            guard let first = r.routes.first else { return .failed(.noRoute) }
            let g = Polyline.decode(first.polyline)
            guard g.count >= 2 else { return .failed(.failed) }
            return .routed(geometry: g, distanceM: max(0, first.distanceM.isFinite ? first.distanceM : 0),
                           durationS: first.durationS.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil })
        } catch {
            return .failed(problem(error, matching: false))
        }
    }

    /// A match answer: ranges are clamped to the points sent; nothing matched at all reads as "no road here".
    static func matched(_ r: BuilderMatchResponse, pointCount: Int) -> LegResolution {
        let g = Polyline.decode(r.polyline)
        let ranges = (r.unmatched ?? []).compactMap { u -> UnmatchedRange? in
            let a = max(0, min(u.fromIndex, u.toIndex)), b = min(pointCount - 1, max(u.fromIndex, u.toIndex))
            return a <= b ? UnmatchedRange(from: a, to: b) : nil
        }
        guard g.count >= 2 else { return .failed(.noRoute) }
        return .matched(geometry: g, distanceM: max(0, r.distanceM.isFinite ? r.distanceM : 0),
                        durationS: r.durationS.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }, unmatched: ranges)
    }

    /// Desktop `status_error`, plus: a server without `/routes/match` (404/405) means drawing to roads isn't available yet.
    static func problem(_ error: any Error, matching: Bool) -> LegProblem {
        guard let e = error as? APIError else { return (error as? URLError) != nil ? .offline : .failed }
        switch e {
        case .transport: return .offline
        case .http(let status, _):
            switch status {
            case 404, 405, 501: return matching ? .matchUnavailable : .failed
            case 422: return .noRoute
            case 503: return .unavailable
            default: return .failed
            }
        case .conflict, .decoding, .notAvailable: return .failed
        }
    }
}
