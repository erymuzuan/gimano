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
}
