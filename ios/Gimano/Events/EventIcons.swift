import SwiftUI

// Flat two-tone icons of the event wizard (boards EwType … EwGuestCard): a pale cobalt base (#8FB0E8) and a cobalt
// detail (#0047AB) on the 24 grid, the same style as `FlatIcon`. Some details are strokes (rings, wheels, the check);
// circles are written as two arcs so the shared SVG path parser can read them.

enum EventIcon: String, CaseIterable, Sendable {
    // Templates
    case kenduri, meeting, seminar, dinner, picnic, other, trip, company, ride, convoy, hike
    // Event shape
    case venue, journey, route
    // Places
    case parking, entrance, dropoff, prayer, start, finish, water, rest, stop
    // Info
    case message, agenda, dress, safety, documents, bring, contact, note
    // Misc
    case lock, calendar, clock, sliders, person, key, globe, fileLock, eye, edit, check

    enum Tone { case base, detail }

    struct Layer {
        var d: String
        var tone: Tone
        /// nil = filled (even-odd), else a stroke of this width in grid units.
        var stroke: CGFloat?
    }

    private static func circle(_ cx: Double, _ cy: Double, _ r: Double) -> String {
        "M\(cx - r) \(cy)a\(r) \(r) 0 1 0 \(2 * r) 0a\(r) \(r) 0 1 0 \(-2 * r) 0z"
    }

    private static func fill(_ d: String, _ t: Tone = .base) -> Layer { Layer(d: d, tone: t, stroke: nil) }
    private static func line(_ d: String, _ t: Tone, _ w: CGFloat) -> Layer { Layer(d: d, tone: t, stroke: w) }

