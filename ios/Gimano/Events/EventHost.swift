import Foundation
import GRDB
import Security

// Organizer (host) side of Events (Acara): drafts on this phone, one Ed25519 key pair and one pack key per event
// (Keychain, this device only), publishing revisions (docs/events.md §3–§5), mirroring the desktop
// `src-tauri/src/publish.rs` + `assemble.rs`.
//
// Privacy: drafts, organizer-only notes, keys, links and files stay on this phone. Organizer-only notes are never
// written into a pack. Nothing here touches the network or logs event content.

// MARK: - Draft

struct HostedStopDraft: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var kindRaw: String
    var label: String
    var lat: Double
    var lng: Double
    var entranceLat: Double?
    var entranceLng: Double?
    /// Guest-visible.
    var notes: String = ""
    var audience: String = ""
    /// Organizer only: kept on this phone, never written into a pack.
    var organizerNote: String = ""

    init(id: String = EventRandom.itemId(), kind: EventStopKind, label: String, location: LatLng) {
        self.id = id
        self.kindRaw = kind.rawValue
        self.label = label
        self.lat = location.lat
        self.lng = location.lng
    }

    var kind: EventStopKind {
        get { EventStopKind(rawValue: kindRaw) ?? .other }
        set { kindRaw = newValue.rawValue }
    }

    var location: LatLng {
        get { LatLng(lat: lat, lng: lng) }
        set { lat = newValue.lat; lng = newValue.lng }
    }

    var entrance: LatLng? {
        get { entranceLat.flatMap { la in entranceLng.map { LatLng(lat: la, lng: $0) } } }
        set { entranceLat = newValue?.lat; entranceLng = newValue?.lng }
    }
}

/// One agenda item ("Atur cara", `sessions`). It ends when the next one starts, the last one when the event ends.
struct HostedSessionDraft: Codable, Hashable, Sendable, Identifiable {
    var id: String = EventRandom.itemId()
    var title: String = ""
    var start: Date
}

/// One guest info block (§4.1): plain text guests see.
struct HostedInfoDraft: Codable, Hashable, Sendable, Identifiable {
    var id: String = UUID().uuidString
    var kindRaw: String
    var text: String = ""

    init(kind: EventInfoKind, text: String = "") {
        kindRaw = kind.rawValue
        self.text = text
    }

    var kind: EventInfoKind {
        get { EventInfoKind(rawValue: kindRaw) ?? .other }
        set { kindRaw = newValue.rawValue }
    }
}

