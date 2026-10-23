import Foundation
import CoreLocation

// MARK: - Geometry

struct LatLng: Codable, Hashable, Sendable {
    var lat: Double
    var lng: Double

    init(lat: Double, lng: Double) {
        self.lat = lat
        self.lng = lng
    }

    init(_ c: CLLocationCoordinate2D) {
        self.lat = c.latitude
        self.lng = c.longitude
    }

    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lng) }
}

// MARK: - Auth & profile

struct DeviceAuthRequest: Codable, Sendable {
    var deviceId: String
    var platform: String = "ios"
    var locale: String
}

struct DeviceAuthResponse: Codable, Sendable {
    var token: String
    var userId: String
    var displayName: String
}

enum Vehicle: String, Codable, Sendable, CaseIterable, Identifiable {
    case car
    case motorcycle
    var id: String { rawValue }
}

struct User: Codable, Sendable, Equatable {
    var id: String
    var displayName: String
    var mood: String?
    var vehicle: Vehicle?
    var points: Int?
    var level: Int?
    var joinedAt: Date?
}

struct UserPatch: Codable, Sendable {
    var displayName: String?
    var mood: String?
    var vehicle: Vehicle?
    var locale: String?
}

// MARK: - Saved places

enum SavedPlaceKind: String, Codable, Sendable {
    case home, work, favorite
}

struct SavedPlace: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var kind: SavedPlaceKind
    var name: String
    var lat: Double
    var lng: Double
    var address: String
    var placeId: String?

    var location: LatLng { LatLng(lat: lat, lng: lng) }
}

struct SavedPlaceInput: Codable, Sendable {
    var name: String
    var lat: Double
    var lng: Double
    var address: String
    var placeId: String?
}

struct Recent: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var name: String
    var address: String
    var lat: Double
    var lng: Double
    var placeId: String?
    var lastUsedAt: Date?

    var location: LatLng { LatLng(lat: lat, lng: lng) }
}

// MARK: - Search & places

enum PlaceCategory: String, Codable, Sendable, CaseIterable {
    case fuel
    case evCharging = "ev_charging"
    case restArea = "rest_area"
    case tollBooth = "toll_booth"
    case parking, restaurant, cafe
    case fastFood = "fast_food"
    case mall, supermarket, convenience, mosque, surau, temple, church, hospital, clinic
    case pharmacy, police, school, university, hotel, atm, bank
    case carRepair = "car_repair"
    case carWash = "car_wash"
    case tourism
    case transitStation = "transit_station"
    case airport, government, other

    init(from decoder: any Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = PlaceCategory(rawValue: raw) ?? .other
    }
}

struct PlaceSummary: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var name: String
    var category: PlaceCategory
    var address: String
    var lat: Double
    var lng: Double
    var distanceM: Double?
    var brand: String?

    var location: LatLng { LatLng(lat: lat, lng: lng) }
}

struct SearchResponse: Codable, Sendable {
    var results: [PlaceSummary]
}

struct Place: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var name: String
    var category: PlaceCategory
    var address: String
    var lat: Double
    var lng: Double
    var distanceM: Double?
    var brand: String?
    var phone: String?
    var website: String?
    var openingHours: String?
    var openNow: Bool?
    var amenities: [String]?
    var tags: [String: String]?
    var osmType: String?
    var osmId: Int64?

    var summary: PlaceSummary {
        PlaceSummary(id: id, name: name, category: category, address: address, lat: lat, lng: lng, distanceM: distanceM, brand: brand)
    }
}

struct ReverseGeocode: Codable, Sendable {
    var name: String
    var address: String
    var lat: Double
    var lng: Double
}

/// What the user picked as a destination, regardless of origin (search, saved, recent, map).
struct Destination: Hashable, Sendable {
    var name: String
    var address: String
    var location: LatLng
    var placeId: String?
    var category: PlaceCategory = .other
}

// MARK: - Routing

struct AvoidOptions: Codable, Sendable, Hashable {
    var tolls = false
    var highways = false
    var ferries = false
}

