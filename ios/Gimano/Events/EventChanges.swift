import Foundation

/// What differs between two revisions of one event, as the guest sees it (board "5 · Pelan berubah"):
/// time, title, and stops moved / renamed / added / removed / with new details.
struct EventChange: Identifiable, Equatable, Sendable {
    enum Kind: String, Sendable {
        case time, title, stopMoved, stopRenamed, stopAdded, stopRemoved, stopDetails
    }

    var kind: Kind
    var stopId: String?
    /// SEBELUM / SEKARANG lines (nil when there's no "before", e.g. an added stop).
    var before: [String]?
    var after: [String]?
    /// How far the navigation target moved, metres (stopMoved).
    var movedM: Double?

    var id: String { "\(kind.rawValue).\(stopId ?? "")" }

    /// Stops count as moved when the place navigation goes to (entrance, else pin) moved by more than 5 m.
    static let moveThresholdM = 5.0

    static func between(_ old: EventDoc, _ new: EventDoc) -> [EventChange] {
        var out: [EventChange] = []
        if old.start.date != new.start.date || old.end.date != new.end.date {
            out.append(EventChange(kind: .time, before: [EventFormat.range(old.start, old.end, old)],
                                   after: [EventFormat.range(new.start, new.end, new)]))
        }
        if old.title != new.title {
            out.append(EventChange(kind: .title, before: [old.title], after: [new.title]))
        }
        for s in new.stops {
            guard let o = old.stop(s.id) else {
                out.append(EventChange(kind: .stopAdded, stopId: s.id, after: lines(s)))
                continue
            }
            let moved = Geo.distance(o.navigationTarget, s.navigationTarget)
            if moved > moveThresholdM {
                out.append(EventChange(kind: .stopMoved, stopId: s.id, before: lines(o), after: lines(s), movedM: moved))
            } else if o.label != s.label {
                out.append(EventChange(kind: .stopRenamed, stopId: s.id, before: lines(o), after: lines(s)))
            } else if o.notes != s.notes || o.audience != s.audience || o.kind != s.kind || o.arrive != s.arrive || o.dwellMin != s.dwellMin {
                out.append(EventChange(kind: .stopDetails, stopId: s.id, before: lines(o), after: lines(s)))
            }
        }
        for o in old.stops where new.stop(o.id) == nil {
            out.append(EventChange(kind: .stopRemoved, stopId: o.id, before: lines(o)))
        }
        return out
    }

    private static func lines(_ s: EventStop) -> [String] {
        [s.label, s.notes, s.audience].compactMap { $0?.isEmpty == false ? $0 : nil }
    }

    /// The stop an active trip is heading to was moved or removed.
    static func affectsTrip(_ changes: [EventChange], stopId: String) -> EventChange? {
        changes.first { $0.stopId == stopId && ($0.kind == .stopMoved || $0.kind == .stopRemoved) }
    }
}