struct HostedEventDraft: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var templateRaw: String
    var title: String = ""
    var description: String = ""
    var langRaw: String
    var tz: String
    var start: Date
    var end: Date
    /// nil = the default, `end` + 24 h.
    var expiresAtOverride: Date?
    var alias: String = ""
    var stops: [HostedStopDraft] = []
    /// "Laluan" (route builder). Optional in storage so drafts saved before routes existed still load.
    var routeDrafts: [RouteDraft]?
    /// "Atur cara". Optional in storage (drafts saved before the wizard).
    var sessionDrafts: [HostedSessionDraft]?
    /// Guest info blocks (§4.1). Optional in storage.
    var infoDrafts: [HostedInfoDraft]?
    /// "Nota untuk saya": organizer only, kept on this phone, never written into a pack.
    var organizerNote: String?
    /// "Hantar sebagai fail + kod": share a suite-2 file plus the 8-character code instead of the open file.
    var sendAsCodeFile: Bool?
    var createdAt: Date
    var updatedAt: Date

    var sessions: [HostedSessionDraft] {
        get { sessionDrafts ?? [] }
        set { sessionDrafts = newValue.isEmpty ? nil : newValue }
    }

    var info: [HostedInfoDraft] {
        get { infoDrafts ?? [] }
        set { infoDrafts = newValue.isEmpty ? nil : newValue }
    }

    var sensitive: Bool {
        get { sendAsCodeFile ?? false }
        set { sendAsCodeFile = newValue ? true : nil }
    }

    /// "Perjalanan": the template is a journey, or the event already has routes (§4.1: not stored, an event with routes
    /// is a journey).
    var isJourney: Bool { template.isJourney || !routes.isEmpty }

    var routes: [RouteDraft] {
        get { routeDrafts ?? [] }
        set { routeDrafts = newValue.isEmpty ? nil : newValue }
    }

    /// A stop moved or was removed: routes follow (linked points move, removed stops become shaping points).
    mutating func syncRoutesWithStops() {
        let ids = Set(stops.map(\.id))
        var rs = routes
        for i in rs.indices {
            rs[i].unlinkStops(notIn: ids)
            for s in stops { rs[i].syncStop(s) }
        }
        if rs != routes { routes = rs }
    }

    /// The template suggests routes (rides, convoys, trips, hikes).
    var routesFirst: Bool { template.isJourney }

    /// A new draft: tomorrow 11:00–16:00 in this phone's time zone, language from the app's.
    static func new(now: Date = .now, timeZone: TimeZone = .current, preferredLanguage: String? = Locale.preferredLanguages.first) -> HostedEventDraft {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let tomorrow = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: now)) ?? now
        let start = cal.date(bySettingHour: 11, minute: 0, second: 0, of: tomorrow) ?? tomorrow
        let end = cal.date(bySettingHour: 16, minute: 0, second: 0, of: tomorrow) ?? tomorrow
        let lang: EventLang = (preferredLanguage ?? "ms").hasPrefix("en") ? .en : .ms
        return HostedEventDraft(id: EventRandom.eventId(), templateRaw: EventTemplate.kenduri.rawValue, langRaw: lang.rawValue,
                                tz: EventValidation.validTimeZoneName(timeZone.identifier) ? timeZone.identifier : "Asia/Kuala_Lumpur",
                                start: start, end: end, createdAt: now, updatedAt: now)
    }

    var template: EventTemplate {
        get { EventTemplate(rawValue: templateRaw) ?? .other }
        set { templateRaw = newValue.rawValue }
    }

    var lang: EventLang {
        get { EventLang(rawValue: langRaw) ?? .ms }
        set { langRaw = newValue.rawValue }
    }

    var timeZone: TimeZone { TimeZone(identifier: tz) ?? .current }
    var defaultExpiry: Date { EventDoc.defaultExpiry(end: end) }
    var expiresAt: Date { expiresAtOverride ?? defaultExpiry }

    /// Draft → guest-visible event (`assemble.rs` `build_event`): trimmed texts, empty optionals omitted, coordinates
    /// rounded to 6 decimals, organizer-only notes never read. Returns the problems to show when it can't be built.
    func event(rev: UInt32, parent: String?, publishedAt: Date) -> (EventDoc?, [EventDraftIssue]) {
        var issues: [EventDraftIssue] = []
        func opt(_ s: String) -> String? {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        let title = self.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if title.isEmpty { issues.append(.titleMissing) }
        if EventValidation.scalars(title) > EventLimits.titleChars { issues.append(.titleTooLong) }
        if EventValidation.scalars(description) > EventLimits.descriptionChars { issues.append(.descriptionTooLong) }
        if EventValidation.scalars(alias) > EventLimits.aliasChars { issues.append(.aliasTooLong) }
        // Agenda: rows without a name are left out; each ends when the next starts, the last at the event's end.
        let agenda = sessions.filter { !$0.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.sorted { $0.start < $1.start }
        if agenda.count > EventLimits.maxSessions { issues.append(.tooManySessions) }
        for (i, s) in agenda.enumerated() {
            if EventValidation.scalars(s.title.trimmingCharacters(in: .whitespacesAndNewlines)) > EventLimits.sessionTitleChars {
                issues.append(.sessionTitle(s.id))
            }
            let sEnd = i + 1 < agenda.count ? agenda[i + 1].start : end
            if sEnd < s.start { issues.append(.sessionTime(s.id)) }
        }
        // Info blocks: empty ones are left out.
        let blocks = info.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        if blocks.count > EventLimits.maxInfoBlocks { issues.append(.tooManyInfoBlocks) }
        for b in blocks where EventValidation.scalars(b.text.trimmingCharacters(in: .whitespacesAndNewlines)) > EventLimits.infoTextChars {
            issues.append(.infoTooLong(b.id))
        }
        if end <= start { issues.append(.endBeforeStart) }
        if end.timeIntervalSince(start) > Double(EventLimits.maxEventDays) * 86_400 { issues.append(.tooLong) }
        if expiresAt <= publishedAt { issues.append(.expired) }
        if stops.isEmpty { issues.append(.noStops) }
        if stops.count > EventLimits.maxStops { issues.append(.tooManyStops) }
        for s in stops {
            let label = s.label.trimmingCharacters(in: .whitespacesAndNewlines)
            if label.isEmpty || EventValidation.scalars(label) > EventLimits.stopLabelChars { issues.append(.stopLabel(s.id)) }
            if EventValidation.scalars(s.notes) > EventLimits.stopNotesChars { issues.append(.stopNotes(s.id)) }
            if EventValidation.scalars(s.audience) > EventLimits.audienceChars { issues.append(.stopAudience(s.id)) }
        }
        // Routes (desktop assemble.rs): fewer than two points are left out; waiting or unmatched legs block.
        let stopIds = Set(stops.map(\.id))
        var eventRoutes: [EventRoute] = []
        for r in routes {
            switch r.eventRoute(knownStops: stopIds) {
            case .success(var er):
                // §5.4: simplified with Douglas–Peucker at 5 m before signing (GPX-imported routes too); distance and
                // time keep the routing values.
                if let p = er.polyline, let s = RouteSimplify.polyline(p) { er.polyline = s }
                eventRoutes.append(er)
            case .failure(.tooFewPoints): continue
            case .failure(.unresolved): issues.append(.routeIncomplete(r.id))
            case .failure(.unmatched): issues.append(.routeUnmatched(r.id))
            }
        }
        if eventRoutes.count > EventLimits.maxRoutes { issues.append(.tooManyRoutes) }
        let points = eventRoutes.reduce(0) { $0 + $1.points.count }
        if points > EventLimits.maxPolylinePoints { issues.append(.tooManyRoutePoints) }
        guard issues.isEmpty else { return (nil, issues) }
        let tz = timeZone
        let doc = EventDoc(
            id: id, rev: rev, parent: parent, publishedAt: .utc(publishedAt), template: template, title: title,
            description: opt(description), lang: lang, tz: self.tz, start: .local(start, tz), end: .local(end, tz),
            expiresAt: .utc(expiresAt), organizer: opt(alias).map { EventOrganizer(alias: $0) },
            stops: stops.map { s in
                EventStop(id: s.id, kind: s.kind, label: s.label.trimmingCharacters(in: .whitespacesAndNewlines),
                          lat: Self.round6(s.lat), lng: Self.round6(s.lng),
                          entrance: s.entrance.map { EventPoint(lat: Self.round6($0.lat), lng: Self.round6($0.lng)) },
                          arrive: nil, dwellMin: nil, notes: opt(s.notes), audience: opt(s.audience))
                // s.organizerNote is deliberately not read: it has no field in the pack.
            },
            sessions: agenda.enumerated().map { i, s in
                EventSession(id: s.id, title: s.title.trimmingCharacters(in: .whitespacesAndNewlines), start: .local(s.start, tz),
                             end: .local(i + 1 < agenda.count ? agenda[i + 1].start : end, tz), stopId: nil)
            },
            info: blocks.map { EventInfoBlock(kind: $0.kind, text: $0.text.trimmingCharacters(in: .whitespacesAndNewlines)) },
            routes: eventRoutes)
        // self.organizerNote is deliberately not read either.
        do {
            try EventValidation.validate(doc)
        } catch {
            return (nil, [.invalid(error)])
        }
        return (doc, [])
    }

    static func round6(_ v: Double) -> Double { (v * 1e6).rounded() / 1e6 }
}

/// A problem that blocks publishing, shown in the editor.
enum EventDraftIssue: Equatable, Sendable {
    case titleMissing, titleTooLong, descriptionTooLong, aliasTooLong, endBeforeStart, tooLong, expired, noStops, tooManyStops
    case stopLabel(String), stopNotes(String), stopAudience(String)
    /// A route has legs still waiting for the routing server ("perlu dikira").
    case routeIncomplete(String)
    /// Part of a drawn route matched no road.
    case routeUnmatched(String)
    case tooManyRoutes, tooManyRoutePoints
    case tooManySessions, sessionTitle(String), sessionTime(String)
    case tooManyInfoBlocks, infoTooLong(String)
    case invalid(EventPackError)

    var message: String {
        switch self {
        case .titleMissing: String(localized: "Give the event a name.")
        case .titleTooLong: String(localized: "The name is too long (120 characters at most).")
        case .descriptionTooLong: String(localized: "The description is too long (2000 characters at most).")
        case .aliasTooLong: String(localized: "The organizer name is too long (80 characters at most).")
        case .endBeforeStart: String(localized: "The event must end after it starts.")
        case .tooLong: String(localized: "An event can last 14 days at most.")
        case .expired: String(localized: "Access would already have ended. Change the date or the access end.")
        case .noStops: String(localized: "Add at least one place.")
        case .tooManyStops: String(localized: "An event can have 200 places at most.")
        case .stopLabel: String(localized: "Every place needs a name (80 characters at most).")
        case .stopNotes: String(localized: "A place's notes are too long (500 characters at most).")
        case .stopAudience: String(localized: "“For whom” is too long (40 characters at most).")
        case .routeIncomplete: String(localized: "A route still has legs to calculate. Open it when you're online, or keep them as sketches.")
        case .routeUnmatched: String(localized: "Part of a drawn route matched no road. Redraw it, add a point or keep it as a sketch.")
        case .tooManyRoutes: String(localized: "An event can have 20 routes at most.")
        case .tooManyRoutePoints: String(localized: "The routes are too detailed (50 000 points at most in total). Remove a route or split it.")
        case .tooManySessions: String(localized: "The agenda can have 50 items at most.")
        case .sessionTitle: String(localized: "An agenda item's name is too long (120 characters at most).")
        case .sessionTime: String(localized: "An agenda item starts after the event ends. Check its time.")
        case .tooManyInfoBlocks: String(localized: "An event can have 12 notes for guests at most.")
        case .infoTooLong: String(localized: "A note for guests is too long (1000 characters at most).")
        case .invalid: String(localized: "This event can't be published. Check its details.")
        }
    }

    var stopId: String? {
        switch self {
        case .stopLabel(let id), .stopNotes(let id), .stopAudience(let id): id
        default: nil
        }
    }

    var routeId: String? {
        switch self {
        case .routeIncomplete(let id), .routeUnmatched(let id): id
        default: nil
        }
    }

    /// Scroll targets in the event editor (`.id(...)`), so a blocked "Share invitation" can show the first problem.
    enum Anchor {
        static let title = "anchor.title"
        static let alias = "anchor.alias"
        static let description = "anchor.description"
        static let time = "anchor.time"
        static let expiry = "anchor.expiry"
        static let addPlace = "anchor.addPlace"
        static let routes = "anchor.routes"
        static let problems = "anchor.problems"
        static let info = "anchor.info"
        static func stop(_ id: String) -> String { "anchor.stop.\(id)" }
        static func route(_ id: String) -> String { "anchor.route.\(id)" }
    }

    /// Where the editor scrolls to for this problem: its field, place or route row, else the list of problems.
    var anchor: String {
        switch self {
        case .titleMissing, .titleTooLong: Anchor.title
        case .aliasTooLong: Anchor.alias
        case .descriptionTooLong: Anchor.description
        case .endBeforeStart, .tooLong: Anchor.time
        case .expired: Anchor.expiry
        case .noStops, .tooManyStops: Anchor.addPlace
        case .stopLabel(let id), .stopNotes(let id), .stopAudience(let id): Anchor.stop(id)
        case .routeIncomplete(let id), .routeUnmatched(let id): Anchor.route(id)
        case .tooManyRoutes, .tooManyRoutePoints: Anchor.routes
        case .tooManySessions, .sessionTitle, .sessionTime, .tooManyInfoBlocks, .infoTooLong: Anchor.info
        case .invalid: Anchor.problems
        }
    }
}

// MARK: - Secrets (Keychain, this device only)

/// Per-event secrets: the 32-byte Ed25519 seed, the 32-byte pack key (links) and the 8-character file code
/// (`XXXX-XXXX`, §5.2 suite 2). Never reused across events. The file code is made once per event and kept for every
/// revision so guests keep one code; events published before suite 2 have none until their next file (`withFileCode`).
struct EventSecrets: Sendable, Equatable {
    var seed: [UInt8]
    var packKey: [UInt8]
    var fileCode: String?

    static func generate() -> EventSecrets {
        EventSecrets(seed: EventRandom.bytes(32), packKey: EventRandom.bytes(EventPack.keyBytes), fileCode: SecretCodeV2.generate())
    }

    /// Keychain item bytes: seed ‖ pack key, then the code's 8 ASCII characters when there is one (72 bytes).
    var itemBytes: [UInt8] {
        seed + packKey + (fileCode.flatMap { try? SecretCodeV2.parse($0) }.map { Array($0.utf8) } ?? [])
    }

    /// 64 bytes (before suite 2) or 72 bytes; anything else is not ours.
    init?(itemBytes b: [UInt8]) {
        guard b.count == 64 || b.count == 64 + SecretCodeV2.chars else { return nil }
        seed = Array(b[0..<32])
        packKey = Array(b[32..<64])
        if b.count > 64 {
            guard let raw = String(bytes: b[64...], encoding: .ascii), let password = try? SecretCodeV2.parse(raw) else { return nil }
            fileCode = SecretCodeV2.display(password)
        }
    }

    init(seed: [UInt8], packKey: [UInt8], fileCode: String? = nil) {
        self.seed = seed
        self.packKey = packKey
        self.fileCode = fileCode
    }
}

protocol EventSecretStore: Sendable {
    func load(eventId: String) -> EventSecrets?
    func save(_ secrets: EventSecrets, eventId: String) -> Bool
    func delete(eventId: String)
}

/// Generic-password items, `WhenUnlockedThisDeviceOnly` and not synchronizable: they never reach iCloud Keychain and
/// are not restored onto another device from a backup.
struct KeychainEventSecretStore: EventSecretStore {
    var service = "my.manotu.events.organizer"

    private func base(_ eventId: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: eventId,
         kSecAttrSynchronizable as String: kCFBooleanFalse as Any]
    }

    func load(eventId: String) -> EventSecrets? {
        var q = base(eventId)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return EventSecrets(itemBytes: [UInt8](data))
    }

    func save(_ secrets: EventSecrets, eventId: String) -> Bool {
        SecItemDelete(base(eventId) as CFDictionary)
        var add = base(eventId)
        add[kSecValueData as String] = Data(secrets.itemBytes)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    func delete(eventId: String) {
        SecItemDelete(base(eventId) as CFDictionary)
    }
}

/// Tests.
final class InMemoryEventSecretStore: EventSecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: EventSecrets] = [:]

    func load(eventId: String) -> EventSecrets? { lock.withLock { items[eventId] } }
    func save(_ secrets: EventSecrets, eventId: String) -> Bool { lock.withLock { items[eventId] = secrets }; return true }
    func delete(eventId: String) { _ = lock.withLock { items.removeValue(forKey: eventId) } }
    var count: Int { lock.withLock { items.count } }
}

