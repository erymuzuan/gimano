import Foundation
import Observation
import SwiftUI

/// A verified pack waiting for the user's confirmation (§7.3–7.5).
struct PendingEventImport: Identifiable, Equatable {
    let id = UUID()
    let pack: OpenedEventPack
    let decision: EventImportDecision
    /// The version on this phone (for "older" / "fork" / "newer" warnings).
    let stored: ImportedEvent?
}

enum EventNavRoute: Hashable {
    case confirm
    case secretCode
    case detail(String)
}

/// Events (Acara): opening invitation links and `.manotu-event` files, the import decision, the on-device library.
@MainActor
@Observable
final class EventCenter {
    let store: EventStore
    private(set) var events: [ImportedEvent] = []

    /// The Acara sheet and its navigation.
    var isPresented = false
    var path: [EventNavRoute] = []
    var detent: PresentationDetent = .large

    private(set) var pending: PendingEventImport?
    /// Bytes of a `.manotu-event` file waiting for its secret code.
    private(set) var pendingFile: [UInt8]?
    var errorMessage: String?
    /// The stop a per-stop "Buka di Manotu" link asked for; the event screen highlights it.
    var focusStopId: String?

    @ObservationIgnored var now: () -> Date = { .now }

    init(store: EventStore) {
        self.store = store
    }

    // MARK: library

    /// Purges expired events (§7.5) and reloads. Called on launch, when the app comes to the foreground and when listing.
    func refresh() {
        events = (try? store.events(now: now())) ?? []
    }

    func event(_ id: String) -> ImportedEvent? {
        if let e = events.first(where: { $0.eventId == id }), !EventImport.isExpired(e.doc.expiresAt.date, now: now()) { return e }
        return nil
    }

    func delete(_ id: String) {
        try? store.delete(eventId: id)
        path.removeAll { $0 == .detail(id) }
        refresh()
    }

    func forkAlternatives(_ id: String) -> [ImportedEvent] {
        (try? store.forkAlternatives(eventId: id)) ?? []
    }

    func chooseFork(_ e: ImportedEvent) {
        try? store.choose(eventId: e.eventId, hash: e.hash)
        refresh()
    }

    func open() {
        refresh()
        path = []
        isPresented = true
    }

    // MARK: incoming

