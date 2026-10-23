import Foundation
import SwiftUI
import Testing
@testable import Gimano

@Suite("Theme")
struct ThemeTests {
    let myt = TimeZone(identifier: "Asia/Kuala_Lumpur")!

    private func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 12, _ min: Int = 0) -> Date {
        var c = DateComponents(year: y, month: m, day: d, hour: h, minute: min)
        c.timeZone = myt
        return Calendar(identifier: .gregorian).date(from: c)!
    }

    @Test("Kuala Lumpur sunrise ≈ 06:55–07:25, sunset ≈ 19:00–19:30 MYT all year")
    func klSunTimes() throws {
        for (m, d) in [(1, 15), (4, 15), (7, 15), (10, 23)] {
            let t = try #require(SunTimes.times(on: date(2026, m, d), location: SunTimes.kualaLumpur, timeZone: myt))
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = myt
            let rise = cal.dateComponents([.hour, .minute], from: t.sunrise)
            let set = cal.dateComponents([.hour, .minute], from: t.sunset)
            let riseMin = rise.hour! * 60 + rise.minute!, setMin = set.hour! * 60 + set.minute!
            #expect((6 * 60 + 50...7 * 60 + 30).contains(riseMin), "sunrise \(rise) on \(m)/\(d)")
            #expect((18 * 60 + 55...19 * 60 + 35).contains(setMin), "sunset \(set) on \(m)/\(d)")
        }
    }

    @Test("Auto day/night switches around sunrise and sunset")
    func auto() {
        #expect(ThemeMode.auto.colorScheme(now: date(2026, 10, 23, 12), location: SunTimes.kualaLumpur, timeZone: myt) == .light)
        #expect(ThemeMode.auto.colorScheme(now: date(2026, 10, 23, 21), location: SunTimes.kualaLumpur, timeZone: myt) == .dark)
        #expect(ThemeMode.auto.colorScheme(now: date(2026, 10, 23, 5), location: SunTimes.kualaLumpur, timeZone: myt) == .dark)
    }

    @Test("Light is forced by default; Follow phone forces nothing")
    func modes() {
        #expect(ThemeMode.light.colorScheme(now: .now, location: nil) == .light)
        #expect(ThemeMode.dark.colorScheme(now: .now, location: nil) == .dark)
        #expect(ThemeMode.system.colorScheme(now: .now, location: nil) == nil)
    }

    @Test("Polar night/day has no sunrise (falls back to day)")
    func polar() {
        let svalbard = LatLng(lat: 78.2, lng: 15.6)
        #expect(SunTimes.times(on: date(2026, 6, 21), location: svalbard, timeZone: .gmt) == nil)
        #expect(SunTimes.isDaytime(at: date(2026, 12, 21), location: svalbard, timeZone: .gmt))
    }

    @Test("Theme persists in the local settings table")
    @MainActor
    func persisted() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("th-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("p"))
        let model = AppModel(store: store)
        #expect(model.theme == .light)
        model.theme = .auto
        #expect(try store.setting("theme") == "auto")
        let again = AppModel(store: store)
        #expect(again.theme == .auto)
    }
}