// MARK: - Stored hosted events

struct HostedRevision: Equatable, Sendable {
    var rev: UInt32
    var hash: [UInt8]
    var eventBytes: [UInt8]
    var publishedAt: Date
}

struct HostedEvent: Identifiable, Hashable, Sendable {
    var draft: HostedEventDraft
    var organizerPub: [UInt8]?
    /// Latest published revision (bytes as signed), if any.
    var latestRev: UInt32?
    var latestPublishedAt: Date?

    var id: String { draft.id }
}

extension EventStore {
    func hostedEvents() throws -> [HostedEvent] {
        try dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT h.draft, h.organizer_pub,
                  (SELECT MAX(rev) FROM hosted_revisions r WHERE r.event_id = h.event_id) AS rev,
                  (SELECT published_at FROM hosted_revisions r WHERE r.event_id = h.event_id ORDER BY rev DESC LIMIT 1) AS published_at
                FROM hosted_events h
                """).compactMap { r -> HostedEvent? in
                guard let draft = try? JSONDecoder().decode(HostedEventDraft.self, from: r["draft"] as Data) else { return nil }
                return HostedEvent(draft: draft, organizerPub: (r["organizer_pub"] as Data?).map { [UInt8]($0) },
                                   latestRev: (r["rev"] as Int64?).map { UInt32($0) },
                                   latestPublishedAt: (r["published_at"] as String?).flatMap(LocalStore.date))
            }
        }
        .sorted { $0.draft.start < $1.draft.start }
    }

    func hostedEvent(id: String) throws -> HostedEvent? {
        try hostedEvents().first { $0.id == id }
    }

    func saveDraft(_ draft: HostedEventDraft) throws {
        let data = try JSONEncoder().encode(draft)
        try dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO hosted_events (event_id, draft, expires_at, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(event_id) DO UPDATE SET draft = excluded.draft, updated_at = excluded.updated_at,
                  expires_at = MAX(excluded.expires_at,
                    COALESCE((SELECT MAX(expires_at) FROM hosted_revisions r WHERE r.event_id = excluded.event_id), 0))
                """, arguments: [draft.id, data, draft.expiresAt.timeIntervalSince1970, LocalStore.timestamp(draft.updatedAt)])
        }
    }

    func setHostedOrganizer(eventId: String, pub: [UInt8]) throws {
        try dbQueue.write { db in
            try db.execute(sql: "UPDATE hosted_events SET organizer_pub = ? WHERE event_id = ?", arguments: [Data(pub), eventId])
        }
    }

    func latestHostedRevision(eventId: String) throws -> HostedRevision? {
        try dbQueue.read { db in
            try Row.fetchOne(db, sql: "SELECT rev, hash, payload, published_at FROM hosted_revisions WHERE event_id = ? ORDER BY rev DESC LIMIT 1",
                             arguments: [eventId]).map {
                HostedRevision(rev: UInt32($0["rev"] as Int64), hash: [UInt8]($0["hash"] as Data), eventBytes: [UInt8]($0["payload"] as Data),
                               publishedAt: LocalStore.date($0["published_at"]) ?? .distantPast)
            }
        }
    }

    func recordHostedRevision(eventId: String, doc: EventDoc, bytes: [UInt8]) throws {
        try dbQueue.write { db in
            try db.execute(sql: """
                INSERT OR REPLACE INTO hosted_revisions (event_id, rev, hash, payload, published_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)
                """, arguments: [eventId, Int64(doc.rev), Data(EventCodes.revisionHash(bytes)), Data(bytes),
                                 LocalStore.timestamp(doc.publishedAt.date), doc.expiresAt.date.timeIntervalSince1970])
            try db.execute(sql: "UPDATE hosted_events SET expires_at = MAX(expires_at, ?) WHERE event_id = ?",
                           arguments: [doc.expiresAt.date.timeIntervalSince1970, eventId])
        }
    }

    func deleteHosted(eventId: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM hosted_revisions WHERE event_id = ?", arguments: [eventId])
            try db.execute(sql: "DELETE FROM hosted_events WHERE event_id = ?", arguments: [eventId])
        }
    }

    /// Hosted events whose access ended: published ones at their latest `expiresAt` (§7.5), drafts never published
    /// a week after their own expiry (so a draft with a past date isn't lost while it's being fixed).
    func expiredHostedIds(now: Date) throws -> [String] {
        try dbQueue.read { db in
            try String.fetchAll(db, sql: """
                SELECT event_id FROM hosted_events h WHERE
                  (EXISTS (SELECT 1 FROM hosted_revisions r WHERE r.event_id = h.event_id) AND h.expires_at <= ?)
                  OR (NOT EXISTS (SELECT 1 FROM hosted_revisions r WHERE r.event_id = h.event_id) AND h.expires_at <= ?)
                """, arguments: [now.timeIntervalSince1970, now.timeIntervalSince1970 - 7 * 86_400])
        }
    }
}

