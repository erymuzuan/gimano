import SwiftUI
import UIKit

// "Kongsi jemputan" (board EwShare, link first since build 19): one message with the invitation link, because files
// were too cumbersome for guests (download, save, open, import). The link opens in Manotu or in any browser.
// If the full link is too long, the stops-only link (§5.5) is offered after a confirm; only when neither fits does
// "Kongsi jemputan" fall back to the event file. Guests still open files (older invitations) as before.
// Nothing here reaches Manotu's servers: the system share sheet hands the message to the app the host picks.

struct EventShareView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let eventId: String

    @State private var sharing: ShareItems?
    @State private var confirmStopsOnly = false
    @State private var useStopsOnly = false
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
        .confirmationDialog("Share a link without routes?", isPresented: $confirmStopsOnly, titleVisibility: .visible) {
            Button("Use the link without routes") { useStopsOnly = true }
        } message: {
            Text("Guests see the places, times and route distances, but not the route lines.")
        }
        .alert("Can't share yet", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(verbatim: error ?? "")
        }
    }

    /// The link guests get: the full one, else the stops-only one once the host agreed.
    private func activeLink(_ inv: PublishedInvite) -> String? {
        inv.link ?? (useStopsOnly ? inv.stopsOnlyLink : nil)
    }

    /// The message with the link cut short for the preview ("https://manotu.app/a/#1.Ab3…").
    static func preview(_ message: String, link: String) -> String {
        let short = link.count > 32 ? String(link.prefix(32)) + "…" : link
        return message.replacingOccurrences(of: link, with: short)
    }

    private func content(_ inv: PublishedInvite) -> some View {
        let doc = inv.doc
        let link = activeLink(inv)
        let text = link.map { Self.preview(EventShareMessage.link(doc, link: $0), link: $0) }
        return VStack(alignment: .leading, spacing: Tokens.Space.m) {
            HStack(spacing: 6) {
                EventIconView(icon: .eye, size: 18)
                Text("Guests will receive").font(Tokens.Font.small.weight(.heavy)).foregroundStyle(Color.textMuted).textCase(.uppercase)
            }
            .padding(.top, Tokens.Space.xs)
            if let text {
                // A chat bubble, as the message arrives in WhatsApp.
                Text(verbatim: text)
                    .font(Tokens.Font.caption).foregroundStyle(Color.black)
                    .padding(Tokens.Space.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(light: 0xDCF8C6, dark: 0x3B6B47), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .padding(.horizontal, Tokens.Space.xl)
                    .padding(.vertical, Tokens.Space.l)
                    .frame(maxWidth: .infinity)
                    .background(Color(light: 0xEFE7DD, dark: 0x1F2A2E), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .accessibilityLabel(Text("Preview of what guests receive"))
                    .accessibilityValue(Text(verbatim: text))
                    .accessibilityIdentifier("eventShareMessage")
            } else {
                tooLong(inv)
            }
            HStack(alignment: .top, spacing: Tokens.Space.s) {
                EventIconView(icon: .safety, size: 18)
                Text("Anyone with the link can see the plan. Share it in groups you trust.")
                    .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            }
            .padding(Tokens.Space.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("eventShareHint")
            versionLine(inv)
        }
    }

    @ViewBuilder
    private func tooLong(_ inv: PublishedInvite) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Label("This plan is too long for a link.", systemImage: "exclamationmark.triangle.fill")
                .font(Tokens.Font.caption).foregroundStyle(Color.amberInk)
            if inv.stopsOnlyLink != nil {
                Text("Share a link without routes: guests see the places, times and route distances, but no lines on the map.")
                    .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                Button { confirmStopsOnly = true } label: { Label("Link without routes", systemImage: "link.badge.plus") }
                    .font(Tokens.Font.label)
                    .accessibilityIdentifier("eventStopsOnlyLink")
            } else {
                Text("“Share invitation” sends the event file instead. Guests open it in Manotu.")
                    .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            }
        }
        .padding(Tokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.amberMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
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

    private func bottomBar(_ inv: PublishedInvite) -> some View {
        let link = activeLink(inv)
        return VStack(spacing: Tokens.Space.s) {
            HStack(spacing: Tokens.Space.m) {
                Button {
                    guard let link else { return }
                    UIPasteboard.general.string = link
                    model.showToast(String(localized: "Link copied"))
                } label: { Label("Copy link", systemImage: "link") }
                    .buttonStyle(SecondaryButtonStyle())
                    .frame(maxWidth: 150)
                    .disabled(link == nil)
                    .accessibilityIdentifier("eventCopyLink")
                Button { share(inv) } label: { Label("Share invitation", systemImage: "square.and.arrow.up") }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("eventShareLink")
            }
            Button { model.events.path = [] } label: { Text("Done") }
                .font(Tokens.Font.label).foregroundStyle(Color.brand)
                .frame(minHeight: 36)
                .accessibilityIdentifier("eventShareDone")
        }
        .padding(.horizontal, Tokens.Space.gutter)
        .padding(.vertical, Tokens.Space.s)
        .background(Color.surface.shadow(.drop(color: .black.opacity(0.06), radius: 8, y: -2)), ignoresSafeAreaEdges: .bottom)
    }

    private func share(_ inv: PublishedInvite) {
        if let link = activeLink(inv) {
            sharing = ShareItems(items: [EventShareMessage.link(inv.doc, link: link)])
        } else if inv.stopsOnlyLink != nil {
            confirmStopsOnly = true
        } else if let url = model.events.inviteFileURL(eventId) {
            // Neither link fits: the open (suite-3) file, which carries the whole plan.
            sharing = ShareItems(items: [url])
        } else {
            error = String(localized: "This event can't be published. Check its details.")
        }
    }
}