    var layers: [Layer] {
        typealias L = EventIcon
        switch self {
        case .kenduri:
            return [L.line(L.circle(9, 14.5, 5.8), .base, 2.6), L.line(L.circle(15, 14.5, 5.8), .detail, 2.6),
                    L.fill("M15 2.5l2.5 3-2.5 3-2.5-3z", .detail)]
        case .meeting:
            return [L.fill(L.circle(7, 6.5, 2.7) + L.circle(17, 6.5, 2.7) + "M2.5 18v-3.5a4.5 4.5 0 0 1 9 0V18zM12.5 18v-3.5a4.5 4.5 0 0 1 9 0V18z"),
                    L.fill("M1.5 14h21v3H1.5zM5 17h2v4H5zM17 17h2v4h-2z", .detail)]
        case .seminar:
            return [L.fill("M2.5 3h19v11h-19z"), L.fill("M5 6h9v2H5zM5 9.5h6v2H5zM9 14h6l1.2 7H7.8z", .detail)]
        case .dinner:
            return [L.fill(L.circle(12, 12.5, 7.5)), L.line(L.circle(12, 12.5, 4), .detail, 1.8),
                    L.line("M2.5 4v5.5a1.5 1.5 0 0 0 3 0V4M4 9.5V21M20.5 3.5c-2 1.2-2.2 6 0 7.5V21", .detail, 1.8)]
        case .picnic:
            return [L.fill(L.circle(12, 9, 7)), L.fill("M11 12h2v8h-2zM4 19.5h16V21H4z", .detail)]
        case .other, .stop:
            return [L.fill(L.circle(12, 12, 9.5)), L.fill("M11 6.5h2v11h-2zM6.5 11h11v2h-11z", .detail)]
        case .trip:
            return [L.fill("M3 12.5 5.2 6.6A2 2 0 0 1 7.1 5.3h9.8a2 2 0 0 1 1.9 1.3l2.2 5.9V18a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1v-1H7v1a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"),
                    L.fill("M6.6 11 7.8 7.6h8.4l1.2 3.4zM5.5 13.2h3v2h-3zM15.5 13.2h3v2h-3z", .detail)]
        case .company:
            return [L.fill("M4 3.5h16V18H4z"), L.fill("M6 5.5h12v5.5H6zM6 18h3v2.8H6zM15 18h3v2.8h-3zM6 13.5h2.5v2H6zM15.5 13.5H18v2h-2.5z", .detail)]
        case .ride:
            return [L.line(L.circle(5.5, 16, 3.9) + L.circle(18.5, 16, 3.9), .base, 2.4),
                    L.line("M5.5 16 9 9h6.5l3 7M9 9l3.5 7h-7M14 6h3", .detail, 2.2)]
        case .convoy:
            return [L.fill(L.circle(5.5, 16, 3.8) + L.circle(18.5, 16, 3.8)), L.line("M5.5 16 9.5 10h5.5l3.5 6M13 10l-1.4-3.2H15", .detail, 2.2)]
        case .hike:
            return [L.line("M11 8.5 8.5 14l3 3v5M11 8.5l3.5 4H18M9.5 15.5 7 21", .base, 2.6), L.fill(L.circle(12.5, 4.3, 2.3), .detail)]
        case .venue:
            return [L.fill("M3 21V10l9-7 9 7v11z"), L.fill("M10 21v-6h4v6zM10.5 8h3v3h-3z", .detail)]
        case .journey:
            return [L.line("M5 18 11 7l8 9", .base, 2.6), L.fill(L.circle(5, 18, 2.8) + L.circle(11, 7, 2.8) + L.circle(19, 16, 2.8), .detail)]
        case .route:
            return [L.line("M5 19c0-6 14-2 14-9", .base, 2.8), L.fill(L.circle(5, 19, 2.6) + L.circle(19, 6, 2.6), .detail)]
        case .parking:
            return [L.fill("M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"),
                    L.fill("M9 7h4a3.5 3.5 0 0 1 0 7h-2v3H9zm2 2v3h2a1.5 1.5 0 0 0 0-3z", .detail)]
        case .entrance:
            return [L.fill("M5 21v-8a7 7 0 0 1 14 0v8z"), L.fill("M10 17a2 2 0 0 1 4 0v4h-4z", .detail)]
        case .dropoff:
            return [L.fill("M3 17.5h18V21H3z"), L.fill("M11 3h2v7h3l-4 5-4-5h3z", .detail)]
        case .prayer:
            return [L.fill("M5 21v-7a7 7 0 0 1 14 0v7z"), L.fill("M11.4 2h1.2v4.5h-1.2zM10 21v-4a2 2 0 0 1 4 0v4z", .detail)]
        case .start:
            return [L.fill(L.circle(12, 12, 9.5)), L.fill("M10 7.5 16.5 12 10 16.5z", .detail)]
        case .finish:
            return [L.fill("M7 4h12l-2.5 4L19 12H7z"), L.fill("M5 3h2v18H5z", .detail)]
        case .water:
            return [L.fill("M12 2.5s-6.5 7.3-6.5 12a6.5 6.5 0 0 0 13 0c0-4.7-6.5-12-6.5-12z"), L.fill("M9 15a3 3 0 0 0 3 3v1.8A4.8 4.8 0 0 1 7.2 15z", .detail)]
        case .rest:
            return [L.fill("M3 9h18v5H3z"), L.fill("M3 14h2v6H3zM19 14h2v6h-2zM6 5h12v4H6z", .detail)]
        case .message, .note:
            return [L.fill("M4 3h16v18H4z"), L.fill("M7 7h10v1.8H7zM7 10.6h10v1.8H7zM7 14.2h6v1.8H7z", .detail)]
        case .agenda:
            return [L.fill("M8 5h13v3H8zM8 10.5h13v3H8zM8 16h13v3H8z"),
                    L.fill(L.circle(4, 6.5, 1.8) + L.circle(4, 12, 1.8) + L.circle(4, 17.5, 1.8), .detail)]
        case .dress:
            return [L.fill("M8 3 2.5 6.5l2.2 4.3L7 9.6V21h10V9.6l2.3 1.2 2.2-4.3L16 3a4 4 0 0 1-8 0z"), L.fill("M9.5 3.2a2.6 2.6 0 0 0 5 0z", .detail)]
        case .safety, .check:
            return [L.fill("M12 2 4 5v6c0 5.2 3.4 9.2 8 11 4.6-1.8 8-5.8 8-11V5z"), L.line("m8.5 12 2.5 2.5 4.5-5", .detail, 2.4)]
        case .documents:
            return [L.fill("M5 2h9l5 5v15H5z"), L.fill("M14 2v5h5zM8 11h8v1.8H8zM8 14.5h8v1.8H8zM8 18h5v1.8H8z", .detail)]
        case .bring:
            return [L.fill("M4 8h16l-1.2 13H5.2z"), L.fill("M8.5 8V6.5a3.5 3.5 0 0 1 7 0V8h-2V6.5a1.5 1.5 0 0 0-3 0V8z", .detail)]
        case .contact:
            return [L.fill("M6.5 2h11v20h-11z"), L.fill("M9.5 18.5h5v1.5h-5zM8.5 4.5h7v11h-7z", .detail)]
        case .lock:
            return [L.fill("M5 10h14v11H5z"), L.fill("M8 10V7a4 4 0 0 1 8 0v3h-2V7a2 2 0 0 0-4 0v3zM11 13.5h2v4h-2z", .detail)]
        case .calendar:
            return [L.fill("M3 5h18v16H3z"), L.fill("M3 5h18v4H3zM7 2h2v5H7zM15 2h2v5h-2zM6 12h3v3H6z", .detail)]
        case .clock:
            return [L.fill(L.circle(12, 12, 9.5)), L.fill("M11 6h2v5.4l3.6 2.1-1 1.7-4.6-2.7z", .detail)]
        case .sliders:
            return [L.line("M3 6h18M3 12h18M3 18h18", .base, 2.6), L.fill(L.circle(8, 6, 2.6) + L.circle(16, 12, 2.6) + L.circle(10, 18, 2.6), .detail)]
        case .person:
            return [L.fill(L.circle(12, 7.5, 4.5)), L.fill("M3.5 21a8.5 8.5 0 0 1 17 0z", .detail)]
        case .key:
            return [L.fill(L.circle(7.5, 12, 5)), L.fill("M11 11h11v2.5h-2V16h-2.5v-2.5H11z" + L.circle(7.5, 12, 1.6), .detail)]
        case .globe:
            return [L.fill(L.circle(12, 12, 9.5)), L.line("M2.5 12h19M12 2.5c3 3 3 16 0 19M12 2.5c-3 3-3 16 0 19", .detail, 1.8)]
        case .fileLock:
            return [L.fill("M5 2h9l5 5v15H5z"), L.fill("M14 2v5h5zM9 13h6v6H9zM10.5 13v-1.5a1.5 1.5 0 0 1 3 0V13h-1v-1.5a.5.5 0 0 0-1 0V13z", .detail)]
        case .eye:
            return [L.fill("M1.5 12S5.5 4.5 12 4.5 22.5 12 22.5 12 18.5 19.5 12 19.5 1.5 12 1.5 12z"), L.fill(L.circle(12, 12, 3.6), .detail)]
        case .edit:
            return [L.fill("M4 20v-3.5L15.5 5l3.5 3.5L7.5 20z"), L.fill("M15.5 5 17.3 3.2a1.4 1.4 0 0 1 2 0l1.5 1.5a1.4 1.4 0 0 1 0 2L19 8.5z", .detail)]
        }
    }

