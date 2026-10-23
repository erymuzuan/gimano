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
    @State private var includeDevice = true
    @State private var includeLocation = false
    @State private var email = ""
    @State private var whatsapp = ""
    @State private var contactConsent = false
    @State private var contactError: String?

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(FeedbackCategory.allCases) { c in
                            Button { category = c } label: {
                                VStack(spacing: 6) {
                                    GimanoIcon(icon: c.gIcon, size: 40)
                                    Text(c.title).font(Tokens.Font.label)
                                }
                                .foregroundStyle(Color.brandInk)
                                .frame(maxWidth: .infinity, minHeight: 88)
                                .selectableTile(category == c, radius: Tokens.Radius.shortcutTile, tint: c.tint, width: 3)
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

                    DeviceInfoToggle(isOn: $includeDevice, device: draft.device)

                    Toggle(isOn: $includeLocation) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Include approximate location").font(Tokens.Font.bodyBold)
                            Text("Rounded to about 1 km on your phone").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                        }
                    }
                    .disabled(draft.location == nil)
                    .accessibilityIdentifier("includeLocation")

                    ContactSection(email: $email, whatsapp: $whatsapp, consent: $contactConsent, error: contactError)

                    Button {
                        guard let category else { return }
                        let (contact, err) = ContactRules.build(email: email, whatsapp: whatsapp, consent: contactConsent)
                        contactError = err
                        guard err == nil else { return }
                        let req = FeedbackRequest(
                            category: category,
                            message: message.trimmingCharacters(in: .whitespacesAndNewlines),
                            rating: rating,
                            screen: draft.screen,
                            device: includeDevice ? draft.device : nil,
                            location: includeLocation ? draft.location : nil,
                            contact: contact,
                            routeId: draft.routeId,
                            placeId: draft.placeId,
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
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text("Add feedback").font(Tokens.Font.bodyBold)
                        Text(verbatim: "\(AppInfo.name) \(AppInfo.versionString)").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                            .accessibilityIdentifier("feedbackVersion")
                    }
                }
            }
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
                            if let sid = item.serverId {
                                contactAction(serverId: sid, hasContact: model.serverFeedback.first { $0.id == sid }?.hasContact == true)
                            }
                        }
                }
                ForEach(remoteOnly) { f in
                    row(category: f.category, message: f.message, date: f.createdAt,
                        status: (Text(f.status.title), Color.brand), hasScreenshot: false)
                        .swipeActions { contactAction(serverId: f.id, hasContact: f.hasContact == true) }
                }
            }
            .navigationTitle("My feedback")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .refreshable { await model.refreshMyFeedback() }
            .task { await model.refreshMyFeedback() }
        }
    }

    /// "Delete my contact details" for a report I left them on.
    @ViewBuilder private func contactAction(serverId: String, hasContact: Bool) -> some View {
        if hasContact {
            Button { Task { await model.deleteFeedbackContact(serverId) } } label: {
                Label("Delete contact", systemImage: "person.crop.circle.badge.xmark")
            }
            .tint(.speeding)
            .accessibilityIdentifier("deleteContact")
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

extension FeedbackCategory {
    /// Board tints: bug = red, idea = amber, other = grey, the rest cobalt.
    var tint: Color {
        switch self {
        case .bug: Color(light: 0xFFE2E2, dark: 0x3A1A1E)
        case .idea: Color(light: 0xFFF1CC, dark: 0x3A2E12)
        case .other: Color(light: 0xEEF0F4, dark: 0x1E2636)
        default: .brandMist
        }
    }
}

/// "Include device info" (default on) with a preview of the exact block that is sent.
struct DeviceInfoToggle: View {
    @Binding var isOn: Bool
    let device: DeviceInfo
    @State private var showPreview = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: $isOn) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Include device info").font(Tokens.Font.bodyBold)
                    Text("App version, iOS version, model, screen size, language, theme").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                }
            }
            .accessibilityIdentifier("includeDevice")
            Button(showPreview ? "Hide what's sent" : "See what's sent") { withAnimation { showPreview.toggle() } }
                .font(Tokens.Font.label)
                .accessibilityIdentifier("deviceInfoPreviewButton")
            if showPreview {
                Text(verbatim: Self.json(device))
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .accessibilityIdentifier("deviceInfoPreview")
            }
        }
    }

    static func json(_ value: some Encodable) -> String {
        let e = JSON.makeEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return (try? e.encode(value)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }
}