// MARK: - Publishing

/// One publication, kept in memory only for the share screen.
struct PublishedInvite: Equatable, Sendable {
    var doc: EventDoc
    var eventBytes: [UInt8]
    /// Same content as the last published revision: its bytes were re-sealed unchanged (no new `rev`).
    var reused: Bool
    /// nil when the fragment would be over 16 000 characters: share the file instead, or the stops-only link.
    var link: String?
    var fragmentLength: Int
    /// "Pautan tanpa laluan" (§5.5): the same revision without route lines, signed with the same key. Only when the
    /// full link is too long and this one fits.
    var stopsOnlyLink: String?
    /// The default share ("Hantar fail", §5.2.1): a suite-3 file with the link's own key, nonce and ciphertext, so it
    /// opens with no code. Always the full event.
    var fileBytes: [UInt8]
    /// "Acara sensitif": a suite-2 file for the code below (Argon2id), made only when asked for.
    var codeFileBytes: [UInt8]?
    /// The event's 8-character file code `XXXX-XXXX` (the same for every revision), for suite-2 files only.
    var secretCode: String
    var changes: [EventChange]
}

enum EventPublishError: Error, Equatable {
    case issues([EventDraftIssue])
    /// The organizer key of an already published event is gone from this phone (e.g. restored onto another device).
    case keyMissing
    case keychain
    case pack(EventPackError)
    case notFound

