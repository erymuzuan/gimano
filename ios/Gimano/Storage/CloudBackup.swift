import CloudKit
import Foundation
import GoogleSignIn
import UIKit

struct RemoteBackup: Identifiable, Hashable, Sendable {
    var id: String
    var name: String
    var createdAt: Date
    var size: Int64?
}

enum BackupDestinationKind: String, CaseIterable, Identifiable, Sendable {
    case icloud, googleDrive, file
    var id: String { rawValue }
}

/// A place backups can be uploaded to and restored from. Keeps the newest `keep` backups.
protocol BackupDestination: Sendable {
    var kind: BackupDestinationKind { get }
    func upload(_ file: URL, createdAt: Date) async throws
    func list() async throws -> [RemoteBackup]
    func download(_ backup: RemoteBackup) async throws -> URL
    func prune(keep: Int) async throws
}

enum CloudBackupError: LocalizedError {
    case notConfigured(String)
    case notSignedIn
    case http(Int, String)

    var errorDescription: String? {
        switch self {
        case .notConfigured(let s): s
        case .notSignedIn: String(localized: "Sign in to Google first.")
        case .http(let code, let body): "HTTP \(code): \(body.prefix(200))"
        }
    }
}

// MARK: - iCloud (CloudKit private database)

/// One `GimanoBackup` record per backup with the file as a `CKAsset`, plus a fixed `GimanoBackupIndex` record listing
/// them, so listing needs no CloudKit query indexes.
struct ICloudBackupDestination: BackupDestination {
    static let containerId = "iCloud.my.gimano.app"
    let kind = BackupDestinationKind.icloud

    /// False when the app was built without the iCloud entitlement (`GIMANO_ICLOUD = NO`); touching CloudKit
    /// without it would crash, so every entry point checks this first.
    static var isEnabled: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "GimanoICloudEnabled") as? String)?.uppercased() == "YES"
    }

    private static var notEnabled: CloudBackupError {
        .notConfigured(String(localized: "iCloud isn't enabled in this build."))
    }

    private var db: CKDatabase { CKContainer(identifier: Self.containerId).privateCloudDatabase }
    private let indexID = CKRecord.ID(recordName: "gimano-backup-index")

    func accountStatus() async -> CKAccountStatus {
        guard Self.isEnabled else { return .couldNotDetermine }
        return (try? await CKContainer(identifier: Self.containerId).accountStatus()) ?? .couldNotDetermine
    }

    private func loadIndex() async throws -> CKRecord {
        guard Self.isEnabled else { throw Self.notEnabled }
        do {
            return try await db.record(for: indexID)
        } catch let e as CKError where e.code == .unknownItem {
            return CKRecord(recordType: "GimanoBackupIndex", recordID: indexID)
        }
    }

    func upload(_ file: URL, createdAt: Date) async throws {
        guard Self.isEnabled else { throw Self.notEnabled }
        let id = CKRecord.ID(recordName: "gimano-backup-\(Int(createdAt.timeIntervalSince1970))")
        let rec = CKRecord(recordType: "GimanoBackup", recordID: id)
        rec["file"] = CKAsset(fileURL: file)
        rec["name"] = file.lastPathComponent as NSString
        rec["createdAt"] = createdAt as NSDate
        rec["size"] = ((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) as NSNumber
        rec["platform"] = "ios" as NSString
        _ = try await db.save(rec)
        let index = try await loadIndex()
        var ids = (index["ids"] as? [String]) ?? []
        ids.insert(id.recordName, at: 0)
        index["ids"] = ids as NSArray
        _ = try await db.modifyRecords(saving: [index], deleting: [], savePolicy: .allKeys)
    }

    func list() async throws -> [RemoteBackup] {
        let index = try await loadIndex()
        let ids = ((index["ids"] as? [String]) ?? []).map { CKRecord.ID(recordName: $0) }
        guard !ids.isEmpty else { return [] }
        let results = try await db.records(for: ids, desiredKeys: ["name", "createdAt", "size"])
        return results.compactMap { id, r in
            guard let rec = try? r.get() else { return nil }
            return RemoteBackup(id: id.recordName, name: rec["name"] as? String ?? id.recordName,
                                createdAt: rec["createdAt"] as? Date ?? .distantPast, size: (rec["size"] as? NSNumber)?.int64Value)
        }.sorted { $0.createdAt > $1.createdAt }
    }

    func download(_ backup: RemoteBackup) async throws -> URL {
        guard Self.isEnabled else { throw Self.notEnabled }
        let rec = try await db.record(for: CKRecord.ID(recordName: backup.id))
        guard let asset = rec["file"] as? CKAsset, let src = asset.fileURL else { throw BackupError.corrupt }
        let dst = FileManager.default.temporaryDirectory.appendingPathComponent(backup.name)
        try? FileManager.default.removeItem(at: dst)
        try FileManager.default.copyItem(at: src, to: dst)
        return dst
    }

    func prune(keep: Int) async throws {
        let index = try await loadIndex()
        let ids = (index["ids"] as? [String]) ?? []
        guard ids.count > keep else { return }
        let drop = ids.dropFirst(keep).map { CKRecord.ID(recordName: $0) }
        index["ids"] = Array(ids.prefix(keep)) as NSArray
        _ = try await db.modifyRecords(saving: [index], deleting: drop, savePolicy: .allKeys)
    }
}

