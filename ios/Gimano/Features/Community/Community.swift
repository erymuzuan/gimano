import SwiftUI
import UIKit

// MARK: - Blocked contributors (Apple 1.2)

/// A contributor the user chose not to see. Kept only on this phone, in the shared `settings` table (key
/// `blocked_authors`, a JSON array), so it is included in backups and restores on Android too. Nothing is sent.
struct BlockedAuthor: Codable, Hashable, Sendable, Identifiable {
    var ref: String
    var name: String
    var blockedAt: Date
    var id: String { ref }
}

/// Shared settings format (docs/privacy-and-backup.md "Shared settings keys", same as Android):
/// `blocked_authors` = `[{"ref": "...", "name": "...", "blockedAt": "2026-10-24T03:04:05Z"}]` (ISO-8601 UTC, no fractions).
enum BlockList {
    static let settingsKey = "blocked_authors"

    /// One entry as written by either app; anything missing is tolerated except `ref`.
    private struct LooseEntry: Decodable {
        var ref: String?
        var name: String?
        var blockedAt: String?
    }

    /// Tolerant: entries without a `ref` are skipped (never the whole list); a bad or missing date becomes "now".
    static func decode(_ raw: String?) -> [BlockedAuthor] {
        guard let raw, let data = raw.data(using: .utf8),
              let loose = try? JSONDecoder().decode([LooseEntry?].self, from: data) else { return [] }
        var seen = Set<String>()
        return loose.compactMap { e -> BlockedAuthor? in
            guard let e, let ref = e.ref?.trimmingCharacters(in: .whitespaces), !ref.isEmpty, seen.insert(ref).inserted else { return nil }
            return BlockedAuthor(ref: ref, name: e.name ?? "", blockedAt: e.blockedAt.flatMap(JSON.parseDate) ?? .now)
        }
    }

    static func encode(_ list: [BlockedAuthor]) -> String? {
        guard !list.isEmpty else { return nil }
        let rows = list.map { ["ref": $0.ref, "name": $0.name, "blockedAt": dateString($0.blockedAt)] }
        guard let data = try? JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys]) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// ISO-8601 UTC without fractional seconds, e.g. 2026-10-24T03:04:05Z.
    static func dateString(_ d: Date) -> String {
        d.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: false, timeZone: TimeZone(identifier: "UTC")!))
    }

    /// Merge restore: union by `ref`; this phone's entry wins for a ref on both sides.
    static func merged(local: String?, backup: String?) -> String? {
        let mine = decode(local)
        let mineRefs = Set(mine.map(\.ref))
        return encode(mine + decode(backup).filter { !mineRefs.contains($0.ref) })
    }

    /// Reviews whose author is blocked are hidden everywhere (place card, all reviews).
    static func visible(_ reviews: [Review], blocked: Set<String>) -> [Review] {
        reviews.filter { r in r.blockRef.map { !blocked.contains($0) } ?? true }
    }

    /// Photos whose uploader is blocked are hidden everywhere.
    static func visible(_ photos: [Photo], blocked: Set<String>) -> [Photo] {
        photos.filter { p in p.blockRef.map { !blocked.contains($0) } ?? true }
    }
}

extension LocalStore {
    func blockedAuthors() -> [BlockedAuthor] { BlockList.decode(try? setting(BlockList.settingsKey)) }

    func setBlockedAuthors(_ list: [BlockedAuthor]) throws { try setSetting(BlockList.settingsKey, BlockList.encode(list)) }
}

// MARK: - Community rules (terms acceptance before the first public contribution)

/// Shown once before the first public contribution (review, photo, place, report, complaint, vote), and again when the
/// terms version changes. Stored on the phone with the date and version (settings table).
enum CommunityRules {
    /// Bump when https://manotu.app/terms/ changes in a way users must accept again.
    static let version = "2026-10-24"
    static let versionKey = "community_rules_version"
    static let acceptedAtKey = "community_rules_accepted_at"
    static let termsURL = URL(string: "https://manotu.app/terms/")!

    static func isAccepted(storedVersion: String?) -> Bool { storedVersion == version }

    /// Merge restore: keep whichever side accepted the later terms version, with its date (versions are dates, so
    /// they compare as text). Nothing on either side → nil.
    static func merged(local: (version: String?, acceptedAt: String?), backup: (version: String?, acceptedAt: String?))
        -> (version: String?, acceptedAt: String?) {
        guard let b = backup.version, !b.isEmpty else { return local }
        guard let l = local.version, !l.isEmpty else { return backup }
        return b > l ? backup : local
    }
}