    var message: String {
        switch self {
        case .issues(let i): i.first?.message ?? String(localized: "This event can't be published. Check its details.")
        case .keyMissing:
            String(localized: "The organizer key for this event isn't on this phone any more, so guests couldn't trust an update. Create a new event instead.")
        case .keychain: String(localized: "The event's key couldn't be stored on this phone. Try again.")
        case .pack, .notFound: String(localized: "This event can't be published. Check its details.")
        }
    }
}

enum EventPublisher {
    struct Prepared: Equatable {
        var doc: EventDoc
        var bytes: [UInt8]
        var reused: Bool
        var previous: EventDoc?
    }

    /// `publish.rs` `prepare`: rev 1 with `parent: null`; later the same content re-uses the last bytes, otherwise
    /// the next revision (rev + 1, `parent` = SHA-256 of the previous bytes, new `publishedAt`; same id).
    static func prepare(_ draft: HostedEventDraft, previousBytes: [UInt8]?, now: Date) -> Result<Prepared, EventPublishError> {
        let previous = previousBytes.flatMap { b in (try? EventDoc.parse(b)).map { ($0, b) } }
        let (rev, parent, at) = previous.map { ($0.0.rev, $0.0.parent, $0.0.publishedAt.date) } ?? (1, nil, now)
        let (built, issues) = draft.event(rev: rev, parent: parent, publishedAt: previous == nil ? now : at)
        // Access must still be open now, whatever the revision's own `publishedAt`.
        if draft.expiresAt <= now, !issues.contains(.expired) { return .failure(.issues([.expired] + issues)) }
        guard let built else { return .failure(.issues(issues)) }
        guard let (prevDoc, prevBytes) = previous else {
            do { return .success(Prepared(doc: built, bytes: try built.bytes(), reused: false, previous: nil)) } catch { return .failure(.pack(error)) }
        }
        do {
            if try built.bytes() == prevBytes { return .success(Prepared(doc: prevDoc, bytes: prevBytes, reused: true, previous: prevDoc)) }
            let next = built.nextRevision(previousBytes: prevBytes, publishedAt: now)
            return .success(Prepared(doc: next, bytes: try next.bytes(), reused: false, previous: prevDoc))
        } catch {
            return .failure(.pack(error))
        }
    }

