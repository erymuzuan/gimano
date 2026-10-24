import SwiftUI
import UIKit
import UniformTypeIdentifiers

// "Kongsi jemputan" (board EwShare, docs/events.md §5.2.1): file first. The default "Hantar fail" sends the suite-3
// `.manotu-event` file plus a short message; there is no code to send. "Acara sensitif" switches to the suite-2 file
// and the 8-character code, sent separately. The web link stays available as "Salin pautan web".
// Nothing here reaches Manotu's servers: the system share sheet hands the file to the app the host picks.

struct EventShareView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let eventId: String

    @State private var sharing: ShareItems?
    @State private var showCode = false
    @State private var confirmStopsOnly = false
    @State private var showStopsOnly = false
    @State private var error: String?

    struct ShareItems: Identifiable {
        let id = UUID()
        var items: [Any]
    }

    var body: some View {
        let center = model.events
        let journey = center.hostedEvent(eventId)?.draft.isJourney ?? false
        VStack(spacing: 0) {
            EventWizardHeader(title: String(localized: "Share invitation"), current: .review, journey: journey,
                              onBack: { dismiss() })
            ScrollView {
                if let inv = center.invites[eventId] {
                    content(inv)
                        .padding(.horizontal, Tokens.Space.gutter)
                        .padding(.bottom, Tokens.Space.xl)
                } else {
                    ContentUnavailableView("Share the event again from its page", systemImage: "square.and.arrow.up")
                }
            }
        }
        .background(Color.appBackground)
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .bottom) {
            if let inv = center.invites[eventId] { bottomBar(inv) }
        }
        .sheet(item: $sharing) { s in ActivityView(items: s.items) }
        .alert("Can't share yet", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(verbatim: error ?? "")
        }
    }

    private var sensitive: Bool { model.events.hostedEvent(eventId)?.draft.sensitive ?? false }

    private func setSensitive(_ on: Bool) {
        guard var d = model.events.hostedEvent(eventId)?.draft else { return }
        d.sensitive = on
        model.events.saveDraft(d)
        if on { Task { if let e = await model.events.makeCodeFile(eventId) { error = e } } }
    }

    private func content(_ inv: PublishedInvite) -> some View {
        let doc = inv.doc
        let fileName = EventCenter.shareFileName(doc.title)
        let size = ByteCountFormatter.string(fromByteCount: Int64(sensitive ? (inv.codeFileBytes?.count ?? inv.fileBytes.count) : inv.fileBytes.count),
                                             countStyle: .file)
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            HStack(spacing: 6) {
                EventIconView(icon: .eye, size: 18)
                Text("Guests will receive").font(Tokens.Font.small.weight(.heavy)).foregroundStyle(Color.textMuted).textCase(.uppercase)
            }
            .padding(.top, Tokens.Space.xs)
            // A chat bubble: the file and the message, as they arrive in WhatsApp.
            VStack(alignment: .leading, spacing: Tokens.Space.s) {
                HStack(spacing: Tokens.Space.m) {
                    Image(systemName: sensitive ? "lock.doc.fill" : "doc.fill")
                        .font(.system(size: 30)).foregroundStyle(Color.brand)
                        .frame(width: 44, height: 52)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: fileName).font(Tokens.Font.label).foregroundStyle(Color.black).lineLimit(2)
                        Text(verbatim: "\(size) · Manotu").font(Tokens.Font.small).foregroundStyle(Color.black.opacity(0.6))
                    }
                }
                .padding(Tokens.Space.s)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(light: 0xC9E8CF, dark: 0x2E5A39), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                Text(verbatim: sensitive ? EventShareMessage.file(doc) : EventShareMessage.openFile(doc))
                    .font(Tokens.Font.caption).foregroundStyle(Color.black)
                    .textSelection(.enabled)
                    .accessibilityIdentifier("eventShareMessage")
            }
            .padding(Tokens.Space.m)
            .background(Color(light: 0xDCF8C6, dark: 0x3B6B47), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .padding(.horizontal, Tokens.Space.xl)
            .padding(.vertical, Tokens.Space.l)
            .frame(maxWidth: .infinity)
            .background(Color(light: 0xEFE7DD, dark: 0x1F2A2E), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("Preview of what guests receive"))

            if !sensitive {
                HStack(alignment: .top, spacing: Tokens.Space.s) {
                    EventIconView(icon: .safety, size: 18)
                    Text("No code to send. Anyone with the file can open it; share it in groups you trust.")
                        .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                }
                .padding(Tokens.Space.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            }

            Toggle(isOn: Binding(get: { sensitive }, set: setSensitive)) {
                HStack(spacing: Tokens.Space.m) {
                    EventIconView(icon: .lock, size: 28)
                        .frame(width: 44, height: 44)
                        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Sensitive event").font(Tokens.Font.bodyBold)
                        Text("File + secret code, sent separately").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                    }
                }
            }
            .tint(Color.brand)
            .padding(Tokens.Space.m)
            .overlay(RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous).strokeBorder(Color.outline, lineWidth: 2))
            .accessibilityIdentifier("eventShareSensitive")

            if sensitive { codeCard(inv) }

            versionLine(inv)

            linkSection(inv)
        }
    }

    private func codeCard(_ inv: PublishedInvite) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            if model.events.makingCodeFile == eventId || inv.codeFileBytes == nil {
                HStack(spacing: Tokens.Space.s) {
                    ProgressView()
                    Text("Locking the file…").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                }
            }
            Text("Secret code").font(Tokens.Font.label).foregroundStyle(Color.textMuted)
            Text(verbatim: showCode ? inv.secretCode : "••••-••••")
                .font(.system(.title2, design: .monospaced).weight(.semibold))
                .foregroundStyle(Color.brandInk)
                .textSelection(.enabled)
                .accessibilityIdentifier("eventSecretCodeValue")
            HStack(spacing: Tokens.Space.s) {
                Button(showCode ? "Hide" : "Show") { showCode.toggle() }
                    .accessibilityIdentifier("eventSecretCodeShow")
                Spacer()
                Button {
                    // Local only (no Universal Clipboard) and cleared after 5 minutes.
                    UIPasteboard.general.setItems([[UTType.plainText.identifier: inv.secretCode]],
                                                  options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(300)])
                    model.showToast(String(localized: "Secret code copied"))
                } label: { Label("Copy code", systemImage: "doc.on.doc") }
                ShareLink(item: inv.secretCode) { Label("Send code…", systemImage: "key.fill") }
            }
            .font(Tokens.Font.label)
            Text("Send the code through a different app or channel than the file.")
                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
        }
        .padding(Tokens.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
    }

    private func versionLine(_ inv: PublishedInvite) -> some View {
        let doc = inv.doc
        return Group {
            if inv.reused {
                Text("Version \(Int(doc.rev)) · no changes since it was last shared")
            } else if doc.rev > 1 {
                Text("Version \(Int(doc.rev)) · \(inv.changes.count) changes. Guests who open the new invitation see what changed.")
            } else {
                Text("Version 1")
            }
        }
        .font(Tokens.Font.small).foregroundStyle(Color.brand)
        .accessibilityIdentifier("eventShareVersion")
    }

    @ViewBuilder
    private func linkSection(_ inv: PublishedInvite) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            if let link = inv.link {
                Button {
                    UIPasteboard.general.string = link
                    model.showToast(String(localized: "Link copied"))
                } label: { Label("Copy web link", systemImage: "link") }
                    .font(Tokens.Font.label)
                    .accessibilityHint(Text("For guests without Manotu: the plan opens in any browser."))
                    .accessibilityIdentifier("eventCopyLink")
            } else {
                Text("This plan is too long for a web link. The file has the whole plan.")
                    .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                if let stopsOnly = inv.stopsOnlyLink {
                    // §5.5: offered only when the full link is too long and this one fits; after a confirm.
                    if showStopsOnly {
                        Text("Link without routes: guests see the places, times and route distances, but no lines on the map.")
                            .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                        Button {
                            UIPasteboard.general.string = stopsOnly
                            model.showToast(String(localized: "Link copied"))
                        } label: { Label("Copy link without routes", systemImage: "link") }
                            .font(Tokens.Font.label)
                            .accessibilityIdentifier("eventCopyStopsOnlyLink")
                    } else {
                        Button { confirmStopsOnly = true } label: { Label("Link without routes", systemImage: "link.badge.plus") }
                            .font(Tokens.Font.label)
                            .accessibilityIdentifier("eventStopsOnlyLink")
                    }
                }
            }
        }
        .confirmationDialog("Share a link without routes?", isPresented: $confirmStopsOnly, titleVisibility: .visible) {
            Button("Use the link without routes") { showStopsOnly = true }
        } message: {
            Text("Guests see the places, times and route distances, but not the route lines. Send the event file too for those who want the full route.")
        }
    }

    private func bottomBar(_ inv: PublishedInvite) -> some View {
        let waiting = sensitive && inv.codeFileBytes == nil
        return HStack(spacing: Tokens.Space.m) {
            Button { model.events.path = [] } label: { Text("Done") }
                .buttonStyle(SecondaryButtonStyle())
                .frame(width: 104)
                .accessibilityIdentifier("eventShareDone")
            Button { sendFile(inv) } label: { Label("Send file", systemImage: "square.and.arrow.up") }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(waiting)
                .accessibilityIdentifier("eventShareFile")
        }
        .padding(.horizontal, Tokens.Space.gutter)
        .padding(.vertical, Tokens.Space.s)
        .background(Color.surface.shadow(.drop(color: .black.opacity(0.06), radius: 8, y: -2)), ignoresSafeAreaEdges: .bottom)
    }

    private func sendFile(_ inv: PublishedInvite) {
        let code = sensitive
        guard let url = model.events.inviteFileURL(eventId, codeFile: code) else {
            error = String(localized: "This event can't be published. Check its details.")
            return
        }
        let text = code ? EventShareMessage.file(inv.doc) : EventShareMessage.openFile(inv.doc)
        sharing = ShareItems(items: [text, url])
    }
}
