import SwiftUI

extension FeedbackCategory {
    var gIcon: GIcon {
        switch self {
        case .bug: .hazard
        case .idea: .star
        case .mapData: .map
        case .route: .logo
        case .search: .search
        case .other: .feedback
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .bug: "Bug"
        case .idea: "Idea"
        case .mapData: "Map data"
        case .route: "Route"
        case .search: "Search"
        case .other: "Other"
        }
    }
}

extension FeedbackStatus {
    var title: LocalizedStringKey {
        switch self {
        case .received: "Received"
        case .triaged: "Triaged"
        case .planned: "Planned"
        case .done: "Done"
        case .wontFix: "Won't fix"
        }
    }
}

/// "Add feedback": big category icons, message, optional stars, screenshot + diagnostics toggles.
struct FeedbackForm: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let draft: FeedbackDraft
    @State private var category: FeedbackCategory?
    @State private var message = ""
    @State private var rating: Int?
    @State private var includeScreenshot = true
    @State private var includeDiagnostics = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(FeedbackCategory.allCases) { c in
                            Button { category = c } label: {
                                VStack(spacing: 6) {
                                    GimanoIcon(icon: c.gIcon, size: 40,
                                               stroke: category == c ? .brandOn : .brandInk,
                                               fill: category == c ? .brandOn : .brandTint)
                                    Text(c.title).font(Tokens.Font.label)
                                }
                                .foregroundStyle(category == c ? Color.brandOn : Color.brandInk)
                                .frame(maxWidth: .infinity, minHeight: 88)
                                .background(category == c ? Color.brand : Color.brandMist,
                                            in: RoundedRectangle(cornerRadius: Tokens.Radius.shortcutTile, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("feedbackCategory.\(c.rawValue)")
                        }
                    }

                    TextField(String(localized: "Tell us what happened"), text: $message, axis: .vertical)
                        .lineLimit(4...8)
                        .font(Tokens.Font.body)
                        .padding(14)
                        .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
                        .accessibilityIdentifier("feedbackMessage")
                        .onChange(of: message) { if message.count > FeedbackRules.maxMessage { message = String(message.prefix(FeedbackRules.maxMessage)) } }

                    HStack(spacing: 6) {
                        ForEach(1...5, id: \.self) { n in
                            Button { rating = rating == n ? nil : n } label: {
                                GimanoIcon(icon: .star, size: 36,
                                           stroke: (rating ?? 0) >= n ? Color(uiColor: Tokens.Palette.traffic2) : .outline,
                                           fill: (rating ?? 0) >= n ? Color(uiColor: Tokens.Palette.traffic1) : .clear)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("\(n) stars"))
                            .accessibilityIdentifier("feedbackStar.\(n)")
                        }
                        Spacer()
                    }

                    Toggle(isOn: $includeScreenshot) {
                        HStack(spacing: 10) {
                            if let data = draft.screenshot, let img = UIImage(data: data) {
                                Image(uiImage: img).resizable().scaledToFill()
                                    .frame(width: 40, height: 64).clipShape(RoundedRectangle(cornerRadius: 6))
                            }
                            Text("Include screenshot").font(Tokens.Font.bodyBold)
                        }
                    }
                    .disabled(draft.screenshot == nil)
                    .accessibilityIdentifier("includeScreenshot")

                    Toggle(isOn: $includeDiagnostics) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Include diagnostics").font(Tokens.Font.bodyBold)
                            Text("App version, device, language and approximate location (about 1 km)").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                        }
                    }
                    .accessibilityIdentifier("includeDiagnostics")

                    Button {
                        guard let category else { return }
                        let req = FeedbackRequest(
                            category: category,
                            message: message.trimmingCharacters(in: .whitespacesAndNewlines),
                            rating: rating,
                            screen: draft.screen,
                            context: includeDiagnostics ? draft.context : nil,
                            screenshot: includeScreenshot ? draft.screenshot?.base64EncodedString() : nil)
                        model.submitFeedback(req)
                        dismiss()
                    } label: {
                        Label("Send", systemImage: "paperplane.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(category == nil || !FeedbackRules.validate(message: message))
                    .accessibilityIdentifier("sendFeedback")
                }
                .padding(Tokens.Space.gutter)
            }
            .navigationTitle("Add feedback")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
        }
    }
}

/// "My feedback": local outbox (queued / failed) merged with the server's statuses.
struct MyFeedbackView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                let local = model.feedbackOutbox.items
                let localServerIds = Set(local.compactMap(\.serverId))
                let remoteOnly = model.serverFeedback.filter { !localServerIds.contains($0.id) }
                if local.isEmpty && remoteOnly.isEmpty {
                    ContentUnavailableView("No feedback yet", systemImage: "bubble.left",
                                           description: Text("Use Add feedback in the menu to tell us what to fix."))
                }
                ForEach(local) { item in
                    row(category: item.request.category, message: item.request.message, date: item.createdAt,
                        status: localStatus(item), hasScreenshot: item.request.screenshot != nil)
                        .swipeActions {
                            if item.state == .queued || item.state == .failed {
                                Button("Retry") { model.feedbackOutbox.retry(item.id); Task { await model.flushFeedback() } }.tint(.brand)
                            }
                            Button("Delete", role: .destructive) { model.feedbackOutbox.delete(item.id) }
                        }
                }
                ForEach(remoteOnly) { f in
                    row(category: f.category, message: f.message, date: f.createdAt,
                        status: (Text(f.status.title), Color.brand), hasScreenshot: false)
                }
            }
            .navigationTitle("My feedback")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .refreshable { await model.refreshMyFeedback() }
            .task { await model.refreshMyFeedback() }
        }
    }

    private func localStatus(_ item: OutboxItem) -> (Text, Color) {
        switch item.state {
        case .queued:
            return (Text("Queued, will retry"), Color(uiColor: Tokens.Palette.traffic2))
        case .sending:
            return (Text("Sending…"), Color.brand)
        case .failed:
            return (Text("Not sent"), Color.speeding)
        case .sent:
            return (item.serverStatus.map { Text($0.title) } ?? Text("Received"), Color.brand)
        }
    }

    private func row(category: FeedbackCategory, message: String, date: Date, status: (Text, Color), hasScreenshot: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            GimanoIcon(icon: category.gIcon, size: 28)
                .frame(width: 44, height: 44)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(message).font(Tokens.Font.body).lineLimit(2)
                HStack(spacing: 6) {
                    status.0
                        .font(Tokens.Font.small)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(status.1, in: Capsule())
                    if hasScreenshot { Image(systemName: "photo").font(.system(size: 12)).foregroundStyle(Color.textMuted) }
                    Text(date.formatted(.relative(presentation: .named))).font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("feedbackRow")
    }
}