    /// Validates, signs with the event's own key, seals with a fresh nonce, re-opens to verify, records the revision.
    /// The link and the default suite-3 file are the same sealed bytes. `codeFile` (default: the draft's "send as file +
    /// code") also makes the suite-2 file, which runs Argon2id (64 MiB, a fraction of a second): call this off the main actor.
    static func publish(eventId: String, store: EventStore, secrets: any EventSecretStore, site: String = EventLinks.site,
                        codeFile: Bool? = nil, now: Date = .now) -> Result<PublishedInvite, EventPublishError> {
        guard let hosted = try? store.hostedEvent(id: eventId) else { return .failure(.notFound) }
        let latest = try? store.latestHostedRevision(eventId: eventId)
        let prepared: Prepared
        switch prepare(hosted.draft, previousBytes: latest?.eventBytes, now: now) {
        case .success(let p): prepared = p
        case .failure(let e): return .failure(e)
        }
        // One key pair per event, created on first publish; never replaced once guests may have pinned it.
        var s = secrets.load(eventId: eventId)
        if s == nil {
            if hosted.organizerPub != nil || latest != nil { return .failure(.keyMissing) }
            let fresh = EventSecrets.generate()
            guard secrets.save(fresh, eventId: eventId), secrets.load(eventId: eventId) == fresh else { return .failure(.keychain) }
            s = fresh
        }
        // Events published before suite 2 get their 8-character file code now, once, kept for every later file.
        if var old = s, old.fileCode == nil {
            old.fileCode = SecretCodeV2.generate()
            guard secrets.save(old, eventId: eventId), secrets.load(eventId: eventId) == old else { return .failure(.keychain) }
            s = old
        }
        guard let s, let code = s.fileCode, let organizer = EventOrganizerKey(seed: s.seed) else { return .failure(.keychain) }
        if let pinned = hosted.organizerPub, pinned != organizer.publicKey { return .failure(.keyMissing) }
        if hosted.organizerPub == nil { try? store.setHostedOrganizer(eventId: eventId, pub: organizer.publicKey) }
        do {
            let sealed = try EventPack.seal(eventBytes: prepared.bytes, organizer: organizer, key: s.packKey)
            let fragment = EventPack.fragment(key: s.packKey, sealed: sealed)
            let link = try? EventPack.link(site: site, key: s.packKey, sealed: sealed)
            // Suite 3 (§5.2): the link's own key, nonce and ciphertext behind the file header. No code, no Argon2id.
            let file = try EventPack.openFileBytes(key: s.packKey, sealed: sealed)
            // Suite 2 only when asked for: the event's stable code, a fresh salt and nonce; Argon2id runs here.
            let codeFileBytes = (codeFile ?? hosted.draft.sensitive)
                ? try EventPack.sealFile(eventBytes: prepared.bytes, organizer: organizer, code: code) : nil
            let stopsOnly = link == nil ? stopsOnlyLink(fullBytes: prepared.bytes, organizer: organizer, key: s.packKey, site: site) : nil
            if !prepared.reused { try? store.recordHostedRevision(eventId: eventId, doc: prepared.doc, bytes: prepared.bytes) }
            return .success(PublishedInvite(doc: prepared.doc, eventBytes: prepared.bytes, reused: prepared.reused, link: link,
                                            fragmentLength: fragment.count, stopsOnlyLink: stopsOnly, fileBytes: file,
                                            codeFileBytes: codeFileBytes, secretCode: code,
                                            changes: prepared.previous.map { EventChange.between($0, prepared.doc) } ?? []))
        } catch {
            return .failure(.pack(error))
        }
    }

