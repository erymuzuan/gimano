import Foundation
import Testing
@testable import Gimano

/// Fixes from the iPhone parity pass against Android (2026-10-24).
@Suite("Parity fixes")
struct ParityFixesTests {
    @Test("Auto-start countdown: off by default; when on, only one-tap trips from GPS (Android AutoStart)")
    func autoStart() {
        // Off by default: never, even for Home from GPS.
        #expect(!AutoStart.shouldCountdown(enabled: false, quickPick: true, originIsGPS: true, hasStops: false, departLater: false, foreground: true))
        #expect(AutoStart.shouldCountdown(enabled: true, quickPick: true, originIsGPS: true, hasStops: false, departLater: false, foreground: true))
        // Never after a search, from a chosen start, with stops, for later, or in the background.
        #expect(!AutoStart.shouldCountdown(enabled: true, quickPick: false, originIsGPS: true, hasStops: false, departLater: false, foreground: true))
        #expect(!AutoStart.shouldCountdown(enabled: true, quickPick: true, originIsGPS: false, hasStops: false, departLater: false, foreground: true))
        #expect(!AutoStart.shouldCountdown(enabled: true, quickPick: true, originIsGPS: true, hasStops: true, departLater: false, foreground: true))
        #expect(!AutoStart.shouldCountdown(enabled: true, quickPick: true, originIsGPS: true, hasStops: false, departLater: true, foreground: true))
        #expect(!AutoStart.shouldCountdown(enabled: true, quickPick: true, originIsGPS: true, hasStops: false, departLater: false, foreground: false))
    }

    @Test("About / Settings: the public server shows as Manotu, any other server by its host")
    func serverLabel() {
        let prod = "https://gimano-api.bukitbunga.com/api/v1/"
        #expect(ServerLabel.text(current: prod, builtIn: prod, debug: false) == "Manotu")
        #expect(ServerLabel.text(current: "https://gimano-api.bukitbunga.com/api/v1", builtIn: prod, debug: false) == "Manotu")
        #expect(ServerLabel.text(current: "http://100.101.150.9:5080/api/v1/", builtIn: prod, debug: false) == "100.101.150.9")
        // Debug builds always show the host (developers need to know which server).
        #expect(ServerLabel.text(current: prod, builtIn: prod, debug: true) == "gimano-api.bukitbunga.com")
    }

    @Test("Closed until is sent as a Malaysian day (yyyy-MM-dd), which the API requires")
    func closedUntil() {
        // 2026-10-31 17:30 UTC is already 1 Nov in Malaysia (UTC+8).
        let d = Date(timeIntervalSince1970: 1_793_467_800)
        #expect(PlaceIssuePayload.untilDay(d) == "2026-11-01")
        #expect(PlaceIssuePayload.untilDay(d).count == 10)
    }

    @Test("A temporary closure's day comes from payload.until (server shape), so the badge says until when")
    func closedUntilFromPayload() throws {
        let json = #"{"id":"x","kind":"place_closed_temp","state":"suspected","lat":3.1,"lng":101.7,"placeId":"w1","payload":{"until":"2026-10-31","other":5}}"#
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        let issue = try d.decode(Issue.self, from: Data(json.utf8))
        let until = try #require(issue.closedUntil)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Kuala_Lumpur")!
        #expect(cal.dateComponents([.year, .month, .day], from: until) == DateComponents(year: 2026, month: 10, day: 31))
    }
}
