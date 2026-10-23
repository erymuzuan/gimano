import LocalAuthentication
import SwiftUI

extension Tier {
    var title: LocalizedStringKey {
        switch self {
        case .baharu: "Baharu"
        case .perak: "Perak"
        case .emas: "Emas"
        }
    }
    var tint: Color {
        switch self {
        case .baharu: .brandMist
        case .perak: Color(light: 0xE6E9EF, dark: 0x2A3242)
        case .emas: .amberMist
        }
    }
    var ink: Color {
        switch self {
        case .baharu: .brandInk
        case .perak: Color(light: 0x3A4A66, dark: 0xC9D3E3)
        case .emas: .amberInk
        }
    }
}

/// "Belum disahkan · 1 daripada 3" / "Emas" badges.
struct VettingBadge: View {
    let confirmed: Int
    let required: Int
    var body: some View {
        Text("Unverified · \(confirmed) of \(required)")
            .font(Tokens.Font.nunito(12, .black))
            .foregroundStyle(Color.amberInk)
            .padding(.horizontal, 10).padding(.vertical, 3)
            .background(Color.amberMist, in: Capsule())
            .accessibilityIdentifier("badgeUnverifiedCount")
    }
}

struct TierBadge: View {
    let tier: Tier
    var body: some View {
        Label { Text(tier.title) } icon: { Image(systemName: tier == .emas ? "medal.fill" : "seal.fill") }
            .font(Tokens.Font.nunito(12, .black))
            .foregroundStyle(tier.ink)
            .padding(.horizontal, 10).padding(.vertical, 3)
            .background(tier.tint, in: Capsule())
            .accessibilityIdentifier("tierBadge.\(tier.rawValue)")
    }
}

/// Board "Nearby driver: verify prompt": shown only when parked within 1 km.
struct VettingPromptSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let place: Place
    @State private var nameCorrect: Bool? = true
    @State private var locationCorrect: Bool? = true
    @State private var result: VerifyResult?
    @State private var error: String?
    @State private var sending = false

    private var distance: Double { Geo.distance(model.position, LatLng(lat: place.lat, lng: place.lng)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VettingBadge(confirmed: place.confirmationsWeighted.map { Int($0) } ?? place.verifiedCount ?? 0,
                         required: place.required.map { Int($0) } ?? 3)
            HStack(spacing: 12) {
                GimanoIcon(icon: place.category.tileIcon, size: 40)
                    .frame(width: 76, height: 76)
                    .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(place.name).font(Tokens.Font.nunito(19, .black)).accessibilityIdentifier("vettingPlaceName")
                    Text("\(Text(AddPlaceCategories.all.first { $0.0 == place.category }?.1 ?? "Place")) · \(ManeuverFormatter.distance(distance)) away")
                        .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    if let h = place.openingHours { Text(verbatim: h).font(Tokens.Font.small).foregroundStyle(Color.textMuted) }
                }
            }
            if let result {
                Label(result.status == .published ? "Thanks! It's now on the map." : "Thanks! \(Int(result.confirmationsWeighted)) of \(Int(result.required)) confirmations.",
                      systemImage: "checkmark.seal.fill")
                    .font(Tokens.Font.bodyBold).foregroundStyle(Color.brand)
                    .accessibilityIdentifier("vettingThanks")
                Button("Done") { dismiss() }.buttonStyle(PrimaryButtonStyle())
            } else {
                Text("Is this place here?").font(Tokens.Font.nunito(20, .black))
                HStack(spacing: 8) {
                    chip("Name is right", on: nameCorrect == true) { nameCorrect = nameCorrect == true ? false : true }
                    chip("Location is right", on: locationCorrect == true) { locationCorrect = locationCorrect == true ? false : true }
                }
                HStack(spacing: 8) {
                    answer("Yes, it's here", .yes, bg: Color(light: 0x0E8A5F, dark: 0x1FA97A), fg: .white)
                    answer("Not here", .no, bg: Color(light: 0xFFE2E2, dark: 0x3A1A1E), fg: Color(light: 0x8B1A1A, dark: 0xFFB4B4))
                    answer("Not sure", .unsure, bg: .surfaceAlt, fg: .textMuted)
                }
                if let error { Text(error).font(Tokens.Font.caption).foregroundStyle(Color.speeding).accessibilityIdentifier("vettingError") }
                Text("Only asked when you've stopped within 1 km. Your phone checks the distance; your location isn't stored.")
                    .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            }
        }
        .padding(Tokens.Space.gutter)
    }

    private func chip(_ title: LocalizedStringKey, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: on ? "checkmark" : "xmark")
                .font(Tokens.Font.nunito(13, .black))
                .padding(.horizontal, 12).frame(height: 34)
                .foregroundStyle(on ? Color.brandInk : Color.textMuted)
                .background(on ? Color.brandMist : Color.surfaceAlt, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func answer(_ title: LocalizedStringKey, _ a: VoteAnswer, bg: Color, fg: Color) -> some View {
        Button {
            guard !sending else { return }
            sending = true
            Task {
                defer { sending = false }
                do {
                    result = try await model.vote(on: place, answer: a,
                                                  nameCorrect: a == .yes ? nameCorrect : nil, locationCorrect: a == .yes ? locationCorrect : nil)
                    if a != .yes { dismiss(); model.showToast(String(localized: "Thanks for checking!")) }
                } catch {
                    self.error = error.localizedDescription
                }
            }
        } label: {
            Text(title).font(Tokens.Font.nunito(15, .black)).multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 64)
                .foregroundStyle(fg)
                .background(bg, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("vote.\(a.rawValue)")
    }
}

/// Settings → Account: pseudonym, tier, recovery code, restore from code.
struct AccountView: View {
    @Environment(AppModel.self) private var model
    @State private var words: [String]?
    @State private var showRestore = false
    @State private var authError: String?

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    TierRing(tier: model.user?.tier ?? .baharu, progress: model.tierProgress)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: model.user?.displayName ?? model.identityName ?? "—").font(Tokens.Font.title)
                            .accessibilityIdentifier("accountPseudonym")
                        TierBadge(tier: model.user?.tier ?? .baharu)
                    }
                }
                if let acc = model.user?.accuracy { LabeledContent("Accuracy", value: acc.formatted(.percent.precision(.fractionLength(0)))) }
                LabeledContent("Places accepted", value: "\(model.user?.placesAccepted ?? 0)")
                LabeledContent("Places you checked", value: "\(model.user?.verifierVotes ?? 0)")
                if let next = model.user?.nextTier {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Next: \(Text(next.name.title))").font(Tokens.Font.bodyBold)
                        ForEach(next.missing, id: \.self) { m in
                            Text(NextTierText.describe(m)).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                        }
                    }
                    .accessibilityIdentifier("nextTier")
                }
            } footer: {
                Text("Gimano never knows who you are. Your public work is credited to this random pseudonym, which only your phone and your own backup can sign for.")
            }

            Section {
                if let words {
                    RecoveryWordsGrid(words: words)
                    Button("Hide recovery code") { self.words = nil }
                } else {
                    Button { reveal() } label: { Label("Show recovery code", systemImage: "key.fill") }
                        .accessibilityIdentifier("showRecoveryCode")
                }
                if let authError { Text(authError).font(Tokens.Font.caption).foregroundStyle(Color.speeding) }
                Button { showRestore = true } label: { Label("Restore from a recovery code", systemImage: "arrow.uturn.backward") }
            } header: {
                Text("Recovery code")
            } footer: {
                Text("24 words that recreate your pseudonym's key. Write them down and keep them private: anyone with them can sign as you. Not needed if you back up.")
            }
        }
        .navigationTitle("Account")
        .sheet(isPresented: $showRestore) { RestoreFromCodeSheet() }
        .task { await model.refreshMe() }
    }

    private func reveal() {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            authError = String(localized: "Set a passcode on this phone to see the recovery code.")
            return
        }
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: String(localized: "Show your recovery code")) { ok, _ in
            Task { @MainActor in
                if ok, let id = try? model.store.identity() { words = RecoveryCode.encode(id.keys.seed) }
            }
        }
    }
}

