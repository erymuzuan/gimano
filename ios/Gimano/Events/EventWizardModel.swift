import Foundation
import SwiftUI

// The new-event wizard (boards EwType … EwFlow): steps, template defaults and the mapping from publish problems to
// the step that fixes them. Pure data, so it is unit-tested.

enum WizardStep: Int, CaseIterable, Comparable, Sendable {
    case type, when, place, info, review

    static func < (a: WizardStep, b: WizardStep) -> Bool { a.rawValue < b.rawValue }

    func label(journey: Bool) -> LocalizedStringKey {
        switch self {
        case .type: "Type"
        case .when: "When"
        case .place: journey ? "Route" : "Place"
        case .info: "Info"
        case .review: "Review"
        }
    }

    var next: WizardStep? { WizardStep(rawValue: rawValue + 1) }
    var previous: WizardStep? { WizardStep(rawValue: rawValue - 1) }
}

/// A suggested time ("Masa cadangan", EwFlow table).
struct EventTimePreset: Hashable, Sendable {
    var startHour: Int, startMinute: Int, endHour: Int, endMinute: Int
    /// "Sepanjang hari" (the table's "hari").
    var allDay = false

    static func at(_ sh: Int, _ sm: Int, _ eh: Int, _ em: Int) -> EventTimePreset {
        EventTimePreset(startHour: sh, startMinute: sm, endHour: eh, endMinute: em)
    }

    static let allDayPreset = EventTimePreset(startHour: 8, startMinute: 0, endHour: 18, endMinute: 0, allDay: true)

    /// "11:00 – 16:00".
    var text: String { String(format: "%02d:%02d – %02d:%02d", startHour, startMinute, endHour, endMinute) }

    /// The same event day(s) at these times; `endDay` keeps a multi-day event's last day.
    func apply(to draft: inout HostedEventDraft, keepEndDay: Bool) {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = draft.timeZone
        let day = cal.startOfDay(for: draft.start)
        let endDay = keepEndDay ? max(cal.startOfDay(for: draft.end), day) : day
        if let s = cal.date(bySettingHour: startHour, minute: startMinute, second: 0, of: day),
           let e = cal.date(bySettingHour: endHour, minute: endMinute, second: 0, of: endDay) {
            draft.start = s
            draft.end = e
        }
    }

    func matches(_ draft: HostedEventDraft) -> Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = draft.timeZone
        let s = cal.dateComponents([.hour, .minute], from: draft.start)
        let e = cal.dateComponents([.hour, .minute], from: draft.end)
        return s.hour == startHour && s.minute == startMinute && e.hour == endHour && e.minute == endMinute
    }
}

extension EventTemplate {
    /// "Satu tempat" templates, in the board's order.
    static let oneVenue: [EventTemplate] = [.kenduri, .meeting, .seminar, .dinner, .picnic, .other]
    /// "Perjalanan" templates.
    static let journeys: [EventTemplate] = [.trip, .company, .ride, .convoy, .hike]

    /// Chip label (and the name a new event starts with).
    var title: LocalizedStringKey {
        switch self {
        case .kenduri: "Kenduri"
        case .meeting: "Meeting"
        case .seminar: "Seminar"
        case .dinner: "Dinner"
        case .picnic: "Picnic"
        case .other: "Other"
        case .trip: "Family trip"
        case .company: "Company trip"
        case .ride: "Ride"
        case .convoy: "Convoy"
        case .hike: "Walk / hiking"
        }
    }

    /// The name a new event is prefilled with ("Bila"); empty for "Lain".
    var defaultName: String {
        switch self {
        case .kenduri: String(localized: "Kenduri")
        case .meeting: String(localized: "Meeting")
        case .seminar: String(localized: "Seminar")
        case .dinner: String(localized: "Dinner")
        case .picnic: String(localized: "Picnic")
        case .other: ""
        case .trip: String(localized: "Family trip")
        case .company: String(localized: "Company trip")
        case .ride: String(localized: "Ride")
        case .convoy: String(localized: "Convoy")
        case .hike: String(localized: "Walk / hiking")
        }
    }

    /// Suggested times (EwFlow "Masa"); the first is applied to a new event.
    var timePresets: [EventTimePreset] {
        switch self {
        case .kenduri: [.at(11, 0, 16, 0), .at(19, 30, 22, 30)]
        case .meeting: [.at(9, 0, 12, 0), .at(14, 0, 17, 0)]
        case .seminar: [.at(8, 30, 17, 0), .allDayPreset]
        case .dinner: [.at(19, 30, 22, 30)]
        case .picnic: [.at(9, 0, 17, 0)]
        case .ride: [.at(7, 0, 13, 0)]
        case .hike: [.at(7, 0, 12, 0)]
        case .trip, .company, .convoy: [.allDayPreset]
        case .other: [.at(11, 0, 16, 0), .at(19, 30, 22, 30)]
        }
    }

