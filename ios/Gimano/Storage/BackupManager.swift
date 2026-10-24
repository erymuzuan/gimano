import BackgroundTasks
import Foundation
import Observation
import os

/// Settings → Backup & restore: destination, last backup, "Back up now", "Restore…", auto-backup, passphrase.
@MainActor
@Observable
final class BackupManager {
    /// "<bundle id>.backup" (listed in Info.plist BGTaskSchedulerPermittedIdentifiers via the same build setting).
    nonisolated static let taskIdentifier = (Bundle.main.bundleIdentifier ?? "my.manotu.app") + ".backup"
    static let keep = 5

    let store: LocalStore
    private(set) var isWorking = false
    private(set) var status: String?
    private(set) var lastError: String?
    private(set) var remoteBackups: [RemoteBackup] = []

    var destination: BackupDestinationKind {
        didSet { try? store.setSetting("backup.destination", destination.rawValue) }
    }
    var autoBackup: Bool {
        didSet {
            try? store.setSetting("backup.auto", autoBackup ? "1" : "0")
            if autoBackup { Self.schedule() } else { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier) }
        }
    }

    @ObservationIgnored private let log = Logger(subsystem: "my.gimano.app", category: "backup")

    /// Demo mode is on: "Demo storage (on this phone)" is offered (set by the app model).
    var demoMode = false { didSet { normalizeDestination() } }

    /// Destinations this build and mode offer, in screen order.
    var offeredDestinations: [BackupDestinationKind] {
        BackupDestinationKind.offered(iCloudEnabled: ICloudBackupDestination.isEnabled, driveEnabled: GoogleDriveBackupDestination.isEnabled,
                                      demoMode: demoMode)
    }

    init(store: LocalStore) {
        self.store = store
        let saved = (try? store.setting("backup.destination")).flatMap(BackupDestinationKind.init(rawValue:))
        destination = BackupDestinationKind.normalized(saved ?? .file, iCloudEnabled: ICloudBackupDestination.isEnabled,
                                                       driveEnabled: GoogleDriveBackupDestination.isEnabled, demoMode: false)
        autoBackup = (try? store.setting("backup.auto")) == "1"
    }

    private func normalizeDestination() {
        let n = BackupDestinationKind.normalized(destination, iCloudEnabled: ICloudBackupDestination.isEnabled,
                                                 driveEnabled: GoogleDriveBackupDestination.isEnabled, demoMode: demoMode)
        if n != destination { destination = n; remoteBackups = [] }
    }

    var lastBackupAt: Date? { LocalStore.date(try? store.setting("backup.lastAt")) }
    var lastBackupSize: Int64? { (try? store.setting("backup.lastSize")).flatMap { Int64($0) } }

    func destinationImpl(_ kind: BackupDestinationKind) -> (any BackupDestination)? {
        Self.destination(kind, iCloudEnabled: ICloudBackupDestination.isEnabled, driveEnabled: GoogleDriveBackupDestination.isEnabled,
                         demoMode: demoMode)
    }

    /// The one factory for backup destinations (Back up now, restore list, download and auto-backup all go through it).
    /// A destination this build doesn't have resolves to nil, so e.g. a `GIMANO_DRIVE = NO` build can't reach Drive.
    nonisolated static func destination(_ kind: BackupDestinationKind, iCloudEnabled: Bool, driveEnabled: Bool,
                                        demoMode: Bool) -> (any BackupDestination)? {
        switch kind {
        case .icloud: iCloudEnabled ? ICloudBackupDestination() : nil
        case .googleDrive: driveEnabled ? GoogleDriveBackupDestination() : nil
        case .file: nil
        case .demo: demoMode ? DemoBackupDestination() : nil
        }
    }

    /// Automatic daily backups need a cloud destination this build has (iCloud or Google Drive). None in the store build.
    var canAutoBackup: Bool {
        (destination == .icloud || destination == .googleDrive) && destinationImpl(destination) != nil
    }

    /// Creates a backup file (for Export or upload).
    func makeFile(passphrase: String?) async throws -> URL {
        let service = BackupService(store: store)
        return try await Task.detached(priority: .userInitiated) { try service.makeBackup(passphrase: passphrase) }.value
    }

    /// Back up now to the chosen cloud (keeps the newest 5).
    func backUpNow(passphrase: String?) async {
        guard let dest = destinationImpl(destination) else { return }
        isWorking = true
        lastError = nil
        status = String(localized: "Backing up…")
        defer { isWorking = false }
        do {
            let file = try await makeFile(passphrase: passphrase)
            let now = Date()
            try await dest.upload(file, createdAt: now)
            try? await dest.prune(keep: Self.keep)
            let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
            try? store.setSetting("backup.lastAt", LocalStore.timestamp(now))
            try? store.setSetting("backup.lastSize", String(size))
            try? store.setSetting("backup.lastChange", try? store.lastChangeAt())
            status = destination != .demo ? String(localized: "Backed up")
                : GoogleDriveBackupDestination.isEnabled ? String(localized: "Saved to demo storage on this phone (not Google Drive)")
                : String(localized: "Saved to demo storage on this phone (not a cloud backup)")
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        } catch {
            lastError = error.localizedDescription
            status = nil
            log.error("Backup failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func refreshRemote() async {
        guard let dest = destinationImpl(destination) else { remoteBackups = []; return }
        do {
            remoteBackups = try await dest.list()
            lastError = nil
        } catch {
            remoteBackups = []
            lastError = error.localizedDescription
        }
    }

    /// Downloads a cloud backup to a local file (so its manifest can be shown before restoring).
    func download(_ remote: RemoteBackup) async throws -> URL {
        guard let dest = destinationImpl(destination) else { throw BackupError.notABackup }
        isWorking = true
        defer { isWorking = false }
        return try await dest.download(remote)
    }

    func restore(remote: RemoteBackup, mode: RestoreMode, passphrase: String?) async throws {
        guard let dest = destinationImpl(destination) else { return }
        isWorking = true
        defer { isWorking = false }
        let file = try await dest.download(remote)
        try await restore(file: file, mode: mode, passphrase: passphrase)
    }

    func restore(file: URL, mode: RestoreMode, passphrase: String?) async throws {
        isWorking = true
        defer { isWorking = false }
        let service = BackupService(store: store)
        let accessed = file.startAccessingSecurityScopedResource()
        defer { if accessed { file.stopAccessingSecurityScopedResource() } }
        // Copy out of the file provider first (security-scoped URLs can't be unzipped in place reliably).
        let local = FileManager.default.temporaryDirectory.appendingPathComponent("restore-\(UUID().uuidString).gimano")
        try FileManager.default.copyItem(at: file, to: local)
        defer { try? FileManager.default.removeItem(at: local) }
        _ = try await Task.detached(priority: .userInitiated) { try service.restore(local, mode: mode, passphrase: passphrase) }.value
        status = String(localized: "Restored")
    }

    func manifest(of file: URL) -> BackupManifest? {
        let accessed = file.startAccessingSecurityScopedResource()
        defer { if accessed { file.stopAccessingSecurityScopedResource() } }
        return try? BackupService.readManifest(file)
    }

    // MARK: automatic daily backup

    /// Registers the BGProcessingTask handler (call before the app finishes launching).
    nonisolated static func registerBackgroundTask(_ handler: @escaping @Sendable (BGProcessingTask) -> Void) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let task = task as? BGProcessingTask else { task.setTaskCompleted(success: false); return }
            handler(task)
        }
    }

    /// Daily, on Wi-Fi/network and while charging.
    nonisolated static func schedule() {
        let req = BGProcessingTaskRequest(identifier: taskIdentifier)
        req.requiresExternalPower = true
        req.requiresNetworkConnectivity = true
        req.earliestBeginDate = Date().addingTimeInterval(24 * 3600)
        try? BGTaskScheduler.shared.submit(req)
    }

    /// Runs an automatic backup only when data changed since the last one. Encrypted backups need the passphrase,
    /// which is never stored, so auto-backup is unencrypted (the UI says so).
    func runAutoBackup() async -> Bool {
        defer { if autoBackup { Self.schedule() } }
        // Automatic backups go to the user's own cloud only (never a file or the demo storage).
        guard autoBackup, canAutoBackup else { return true }
        let changed = (try? store.lastChangeAt()) ?? nil
        guard changed != nil, changed != (try? store.setting("backup.lastChange")) ?? nil else { return true }
        await backUpNow(passphrase: nil)
        return lastError == nil
    }
}