struct RouteEndpoint: Codable, Sendable, Hashable {
    var lat: Double
    var lng: Double
    var placeId: String?
}

struct RouteRequest: Codable, Sendable, Hashable {
    var origin: RouteEndpoint
    var destination: RouteEndpoint
    var waypoints: [RouteEndpoint] = []
    var vehicle: Vehicle = .car
    var avoid = AvoidOptions()
    var departAt: Date?
    var alternatives = 2

    enum CodingKeys: String, CodingKey { case origin, destination, waypoints, vehicle, avoid, departAt, alternatives }

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(origin, forKey: .origin)
        try c.encode(destination, forKey: .destination)
        try c.encode(waypoints, forKey: .waypoints)
        try c.encode(vehicle, forKey: .vehicle)
        try c.encode(avoid, forKey: .avoid)
        // Contract shows explicit null.
        if let departAt { try c.encode(departAt, forKey: .departAt) } else { try c.encodeNil(forKey: .departAt) }
        try c.encode(alternatives, forKey: .alternatives)
    }
}

struct RoutesResponse: Codable, Sendable {
    var routes: [Route]
}

enum ManeuverType: String, Codable, Sendable, CaseIterable {
    case depart, arrive, straight
    case slightLeft = "slight_left"
    case left
    case sharpLeft = "sharp_left"
    case slightRight = "slight_right"
    case right
    case sharpRight = "sharp_right"
    case uturnLeft = "uturn_left"
    case uturnRight = "uturn_right"
    case roundaboutEnter = "roundabout_enter"
    case roundaboutExit = "roundabout_exit"
    case merge
    case rampLeft = "ramp_left"
    case rampRight = "ramp_right"
    case exitLeft = "exit_left"
    case exitRight = "exit_right"
    case keepLeft = "keep_left"
    case keepRight = "keep_right"
    case ferry

    init(from decoder: any Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = ManeuverType(rawValue: raw) ?? .straight
    }
}

struct Maneuver: Codable, Sendable, Hashable {
    var type: ManeuverType
    var instruction: String
    var streetName: String?
    var exitNumber: String?
    var roadRef: String?
    var distanceM: Double
    var durationS: Double
    var beginShapeIndex: Int
    var endShapeIndex: Int
    var verbalPre: String?
    var verbalPost: String?
}

struct RouteLeg: Codable, Sendable, Hashable {
    var maneuvers: [Maneuver]
}

struct TrafficSegment: Codable, Sendable, Hashable {
    var fromIndex: Int
    var toIndex: Int
    var level: Int
}

struct Route: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var label: String
    var durationS: Double
    var distanceM: Double
    var trafficDurationS: Double?
    var summary: String
    var trafficDescription: String?
    var hasTolls: Bool
    var tollEstimateMyr: Double?
    var polyline: String
    var bbox: [Double]
    var legs: [RouteLeg]
    var trafficSegments: [TrafficSegment]?
    var alerts: [AlertOnRoute]?
    /// Client-side only (demo mode): explains that a captured route was substituted. Not part of the API contract.
    var demoNote: String?

    var isBest: Bool { label == "best" }
    /// Duration including live traffic when available.
    var effectiveDurationS: Double { trafficDurationS ?? durationS }
    var maneuvers: [Maneuver] { legs.flatMap(\.maneuvers) }
}

struct RerouteRequest: Codable, Sendable {
    var lat: Double
    var lng: Double
    var heading: Double
}

struct DepartureSlot: Codable, Sendable, Hashable, Identifiable {
    var departAt: Date
    var durationS: Double
    var id: Date { departAt }
}

struct DepartureTimesResponse: Codable, Sendable {
    var slots: [DepartureSlot]
}

// MARK: - Live map

enum ReportType: String, Codable, Sendable, CaseIterable, Identifiable {
    case traffic, police, accident, hazard, closure
    case blockedLane = "blocked_lane"
    case mapIssue = "map_issue"
    case weather, animal
    var id: String { rawValue }

    init(from decoder: any Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = ReportType(rawValue: raw) ?? .hazard
    }
}

