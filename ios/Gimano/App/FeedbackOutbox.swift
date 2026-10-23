import Foundation
import Observation
import UIKit

/// Decides what to do with a failed feedback upload.
enum OutboxPolicy {
    enum Decision: Equatable { case retry, fail }

    /// Validation (400) and too-large (413) can never succeed; everything else (offline, 429 rate limit,
    /// 404/405 endpoint not deployed yet, 5xx) is retried later.
    static func classify(_ error: any Error) -> Decision {
        if case APIError.http(let status, _) = error, status == 400 || status == 413 || status == 422 { return .fail }
        return .retry
    }

    /// Exponential backoff: 30 s, 60 s, 120 s … capped at 1 h.
    static func backoff(attempts: Int) -> TimeInterval {
        min(3600, 30 * pow(2, Double(max(0, attempts - 1))))
    }
}

struct OutboxItem: Codable, Identifiable, Hashable, Sendable {
    enum State: String, Codable, Sendable { case queued, sending, sent, failed }

    var id = UUID()
    var request: FeedbackRequest
    var createdAt = Date()
    var state: State = .queued
    var serverId: String?
    var lastError: String?
    var attempts = 0
    var nextAttemptAt = Date.distantPast
    /// Last status reported by `GET /me/feedback`.
    var serverStatus: FeedbackStatus?
}

/// Where the outbox persists: the shared `outbox` table (device-local, never backed up), or a file in tests.
protocol OutboxStorage: Sendable {
    func load() -> [OutboxItem]
    func save(_ items: [OutboxItem])
}

struct FileOutboxStorage: OutboxStorage {
    let url: URL
    func load() -> [OutboxItem] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSON.makeDecoder().decode([OutboxItem].self, from: data)) ?? []
    }
    func save(_ items: [OutboxItem]) {
        guard let data = try? JSON.makeEncoder().encode(items) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

/// Rows in `outbox` with kind = "feedback"; `payload_json` holds the whole item.
struct LocalStoreOutboxStorage: OutboxStorage {
    let store: LocalStore
    func load() -> [OutboxItem] {
        let rows = (try? store.outboxItems(kind: "feedback")) ?? []
        return rows.compactMap { try? JSON.makeDecoder().decode(OutboxItem.self, from: Data($0.payload.utf8)) }
            .sorted { $0.createdAt > $1.createdAt }
    }
    func save(_ items: [OutboxItem]) {
        let enc = JSON.makeEncoder()
        let rows = items.compactMap { i -> (id: String, payload: String, attempts: Int, nextAttemptAt: Date)? in
            guard let d = try? enc.encode(i) else { return nil }
            return (i.id.uuidString, String(decoding: d, as: UTF8.self), i.attempts, i.nextAttemptAt == .distantPast ? Date(timeIntervalSince1970: 0) : i.nextAttemptAt)
        }
        try? store.replaceOutbox(kind: "feedback", items: rows)
    }
}

/// Offline-first queue for `POST /feedback`, persisted locally and retried with backoff.
@MainActor
@Observable
final class FeedbackOutbox {
    private(set) var items: [OutboxItem] = []
    @ObservationIgnored private let storage: any OutboxStorage
    @ObservationIgnored private var flushing = false

    init(storage: any OutboxStorage) {
        self.storage = storage
        load()
        // An app kill mid-upload leaves items "sending": treat them as queued.
        for i in items.indices where items[i].state == .sending { items[i].state = .queued }
    }

    var pendingCount: Int { items.filter { $0.state == .queued || $0.state == .sending }.count }

    func enqueue(_ request: FeedbackRequest) -> OutboxItem {
        let item = OutboxItem(request: request)
        items.insert(item, at: 0)
        save()
        return item
    }

    /// Sends every due item. `send` is injected (the repository call) so this is unit-testable.
    @discardableResult
    func flush(now: Date = .now, send: (FeedbackRequest) async throws -> FeedbackCreated) async -> Int {
        guard !flushing else { return 0 }
        flushing = true
        defer { flushing = false }
        var sent = 0
        for id in items.filter({ $0.state == .queued && $0.nextAttemptAt <= now }).map(\.id) {
            guard let i = items.firstIndex(where: { $0.id == id }) else { continue }
            items[i].state = .sending
            items[i].attempts += 1
            let request = items[i].request
            do {
                let created = try await send(request)
                guard let j = items.firstIndex(where: { $0.id == id }) else { continue }
                items[j].state = .sent
                items[j].serverId = created.id
                items[j].serverStatus = .received
                items[j].lastError = nil
                sent += 1
            } catch {
                guard let j = items.firstIndex(where: { $0.id == id }) else { continue }
                items[j].lastError = Self.describe(error)
                switch OutboxPolicy.classify(error) {
                case .fail:
                    items[j].state = .failed
                case .retry:
                    items[j].state = .queued
                    items[j].nextAttemptAt = now.addingTimeInterval(OutboxPolicy.backoff(attempts: items[j].attempts))
                }
            }
            save()
        }
        return sent
    }

    /// Retry now (user tapped "Retry").
    func retry(_ id: UUID) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        items[i].state = .queued
        items[i].nextAttemptAt = .distantPast
        save()
    }

    func delete(_ id: UUID) {
        items.removeAll { $0.id == id }
        save()
    }

    /// Applies server statuses from `GET /me/feedback` to sent items.
    func applyServerStatuses(_ summaries: [FeedbackSummary]) {
        let byId = Dictionary(summaries.map { ($0.id, $0.status) }, uniquingKeysWith: { a, _ in a })
        for i in items.indices {
            if let sid = items[i].serverId, let st = byId[sid] { items[i].serverStatus = st }
        }
        save()
    }

    func reset() {
        items = []
        save()
    }

    private static func describe(_ error: any Error) -> String {
        if case APIError.http(let status, _) = error {
            if status == 404 || status == 405 { return String(localized: "Feedback service not available yet (pending backend)") }
            if status == 429 { return String(localized: "Too many feedback messages, will retry later") }
            return "HTTP \(status)"
        }
        if let e = error as? APIError, e.isUnreachable { return String(localized: "Offline, will retry") }
        return error.localizedDescription
    }

    private func load() { items = storage.load() }

    private func save() { storage.save(items) }
}

/// Builds the screenshot and diagnostics attached to feedback.
enum FeedbackAttachments {
    /// Renders the current key window (including the map) into a JPEG ≤ 1.5 MB, downscaled to ≤ 1280 pt tall.
    @MainActor
    static func captureWindowJPEG() -> Data? {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows).first(where: \.isKeyWindow) else { return nil }
        let bounds = window.bounds
        let scale = min(2, 1280 / max(1, bounds.height) * window.screen.scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = max(1, scale)
        let image = UIGraphicsImageRenderer(bounds: bounds, format: format).image { _ in
            window.drawHierarchy(in: bounds, afterScreenUpdates: false)
        }
        return compress(image)
    }

    static func compress(_ image: UIImage, limit: Int = FeedbackRules.maxScreenshotBytes) -> Data? {
        var quality: CGFloat = 0.7
        var img = image
        while true {
            if let d = img.jpegData(compressionQuality: quality), d.count <= limit { return d }
            if quality > 0.35 { quality -= 0.15; continue }
            // Still too big: halve the dimensions.
            let size = CGSize(width: img.size.width / 2, height: img.size.height / 2)
            guard size.width > 64 else { return nil }
            img = UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
            quality = 0.6
        }
    }

}
