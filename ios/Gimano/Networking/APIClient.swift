import Foundation

struct ProblemDetails: Codable, Sendable {
    var type: String?
    var title: String?
    var status: Int?
    var detail: String?
}

enum APIError: Error, LocalizedError, Sendable {
    case transport(URLError)
    case http(status: Int, problem: ProblemDetails?)
    case decoding(String)
    case notAvailable

    var errorDescription: String? {
        switch self {
        case .transport(let e): e.localizedDescription
        case .http(let status, let p): p?.detail ?? p?.title ?? "HTTP \(status)"
        case .decoding(let s): "Unexpected response: \(s)"
        case .notAvailable: "Not available"
        }
    }

    /// The backend can't be reached at all (vs. it answered with an error).
    var isUnreachable: Bool {
        guard case .transport(let e) = self else { return false }
        switch e.code {
        case .cannotConnectToHost, .cannotFindHost, .timedOut, .networkConnectionLost, .notConnectedToInternet,
             .dnsLookupFailed, .secureConnectionFailed, .appTransportSecurityRequiresSecureConnection,
             .resourceUnavailable, .badServerResponse, .cannotLoadFromNetwork, .internationalRoamingOff, .dataNotAllowed:
            return true
        default:
            return false
        }
    }

    /// Endpoint missing or server-side failure: worth serving demo data for this call in auto mode.
    var isServerSideGap: Bool {
        if case .http(let status, _) = self { return status == 404 || status == 405 || status == 501 || status >= 502 }
        return false
    }
}

enum JSON {
    static func makeDecoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let c = try decoder.singleValueContainer()
            let s = try c.decode(String.self)
            if let date = parseDate(s) { return date }
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "Bad date \(s)")
        }
        return d
    }

    static func makeEncoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }

    /// ISO-8601 with or without fractional seconds (any number of digits, .NET emits 7), with or without zone.
    static func parseDate(_ raw: String) -> Date? {
        var s = raw
        if let dot = s.firstIndex(of: ".") {
            // Normalise fractional seconds to 3 digits.
            var end = s.index(after: dot)
            while end < s.endIndex, s[end].isNumber { end = s.index(after: end) }
            let digits = s[s.index(after: dot)..<end]
            let ms = String((digits + "000").prefix(3))
            s = String(s[..<dot]) + "." + ms + String(s[end...])
        }
        let hasZone = s.hasSuffix("Z") || s.range(of: #"[+-]\d{2}:?\d{2}$"#, options: .regularExpression) != nil
        if !hasZone { s += "Z" }
        if let d = try? Date(s, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) { return d }
        if let d = try? Date(s, strategy: Date.ISO8601FormatStyle()) { return d }
        return nil
    }
}

/// Thin async JSON client for the Gimano REST API.
actor APIClient {
    let baseURL: URL
    private(set) var token: String?
    private let session: URLSession
    private let decoder = JSON.makeDecoder()
    private let encoder = JSON.makeEncoder()

    init(baseURL: URL, token: String?, timeout: TimeInterval = 8) {
        self.baseURL = baseURL
        self.token = token
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout * 2
        cfg.waitsForConnectivity = false
        self.session = URLSession(configuration: cfg)
    }

    func setToken(_ token: String?) { self.token = token }

    func url(_ path: String, query: [URLQueryItem] = []) -> URL {
        let relative = path.hasPrefix("/") ? String(path.dropFirst()) : path
        var c = URLComponents(url: baseURL.appendingPathComponent(relative), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { c.queryItems = query }
        return c.url!
    }

    func get<T: Decodable & Sendable>(_ path: String, query: [URLQueryItem] = [], as: T.Type = T.self) async throws -> T {
        let data = try await perform(method: "GET", path: path, query: query, body: Optional<Data>.none)
        return try decode(data)
    }

    func send<T: Decodable & Sendable>(_ method: String, _ path: String, body: (some Encodable & Sendable)?, as: T.Type = T.self) async throws -> T {
        let payload = try body.map { try encoder.encode($0) }
        let data = try await perform(method: method, path: path, query: [], body: payload)
        return try decode(data)
    }

    func sendNoContent(_ method: String, _ path: String, body: (some Encodable & Sendable)?) async throws {
        let payload = try body.map { try encoder.encode($0) }
        _ = try await perform(method: method, path: path, query: [], body: payload)
    }

    func ping(_ path: String) async -> Bool {
        (try? await perform(method: "GET", path: path, query: [], body: nil)) != nil
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decoding(String(describing: error))
        }
    }

    private func perform(method: String, path: String, query: [URLQueryItem], body: Data?) async throws -> Data {
        var req = URLRequest(url: url(path, query: query))
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        req.setValue(Locale.preferredLanguages.first ?? "en", forHTTPHeaderField: "Accept-Language")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch let e as URLError {
            throw APIError.transport(e)
        }
        guard let http = response as? HTTPURLResponse else { throw APIError.transport(URLError(.badServerResponse)) }
        guard (200..<300).contains(http.statusCode) else {
            let problem = try? decoder.decode(ProblemDetails.self, from: data)
            throw APIError.http(status: http.statusCode, problem: problem)
        }
        return data
    }
}

/// Empty body / response helper.
struct Empty: Codable, Sendable {}