struct ReportAuthor: Codable, Sendable, Hashable {
    var displayName: String
    var mood: String?
}

struct Report: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var type: ReportType
    var subtype: String?
    var lat: Double
    var lng: Double
    var heading: Double?
    var street: String?
    var reportedBy: ReportAuthor?
    var createdAt: Date
    var expiresAt: Date
    var confirmations: Int
    var thanks: Int

    var location: LatLng { LatLng(lat: lat, lng: lng) }
}

/// `AlertOnRoute = Report + {distanceAlongRouteM}` — decoded flat.
struct AlertOnRoute: Codable, Sendable, Hashable, Identifiable {
    var report: Report
    var distanceAlongRouteM: Double
    var id: String { report.id }

    init(report: Report, distanceAlongRouteM: Double) {
        self.report = report
        self.distanceAlongRouteM = distanceAlongRouteM
    }

    private enum K: String, CodingKey { case distanceAlongRouteM }

    init(from decoder: any Decoder) throws {
        report = try Report(from: decoder)
        distanceAlongRouteM = try decoder.container(keyedBy: K.self).decode(Double.self, forKey: .distanceAlongRouteM)
    }

    func encode(to encoder: any Encoder) throws {
        try report.encode(to: encoder)
        var c = encoder.container(keyedBy: K.self)
        try c.encode(distanceAlongRouteM, forKey: .distanceAlongRouteM)
    }
}

struct Jam: Codable, Sendable, Identifiable, Hashable {
    var id: String
    var level: Int
    var speedKmh: Double
    var delayS: Double
    var lengthM: Double
    var polyline: String
    var street: String?
    var updatedAt: Date
}

struct Driver: Codable, Sendable, Hashable {
    var lat: Double
    var lng: Double
    var heading: Double
    var mood: String?
}

struct LiveResponse: Codable, Sendable {
    var alerts: [Report]
    var jams: [Jam]
    var drivers: [Driver]
    var serverTime: Date?
}

struct NewReport: Codable, Sendable {
    var type: ReportType
    var subtype: String?
    var lat: Double
    var lng: Double
    var heading: Double?
    var comment: String?
}

struct VoteRequest: Codable, Sendable {
    var stillThere: Bool
}

// MARK: - Location pings

struct LocationPoint: Codable, Sendable, Hashable {
    var lat: Double
    var lng: Double
    var speedKmh: Double
    var heading: Double
    var accuracyM: Double
    var ts: Date
}

struct LocationBatch: Codable, Sendable {
    var routeId: String?
    var points: [LocationPoint]
}

/// Bounding box `[minLng, minLat, maxLng, maxLat]`.
struct BBox: Hashable, Sendable {
    var minLng: Double
    var minLat: Double
    var maxLng: Double
    var maxLat: Double

    var queryValue: String {
        [minLng, minLat, maxLng, maxLat].map { String(format: "%.5f", $0) }.joined(separator: ",")
    }

    var array: [Double] { [minLng, minLat, maxLng, maxLat] }

    func contains(_ p: LatLng) -> Bool {
        p.lat >= minLat && p.lat <= maxLat && p.lng >= minLng && p.lng <= maxLng
    }

    init(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double) {
        self.minLng = minLng
        self.minLat = minLat
        self.maxLng = maxLng
        self.maxLat = maxLat
    }

    init?(array: [Double]) {
        guard array.count == 4 else { return nil }
        self.init(minLng: array[0], minLat: array[1], maxLng: array[2], maxLat: array[3])
    }

    static func around(_ points: [LatLng]) -> BBox? {
        guard let first = points.first else { return nil }
        var b = BBox(minLng: first.lng, minLat: first.lat, maxLng: first.lng, maxLat: first.lat)
        for p in points.dropFirst() {
            b.minLng = min(b.minLng, p.lng); b.maxLng = max(b.maxLng, p.lng)
            b.minLat = min(b.minLat, p.lat); b.maxLat = max(b.maxLat, p.lat)
        }
        return b
    }
}