    /// The suite-2 file ("Acara sensitif") for bytes already published, with the event's stable code: fresh salt and
    /// nonce, same organizer key. Runs Argon2id: not on the main actor.
    static func codeFile(eventId: String, eventBytes: [UInt8], secrets: any EventSecretStore) -> Result<[UInt8], EventPublishError> {
        guard let s = secrets.load(eventId: eventId), let code = s.fileCode, let organizer = EventOrganizerKey(seed: s.seed) else {
            return .failure(.keyMissing)
        }
        do {
            return .success(try EventPack.sealFile(eventBytes: eventBytes, organizer: organizer, code: code))
        } catch {
            return .failure(.pack(error))
        }
    }

    /// The organizer key for an event, created now if it has none yet (the first publish would do the same), so "Semak"
    /// can show the safety code guests will see. nil when the key of an already published event is gone from this phone.
    static func ensureOrganizerKey(eventId: String, store: EventStore, secrets: any EventSecretStore) -> [UInt8]? {
        guard let hosted = try? store.hostedEvent(id: eventId) else { return nil }
        if let s = secrets.load(eventId: eventId), let org = EventOrganizerKey(seed: s.seed) {
            if let pinned = hosted.organizerPub, pinned != org.publicKey { return nil }
            if hosted.organizerPub == nil { try? store.setHostedOrganizer(eventId: eventId, pub: org.publicKey) }
            return org.publicKey
        }
        let published = (try? store.latestHostedRevision(eventId: eventId)) ?? nil
        guard hosted.organizerPub == nil, published == nil else { return nil }
        let fresh = EventSecrets.generate()
        guard secrets.save(fresh, eventId: eventId), secrets.load(eventId: eventId) == fresh,
              let org = EventOrganizerKey(seed: fresh.seed) else { return nil }
        try? store.setHostedOrganizer(eventId: eventId, pub: org.publicKey)
        return org.publicKey
    }

