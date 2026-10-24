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
    var createdAt: Date
    var updatedAt: Date

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

    /// The template suggests routes (rides, convoys, trips).
    var routesFirst: Bool { [.ride, .convoy, .trip].contains(template) }

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
            routes: eventRoutes)
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
        case .invalid: Anchor.problems
        }
    }
}

// MARK: - Secrets (Keychain, this device only)

/// Per-event secrets: the 32-byte Ed25519 seed and the 32-byte pack key. Never reused across events.
struct EventSecrets: Sendable, Equatable {
    var seed: [UInt8]
    var packKey: [UInt8]

    static func generate() -> EventSecrets {
        EventSecrets(seed: EventRandom.bytes(32), packKey: EventRandom.bytes(EventPack.keyBytes))
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
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data, data.count == 64 else { return nil }
        let b = [UInt8](data)
        return EventSecrets(seed: Array(b[0..<32]), packKey: Array(b[32..<64]))
    }

    func save(_ secrets: EventSecrets, eventId: String) -> Bool {
        SecItemDelete(base(eventId) as CFDictionary)
        var add = base(eventId)
        add[kSecValueData as String] = Data(secrets.seed + secrets.packKey)
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
    /// The file always carries the full event.
    var fileBytes: [UInt8]
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
    static func publish(eventId: String, store: EventStore, secrets: any EventSecretStore, site: String = EventLinks.site,
                        now: Date = .now) -> Result<PublishedInvite, EventPublishError> {
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
        guard let s, let organizer = EventOrganizerKey(seed: s.seed) else { return .failure(.keychain) }
        if let pinned = hosted.organizerPub, pinned != organizer.publicKey { return .failure(.keyMissing) }
        if hosted.organizerPub == nil { try? store.setHostedOrganizer(eventId: eventId, pub: organizer.publicKey) }
        do {
            let sealed = try EventPack.seal(eventBytes: prepared.bytes, organizer: organizer, key: s.packKey)
            let fragment = EventPack.fragment(key: s.packKey, sealed: sealed)
            let link = try? EventPack.link(site: site, key: s.packKey, sealed: sealed)
            let file = try EventPack.fileBytes(sealed)
            let stopsOnly = link == nil ? stopsOnlyLink(fullBytes: prepared.bytes, organizer: organizer, key: s.packKey, site: site) : nil
            if !prepared.reused { try? store.recordHostedRevision(eventId: eventId, doc: prepared.doc, bytes: prepared.bytes) }
            return .success(PublishedInvite(doc: prepared.doc, eventBytes: prepared.bytes, reused: prepared.reused, link: link,
                                            fragmentLength: fragment.count, stopsOnlyLink: stopsOnly, fileBytes: file,
                                            secretCode: SecretCode.encode(s.packKey),
                                            changes: prepared.previous.map { EventChange.between($0, prepared.doc) } ?? []))
        } catch {
            return .failure(.pack(error))
        }
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