// MARK: - Google Drive (appDataFolder)

/// Google Drive app-private folder (`drive.appdata` scope) via GoogleSignIn + Drive REST v3.
struct GoogleDriveBackupDestination: BackupDestination {
    static let scope = "https://www.googleapis.com/auth/drive.appdata"
    let kind = BackupDestinationKind.googleDrive

    /// The OAuth client id from git-ignored `Config/Google.xcconfig` (via Info.plist `GIDClientID`).
    static var clientID: String? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              !id.isEmpty, !id.contains("$("), id.hasSuffix(".apps.googleusercontent.com") else { return nil }
        return id
    }

    static var isConfigured: Bool { clientID != nil }

    @MainActor
    static func signIn() async throws -> String {
        guard let clientID else { throw CloudBackupError.notConfigured(String(localized: "Google Drive isn't configured in this build.")) }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        guard let root = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController else { throw CloudBackupError.notSignedIn }
        var top = root
        while let p = top.presentedViewController { top = p }
        let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: top, hint: nil, additionalScopes: [scope])
        return result.user.profile?.email ?? ""
    }

    @MainActor
    static func signOut() { GIDSignIn.sharedInstance.signOut() }

    @MainActor
    static var signedInEmail: String? { GIDSignIn.sharedInstance.currentUser?.profile?.email }

    /// Restores the previous Google session after an app relaunch (tokens are kept in the Keychain by GoogleSignIn).
    @MainActor
    static func restoreSession() async -> String? {
        guard let clientID else { return nil }
        if GIDSignIn.sharedInstance.configuration == nil { GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID) }
        if let email = signedInEmail { return email }
        guard GIDSignIn.sharedInstance.hasPreviousSignIn() else { return nil }
        return (try? await GIDSignIn.sharedInstance.restorePreviousSignIn())?.profile?.email
    }

    @MainActor
    private static func accessToken() async throws -> String {
        guard isConfigured else { throw CloudBackupError.notConfigured(String(localized: "Google Drive isn't configured in this build.")) }
        if GIDSignIn.sharedInstance.currentUser == nil {
            _ = await restoreSession()
        }
        guard let user = GIDSignIn.sharedInstance.currentUser else { throw CloudBackupError.notSignedIn }
        let refreshed = try await user.refreshTokensIfNeeded()
        return refreshed.accessToken.tokenString
    }

    private func request(_ url: URL, method: String = "GET", body: Data? = nil, contentType: String? = nil) async throws -> Data {
        let token = try await Self.accessToken()
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body
        let (data, res) = try await URLSession.shared.data(for: req)
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw CloudBackupError.http(code, String(decoding: data, as: UTF8.self)) }
        return data
    }

    func upload(_ file: URL, createdAt: Date) async throws {
        let boundary = "gimano-\(UUID().uuidString)"
        let meta = try JSONSerialization.data(withJSONObject: ["name": file.lastPathComponent, "parents": ["appDataFolder"],
                                                               "mimeType": "application/vnd.gimano.backup+zip"])
        var body = Data()
        body.append("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".data(using: .utf8)!)
        body.append(meta)
        body.append("\r\n--\(boundary)\r\nContent-Type: application/vnd.gimano.backup+zip\r\n\r\n".data(using: .utf8)!)
        body.append(try Data(contentsOf: file))
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        _ = try await request(URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")!, method: "POST",
                              body: body, contentType: "multipart/related; boundary=\(boundary)")
    }

    func list() async throws -> [RemoteBackup] {
        var c = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        c.queryItems = [URLQueryItem(name: "spaces", value: "appDataFolder"), URLQueryItem(name: "orderBy", value: "createdTime desc"),
                        URLQueryItem(name: "fields", value: "files(id,name,size,createdTime)"), URLQueryItem(name: "pageSize", value: "50")]
        let data = try await request(c.url!)
        struct Files: Decodable { struct F: Decodable { var id: String; var name: String; var size: String?; var createdTime: String }; var files: [F] }
        return try JSONDecoder().decode(Files.self, from: data).files.map {
            RemoteBackup(id: $0.id, name: $0.name, createdAt: JSON.parseDate($0.createdTime) ?? .distantPast, size: $0.size.flatMap(Int64.init))
        }
    }

    func download(_ backup: RemoteBackup) async throws -> URL {
        let data = try await request(URL(string: "https://www.googleapis.com/drive/v3/files/\(backup.id)?alt=media")!)
        let dst = FileManager.default.temporaryDirectory.appendingPathComponent(backup.name)
        try data.write(to: dst)
        return dst
    }

    func prune(keep: Int) async throws {
        for b in try await list().dropFirst(keep) {
            _ = try await request(URL(string: "https://www.googleapis.com/drive/v3/files/\(b.id)")!, method: "DELETE")
        }
    }
}