/// One-time import of server-side favourites/recents from earlier app versions (docs/api.md "Saved places").
struct LegacyDataMigration {
    struct Export: Codable, Sendable {
        var savedPlaces: [SavedPlace]?
        var recents: [Recent]?
    }

    static let doneKey = "legacy.migrated"

    /// Imports the legacy export into the store. Home/Work replace local ones only if absent.
    static func importExport(_ e: Export, into store: LocalStore) throws -> Int {
        var n = 0
        let existing = try store.savedPlaces()
        for p in e.savedPlaces ?? [] {
            switch p.kind {
            case .home, .work:
                if !existing.contains(where: { $0.kind == p.kind }) {
                    try store.setHomeWork(p.kind, name: p.name, lat: p.lat, lng: p.lng, address: p.address, placeId: p.placeId)
                    n += 1
                }
            case .favorite:
                let dup = existing.contains { $0.kind == .favorite && $0.name == p.name && Geo.distance($0.location, p.location) < 30 }
                if !dup {
                    try store.addFavorite(name: p.name, icon: p.favoriteIcon, lat: p.lat, lng: p.lng, address: p.address, placeId: p.placeId)
                    n += 1
                }
            }
        }
        for r in (e.recents ?? []).reversed() {
            try store.recordRecent(name: r.name, address: r.address, lat: r.lat, lng: r.lng, placeId: r.placeId, at: r.lastUsedAt ?? .now)
            n += 1
        }
        return n
    }
}