struct CommunityRulesSheet: View {
    let onAnswer: (Bool) -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Reviews, photos, places, reports and votes are public and help other drivers. Before you post:")
                        .font(Tokens.Font.body)
                    rule("hand.raised.fill", "No abusive, hateful, sexual, violent or otherwise objectionable content, and no personal information about others.")
                    rule("trash.fill", "Content that breaks these rules is removed, and users who post it can be suspended.")
                    rule("person.crop.circle.badge.xmark", "You can block any user: their reviews and photos are hidden on your phone.")
                    rule("flag.fill", "See something wrong? Tap Report on the review, or Report photo. Moderators check reports.")
                    Link(destination: CommunityRules.termsURL) {
                        Text("Full terms: \(CommunityRules.termsURL.absoluteString)")
                    }
                    .font(Tokens.Font.bodyBold)
                    .accessibilityIdentifier("communityTermsLink")
                    Button("I agree") { onAnswer(true) }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("communityAgree")
                    Button("Not now") { onAnswer(false) }
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("communityNotNow")
                }
                .padding(Tokens.Space.gutter)
            }
            .navigationTitle("Community rules")
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled()
    }

    private func rule(_ icon: String, _ text: LocalizedStringKey) -> some View {
        Label { Text(text).font(Tokens.Font.body) } icon: { Image(systemName: icon).foregroundStyle(Color.brand) }
    }
}

/// Presents a SwiftUI view over whatever is on screen (including another sheet) and waits for its answer.
@MainActor
enum TopPresenter {
    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var vc = scene?.windows.first { $0.isKeyWindow }?.rootViewController ?? scene?.windows.first?.rootViewController
        while let p = vc?.presentedViewController, !p.isBeingDismissed { vc = p }
        return vc
    }

    static func askRules() async -> Bool {
        await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            guard let top = topViewController() else { c.resume(returning: false); return }
            var host: UIHostingController<CommunityRulesSheet>?
            var answered = false
            let sheet = CommunityRulesSheet { ok in
                guard !answered else { return }
                answered = true
                host?.dismiss(animated: true)
                c.resume(returning: ok)
            }
            host = UIHostingController(rootView: sheet)
            host?.isModalInPresentation = true
            top.present(host!, animated: true)
        }
    }
}

// MARK: - Settings: blocked users

struct BlockedUsersView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            if model.blockedAuthors.isEmpty {
                Text("You haven't blocked anyone.").foregroundStyle(Color.textMuted)
                    .accessibilityIdentifier("blockedEmpty")
            }
            ForEach(model.blockedAuthors) { b in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: b.name).font(Tokens.Font.bodyBold)
                        Text("Blocked \(b.blockedAt.formatted(date: .abbreviated, time: .omitted))").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    }
                    Spacer()
                    Button("Unblock") { model.unblockAuthor(b.ref) }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier("unblock.\(b.name)")
                }
            }
            Section {
                EmptyView()
            } footer: {
                Text("Blocked contributors' reviews and photos are hidden on this phone. The list stays on your phone and in your own backups; Manotu isn't told.")
            }
        }
        .navigationTitle("Blocked users")
    }
}

// MARK: - Account: delete my Manotu account (Apple 5.1.1(v))

struct DeleteAccountSection: View {
    @Environment(AppModel.self) private var model
    @State private var confirm = false
    @State private var clearLocal = false
    @State private var working = false
    @State private var error: String?
    static let deleteInfoURL = URL(string: "https://manotu.app/delete/")!

    var body: some View {
        Section {
            if model.config.isDemoMode {
                Text("Demo mode has no account on the server. Turn off demo mode to delete your Manotu account.")
                    .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    .accessibilityIdentifier("deleteAccountDemoNote")
            } else {
                Toggle("Also clear this phone's places, planned drives and settings", isOn: $clearLocal)
                    .accessibilityIdentifier("deleteAccountClearLocal")
                Button(role: .destructive) { confirm = true } label: {
                    if working { ProgressView() } else { Label("Delete my Manotu account", systemImage: "person.crop.circle.badge.minus") }
                }
                .disabled(working)
                .accessibilityIdentifier("deleteAccount")
            }
            if let error { Text(error).font(Tokens.Font.caption).foregroundStyle(Color(uiColor: Tokens.Palette.speeding)).accessibilityIdentifier("deleteAccountError") }
            Link("How account deletion works (manotu.app/delete)", destination: Self.deleteInfoURL)
                .font(Tokens.Font.caption)
        } header: {
            Text("Delete account")
        } footer: {
            Text("Deletes your pseudonym, reputation, votes, inbox and recovery from Manotu's server. Your public reviews, photos, places and reports stay, anonymised. Your places on this phone are kept unless you choose to clear them.")
        }
        .confirmationDialog("Delete your Manotu account?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Delete account", role: .destructive) {
                Task {
                    working = true
                    defer { working = false }
                    error = await model.deleteAccount(clearLocal: clearLocal)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This can't be undone. Manotu then starts you again with a new, empty pseudonym.")
        }
    }
}