    /// The stops-only link of a full revision (§5.5): same rev, same organizer key, same pack key, fresh nonce. nil when
    /// the event has no routes (nothing to drop) or it still doesn't fit in a link.
    static func stopsOnlyLink(fullBytes: [UInt8], organizer: EventOrganizerKey, key: [UInt8], site: String) -> String? {
        guard let doc = try? EventDoc.parse(fullBytes), !doc.routes.isEmpty,
              let variant = try? EventDoc.stopsOnlyVariant(fullEventBytes: fullBytes),
              let sealed = try? EventPack.seal(eventBytes: variant, organizer: organizer, key: key) else { return nil }
        return try? EventPack.link(site: site, key: key, sealed: sealed)
    }

    /// What the share screen offers (§5.1, §5.5): the full link when it fits; otherwise the file, plus a stops-only link
    /// (after a confirm) when that one fits.
    enum ShareChoice: Equatable {
        case link
        case fileOrStopsOnly
        case fileOnly
    }

    static func shareChoice(_ invite: PublishedInvite) -> ShareChoice {
        if invite.link != nil { return .link }
        return invite.stopsOnlyLink != nil ? .fileOrStopsOnly : .fileOnly
    }

    /// Is a pack the host's own event? (Same id as a hosted event and signed with its key.)
    static func isOwn(_ pack: OpenedEventPack, store: EventStore) -> Bool {
        guard let h = try? store.hostedEvent(id: pack.event.id), let pub = h.organizerPub else { return false }
        return pub == pack.organizerPub
    }
}
