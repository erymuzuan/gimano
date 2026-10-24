import Foundation
import GRDB

/// One imported event as shown in the app: the current revision's exact signed bytes, parsed.
struct ImportedEvent: Identifiable, Hashable, Sendable {
    var eventId: String
    var doc: EventDoc
    var hash: [UInt8]
    var organizerPub: [UInt8]
    var importedAt: Date
    /// Other kept revisions with the same `rev` (fork, §7.4): the user chooses one.
    var forkAlternatives: Int
    /// The newer revision's `parent` didn't match what we had, or revisions were missed.
    var parentWarning: EventParentCheck?

    var id: String { eventId }
    var safetyCode: String { EventCodes.safetyCode(organizerPub) }
}

/// Imported events (docs/events.md §7). Deliberately **not** in `LocalStore`: that database is what backups
/// copy (`VACUUM INTO`), and event data must never be in a backup (§7.6). This one lives in its own directory,
/// excluded from iCloud/Finder device backups, with complete file protection (unreadable while the phone is
/// locked). It holds no pack keys: only the signed event bytes and the pinned organizer key, deleted at `expiresAt`.
final class EventStore: Sendable {
    let dbQueue: DatabaseQueue
    /// The directory holding the database (nil for in-memory test stores).
    let directory: URL?

    init(directory: URL?) throws {
        var config = Configuration()
        config.foreignKeysEnabled = true
        if let directory {
            try Self.prepare(directory)
            let path = directory.appendingPathComponent("events.sqlite").path
            dbQueue = try DatabaseQueue(path: path, configuration: config)
            Self.protect(URL(fileURLWithPath: path))
        } else {
            dbQueue = try DatabaseQueue(configuration: config)
        }
        self.directory = directory
        try Self.migrator.migrate(dbQueue)
    }

    static let shared = EventStore.makeDefault()

