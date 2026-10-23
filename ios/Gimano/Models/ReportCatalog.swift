import SwiftUI
import UIKit

/// Display metadata for report types and subtypes (Gimano's own icons/colours).
struct ReportSubtype: Identifiable, Sendable {
    let id: String
    let title: LocalizedStringResource
    let systemImage: String
}

extension ReportType {
    var title: LocalizedStringResource {
        switch self {
        case .traffic: "Traffic"
        case .police: "Police"
        case .accident: "Accident"
        case .hazard: "Hazard"
        case .closure: "Closure"
        case .blockedLane: "Blocked lane"
        case .mapIssue: "Map issue"
        case .weather: "Bad weather"
        case .animal: "Animal"
        }
    }

    /// One-word tile label ("big icons, little text").
    var shortTitle: LocalizedStringResource {
        switch self {
        case .traffic: "Jam"
        case .police: "Police"
        case .accident: "Crash"
        case .hazard: "Hazard"
        case .closure: "Closed"
        case .blockedLane: "Lane"
        case .mapIssue: "Map"
        case .weather: "Weather"
        case .animal: "Animal"
        }
    }

    var gIcon: GIcon {
        switch self {
        case .traffic: .traffic
        case .police: .police
        case .accident: .accident
        case .hazard: .hazard
        case .closure: .closure
        case .blockedLane: .lane
        case .mapIssue: .map
        case .weather: .flood
        case .animal: .animal
        }
    }

    /// Duotone tile / fill / stroke colours (docs/design-tokens.md).
    var duotone: (tile: UInt32, fill: UInt32, stroke: UInt32) {
        switch self {
        case .traffic: (0xFFEBD6, 0xFFB870, 0x8A4300)
        case .police, .mapIssue: (0xE3ECFA, 0x8FB0E8, 0x0A2E6E)
        case .accident: (0xFFE2E2, 0xF5A3A3, 0x8B1A1A)
        case .hazard: (0xFFF1CC, 0xFFD466, 0x7A5200)
        case .closure: (0xECEEF1, 0xC3C8D0, 0x2F3640)
        case .blockedLane: (0xEEE6FF, 0xC4B1F7, 0x4C2A9E)
        case .weather: (0xDDF4F2, 0x8EDCD3, 0x0E5A54)
        case .animal: (0xE8F5D6, 0xB7DC7F, 0x3F6212)
        }
    }

    var tileColor: Color { Color(uiColor: UIColor(hex: duotone.tile)) }
    var fillColor: Color { Color(uiColor: UIColor(hex: duotone.fill)) }
    var strokeColor: Color { Color(uiColor: UIColor(hex: duotone.stroke)) }

    var systemImage: String {
        switch self {
        case .traffic: "car.2.fill"
        case .police: "shield.lefthalf.filled"
        case .accident: "car.side.rear.and.collision.and.car.side.front"
        case .hazard: "exclamationmark.triangle.fill"
        case .closure: "xmark.octagon.fill"
        case .blockedLane: "road.lanes"
        case .mapIssue: "map.fill"
        case .weather: "cloud.heavyrain.fill"
        case .animal: "pawprint.fill"
        }
    }

    var hex: UInt32 {
        switch self {
        case .traffic: 0xFF8A00
        case .police: 0x2563EB
        case .accident: 0xE53935
        case .hazard: 0xF59E0B
        case .closure: 0x6B7280
        case .blockedLane: 0x8B5CF6
        case .mapIssue: 0x0EA5E9
        case .weather: 0x14B8A6
        case .animal: 0x84CC16
        }
    }

    var uiColor: UIColor { UIColor(hex: hex) }
    var color: Color { Color(uiColor: uiColor) }

    /// Reports that accept a free-text comment instead of / in addition to subtypes.
    var wantsComment: Bool { self == .closure || self == .mapIssue }

    var subtypes: [ReportSubtype] {
        switch self {
        case .traffic: [
            .init(id: "moderate", title: "Moderate", systemImage: "gauge.with.dots.needle.33percent"),
            .init(id: "heavy", title: "Heavy", systemImage: "gauge.with.dots.needle.67percent"),
            .init(id: "standstill", title: "Standstill", systemImage: "gauge.with.dots.needle.0percent"),
        ]
        case .police: [
            .init(id: "visible", title: "Visible", systemImage: "eye.fill"),
            .init(id: "hidden", title: "Hidden", systemImage: "eye.slash.fill"),
            .init(id: "roadblock", title: "Roadblock", systemImage: "barricade.fill"),
        ]
        case .accident: [
            .init(id: "minor", title: "Minor", systemImage: "car.fill"),
            .init(id: "major", title: "Major", systemImage: "exclamationmark.octagon.fill"),
            .init(id: "other_side", title: "Opposite", systemImage: "arrow.left.arrow.right"),
        ]
        case .hazard: [
            .init(id: "object_on_road", title: "Object", systemImage: "shippingbox.fill"),
            .init(id: "pothole", title: "Pothole", systemImage: "circle.bottomhalf.filled"),
            .init(id: "stopped_vehicle", title: "Stopped", systemImage: "car.rear.fill"),
            .init(id: "roadkill", title: "Roadkill", systemImage: "hare.fill"),
            .init(id: "construction", title: "Works", systemImage: "hammer.fill"),
            .init(id: "flood", title: "Flood", systemImage: "water.waves"),
            .init(id: "oil_spill", title: "Oil", systemImage: "drop.triangle.fill"),
        ]
        case .closure: []
        case .blockedLane: [
            .init(id: "left", title: "Left", systemImage: "arrow.up.left"),
            .init(id: "center", title: "Centre", systemImage: "arrow.up"),
            .init(id: "right", title: "Right", systemImage: "arrow.up.right"),
        ]
        case .mapIssue: []
        case .weather: [
            .init(id: "heavy_rain", title: "Rain", systemImage: "cloud.heavyrain.fill"),
            .init(id: "fog", title: "Fog", systemImage: "cloud.fog.fill"),
            .init(id: "flood", title: "Flood", systemImage: "water.waves"),
            .init(id: "haze", title: "Haze", systemImage: "sun.haze.fill"),
        ]
        case .animal: [
            .init(id: "on_road", title: "Road", systemImage: "pawprint.fill"),
            .init(id: "on_shoulder", title: "Shoulder", systemImage: "arrow.down.right"),
        ]
        }
    }

    func subtypeTitle(_ id: String?) -> LocalizedStringResource? {
        guard let id else { return nil }
        return subtypes.first { $0.id == id }?.title
    }
}
