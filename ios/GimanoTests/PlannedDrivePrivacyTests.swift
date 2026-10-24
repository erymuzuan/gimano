import Foundation
import Testing
@testable import Gimano

/// User decision (2026-10-24): planned drives never send the user's position in the background.
@Suite("Planned drives: no position in the background")
@MainActor
struct PlannedDrivePrivacyTests {
    let fix = LatLng(lat: 3.152613, lng: 101.703718)
    let saved = PlannedDrive.Place(lat: 3.1179, lng: 101.6774, name: "Rumah")

    func drive(origin: PlannedDrive.Place?) -> PlannedDrive {
        PlannedDrive(id: "p", name: "Kerja", origin: origin, destination: .init(lat: 3.14, lng: 101.69), waypoints: [], vehicle: .car,
                     departAt: nil, schedule: .init(arriveBy: "08:30", days: ["Mo", "Tu", "We", "Th", "Fr"]), createdAt: .now)
    }

    @Test("Automatic (launch, list): a drive without a fixed start makes no call at all")
    func automaticNoOrigin() async {
        let src = RecordingDepartures()
        let lb = await PlannedDriveNotifier.reschedule(drive(origin: nil), trigger: .automatic, repo: src)
        #expect(lb == nil)
        #expect(await src.origins.isEmpty)
        #expect(PlannedDriveNotifier.origin(for: drive(origin: nil), trigger: .automatic) == nil)
    }

    @Test("Automatic with a saved fixed start uses the saved place, never the live fix")
    func automaticFixedStart() async {
        let src = RecordingDepartures()
        _ = await PlannedDriveNotifier.reschedule(drive(origin: saved), trigger: .automatic, repo: src)
        let origins = await src.origins
        #expect(!origins.isEmpty)
        #expect(origins.allSatisfy { $0 == saved.location })
        #expect(!origins.contains(fix))
    }

    @Test("Only when the user asks does the fresh fix become the origin")
    func userAsked() {
        #expect(PlannedDriveNotifier.origin(for: drive(origin: nil), trigger: .userAsked(fix: fix)) == fix)
        #expect(PlannedDriveNotifier.origin(for: drive(origin: nil), trigger: .userAsked(fix: nil)) == nil)
        // A saved start still wins over the fix.
        #expect(PlannedDriveNotifier.origin(for: drive(origin: saved), trigger: .userAsked(fix: fix)) == saved.location)
    }
}

actor RecordingDepartures: DepartureTimesSource {
    var origins: [LatLng] = []
    func departureTimes(origin: LatLng, destination: LatLng, date: Date, vehicle: Vehicle) async throws -> [DepartureSlot] {
        origins.append(origin)
        return []
    }
}