    nonisolated(unsafe) fileprivate static let cache: [EventIcon: [(SVGPath, Layer)]] = Dictionary(uniqueKeysWithValues: EventIcon.allCases.map {
        ($0, $0.layers.map { (SVGPath.parse($0.d), $0) })
    })
}

private struct EventIconShape: Shape {
    let icon: EventIcon
    let layer: Int
    let stroke: Bool
    func path(in rect: CGRect) -> Path {
        guard let svg = EventIcon.cache[icon]?[safe: layer]?.0 else { return Path() }
        let path = stroke ? svg.stroke : svg.fill
        let scale = min(rect.width, rect.height) / 24
        var t = CGAffineTransform(translationX: rect.midX - 12 * scale, y: rect.midY - 12 * scale).scaledBy(x: scale, y: scale)
        return Path(path.copy(using: &t) ?? path)
    }
}

/// Flat two-tone event icon (decorative: hidden from VoiceOver; label the control instead).
struct EventIconView: View {
    let icon: EventIcon
    var size: CGFloat = 24
    var base: Color = .brandTint
    var detail: Color = .brand

    var body: some View {
        ZStack {
            ForEach(Array(icon.layers.enumerated()), id: \.offset) { i, layer in
                let color = layer.tone == .base ? base : detail
                if let w = layer.stroke {
                    EventIconShape(icon: icon, layer: i, stroke: true)
                        .stroke(color, style: StrokeStyle(lineWidth: w * size / 24, lineCap: .round, lineJoin: .round))
                } else {
                    EventIconShape(icon: icon, layer: i, stroke: false).fill(color, style: FillStyle(eoFill: true))
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

extension EventTemplate {
    var eventIcon: EventIcon {
        switch self {
        case .kenduri: .kenduri
        case .dinner: .dinner
        case .company: .company
        case .ride: .ride
        case .convoy: .convoy
        case .trip: .trip
        case .meeting: .meeting
        case .seminar: .seminar
        case .picnic: .picnic
        case .hike: .hike
        case .other: .other
        }
    }

    /// "Perjalanan": start → stops → finish, with routes.
    var isJourney: Bool { [.trip, .company, .ride, .convoy, .hike].contains(self) }
}

extension EventStopKind {
    var eventIcon: EventIcon {
        switch self {
        case .parking: .parking
        case .dropoff: .dropoff
        case .entrance: .entrance
        case .venue, .accommodation: .venue
        case .meal: .dinner
        case .checkpoint, .finish: .finish
        case .fuel: .trip
        case .water: .water
        case .rest: .rest
        case .prayer: .prayer
        case .start: .start
        case .other: .stop
        }
    }
}

extension EventInfoKind {
    var eventIcon: EventIcon {
        switch self {
        case .dress: .dress
        case .safety: .safety
        case .documents: .documents
        case .bring: .bring
        case .contact: .contact
        case .other: .message
        }
    }
}
