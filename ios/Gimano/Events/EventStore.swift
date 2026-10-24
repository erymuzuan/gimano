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
/// locked). It holds no pack keys: only the signed event bytes, the pinned organizer key and, for events opened from a
/// short link (§5.6), that link's drop id and secret, all deleted at `expiresAt`.
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
        // "Laluan saya" lived here in earlier builds. They now live in `LocalStore.personal_routes` (schema 3, backed up):
        // `PersonalRouteMove` copies these rows there once and drops this table when it is empty. The migration stays
        // registered so existing databases keep a valid migration history.
        m.registerMigration("v3-personal-routes") { db in
            try db.execute(sql: """
                CREATE TABLE personal_routes (
                  route_id TEXT PRIMARY KEY,
                  draft BLOB NOT NULL,
                  updated_at TEXT NOT NULL);
                """)
        }
        // Stops-only variants (§5.5): `hash` is the revision hash (`fullHash` for a variant); this marks rows whose
        // bytes are a variant, so the full event of the same revision replaces them ("Laluan penuh ditambah").
        m.registerMigration("v4-stops-only") { db in
            try db.execute(sql: "ALTER TABLE event_revisions ADD COLUMN stops_only INTEGER NOT NULL DEFAULT 0")
        }
        // Short links (§5.6): the guest keeps the drop id and its secret with the imported event, to fetch later
        // revisions. Same protection as the event itself (this database, never backed up); removed with the event.
        m.registerMigration("v5-drops") { db in
            try db.execute(sql: """
                CREATE TABLE event_drops (
                  event_id TEXT PRIMARY KEY REFERENCES event_organizers(event_id) ON DELETE CASCADE,
                  drop_id TEXT NOT NULL,
                  secret BLOB NOT NULL);
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
            let revs = try Row.fetchAll(db, sql: "SELECT rev, hash, stops_only FROM event_revisions WHERE event_id = ?", arguments: [eventId]).map {
                StoredEventRevision(rev: UInt32($0["rev"] as Int64), hash: [UInt8]($0["hash"] as Data), stopsOnly: $0["stops_only"] as Bool)
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

    /// Stores an opened pack per its decision (only `.new`, `.newer`, `.fork`, `.upgradeToFull` store anything).
    /// Returns whether it stored.
    @discardableResult
    func save(_ pack: OpenedEventPack, decision: EventImportDecision, now: Date = .now) throws -> Bool {
        guard decision.canImport, !EventImport.isExpired(pack.event.expiresAt.date, now: now) else { return false }
        let id = pack.event.id
        let ts = LocalStore.timestamp(now)
        try dbQueue.write { db in
            func insert(current: Bool, parentCheck: String?) throws {
                try db.execute(sql: """
                    INSERT INTO event_revisions (event_id, rev, hash, payload, expires_at, imported_at, is_current, parent_check, stops_only)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, arguments: [id, Int64(pack.event.rev), Data(pack.revisionHash), Data(pack.eventBytes),
                                     pack.event.expiresAt.date.timeIntervalSince1970, ts, current ? 1 : 0, parentCheck, pack.isStopsOnly])
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
            case .upgradeToFull(let rev):
                // Same revision (same revision hash): the full bytes replace the variant's. A revision whose contents
                // were already dropped (an older one) only stops being marked stops-only.
                try db.execute(sql: """
                    UPDATE event_revisions SET stops_only = 0,
                      payload = CASE WHEN payload IS NULL THEN NULL ELSE ? END
                    WHERE event_id = ? AND rev = ? AND hash = ? AND stops_only = 1
                    """, arguments: [Data(pack.eventBytes), id, Int64(rev), Data(pack.revisionHash)])
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

    // MARK: short links (§5.6)

    /// Keeps the short link an event was opened from (the newest one wins). The event must already be stored.
    func saveDrop(_ ref: EventDropRef, eventId: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO event_drops (event_id, drop_id, secret) SELECT ?, ?, ?
                WHERE EXISTS (SELECT 1 FROM event_organizers WHERE event_id = ?)
                ON CONFLICT(event_id) DO UPDATE SET drop_id = excluded.drop_id, secret = excluded.secret
                """, arguments: [eventId, ref.dropId, Data(ref.secret), eventId])
        }
    }

    func drop(eventId: String) throws -> EventDropRef? {
        try dbQueue.read { db in
            try Row.fetchOne(db, sql: "SELECT drop_id, secret FROM event_drops WHERE event_id = ?", arguments: [eventId]).map {
                EventDropRef(dropId: $0["drop_id"], secret: [UInt8]($0["secret"] as Data))
            }
        }
    }

    /// Events opened from a short link.
    func dropEventIds() throws -> [String] {
        try dbQueue.read { db in try String.fetchAll(db, sql: "SELECT event_id FROM event_drops") }
    }

    func deleteDrop(eventId: String) throws {
        try dbQueue.write { db in try db.execute(sql: "DELETE FROM event_drops WHERE event_id = ?", arguments: [eventId]) }
    }

    /// Removes the event, its revisions and the pinned organizer key.
    func delete(eventId: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM event_drops WHERE event_id = ?", arguments: [eventId])
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
                try db.execute(sql: "DELETE FROM event_drops WHERE event_id = ?", arguments: [id])
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
            try db.execute(sql: "DELETE FROM event_drops")
            try db.execute(sql: "DELETE FROM event_revisions")
            try db.execute(sql: "DELETE FROM event_organizers")
            try db.execute(sql: "DELETE FROM hosted_revisions")
            try db.execute(sql: "DELETE FROM hosted_events")
        }
    }
}
