import Foundation
import Observation

/// Where the builder was opened from.
enum RouteBuilderContext: Equatable {
    /// An event's route: points can become or snap to the event's stops.
    case event(title: String)
    /// "Laluan saya": no stops.
    case personal
}

/// State of one "Bina laluan" session: the route (and the event's stops it may create or move), undo/redo, and the
/// routing of waiting legs. Results from the server are not undo steps.
@MainActor
@Observable
final class RouteBuilderModel {
    enum Tool: String { case points, draw }

    struct Snapshot: Equatable {
        var route: RouteDraft
        var stops: [HostedStopDraft]
    }

    private(set) var snapshot: Snapshot
    let context: RouteBuilderContext
    var tool: Tool = .points
    /// The point whose actions are shown.
    var selectedPoint: Int?
    /// A short message (e.g. "Lukis ikut jalan belum tersedia").
    var notice: String?
    private(set) var history = EditHistory<Snapshot>()
    /// Requests in flight, by leg version.
    private(set) var inFlight: Set<Int> = []

    @ObservationIgnored let transport: (any RouteBuilderTransport)?
    /// Answers already received, by leg version: undo/redo back to a waiting leg needs no new request.
    @ObservationIgnored private var answers: [Int: LegResolution] = [:]
    @ObservationIgnored private let maxParallel = 4

    init(route: RouteDraft, stops: [HostedStopDraft] = [], context: RouteBuilderContext, transport: (any RouteBuilderTransport)?) {
        snapshot = Snapshot(route: route, stops: stops)
        self.context = context
        self.transport = transport
    }

    var route: RouteDraft { snapshot.route }
    var stops: [HostedStopDraft] { snapshot.stops }
    var isEvent: Bool { if case .event = context { true } else { false } }
    var isCalculating: Bool { !inFlight.isEmpty }
    var canUndo: Bool { history.canUndo }
    var canRedo: Bool { history.canRedo }

    func stop(_ id: String?) -> HostedStopDraft? { id.flatMap { i in stops.first { $0.id == i } } }

    // MARK: edits (each one undo step)

    /// Applies an edit as one undo step; nothing is recorded when it changes nothing. Returns false on an edit error.
    @discardableResult
    func edit(_ body: (inout Snapshot) throws -> Void) -> Bool {
        let before = snapshot
        var s = snapshot
        do {
            try body(&s)
        } catch RouteEditError.needsTwoPoints {
            notice = String(localized: "Add at least two points first.")
            return false
        } catch {
            notice = String(localized: "The route already ends at its start.")
            return false
        }
        guard s != before else { return true }
        history.record(before)
        snapshot = s
        if let sel = selectedPoint, sel >= s.route.points.count { selectedPoint = nil }
        resolvePending()
        return true
    }

    func undo() {
        guard let s = history.undo(snapshot) else { return }
        snapshot = s
        selectedPoint = nil
        resolvePending()
    }

    func redo() {
        guard let s = history.redo(snapshot) else { return }
        snapshot = s
        selectedPoint = nil
        resolvePending()
    }

    /// A tap on the map: a new point (linked to the event stop that was tapped, if any).
    func tap(_ p: LatLng, stopId: String? = nil) {
        if let s = stop(stopId) {
            edit { $0.route.addPoint(s.location, stopId: s.id) }
        } else {
            edit { $0.route.addPoint(p) }
        }
    }

    func insert(_ p: LatLng, inLeg i: Int) { edit { $0.route.insertPoint(p, inLeg: i) } }

    /// Dragging a point linked to a stop moves the stop too (and every other point linked to it).
    func move(_ i: Int, to p: LatLng) {
        edit { s in
            s.route.movePoint(i, to: p)
            guard let sid = s.route.points[safe: i]?.stopId, let k = s.stops.firstIndex(where: { $0.id == sid }) else { return }
            s.stops[k].location = s.route.points[i].location
            s.route.syncStop(s.stops[k])
        }
    }

    func delete(_ i: Int) { edit { $0.route.deletePoint(i) } }
    func moveInList(_ i: Int, by d: Int) { edit { $0.route.movePointInList(i, by: d) } }
    func reverse() { edit { try $0.route.reverse() } }
    func closeLoop() { edit { try $0.route.closeLoop() } }
    func outAndBack() { edit { try $0.route.outAndBack() } }
    func clear() { edit { $0.route.clear() } }
    func setMode(_ m: EventTravelMode) { edit { $0.route.setMode(m) } }
    func setFollowRoads(_ on: Bool) { edit { $0.route.setFollowRoads(on) } }
    func setTitle(_ t: String) { edit { $0.route.title = t } }
    func setDay(_ d: String?) { edit { $0.route.day = d } }
    func keepAsSketch(_ i: Int) { edit { $0.route.keepAsSketch(i) } }
    func splitAtUnmatched(_ i: Int) { edit { $0.route.splitAtUnmatched(i) } }
    func retryAll() { edit { $0.route.retryAll() } }

    func draw(_ line: [LatLng]) { edit { try $0.route.draw(line) } }

    /// "Lukis semula": removes the drawn leg (and its end point) so the user can draw it again.
    func redraw(_ i: Int) { edit { $0.route.deletePoint(i + 1) } }

    /// Links point `i` to an existing stop (the point moves onto it).
    func link(_ i: Int, to stopId: String) {
        guard let s = stop(stopId) else { return }
        edit { $0.route.link(i, to: s) }
    }

    func unlink(_ i: Int) { edit { $0.route.unlink(i) } }

    /// "Jadikan tempat acara": a new event stop at this point ("Hentian N"; start / finish / checkpoint by position).
    func makeStop(_ i: Int) {
        edit { s in
            guard let p = s.route.points[safe: i] else { return }
            let n = s.route.points.count
            let kind: EventStopKind = i == 0 ? .start : (i == n - 1 ? .finish : .checkpoint)
            let stop = HostedStopDraft(kind: kind, label: String(localized: "Stop \(s.stops.count + 1)"), location: p.location)
            s.stops.append(stop)
            s.route.points[i].stopId = stop.id
        }
    }

    // MARK: routing

    /// Starts requests for waiting legs (at most 4 at a time). Without a transport they read as offline.
    func resolvePending() {
        let mode = route.mode
        for leg in route.pendingLegs where !inFlight.contains(leg.generation) {
            if let cached = answers[leg.generation] {
                snapshot.route.apply(cached, generation: leg.generation)
                continue
            }
            guard inFlight.count < maxParallel else { break }
            inFlight.insert(leg.generation)
            let transport = transport
            Task { [weak self] in
                let r = await RouteBuilderService.resolve(from: leg.from, to: leg.to, drawn: leg.drawn, mode: mode, transport: transport)
                self?.finish(r, generation: leg.generation)
            }
        }
    }

    private func finish(_ r: LegResolution, generation: Int) {
        inFlight.remove(generation)
        if case .failed(let p) = r, p != .matchUnavailable {
            // Not cached: "Cuba lagi" asks again.
        } else {
            answers[generation] = r
        }
        if snapshot.route.apply(r, generation: generation), case .failed(.matchUnavailable) = r {
            notice = String(localized: "Drawing to follow roads isn't available yet. Kept as a sketch.")
        }
        resolvePending()
    }

    /// Waits until no leg is being calculated (tests, UI tests).
    func settle() async {
        for _ in 0..<600 where !inFlight.isEmpty || !route.pendingLegs.isEmpty {
            try? await Task.sleep(for: .milliseconds(50))
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
