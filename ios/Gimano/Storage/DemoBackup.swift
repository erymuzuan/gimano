import Foundation

extension BackupDestinationKind {
    /// Destinations offered in this build and mode, in screen order. Google Drive (the user's own account) and a file
    /// are always there; "Demo storage (on this phone)" only in Demo mode; iCloud only when the build has the entitlement
    /// (otherwise it is shown as unavailable, never as a working choice).
    static func offered(iCloudEnabled: Bool, demoMode: Bool) -> [BackupDestinationKind] {
        var kinds: [BackupDestinationKind] = [.googleDrive, .file]
        if demoMode { kinds.append(.demo) }
        if iCloudEnabled { kinds.append(.icloud) }
        return kinds
    }

    /// A saved choice that is no longer offered (iCloud in a build without it, Demo storage after Demo mode) falls back
    /// to a file, which needs no account.
    static func normalized(_ current: BackupDestinationKind, iCloudEnabled: Bool, demoMode: Bool) -> BackupDestinationKind {
        offered(iCloudEnabled: iCloudEnabled, demoMode: demoMode).contains(current) ? current : .file
    }
}

/// Demo mode only: backups kept in a folder on this phone so a full backup → restore round trip works without any
/// account. Labelled "Demo storage (on this phone)", never Google Drive. The files are real backups (same format,
/// optional passphrase); they are removed when the app is deleted.
struct DemoBackupDestination: BackupDestination {
    let kind = BackupDestinationKind.demo
    var folder: URL = DemoBackupDestination.defaultFolder

    static var defaultFolder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("DemoBackups", isDirectory: true)
    }

    private func ensureFolder() throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    func upload(_ file: URL, createdAt: Date) async throws {
        try ensureFolder()
        let name = "demo-backup-\(Int(createdAt.timeIntervalSince1970)).\(file.pathExtension.isEmpty ? "manotu" : file.pathExtension)"
        let dest = folder.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: file, to: dest)
    }

    func list() async throws -> [RemoteBackup] {
        try ensureFolder()
        let urls = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.fileSizeKey, .creationDateKey])
        return urls.compactMap { u -> RemoteBackup? in
            guard u.lastPathComponent.hasPrefix("demo-backup-") else { return nil }
            let v = try? u.resourceValues(forKeys: [.fileSizeKey, .creationDateKey])
            let stamp = u.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "demo-backup-", with: "")
            let at = Double(stamp).map { Date(timeIntervalSince1970: $0) } ?? v?.creationDate ?? .distantPast
            return RemoteBackup(id: u.lastPathComponent, name: u.lastPathComponent, createdAt: at, size: v?.fileSize.map { Int64($0) },
                                installId: BackupRetention.installId)
        }
        .sorted { $0.createdAt > $1.createdAt }
    }

    func download(_ backup: RemoteBackup) async throws -> URL {
        let src = folder.appendingPathComponent(backup.id)
        guard FileManager.default.fileExists(atPath: src.path) else { throw BackupError.notABackup }
        let local = FileManager.default.temporaryDirectory.appendingPathComponent("demo-restore-\(UUID().uuidString).\(src.pathExtension)")
        try FileManager.default.copyItem(at: src, to: local)
        return local
    }

    func prune(keep: Int) async throws {
        for b in try await list().dropFirst(keep) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(b.id))
        }
    }
}