    /// Info suggestions (EwFlow "Cadangan info"), shown first among the "Tambah" chips. Nothing is added untapped.
    var suggestedInfo: [EventInfoKind] {
        switch self {
        case .kenduri: [.dress]
        case .meeting: [.documents, .contact]
        case .seminar: [.documents]
        case .dinner: [.dress]
        case .picnic: [.bring, .safety]
        case .trip: [.bring, .documents]
        case .company: [.contact, .documents]
        case .ride, .hike: [.safety, .bring]
        case .convoy: [.safety]
        case .other: []
        }
    }

    /// "Atur cara" is among the suggestions (kenduri, seminar).
    var suggestsAgenda: Bool { self == .kenduri || self == .seminar }

    /// Travel mode of a new route.
    var defaultMode: EventTravelMode {
        switch self {
        case .ride: .bicycle
        case .convoy: .motorcycle
        case .hike: .walk
        default: .car
        }
    }

    /// Stop chips on "Laluan" after Hentian / Air / Rehat.
    var extraRouteStops: [EventStopKind] {
        switch self {
        case .trip: [.fuel, .accommodation]
        case .convoy: [.fuel]
        default: []
        }
    }
}

extension EventStopKind {
    /// Plain-text name, also the default label of a stop added from a chip.
    var name: String {
        switch self {
        case .parking: String(localized: "Parking")
        case .dropoff: String(localized: "Drop-off")
        case .entrance: String(localized: "Entrance")
        case .venue: String(localized: "Venue")
        case .meal: String(localized: "Meal")
        case .checkpoint: String(localized: "Checkpoint")
        case .fuel: String(localized: "Fuel")
        case .water: String(localized: "Water")
        case .rest: String(localized: "Rest")
        case .prayer: String(localized: "Prayer")
        case .accommodation: String(localized: "Accommodation")
        case .start: String(localized: "Start")
        case .finish: String(localized: "Finish")
        case .other: String(localized: "Other")
        }
    }

    /// Chips on "Di mana" (extra places).
    static let venueChips: [EventStopKind] = [.parking, .entrance, .dropoff, .prayer, .other]
}

extension EventInfoKind {
    var title: LocalizedStringKey {
        switch self {
        case .dress: "Dress code"
        case .safety: "Safety"
        case .documents: "Documents"
        case .bring: "Bring"
        case .contact: "Contact"
        case .other: "Other"
        }
    }

    /// Placeholder of the block's text field.
    var prompt: LocalizedStringKey {
        switch self {
        case .dress: "e.g. Pastel theme. Baju Melayu / kurung."
        case .safety: "e.g. Helmets are required. Lights after 7 pm."
        case .documents: "e.g. Bring your IC and the registration slip."
        case .bring: "e.g. Water bottle, umbrella, mat."
        case .contact: "e.g. Ask for Aqil at the gate."
        case .other: "Anything else guests should know."
        }
    }

    /// "Tambah" chips in the board's order.
    static let chips: [EventInfoKind] = [.safety, .documents, .bring, .contact, .dress, .other]
}

extension EventDraftIssue {
    /// The wizard step that fixes this problem ("Semak" rows jump there).
    var wizardStep: WizardStep {
        switch self {
        case .titleMissing, .titleTooLong, .endBeforeStart, .tooLong: .when
        case .noStops, .tooManyStops, .stopLabel, .stopNotes, .stopAudience: .place
        case .routeIncomplete, .routeUnmatched, .tooManyRoutes, .tooManyRoutePoints: .place
        case .descriptionTooLong, .aliasTooLong, .expired, .tooManySessions, .sessionTitle, .sessionTime,
             .tooManyInfoBlocks, .infoTooLong: .info
        case .invalid: .review
        }
    }
}

extension HostedEventDraft {
    /// The main venue on "Di mana": the first `venue` stop, else the first stop.
    var mainVenue: HostedStopDraft? { stops.first { $0.kind == .venue } ?? stops.first }

    /// The route shown for day `index` on "Laluan" (the first route on a one-day event).
    func routeIndex(day index: Int) -> Int? {
        let ds = days
        guard !ds.isEmpty else { return routes.indices.first }
        let day = ds[min(max(index, 0), ds.count - 1)]
        return routes.firstIndex { $0.day == day } ?? (index == 0 ? routes.firstIndex { $0.day == nil } : nil)
    }

    /// The stops a route visits, in order (consecutive repeats once).
    func timeline(_ route: RouteDraft) -> [HostedStopDraft] {
        var out: [HostedStopDraft] = []
        for p in route.points {
            guard let sid = p.stopId, let s = stops.first(where: { $0.id == sid }) else { continue }
            if out.last?.id != s.id { out.append(s) }
        }
        return out
    }

    /// Expected arrival at each stop of a route: the event start (or the day's start time) plus the calculated legs
    /// before it. nil when a leg before it has no time yet.
    func arrivals(_ route: RouteDraft, dayStart: Date) -> [String: Date] {
        var out: [String: Date] = [:]
        var t: Double? = 0
        for (i, p) in route.points.enumerated() {
            if i > 0 {
                let leg = route.legs[safe: i - 1]
                t = t.flatMap { acc in leg?.durationS.map { acc + $0 } }
            }
            if let sid = p.stopId, out[sid] == nil, let t { out[sid] = dayStart.addingTimeInterval(t) }
        }
        return out
    }
}