enum NextTierText {
    static func describe(_ code: String) -> String {
        let parts = code.split(separator: ":", maxSplits: 1).map(String.init)
        let n = parts.count > 1 ? parts[1] : ""
        switch parts[0] {
        case "placesAccepted": return String(localized: "\(n) more accepted places")
        case "accuracy": return String(localized: "Accuracy of at least \(n)%")
        case "accountDays": return String(localized: "\(n) more days")
        case "signingKey": return String(localized: "Update the app to sign your contributions")
        case "noUpheldDispute90d": return String(localized: "No upheld disputes in 90 days")
        default: return code
        }
    }
}

struct TierRing: View {
    let tier: Tier
    let progress: Double
    var body: some View {
        ZStack {
            Circle().stroke(Color.brandMist, lineWidth: 8)
            Circle().trim(from: 0, to: max(0.02, min(1, progress)))
                .stroke(tier == .emas ? Color.amberInk : Color.brand, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Image(systemName: tier == .emas ? "medal.fill" : "seal.fill").font(.system(size: 22, weight: .bold)).foregroundStyle(tier.ink)
        }
        .frame(width: 64, height: 64)
        .accessibilityLabel(Text(tier.title))
    }
}

struct RecoveryWordsGrid: View {
    let words: [String]
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
            ForEach(Array(words.enumerated()), id: \.offset) { i, w in
                Text(verbatim: "\(i + 1). \(w)")
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(6)
                    .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 8))
            }
        }
        .privacySensitive()
        .accessibilityIdentifier("recoveryWords")
    }
}

struct RestoreFromCodeSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var message: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(String(localized: "24 words, separated by spaces"), text: $text, axis: .vertical)
                        .lineLimit(4...8)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("recoveryInput")
                } footer: {
                    Text("This replaces the pseudonym on this phone with the one the code belongs to.")
                }
                if let message { Text(message).font(Tokens.Font.caption).accessibilityIdentifier("recoveryMessage") }
                Button(working ? "Restoring…" : "Restore") {
                    working = true
                    Task {
                        message = await model.restoreIdentity(fromCode: text)
                        working = false
                        if message == nil { dismiss() }
                    }
                }
                .disabled(working || text.split(whereSeparator: \.isWhitespace).count != 24)
            }
            .navigationTitle("Restore pseudonym")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
        }
    }
}
