import Foundation

// MARK: - Road and place complaints (docs/complaints.md v1; backend contract docs/api.md "Issues")

enum IssueKind: String, Codable, Sendable, CaseIterable {
    case roadClosedTemp = "road_closed_temp"
    case roadNotExist = "road_not_exist"
    case onewayWrongClosedDir = "oneway_wrong_closed_dir"
    case onewayWrongOpenDir = "oneway_wrong_open_dir"
    case turnNotAllowed = "turn_not_allowed"
    case oddRoute = "odd_route"
    case newRoad = "new_road"
    case placeClosedPerm = "place_closed_perm"
    case placeClosedTemp = "place_closed_temp"
    case placeHoursWrong = "place_hours_wrong"
    case placeEntranceWrong = "place_entrance_wrong"
    case placeInfoWrong = "place_info_wrong"
    case placeDuplicate = "place_duplicate"
    case unknown

    init(from decoder: any Decoder) throws {
        self = IssueKind(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
    }

    var isRoad: Bool { [.roadClosedTemp, .roadNotExist, .onewayWrongClosedDir, .onewayWrongOpenDir, .turnNotAllowed].contains(self) }
}

enum IssueState: String, Codable, Sendable {
    case reported, suspected, confirmed, refuted, expired, resolved, unknown
    init(from decoder: any Decoder) throws {
        self = IssueState(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
    }
    var isOpen: Bool { self == .reported || self == .suspected || self == .confirmed }
}

/// Subtype of a temporary closure.
enum ClosureSubtype: String, Codable, Sendable, CaseIterable {
    case accident, flood, event, roadworks
}

/// "How long?" chips for a closure (sent as durationHint).
enum ClosureDuration: String, Codable, Sendable, CaseIterable {
    case unknown, today, days, longWorks = "long_works"
}

struct Issue: Codable, Sendable, Hashable, Identifiable {
    var id: String
    var kind: IssueKind
    var subtype: String?
    var state: IssueState
    var lat: Double
    var lng: Double
    var placeId: String?
    var roadName: String?
    var summary: String?
    /// Weighted confirmations so far (e.g. 2 of 3 on major roads).
    var confirmations: Double?
    var required: Double?
    var createdAt: Date?
    var expiresAt: Date?
    var lastEvidenceAt: Date?
    /// place_closed_temp: closed until this day.
    var until: Date?
    /// The server sends a temporary closure's day in `payload.until` (yyyy-MM-dd); other payload keys are ignored here.
    var payload: IssuePayload?
    /// Snapped road segment (the server sends the street name here, not in `roadName`).
    var segment: IssueSegment?
    var location: LatLng { LatLng(lat: lat, lng: lng) }

    /// Street for prompts: `roadName`, else the snapped segment's name.
    var streetName: String? { roadName ?? segment?.name }

    /// "Closed until" for a place_closed_temp badge: `until`, else the payload's Malaysian day.
    var closedUntil: Date? { until ?? payload?.until.flatMap(IssuePayload.day) }
}

struct IssueSegment: Codable, Sendable, Hashable {
    var name: String?
}

struct IssuePayload: Codable, Sendable, Hashable {
    var until: String?

    /// Noon of a yyyy-MM-dd day in Malaysia, so formatting never shifts it to the day before.
    static func day(_ s: String) -> Date? {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Asia/Kuala_Lumpur")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.date(from: s + " 12:00")
    }
}

struct NewIssue: Codable, Sendable, Hashable {
    enum Source: String, Codable, Sendable { case report, deviation, placeEdit = "place_edit" }
    var kind: IssueKind
    var subtype: String?
    var lat: Double
    var lng: Double
    var heading: Double?
    var placeId: String?
    var vehicle: Vehicle?
    var durationHint: ClosureDuration?
    var source: Source
    var payload: [String: String]?
}

enum IssueVoteAnswer: String, Codable, Sendable { case still, cleared, unsure }

struct IssueVote: Codable, Sendable, Hashable { var answer: IssueVoteAnswer }

/// `routes[].issues`: issues on, or avoided by, a route.
struct RouteIssue: Codable, Sendable, Hashable, Identifiable {
    var id: String
    var kind: IssueKind
    var state: IssueState
    var summary: String?
    var roadName: String?
    var subtype: String?
    /// true: this route goes around it; false/nil: the route passes it (warn).
    var avoided: Bool?
    var confirmations: Double?
    var createdAt: Date?
}

// MARK: - On-phone deviation ("Why didn't you follow the route?")

/// Where the driver left the suggested route at a decision point (kept only on the phone until answered).
struct DeviationEvent: Codable, Sendable, Hashable, Identifiable {
    var id = UUID()
    var lat: Double
    var lng: Double
    var heading: Double?
    /// The road the route wanted them to take.
    var roadName: String?
    var at: Date
    var vehicle: Vehicle
}

enum DeviationReason: String, CaseIterable, Sendable {
    case closed, noRoad, oneWay, noTurn, jam, myChoice

    /// What gets sent (nil: nothing: a jam or the driver's own choice isn't a map problem).
    var issueKind: IssueKind? {
        switch self {
        case .closed: .roadClosedTemp
        case .noRoad: .roadNotExist
        case .oneWay: .onewayWrongClosedDir
        case .noTurn: .turnNotAllowed
        case .jam, .myChoice: nil
        }
    }
}

/// Never while driving; at most once a day; never again after "Don't ask again".
struct DeviationAskPolicy {
    static let minInterval: TimeInterval = 24 * 3600
    static func shouldAsk(lastAsked: Date?, optedOut: Bool, speedKmh: Double, navigating: Bool, now: Date) -> Bool {
        if optedOut || navigating || speedKmh >= 5 { return false }
        if let lastAsked, now.timeIntervalSince(lastAsked) < minInterval { return false }
        return true
    }
}

/// "Masih ditutup?" when within 300 m of an open road issue (once per issue per trip).
enum StillClosedPolicy {
    static let radiusM = 300.0
    static func nearest(_ issues: [Issue], to p: LatLng, asked: Set<String>) -> Issue? {
        issues.filter { $0.kind.isRoad && $0.state.isOpen && !asked.contains($0.id) && Geo.distance($0.location, p) <= radiusM }
            .min { Geo.distance($0.location, p) < Geo.distance($1.location, p) }
    }
}

extension DeviationEvent {
    var location: LatLng { LatLng(lat: lat, lng: lng) }
}

extension BBox {
    /// Grown by `m` metres on every side.
    func expanded(byM m: Double) -> BBox {
        let dLat = m / 111_320
        let dLng = m / (111_320 * max(0.2, cos(((minLat + maxLat) / 2) * .pi / 180)))
        return BBox(minLng: minLng - dLng, minLat: minLat - dLat, maxLng: maxLng + dLng, maxLat: maxLat + dLat)
    }
}

extension IssueKind: Identifiable { var id: String { rawValue } }
