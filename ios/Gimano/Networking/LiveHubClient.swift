import Foundation

enum LiveEvent: Sendable {
    case alertCreated(Report)
    case alertUpdated(Report)
    case alertExpired(String)
    case jamUpdated(Jam)
}

/// SignalR JSON hub protocol (v1) framing: messages are JSON objects terminated by 0x1E.
enum SignalRProtocol {
    static let recordSeparator: Character = "\u{1e}"

    static var handshake: String { #"{"protocol":"json","version":1}"# + String(recordSeparator) }
    static var ping: String { #"{"type":6}"# + String(recordSeparator) }

    static func invocation(target: String, arguments: [Any]) -> String? {
        let obj: [String: Any] = ["type": 1, "target": target, "arguments": arguments]
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: data, encoding: .utf8) else { return nil }
        return s + String(recordSeparator)
    }

    enum Message: Equatable {
        case handshakeResponse(error: String?)
        case invocation(target: String, arguments: [Data])
        case ping
        case close(error: String?)
        case other(type: Int)
    }

    /// Splits a text frame into messages. Each argument is returned as raw JSON for typed decoding.
    static func parse(_ text: String) -> [Message] {
        text.split(separator: recordSeparator, omittingEmptySubsequences: true).compactMap { part in
            guard let data = part.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            guard let type = obj["type"] as? Int else {
                // Handshake response has no type: {} or {"error":"..."}
                return .handshakeResponse(error: obj["error"] as? String)
            }
            switch type {
            case 1:
                let target = obj["target"] as? String ?? ""
                let args = (obj["arguments"] as? [Any] ?? []).compactMap { arg -> Data? in
                    if JSONSerialization.isValidJSONObject(arg) { return try? JSONSerialization.data(withJSONObject: arg) }
                    // Scalars (e.g. reportId string)
                    return try? JSONSerialization.data(withJSONObject: arg, options: .fragmentsAllowed)
                }
                return .invocation(target: target, arguments: args)
            case 6: return .ping
            case 7: return .close(error: obj["error"] as? String)
            default: return .other(type: type)
            }
        }
    }

    static func event(target: String, arguments: [Data], decoder: JSONDecoder) -> LiveEvent? {
        guard let first = arguments.first else { return nil }
        switch target {
        case "AlertCreated": return (try? decoder.decode(Report.self, from: first)).map(LiveEvent.alertCreated)
        case "AlertUpdated": return (try? decoder.decode(Report.self, from: first)).map(LiveEvent.alertUpdated)
        case "AlertExpired": return (try? decoder.decode(String.self, from: first)).map(LiveEvent.alertExpired)
        case "JamUpdated": return (try? decoder.decode(Jam.self, from: first)).map(LiveEvent.jamUpdated)
        default: return nil
        }
    }
}

/// Minimal SignalR client for `/hubs/live` over `URLSessionWebSocketTask` (WebSockets transport,
/// skip-negotiation). Reconnects with exponential backoff and re-sends the last `Subscribe`.
actor LiveHubClient {
    enum Status: Sendable, Equatable { case disconnected, connecting, connected }

    private let hubURL: URL
    private let tokenProvider: @Sendable () async -> String?
    private let onEvent: @Sendable (LiveEvent) -> Void
    private let onStatus: @Sendable (Status) -> Void
    private let session = URLSession(configuration: .default)
    private let decoder = JSON.makeDecoder()

    private var task: URLSessionWebSocketTask?
    private var lastBBox: [Double]?
    private var running = false
    private var attempt = 0
    private var loop: Task<Void, Never>?
    private var pinger: Task<Void, Never>?

    init(hubURL: URL,
         tokenProvider: @escaping @Sendable () async -> String?,
         onEvent: @escaping @Sendable (LiveEvent) -> Void,
         onStatus: @escaping @Sendable (Status) -> Void) {
        self.hubURL = hubURL
        self.tokenProvider = tokenProvider
        self.onEvent = onEvent
        self.onStatus = onStatus
    }

    func start() {
        guard !running else { return }
        running = true
        loop = Task { await self.runLoop() }
    }

    func stop() {
        running = false
        loop?.cancel()
        pinger?.cancel()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        onStatus(.disconnected)
    }

    func subscribe(bbox: BBox) async {
        lastBBox = bbox.array
        await sendSubscribe()
    }

    private func sendSubscribe() async {
        guard let task, let bbox = lastBBox,
              let msg = SignalRProtocol.invocation(target: "Subscribe", arguments: [bbox]) else { return }
        try? await task.send(.string(msg))
    }

    private func runLoop() async {
        while running && !Task.isCancelled {
            onStatus(.connecting)
            do {
                try await connectOnce()
            } catch {
                // fall through to backoff
            }
            pinger?.cancel()
            task = nil
            guard running else { break }
            onStatus(.disconnected)
            attempt += 1
            let delay = min(60, pow(2, Double(min(attempt, 6))))
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    private func connectOnce() async throws {
        var c = URLComponents(url: hubURL, resolvingAgainstBaseURL: false)!
        if let token = await tokenProvider() {
            c.queryItems = [URLQueryItem(name: "access_token", value: token)]
        }
        let ws = session.webSocketTask(with: c.url!)
        task = ws
        ws.resume()
        try await ws.send(.string(SignalRProtocol.handshake))

        var handshakeDone = false
        while running && !Task.isCancelled {
            let message = try await ws.receive()
            let text: String
            switch message {
            case .string(let s): text = s
            case .data(let d): text = String(decoding: d, as: UTF8.self)
            @unknown default: continue
            }
            for m in SignalRProtocol.parse(text) {
                switch m {
                case .handshakeResponse(let error):
                    if let error { throw APIError.decoding("SignalR handshake: \(error)") }
                    handshakeDone = true
                    attempt = 0
                    onStatus(.connected)
                    await sendSubscribe()
                    startPinger()
                case .invocation(let target, let args):
                    if let e = SignalRProtocol.event(target: target, arguments: args, decoder: decoder) { onEvent(e) }
                case .close:
                    return
                case .ping, .other:
                    break
                }
            }
            _ = handshakeDone
        }
    }

    private func startPinger() {
        pinger?.cancel()
        let ws = task
        pinger = Task { [weak ws] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                try? await ws?.send(.string(SignalRProtocol.ping))
            }
        }
    }
}
