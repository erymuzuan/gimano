import Foundation
import Testing
@testable import Gimano

private func maneuver(_ type: ManeuverType, street: String? = nil, ref: String? = nil, exit: String? = nil) -> Maneuver {
    Maneuver(type: type, instruction: "", streetName: street, exitNumber: exit, roadRef: ref, distanceM: 100, durationS: 10,
             beginShapeIndex: 0, endShapeIndex: 1, verbalPre: nil, verbalPost: nil)
}

@Suite("Maneuver formatting")
struct ManeuverFormatterTests {
    @Test("Distances: 10 m steps below 1 km, one decimal below 10 km, integers above", arguments: [
        (0.0, "0 m"), (4.0, "0 m"), (6.0, "10 m"), (237.0, "240 m"), (994.0, "990 m"), (996.0, "1.0 km"),
        (1_249.0, "1.2 km"), (3_750.0, "3.8 km"), (9_960.0, "10 km"), (12_400.0, "12 km"), (260_000.0, "260 km"), (-5.0, "0 m"),
    ])
    func distance(meters: Double, expected: String) {
        #expect(ManeuverFormatter.distance(meters) == expected)
    }

    @Test("Instructions include the road name when present")
    func instructions() {
        #expect(ManeuverFormatter.instruction(for: maneuver(.left, street: "Jalan Ampang")) == "Turn left onto Jalan Ampang")
        #expect(ManeuverFormatter.instruction(for: maneuver(.right)) == "Turn right")
        #expect(ManeuverFormatter.instruction(for: maneuver(.arrive)) == "You have arrived")
        #expect(ManeuverFormatter.instruction(for: maneuver(.uturnRight)) == "Make a U-turn")
        #expect(ManeuverFormatter.instruction(for: maneuver(.keepLeft, street: "Lebuhraya DUKE", ref: "E33")) == "Keep left toward E33 Lebuhraya DUKE")
    }

    @Test("Exit numbers are used for ramps and exits")
    func exits() {
        #expect(ManeuverFormatter.instruction(for: maneuver(.exitLeft, street: "SS2 Petaling Jaya", exit: "1107")) == "Take exit 1107 toward SS2 Petaling Jaya")
        #expect(ManeuverFormatter.instruction(for: maneuver(.rampRight)) == "Take the ramp on the right")
    }

    @Test("Road name avoids duplicating the ref")
    func roadName() {
        #expect(ManeuverFormatter.roadName(maneuver(.straight, street: "E11 LDP", ref: "E11")) == "E11 LDP")
        #expect(ManeuverFormatter.roadName(maneuver(.straight, street: "LDP", ref: "E11")) == "E11 LDP")
        #expect(ManeuverFormatter.roadName(maneuver(.straight, street: "  ", ref: nil)) == nil)
    }

    @Test("Every maneuver type has an SF Symbol and an instruction")
    func everyType() {
        for t in ManeuverType.allCases {
            #expect(!ManeuverFormatter.symbol(for: t).isEmpty)
            #expect(!ManeuverFormatter.instruction(for: maneuver(t)).isEmpty)
        }
    }

    @Test("Voice prompts round distances for speech")
    func voice() {
        #expect(ManeuverFormatter.spokenDistance(430) == "400 metres")
        #expect(ManeuverFormatter.spokenDistance(160) == "150 metres")
        #expect(ManeuverFormatter.spokenDistance(1_480) == "1.5 kilometres")
        #expect(ManeuverFormatter.spokenDistance(2_000) == "2 kilometres")
        #expect(ManeuverFormatter.voicePrompt(for: maneuver(.left, street: "Jalan Duta"), distanceM: 420) == "In 400 metres, turn left onto Jalan Duta")
        #expect(ManeuverFormatter.voicePrompt(for: maneuver(.right), distanceM: nil) == "Turn right")
    }

    @Test("Announcer speaks early and near prompts once each")
    func announcer() {
        var a = VoiceAnnouncer()
        let m = maneuver(.left, street: "Jalan Duta")
        let r1 = a.prompt(maneuverIndex: 1, maneuver: m, distanceM: 2000, speedKmh: 50)
        #expect(r1 == nil)
        let r2 = a.prompt(maneuverIndex: 1, maneuver: m, distanceM: 550, speedKmh: 50)
        #expect(r2 == "In 600 metres, turn left onto Jalan Duta")
        let r3 = a.prompt(maneuverIndex: 1, maneuver: m, distanceM: 500, speedKmh: 50)
        #expect(r3 == nil)
        let r4 = a.prompt(maneuverIndex: 1, maneuver: m, distanceM: 70, speedKmh: 50)
        #expect(r4 == "Turn left onto Jalan Duta")
        let r5 = a.prompt(maneuverIndex: 1, maneuver: m, distanceM: 40, speedKmh: 50)
        #expect(r5 == nil)
    }
}

@Suite("ETA formatting")
struct ETAFormatterTests {
    let utc = TimeZone(identifier: "UTC")!
    let kl = TimeZone(identifier: "Asia/Kuala_Lumpur")!

    @Test("Durations", arguments: [
        (0.0, "0 min"), (20.0, "1 min"), (89.0, "1 min"), (91.0, "2 min"), (600.0, "10 min"),
        (3_600.0, "1 h"), (3_900.0, "1 h 5 min"), (7_170.0, "2 h"), (9_000.0, "2 h 30 min"),
    ])
    func duration(seconds: Double, expected: String) {
        #expect(ETAFormatter.duration(seconds) == expected)
    }

    @Test("Arrival clock time uses the given time zone (Malaysia UTC+8)")
    func arrival() {
        let now = Date(timeIntervalSince1970: 1_761_177_000) // 2025-10-22T23:50:00Z
        let en = Locale(identifier: "en_US")
        let s = ETAFormatter.arrivalTime(now: now, remainingS: 23 * 60, locale: en, timeZone: kl)
        #expect(s.replacingOccurrences(of: "\u{202F}", with: " ") == "8:13 AM")
        let utcS = ETAFormatter.arrivalTime(now: now, remainingS: 23 * 60, locale: Locale(identifier: "en_GB"), timeZone: utc)
        #expect(utcS == "00:13")
    }

    @Test("Negative remaining time clamps to now")
    func negative() {
        let now = Date(timeIntervalSince1970: 0)
        #expect(ETAFormatter.arrivalTime(now: now, remainingS: -500, locale: Locale(identifier: "en_GB"), timeZone: utc) == "00:00")
    }

    @Test("Trip summary combines duration and distance")
    func summary() {
        #expect(ETAFormatter.tripSummary(remainingS: 600, remainingM: 3_700) == "10 min • 3.7 km")
    }

    @Test("Delay only shown for a minute or more")
    func delay() {
        #expect(ETAFormatter.delay(30) == nil)
        #expect(ETAFormatter.delay(300) == "+5 min")
    }
}