    /// `manotu://event#1.<key>.<ct>`, `gimano://event#…`, a pasted `https://…/a/#1.…`, or a `.manotu-event` file URL.
    /// Returns false when the URL isn't an event invitation.
    @discardableResult
    func handle(url: URL) -> Bool {
        if url.isFileURL {
            guard url.pathExtension.lowercased() == EventPack.fileExtension else { return false }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            let data = size <= EventLimits.maxFileBytes ? try? Data(contentsOf: url) : nil
            // Opened from another app: iOS copied it into our Inbox. Don't keep the encrypted copy around.
            if url.path.contains("/Inbox/") { try? FileManager.default.removeItem(at: url) }
            present()
            if size > EventLimits.maxFileBytes {
                fail(.fileTooLarge)
            } else if let data {
                receiveFile([UInt8](data))
            } else {
                fail(.malformedFile)
            }
            return true
        }
        let scheme = url.scheme?.lowercased() ?? ""
        let isAppLink = ["manotu", "gimano"].contains(scheme) && url.host?.lowercased() == "event"
        let path = url.path(percentEncoded: false)
        let isWebLink = ["https", "http"].contains(scheme) && (path.hasSuffix("/a/") || path.hasSuffix("/a"))
            && (url.fragment?.hasPrefix("1.") ?? false)
        guard isAppLink || isWebLink else { return false }
        present()
        // §5.3: `manotu://event?stop=<stopId>#…` selects that stop after import (ignored if unknown).
        focusStopId = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "stop" }?.value
        openLink(url.absoluteString)
        return true
    }

    private func present() {
        if !isPresented { path = [] }
        isPresented = true
        detent = .large
    }

    /// From the "Import invitation" field: a link, a bare fragment, or a whole message containing a link.
    func importPasted(_ text: String) {
        focusStopId = nil
        openLink(EventPack.extractLink(fromPasted: text))
    }

    func openLink(_ link: String) {
        do {
            try decide(EventPack.openLink(link))
        } catch {
            fail(error)
        }
    }

    func receiveFile(_ bytes: [UInt8]) {
        do {
            _ = try EventPack.parseFile(bytes)
        } catch {
            fail(error)
            return
        }
        pendingFile = bytes
        errorMessage = nil
        path.append(.secretCode)
    }

    /// Returns an error message for the secret-code screen, or nil when the file opened.
    func openPendingFile(secretCode: String) -> String? {
        guard let bytes = pendingFile else { return nil }
        do {
            let pack = try EventPack.openFile(bytes, secretCode: secretCode)
            pendingFile = nil
            path.removeAll { $0 == .secretCode }
            try decide(pack)
            return nil
        } catch {
            return error.userMessage
        }
    }

    private func decide(_ pack: OpenedEventPack) throws(EventPackError) {
        let stored = try? store.record(eventId: pack.event.id)
        let decision = EventImport.decide(EventImportCandidate(pack), stored: stored, now: now())
        let current = (try? store.events(now: now()))?.first { $0.eventId == pack.event.id }
        pending = PendingEventImport(pack: pack, decision: decision, stored: current)
        errorMessage = nil
        path.removeAll { $0 == .confirm }
        path.append(.confirm)
    }

    private func fail(_ error: EventPackError) {
        errorMessage = error.userMessage
    }

    /// The user confirmed the pending import. Returns the event id when something was stored.
    @discardableResult
    func confirmPending() -> String? {
        guard let p = pending else { return nil }
        pending = nil
        let id = p.pack.event.id
        let stored = (try? store.save(p.pack, decision: p.decision, now: now())) ?? false
        refresh()
        let opensStored = stored || p.decision == .alreadyImported || p.decision.code == "older"
        path = opensStored && event(id) != nil ? [.detail(id)] : []
        return stored ? id : nil
    }

    func cancelPending() {
        pending = nil
        pendingFile = nil
        path.removeAll { $0 == .confirm || $0 == .secretCode }
    }

    // MARK: schedule

    /// "Seterusnya": the first stop whose arrival window (or the event) hasn't ended, in plan order.
    nonisolated static func nextStop(_ doc: EventDoc, now: Date) -> EventStop? {
        guard !doc.stops.isEmpty else { return nil }
        if doc.end.date <= now { return nil }
        return doc.stops.first { ($0.arrive?.to.date ?? doc.end.date) > now }
    }

    /// Travel mode for a stop: the first route that visits it, else nil (the user's own mode).
    nonisolated static func vehicle(for stop: EventStop, in doc: EventDoc) -> Vehicle? {
        guard let r = doc.routes.first(where: { $0.stopIds.contains(stop.id) }) else { return nil }
        return Vehicle(rawValue: r.mode.rawValue)
    }
}

extension AppModel {
    /// "Pandu": route preview to the stop's vehicle entrance (else its pin). The request carries coordinates only:
    /// no waypoint name, no place id (§7.7). The stop label is shown on this phone only and is not kept in recents.
    func navigate(toEventStop stop: EventStop, in doc: EventDoc) {
        events.isPresented = false
        clearEventFromMap()
        let destination = Destination(name: stop.label, address: doc.title, location: stop.navigationTarget, placeId: nil)
        planRoute(to: destination, remember: false, vehicle: EventCenter.vehicle(for: stop, in: doc), eventTrip: true)
    }

    /// Draws the event's routes (and the next stop's pin) on the home map behind the Acara sheet.
    func showEventOnMap(_ e: ImportedEvent) {
        guard screen == .home else { return }
        let routes = e.doc.routes.enumerated().map {
            RouteLine(id: "event-\($0.element.id)", points: $0.element.points, selected: $0.offset == 0, traffic: [])
        }
        map.routes = routes
        map.destination = EventCenter.nextStop(e.doc, now: .now)?.pin
        map.bubbleAnchors = [:]
        let pts = e.doc.stops.map(\.pin) + routes.flatMap(\.points)
        if let b = BBox.around(pts) {
            map.overviewInsets = (top: 80, bottom: 460)
            map.setCamera(.overview(b))
        }
    }

    func clearEventFromMap() {
        guard screen == .home, map.routes.contains(where: { $0.id.hasPrefix("event-") }) || map.destination != nil else { return }
        map.routes = []
        map.destination = nil
    }
}
