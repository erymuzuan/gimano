import Foundation

/// Turn-by-turn text, icons and distances. All user-facing strings go through the String Catalog (en + ms).
enum ManeuverFormatter {
    static func symbol(for type: ManeuverType) -> String {
        switch type {
        case .depart, .straight: "arrow.up"
        case .arrive: "flag.checkered"
        case .slightLeft, .keepLeft: "arrow.up.left"
        case .slightRight, .keepRight: "arrow.up.right"
        case .left: "arrow.turn.up.left"
        case .right: "arrow.turn.up.right"
        case .sharpLeft: "arrow.down.left"
        case .sharpRight: "arrow.down.right"
        case .uturnLeft: "arrow.uturn.down"
        case .uturnRight: "arrow.uturn.down"
        case .roundaboutEnter, .roundaboutExit: "arrow.triangle.2.circlepath"
        case .merge: "arrow.triangle.merge"
        case .rampLeft, .exitLeft: "arrow.up.left"
        case .rampRight, .exitRight: "arrow.up.right"
        case .ferry: "ferry.fill"
        }
    }

    /// "E11 Lebuhraya Damansara–Puchong" style road label.
    static func roadName(_ m: Maneuver) -> String? {
        let parts = [m.roadRef, m.streetName].compactMap { $0?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        // Avoid "E11 E11 LDP" when the street already starts with the ref.
        if parts.count == 2, parts[1].hasPrefix(parts[0]) { return parts[1] }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    /// - Parameters:
    ///   - mode: travel mode; walking/cycling get their own departure wording.
    ///   - stopName: set for the arrival maneuver of an intermediate stop.
    static func instruction(for m: Maneuver, mode: Vehicle = .car, stopName: String? = nil) -> String {
        let road = roadName(m)
        switch m.type {
        case .depart:
            switch mode {
            case .walk:
                if let road { return String(localized: "Walk along \(road)") }
                return String(localized: "Start walking")
            case .bicycle:
                if let road { return String(localized: "Cycle along \(road)") }
                return String(localized: "Start cycling")
            default:
                if let road { return String(localized: "Head out on \(road)") }
                return String(localized: "Head out")
            }
        case .arrive:
            if let stopName { return String(localized: "Arrive at \(stopName)") }
            return String(localized: "You have arrived")
        case .straight:
            if let road { return String(localized: "Continue on \(road)") }
            return String(localized: "Continue straight")
        case .slightLeft:
            if let road { return String(localized: "Bear left onto \(road)") }
            return String(localized: "Bear left")
        case .slightRight:
            if let road { return String(localized: "Bear right onto \(road)") }
            return String(localized: "Bear right")
        case .left:
            if let road { return String(localized: "Turn left onto \(road)") }
            return String(localized: "Turn left")
        case .right:
            if let road { return String(localized: "Turn right onto \(road)") }
            return String(localized: "Turn right")
        case .sharpLeft:
            if let road { return String(localized: "Sharp left onto \(road)") }
            return String(localized: "Sharp left")
        case .sharpRight:
            if let road { return String(localized: "Sharp right onto \(road)") }
            return String(localized: "Sharp right")
        case .uturnLeft, .uturnRight:
            return String(localized: "Make a U-turn")
        case .roundaboutEnter:
            if let road { return String(localized: "Enter the roundabout toward \(road)") }
            return String(localized: "Enter the roundabout")
        case .roundaboutExit:
            if let road { return String(localized: "Exit the roundabout onto \(road)") }
            return String(localized: "Exit the roundabout")
        case .merge:
            if let road { return String(localized: "Merge onto \(road)") }
            return String(localized: "Merge")
        case .rampLeft, .rampRight, .exitLeft, .exitRight:
            if let exit = m.exitNumber, !exit.isEmpty {
                if let road { return String(localized: "Take exit \(exit) toward \(road)") }
                return String(localized: "Take exit \(exit)")
            }
            if let road { return String(localized: "Take the ramp toward \(road)") }
            return m.type == .rampLeft || m.type == .exitLeft
                ? String(localized: "Take the ramp on the left")
                : String(localized: "Take the ramp on the right")
        case .keepLeft:
            if let road { return String(localized: "Keep left toward \(road)") }
            return String(localized: "Keep left")
        case .keepRight:
            if let road { return String(localized: "Keep right toward \(road)") }
            return String(localized: "Keep right")
        case .ferry:
            return String(localized: "Board the ferry")
        }
    }

    enum DistanceUnit: Equatable { case m, km }

    /// Rounds a distance the way a driver reads it: 10 m steps below 1 km, one decimal below 10 km.
    static func rounded(_ meters: Double) -> (value: Double, unit: DistanceUnit) {
        let m = max(0, meters)
        if m < 1000 {
            let r = (m / 10).rounded() * 10
            if r < 1000 { return (r, .m) }
        }
        let km = m / 1000
        if km < 10 {
            return ((km * 10).rounded() / 10, .km)
        }
        return (km.rounded(), .km)
    }

    /// "240 m", "1.2 km", "12 km".
    static func distance(_ meters: Double) -> String {
        let r = rounded(meters)
        switch r.unit {
        case .m: return "\(Int(r.value)) m"
        case .km:
            if r.value < 10 { return String(format: "%.1f km", locale: Locale(identifier: "en_US_POSIX"), r.value) }
            return "\(Int(r.value)) km"
        }
    }

    /// Spoken distance, rounded more aggressively for speech ("400 metres", "1.5 kilometres").
    static func spokenDistance(_ meters: Double) -> String {
        if meters < 1000 {
            let step: Double = meters < 200 ? 50 : 100
            let r = Int(max(step, (meters / step).rounded() * step))
            if r < 1000 { return String(localized: "\(r) metres") }
        }
        let km = (meters / 100).rounded() / 10
        if km == km.rounded() { return String(localized: "\(Int(km)) kilometres") }
        return String(localized: "\(String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), km)) kilometres")
    }

    /// Voice prompt for a maneuver. `distanceM == nil` means "now".
    static func voicePrompt(for m: Maneuver, distanceM: Double?, mode: Vehicle = .car, stopName: String? = nil) -> String {
        let text = instruction(for: m, mode: mode, stopName: stopName)
        guard let distanceM else { return text }
        if m.type == .arrive, let stopName {
            return String(localized: "In \(spokenDistance(distanceM)), you will arrive at \(stopName)")
        }
        if m.type == .arrive {
            return String(localized: "In \(spokenDistance(distanceM)), you will arrive at your destination")
        }
        return String(localized: "In \(spokenDistance(distanceM)), \(lowercasedFirst(text))")
    }

    private static func lowercasedFirst(_ s: String) -> String {
        guard let f = s.first else { return s }
        return f.lowercased() + s.dropFirst()
    }
}

/// ETA, durations and trip summaries.
enum ETAFormatter {
    /// Arrival clock time, e.g. "8:13 AM" (en) / "8:13 PG" (ms).
    static func arrivalTime(now: Date = .now, remainingS: Double, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = timeZone
        f.dateStyle = .none
        f.timeStyle = .short
        return f.string(from: now.addingTimeInterval(max(0, remainingS)))
    }

    /// Whole minutes, rounded; never shows "0 min" for a non-zero trip.
    static func minutes(_ seconds: Double) -> Int {
        guard seconds > 0 else { return 0 }
        return max(1, Int((seconds / 60).rounded()))
    }

    /// "10 min", "1 h 5 min", "2 h".
    static func duration(_ seconds: Double) -> String {
        let total = minutes(seconds)
        let h = total / 60
        let m = total % 60
        if h == 0 { return String(localized: "\(m) min") }
        if m == 0 { return String(localized: "\(h) h") }
        return String(localized: "\(h) h \(m) min")
    }

    /// "10 min • 3.7 km"
    static func tripSummary(remainingS: Double, remainingM: Double) -> String {
        "\(duration(remainingS)) • \(ManeuverFormatter.distance(remainingM))"
    }

    /// Spoken/visible delay, e.g. "+5 min".
    static func delay(_ seconds: Double) -> String? {
        let m = minutes(seconds)
        guard seconds >= 60 else { return nil }
        return "+\(duration(Double(m) * 60))"
    }
}
