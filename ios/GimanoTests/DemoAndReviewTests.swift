import Foundation
import Testing
@testable import Gimano

/// Account-free review flow (2026-10-24): public Demo mode, explicit backup destinations, no fake success.
@Suite("Demo mode and backup destinations")
struct DemoAndReviewTests {
    func tempStore() throws -> (LocalStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("demo-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("p")), dir)
    }

    @Test("Destinations: Drive and a file always; demo storage only in Demo mode; iCloud only with the entitlement")
    func offered() {
        #expect(BackupDestinationKind.offered(iCloudEnabled: false, demoMode: false) == [.googleDrive, .file])
        #expect(BackupDestinationKind.offered(iCloudEnabled: false, demoMode: true) == [.googleDrive, .file, .demo])
        #expect(BackupDestinationKind.offered(iCloudEnabled: true, demoMode: false) == [.googleDrive, .file, .icloud])
        // A saved choice that is no longer offered falls back to a file (no account needed).
        #expect(BackupDestinationKind.normalized(.icloud, iCloudEnabled: false, demoMode: false) == .file)
        #expect(BackupDestinationKind.normalized(.demo, iCloudEnabled: false, demoMode: false) == .file)
        #expect(BackupDestinationKind.normalized(.googleDrive, iCloudEnabled: false, demoMode: true) == .googleDrive)
    }

    @Test("This test build has no iCloud entitlement, so iCloud is never offered as working")
    func noICloudInThisBuild() {
        #expect(!ICloudBackupDestination.isEnabled)
    }

    @Test("Demo storage: a real backup → list → restore round trip on the phone, newest five kept")
    func demoRoundTrip() async throws {
        let (src, dir) = try tempStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = try src.addFavorite(name: "Rumah Mertua", icon: .mertua, lat: 3.1, lng: 101.6, address: "Kampung", placeId: nil)
        let dest = DemoBackupDestination(folder: dir.appendingPathComponent("DemoBackups"))
        let file = try BackupService(store: src, kdfLimits: .interactive).makeBackup(passphrase: "ujian-12345")
        try await dest.upload(file, createdAt: Date(timeIntervalSince1970: 1_800_000_000))
        let listed = try await dest.list()
        #expect(listed.count == 1)
        let back = try await dest.download(listed[0])
        let (dst, dir2) = try tempStore()
        defer { try? FileManager.default.removeItem(at: dir2) }
        _ = try BackupService(store: dst).restore(back, mode: .replace, passphrase: "ujian-12345")
        #expect(try dst.savedPlaces().contains { $0.name == "Rumah Mertua" })
        // Retention keeps the newest five.
        for i in 1...6 { try await dest.upload(file, createdAt: Date(timeIntervalSince1970: 1_800_000_000 + Double(i) * 60)) }
        try await dest.prune(keep: 5)
        #expect(try await dest.list().count == 5)
    }

    @Test("Demo storage reports a missing backup as an error, never as success")
    func noFakeSuccess() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("demo-\(UUID().uuidString)")
        let dest = DemoBackupDestination(folder: dir)
        await #expect(throws: (any Error).self) {
            _ = try await dest.download(RemoteBackup(id: "demo-backup-1.manotu", name: "x", createdAt: .now))
        }
    }

    @Test("Demo mode is one public switch, remembered, and the same for everyone")
    @MainActor
    func demoSwitch() {
        let suite = "demo-test-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        let c = AppConfig(defaults: d)
        c.setDemoMode(true)
        #expect(c.isDemoMode && c.dataMode == .demo)
        #expect(AppConfig(defaults: d).isDemoMode, "remembered across launches")
        c.setDemoMode(false)
        #expect(!c.isDemoMode && c.dataMode == .auto)
    }

    @Test("Demo data includes a closure on the Mid Valley demo route, so a demo drive asks \"Still closed?\"")
    func demoClosure() async throws {
        let repo = DemoRepository(latency: .zero)
        let issues = await repo.issues
        let closure = try #require(issues.first { $0.kind == .roadClosedTemp })
        #expect(closure.state.isOpen)
        let route = try #require(CannedRoutes.shared.route(key: "midvalley", routeIndex: 0))
        let onRoute = Polyline.decode(route.polyline).contains { Geo.distance($0, closure.location) < 1 }
        #expect(onRoute)
    }
}