/// Optional contact details for THIS report only: never prefilled, explicit consent, deletable later.
struct ContactSection: View {
    @Binding var email: String
    @Binding var whatsapp: String
    @Binding var consent: Bool
    var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Want a reply? (optional)").font(Tokens.Font.bodyBold)
            Text("Only if you want us to contact you about this report. Kept encrypted, used for this report only, deleted automatically within 90 days (or any time from My feedback). Never added to your pseudonym.")
                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            TextField(String(localized: "Email"), text: $email)
                .keyboardType(.emailAddress).textContentType(.none).textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityIdentifier("contactEmail")
            TextField(String(localized: "WhatsApp number, e.g. +60123456789"), text: $whatsapp)
                .keyboardType(.phonePad).textContentType(.none)
                .padding(12).background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityIdentifier("contactWhatsApp")
            if !email.isEmpty || !whatsapp.isEmpty {
                Toggle(isOn: $consent) {
                    Text("Gimano may contact me about this report").font(Tokens.Font.caption)
                }
                .accessibilityIdentifier("contactConsent")
            }
            if let error { Text(error).font(Tokens.Font.caption).foregroundStyle(Color.speeding).accessibilityIdentifier("contactError") }
        }
    }
}

/// "Gimano stopped last time. Send a report?" (next launch after a crash/hang; MetricKit, no third-party SDK).
struct CrashReportSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showDetails = false
    @State private var email = ""
    @State private var whatsapp = ""
    @State private var consent = false
    @State private var error: String?
    @State private var preference = CrashReporter.shared.preference

    /// Exactly what is sent (scrubbed, with the device block; contact is added only if entered).
    private func previewJSON(_ r: CrashReport) -> String {
        var p = r.scrubbed()
        p.device = model.currentDevice()
        return DeviceInfoToggle.json(p)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        GimanoIcon(icon: .hazard, size: 36)
                            .frame(width: 60, height: 60)
                            .background(Color.reportTint, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        Text("Gimano stopped last time. Send a report?").font(Tokens.Font.title)
                    }
                    Text("It helps us fix it. Locations, emails, phone numbers, tokens and file paths are removed on your phone first. No favourites, routes, searches or screenshots.")
                        .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    Button(showDetails ? "Hide details" : "See details") { withAnimation { showDetails.toggle() } }
                        .font(Tokens.Font.label)
                        .accessibilityIdentifier("crashDetailsButton")
                    if showDetails, let first = model.pendingCrashes.first {
                        Text(verbatim: previewJSON(first))
                            .font(.system(size: 11, design: .monospaced))
                            .lineLimit(40)
                            .textSelection(.enabled)
                            .padding(10)
                            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .accessibilityIdentifier("crashDetails")
                    }
                    ContactSection(email: $email, whatsapp: $whatsapp, consent: $consent, error: error)
                    Picker("Next time", selection: $preference) {
                        ForEach(CrashPreference.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("crashPreference")
                    Button {
                        let (contact, err) = ContactRules.build(email: email, whatsapp: whatsapp, consent: consent)
                        error = err
                        guard err == nil else { return }
                        CrashReporter.shared.preference = preference
                        Task { await model.sendCrashReports(contact: contact) }
                        dismiss()
                    } label: { Label("Send report", systemImage: "paperplane.fill") }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("sendCrashReport")
                    Button("Don't send") {
                        CrashReporter.shared.preference = preference == .always ? .ask : preference
                        model.discardCrashReports()
                        dismiss()
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("discardCrashReport")
                }
                .padding(Tokens.Space.gutter)
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
