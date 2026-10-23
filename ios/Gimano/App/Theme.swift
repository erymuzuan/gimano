import Foundation
import SwiftUI

/// Settings → Theme. Default is Light, even when iOS is in Dark mode (user request).
enum ThemeMode: String, CaseIterable, Identifiable, Sendable {
    case light, dark, system, auto
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .light: "Light"
        case .dark: "Dark"
        case .system: "Follow phone"
        case .auto: "Auto day/night"
        }
    }

    var subtitle: LocalizedStringKey {
        switch self {
        case .light: "Default"
        case .dark: "For night"
        case .system: "System mode"
        case .auto: "Switches at sunset"
        }
    }

    var systemImage: String {
        switch self {
        case .light: "sun.max.fill"
        case .dark: "moon.fill"
        case .system: "iphone"
        case .auto: "sunrise.fill"
        }
    }

    /// The scheme to force, or nil to follow the phone.
    func colorScheme(now: Date, location: LatLng?, timeZone: TimeZone = .current) -> ColorScheme? {
        switch self {
        case .light: .light
        case .dark: .dark
        case .system: nil
        case .auto: SunTimes.isDaytime(at: now, location: location ?? SunTimes.kualaLumpur, timeZone: timeZone) ? .light : .dark
        }
    }
}

/// Sunrise/sunset (NOAA solar position algorithm, ±1–2 min), used for "Auto day/night".
enum SunTimes {
    static let kualaLumpur = LatLng(lat: 3.139, lng: 101.687)

    /// Sunrise and sunset for the calendar day of `date` in `timeZone`. Nil in polar day/night.
    static func times(on date: Date, location: LatLng, timeZone: TimeZone = .current) -> (sunrise: Date, sunset: Date)? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let startOfDay = cal.startOfDay(for: date)
        guard let dayOfYear = cal.ordinality(of: .day, in: .year, for: date) else { return nil }
        let year = cal.component(.year, from: date)
        let daysInYear = Double(cal.range(of: .day, in: .year, for: date)?.count ?? 365)
        _ = year

        // Fractional year (radians) at local noon.
        let gamma = 2 * Double.pi / daysInYear * (Double(dayOfYear) - 1 + (12 - 12) / 24)
        let eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma)
                               - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        let decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma)
            + 0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        let lat = location.lat * .pi / 180
        let zenith = 90.833 * Double.pi / 180
        let cosHA = cos(zenith) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        guard (-1...1).contains(cosHA) else { return nil }
        let ha = acos(cosHA) * 180 / .pi
        // Minutes from 00:00 UTC.
        let sunriseUTC = 720 - 4 * (location.lng + ha) - eqTime
        let sunsetUTC = 720 - 4 * (location.lng - ha) - eqTime
        let offsetMin = Double(timeZone.secondsFromGMT(for: startOfDay)) / 60
        return (startOfDay.addingTimeInterval((sunriseUTC + offsetMin) * 60),
                startOfDay.addingTimeInterval((sunsetUTC + offsetMin) * 60))
    }

    static func isDaytime(at date: Date, location: LatLng, timeZone: TimeZone = .current) -> Bool {
        guard let t = times(on: date, location: location, timeZone: timeZone) else { return true }
        return date >= t.sunrise && date < t.sunset
    }
}
