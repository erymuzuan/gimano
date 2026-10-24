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

    /// The build flags this test run was built with (Info.plist `GimanoICloudEnabled` / `GimanoDriveEnabled`, from
    /// `GIMANO_ICLOUD` / `GIMANO_DRIVE`). Tests that depend on the build assert against these, never a fixed answer.
    static var iCloudBuild: Bool { ICloudBackupDestination.isEnabled }
    static var driveBuild: Bool { GoogleDriveBackupDestination.isEnabled }
    /// The store/TestFlight configuration: GIMANO_ICLOUD=NO GIMANO_DRIVE=NO.
    static var storeBuild: Bool { !iCloudBuild && !driveBuild }

    @Test("Destinations: a file always; Drive only with GIMANO_DRIVE; demo storage only in Demo mode; iCloud only with the entitlement")
    func offered() {
        #expect(BackupDestinationKind.offered(iCloudEnabled: false, driveEnabled: true, demoMode: false) == [.googleDrive, .file])
        #expect(BackupDestinationKind.offered(iCloudEnabled: false, driveEnabled: true, demoMode: true) == [.googleDrive, .file, .demo])
        #expect(BackupDestinationKind.offered(iCloudEnabled: true, driveEnabled: true, demoMode: false) == [.googleDrive, .file, .icloud])
        #expect(BackupDestinationKind.offered(iCloudEnabled: false, driveEnabled: false, demoMode: false) == [.file])
        #expect(BackupDestinationKind.offered(iCloudEnabled: false, driveEnabled: false, demoMode: true) == [.file, .demo])
        #expect(BackupDestinationKind.offered(iCloudEnabled: true, driveEnabled: false, demoMode: false) == [.file, .icloud])
        // A saved choice that is no longer offered falls back to a file (no account needed).
        #expect(BackupDestinationKind.normalized(.icloud, iCloudEnabled: false, driveEnabled: true, demoMode: false) == .file)
        #expect(BackupDestinationKind.normalized(.demo, iCloudEnabled: false, driveEnabled: true, demoMode: false) == .file)
        #expect(BackupDestinationKind.normalized(.googleDrive, iCloudEnabled: false, driveEnabled: true, demoMode: true) == .googleDrive)
        #expect(BackupDestinationKind.normalized(.googleDrive, iCloudEnabled: true, driveEnabled: false, demoMode: true) == .file)
    }

    @Test("Drive disabled: the factory has no Drive destination, so Back up now, restore and auto-backup can't reach Drive")
    func factoryWithoutDrive() {
        for iCloud in [false, true] {
            for demo in [false, true] {
                #expect(BackupManager.destination(.googleDrive, iCloudEnabled: iCloud, driveEnabled: false, demoMode: demo) == nil)
                let offered = BackupDestinationKind.offered(iCloudEnabled: iCloud, driveEnabled: false, demoMode: demo)
                #expect(!offered.contains(.googleDrive))
                #expect(offered.compactMap { BackupManager.destination($0, iCloudEnabled: iCloud, driveEnabled: false, demoMode: demo) }
                    .allSatisfy { !($0 is GoogleDriveBackupDestination) && $0.kind != .googleDrive })
            }
        }
    }

    @Test("Drive enabled: the factory returns the Google Drive destination and it is offered")
    func factoryWithDrive() throws {
        let d = try #require(BackupManager.destination(.googleDrive, iCloudEnabled: false, driveEnabled: true, demoMode: false))
        #expect(d.kind == .googleDrive && d is GoogleDriveBackupDestination)
        #expect(BackupDestinationKind.offered(iCloudEnabled: false, driveEnabled: true, demoMode: false).contains(.googleDrive))
    }

    @Test("The app's own backup manager follows this build's GIMANO_DRIVE / GIMANO_ICLOUD flags")
    @MainActor
    func managerFollowsBuildFlags() throws {
        let (store, dir) = try tempStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        // A choice saved by a build that had Drive (e.g. a sideload build) and auto-backup on.
        try store.setSetting("backup.destination", BackupDestinationKind.googleDrive.rawValue)
        try store.setSetting("backup.auto", "1")
        let m = BackupManager(store: store)
        #expect(m.offeredDestinations.contains(.googleDrive) == Self.driveBuild)
        #expect(m.offeredDestinations.contains(.icloud) == Self.iCloudBuild)
        #expect((m.destinationImpl(.googleDrive) != nil) == Self.driveBuild)
        #expect((m.destinationImpl(.icloud) != nil) == Self.iCloudBuild)
        #expect(m.destination == (Self.driveBuild ? .googleDrive : .file))
        #expect(m.canAutoBackup == Self.driveBuild)
        m.demoMode = true
        #expect(m.offeredDestinations.contains(.demo))
        #expect(m.offeredDestinations.contains(.googleDrive) == Self.driveBuild)
    }

    @Test("Store build (GIMANO_ICLOUD=NO GIMANO_DRIVE=NO): no iCloud, no Drive, no Google client id, no auto-backup",
          .enabled(if: DemoAndReviewTests.storeBuild, "runs only in the store configuration"))
    @MainActor
    func storeBuildHasNoCloud() async throws {
        #expect(!ICloudBackupDestination.isEnabled)
        #expect(!GoogleDriveBackupDestination.isEnabled)
        #expect(!GoogleDriveBackupDestination.isConfigured)
        #expect(GoogleDriveBackupDestination.clientID == nil)
        let gid = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String ?? ""
        #expect(!gid.hasSuffix(".apps.googleusercontent.com"), "no Google OAuth client in the store build's Info.plist")
        await #expect(throws: (any Error).self) { _ = try await GoogleDriveBackupDestination.signIn() }
        #expect(await GoogleDriveBackupDestination.restoreSession() == nil)

        let (store, dir) = try tempStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        try store.setSetting("backup.destination", BackupDestinationKind.googleDrive.rawValue)
        try store.setSetting("backup.auto", "1")
        _ = try store.addFavorite(name: "Rumah", icon: .home, lat: 3.1, lng: 101.6, address: nil, placeId: nil)
        let m = BackupManager(store: store)
        #expect(m.offeredDestinations == [.file])
        #expect(m.destination == .file)
        #expect(!m.canAutoBackup)
        // Auto-backup is a no-op: nothing uploaded, no error, no "Backed up".
        #expect(await m.runAutoBackup())
        #expect(m.lastBackupAt == nil && m.status == nil && m.lastError == nil)
        m.demoMode = true
        #expect(m.offeredDestinations == [.file, .demo])
    }

    /// Records schedule/cancel calls instead of touching BGTaskScheduler.
    final class RecordingScheduler: AutoBackupScheduling, @unchecked Sendable {
        var calls: [String] = []
        func schedule() { calls.append("schedule") }
        func cancel() { calls.append("cancel") }
    }

    @Test("Auto-backup schedule follows the build: never rescheduled without a cloud destination, a pending request is cancelled")
    @MainActor
    func autoBackupScheduleFollowsBuild() async throws {
        let (store, dir) = try tempStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        // Left by an earlier build with Drive: destination Drive, auto-backup on, a BGTask pending.
        try store.setSetting("backup.destination", BackupDestinationKind.googleDrive.rawValue)
        try store.setSetting("backup.auto", "1")
        let m = BackupManager(store: store)
        let rec = RecordingScheduler()
        m.scheduler = rec
        m.syncAutoBackupSchedule()   // app start
        _ = await m.runAutoBackup()   // the pending BGTask fires
        let expected = Self.driveBuild ? "schedule" : "cancel"
        #expect(rec.calls == [expected, expected])
        if !Self.driveBuild { #expect(!rec.calls.contains("schedule")) }
        // Turning auto-backup off always cancels.
        rec.calls = []
        m.autoBackup = false
        #expect(rec.calls == ["cancel"])
    }

    @Test("Dev/sideload build (GIMANO_DRIVE=YES): Google Drive is offered",
          .enabled(if: DemoAndReviewTests.driveBuild, "runs only when Drive is built in"))
    @MainActor
    func driveBuildOffersDrive() throws {
        #expect(GoogleDriveBackupDestination.isEnabled)
        let (store, dir) = try tempStore()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(BackupManager(store: store).offeredDestinations.first == .googleDrive)
    }

    @Test("Without the iCloud entitlement iCloud is never offered as working (follows GIMANO_ICLOUD)")
    func noICloudInThisBuild() {
        let icloudFlag = (Bundle.main.object(forInfoDictionaryKey: "GimanoICloudEnabled") as? String)?.uppercased() == "YES"
        #expect(ICloudBackupDestination.isEnabled == icloudFlag)
        if !icloudFlag {
            #expect(BackupManager.destination(.icloud, iCloudEnabled: ICloudBackupDestination.isEnabled, driveEnabled: true, demoMode: false) == nil)
        }
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