    static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("manotu-events", isDirectory: true)
    }

    static func makeDefault() -> EventStore {
        do {
            return try EventStore(directory: defaultDirectory)
        } catch {
            // Locked phone / corrupt DB: events are unavailable this session rather than crashing the app.
            return try! EventStore(directory: nil)
        }
    }

    /// Creates the directory with complete protection and excludes it from device backups.
    static func prepare(_ dir: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        protect(dir)
        var d = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try d.setResourceValues(values)
    }

    private static func protect(_ url: URL) {
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
    }

    static var migrator: DatabaseMigrator {
        var m = DatabaseMigrator()
        m.registerMigration("v1-events") { db in
            try db.execute(sql: """
                CREATE TABLE event_organizers (
                  event_id TEXT PRIMARY KEY,
                  organizer_pub BLOB NOT NULL,
                  pinned_at TEXT NOT NULL);
                CREATE TABLE event_revisions (
                  event_id TEXT NOT NULL REFERENCES event_organizers(event_id) ON DELETE CASCADE,
                  rev INTEGER NOT NULL,
                  hash BLOB NOT NULL,
                  payload BLOB,
                  expires_at REAL NOT NULL,
                  imported_at TEXT NOT NULL,
                  is_current INTEGER NOT NULL DEFAULT 0,
                  parent_check TEXT,
                  PRIMARY KEY (event_id, hash));
                CREATE INDEX ix_event_revisions_expiry ON event_revisions(expires_at);
                """)
        }
        // Events this phone organizes (EventHost.swift): the draft (with organizer-only notes, never packed),
        // the organizer public key (the seed and pack key are in the Keychain, this device only) and every
        // published revision's exact signed bytes (for `parent`).
        m.registerMigration("v2-hosted") { db in
            try db.execute(sql: """
                CREATE TABLE hosted_events (
                  event_id TEXT PRIMARY KEY,
                  draft BLOB NOT NULL,
                  organizer_pub BLOB,
                  expires_at REAL NOT NULL,
                  updated_at TEXT NOT NULL);
                CREATE TABLE hosted_revisions (
                  event_id TEXT NOT NULL REFERENCES hosted_events(event_id) ON DELETE CASCADE,
                  rev INTEGER NOT NULL,
                  hash BLOB NOT NULL,
                  payload BLOB NOT NULL,
                  published_at TEXT NOT NULL,
                  expires_at REAL NOT NULL,
                  PRIMARY KEY (event_id, rev));
                """)
        }
        return m
    }

    // MARK: read

    func record(eventId: String) throws -> StoredEventRecord? {
        try dbQueue.read { db in
            guard let pub = try Data.fetchOne(db, sql: "SELECT organizer_pub FROM event_organizers WHERE event_id = ?", arguments: [eventId]) else {
                return nil
            }
            let revs = try Row.fetchAll(db, sql: "SELECT rev, hash FROM event_revisions WHERE event_id = ?", arguments: [eventId]).map {
                StoredEventRevision(rev: UInt32($0["rev"] as Int64), hash: [UInt8]($0["hash"] as Data))
            }
            return StoredEventRecord(organizerPub: [UInt8](pub), revisions: revs)
        }
    }

    /// Current revisions, soonest first. Expired events are purged first.
    func events(now: Date = .now) throws -> [ImportedEvent] {
        try purgeExpired(now: now)
        return try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT r.event_id, r.rev, r.hash, r.payload, r.imported_at, r.parent_check, o.organizer_pub,
                  (SELECT COUNT(*) FROM event_revisions f WHERE f.event_id = r.event_id AND f.rev = r.rev AND f.hash != r.hash
                     AND f.payload IS NOT NULL) AS forks
                FROM event_revisions r JOIN event_organizers o ON o.event_id = r.event_id
                WHERE r.is_current = 1 AND r.payload IS NOT NULL
                """).compactMap(Self.imported)
        }
        .sorted { $0.doc.start.date < $1.doc.start.date }
    }

    func event(id: String, now: Date = .now) throws -> ImportedEvent? {
        try events(now: now).first { $0.eventId == id }
    }

    /// The other side(s) of a fork for `eventId` (same rev, different bytes).
    func forkAlternatives(eventId: String) throws -> [ImportedEvent] {
        try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT r.event_id, r.rev, r.hash, r.payload, r.imported_at, r.parent_check, o.organizer_pub, 0 AS forks
                FROM event_revisions r JOIN event_organizers o ON o.event_id = r.event_id
                WHERE r.event_id = ? AND r.is_current = 0 AND r.payload IS NOT NULL
                  AND r.rev = (SELECT rev FROM event_revisions WHERE event_id = ? AND is_current = 1)
                """, arguments: [eventId, eventId]).compactMap(Self.imported)
        }
    }

    private static func imported(_ r: Row) -> ImportedEvent? {
        let bytes = [UInt8](r["payload"] as Data)
        // Stored bytes were verified and validated on import; re-parse (never re-serialize).
        guard let doc = try? EventDoc.parse(bytes) else { return nil }
        return ImportedEvent(eventId: r["event_id"], doc: doc, hash: [UInt8](r["hash"] as Data), organizerPub: [UInt8](r["organizer_pub"] as Data),
                             importedAt: LocalStore.date(r["imported_at"]) ?? .now, forkAlternatives: r["forks"],
                             parentWarning: (r["parent_check"] as String?).flatMap(Self.parentCheck))
    }

    private static func parentCheck(_ s: String) -> EventParentCheck? {
        switch s {
        case "missed": .missedRevisions
        case "mismatch": .mismatch
        default: nil
        }
    }

    // MARK: write

    /// Stores an opened pack per its decision (only `.new`, `.newer`, `.fork` store anything). Returns whether it stored.
    @discardableResult
    func save(_ pack: OpenedEventPack, decision: EventImportDecision, now: Date = .now) throws -> Bool {
        guard decision.canImport, !EventImport.isExpired(pack.event.expiresAt.date, now: now) else { return false }
        let id = pack.event.id
        let ts = LocalStore.timestamp(now)
        try dbQueue.write { db in
            func insert(current: Bool, parentCheck: String?) throws {
                try db.execute(sql: """
                    INSERT INTO event_revisions (event_id, rev, hash, payload, expires_at, imported_at, is_current, parent_check)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, arguments: [id, Int64(pack.event.rev), Data(pack.revisionHash), Data(pack.eventBytes),
                                     pack.event.expiresAt.date.timeIntervalSince1970, ts, current ? 1 : 0, parentCheck])
            }
            switch decision {
            case .new:
                try db.execute(sql: "INSERT INTO event_organizers (event_id, organizer_pub, pinned_at) VALUES (?, ?, ?)",
                               arguments: [id, Data(pack.organizerPub), ts])
                try insert(current: true, parentCheck: nil)
            case .newer(_, let parent):
                // Older revisions keep only rev + hash (for parent checks); their contents are dropped.
                try db.execute(sql: "UPDATE event_revisions SET is_current = 0, payload = NULL WHERE event_id = ?", arguments: [id])
                try insert(current: true, parentCheck: parent == .mismatch ? "mismatch" : parent == .missedRevisions ? "missed" : nil)
            case .fork:
                try insert(current: false, parentCheck: nil)
            default:
                break
            }
        }
        return true
    }

    /// Fork: keep the chosen side, drop the other's contents.
    func choose(eventId: String, hash: [UInt8]) throws {
        try dbQueue.write { db in
            try db.execute(sql: "UPDATE event_revisions SET is_current = (hash = ?) WHERE event_id = ?", arguments: [Data(hash), eventId])
            try db.execute(sql: "UPDATE event_revisions SET payload = NULL WHERE event_id = ? AND is_current = 0", arguments: [eventId])
        }
    }

    /// Removes the event, its revisions and the pinned organizer key.
    func delete(eventId: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM event_revisions WHERE event_id = ?", arguments: [eventId])
            try db.execute(sql: "DELETE FROM event_organizers WHERE event_id = ?", arguments: [eventId])
        }
    }

    /// §7.5: an event is deleted entirely (payload, revisions, pinned key) once its current revision expired.
    /// Returns how many events were removed.
    @discardableResult
    func purgeExpired(now: Date = .now) throws -> Int {
        try dbQueue.write { db in
            let t = now.timeIntervalSince1970
            let ids = try String.fetchAll(db, sql: """
                SELECT DISTINCT event_id FROM event_revisions WHERE is_current = 1 AND expires_at <= ?
                UNION
                SELECT event_id FROM event_organizers o WHERE NOT EXISTS
                  (SELECT 1 FROM event_revisions r WHERE r.event_id = o.event_id AND r.is_current = 1)
                  AND NOT EXISTS (SELECT 1 FROM event_revisions r WHERE r.event_id = o.event_id AND r.payload IS NOT NULL AND r.expires_at > ?)
                """, arguments: [t, t])
            for id in ids {
                try db.execute(sql: "DELETE FROM event_revisions WHERE event_id = ?", arguments: [id])
                try db.execute(sql: "DELETE FROM event_organizers WHERE event_id = ?", arguments: [id])
            }
            // Expired fork sides that aren't current.
            try db.execute(sql: "DELETE FROM event_revisions WHERE is_current = 0 AND payload IS NOT NULL AND expires_at <= ?", arguments: [t])
            return ids.count
        }
    }

    func deleteAll() throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM event_revisions")
            try db.execute(sql: "DELETE FROM event_organizers")
            try db.execute(sql: "DELETE FROM hosted_revisions")
            try db.execute(sql: "DELETE FROM hosted_events")
        }
    }
}
