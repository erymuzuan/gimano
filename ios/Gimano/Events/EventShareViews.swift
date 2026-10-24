import SwiftUI
import UIKit

// "Kongsi jemputan" (board EwShare, link first since build 19): one message with the invitation link, because files
// were too cumbersome for guests (download, save, open, import). The link opens in Manotu or in any browser.
// Since build 20 the message carries the **short link** (§5.6, `https://manotu.app/j/#<dropId>.<secret>`): the encrypted
// plan is uploaded (or updated after a republish) to Manotu's relay, which never gets the secret after `#`. If that
// fails (offline, server error, too big), the message falls back to the full link (§5.1) with a one-line note; if the
// full link is too long, the stops-only link (§5.5) is offered after a confirm; only when neither fits does
// "Kongsi jemputan" fall back to the event file. Guests still open files (older invitations) as before.

struct EventShareView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let eventId: String

    @State private var sharing: ShareItems?
    @State private var confirmStopsOnly = false
    @State private var useStopsOnly = false
    @State private var error: String?
    /// The short link couldn't be made: the full link is used, with this one-line note.
    @State private var fallbackNote: String?

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

    /// The long link guests get when there is no short link: the full one, else the stops-only one once the host agreed.
    private func activeLink(_ inv: PublishedInvite) -> String? {
        inv.link ?? (useStopsOnly ? inv.stopsOnlyLink : nil)
    }

    /// What the preview shows: the short link (made, or about to be), or the long fallback after a failure.
    private func previewLink(_ inv: PublishedInvite) -> String? {
        if fallbackNote == nil {
            return model.events.existingShortLink(eventId) ?? "\(EventLinks.site)\(EventDrop.path)#…"
        }
        return activeLink(inv)
    }

    private var isPreparing: Bool { model.events.preparingShortLink == eventId }

    /// The message with the link cut short for the preview ("https://manotu.app/a/#1.Ab3…").
    static func preview(_ message: String, link: String) -> String {
        let short = link.count > 32 ? String(link.prefix(32)) + "…" : link
        return message.replacingOccurrences(of: link, with: short)
    }

    private func content(_ inv: PublishedInvite) -> some View {
        let doc = inv.doc
        let link = previewLink(inv)
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
            if let fallbackNote {
                Label { Text(verbatim: fallbackNote) } icon: { Image(systemName: "wifi.exclamationmark") }
                    .font(Tokens.Font.small).foregroundStyle(Color.amberInk)
                    .accessibilityIdentifier("eventShortLinkFallback")
            }
            HStack(alignment: .top, spacing: Tokens.Space.s) {
                EventIconView(icon: .safety, size: 18)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Anyone with the link can see the plan. Share it in groups you trust.")
                    Text("Manotu keeps only an encrypted copy, without its key, until 14 days after the event.")
                }
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
        VStack(spacing: Tokens.Space.s) {
            HStack(spacing: Tokens.Space.m) {
                Button { copy(inv) } label: { Label("Copy link", systemImage: "link") }
                    .buttonStyle(SecondaryButtonStyle())
                    .frame(maxWidth: 150)
                    .disabled(isPreparing)
                    .accessibilityIdentifier("eventCopyLink")
                Button { share(inv) } label: {
                    if isPreparing {
                        HStack(spacing: Tokens.Space.s) { ProgressView().tint(Color.brandOn); Text("Preparing link…") }
                    } else {
                        Label("Share invitation", systemImage: "square.and.arrow.up")
                    }
                }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(isPreparing)
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

    /// Makes (or updates) the short link, then runs `use` with it; on failure notes the fallback and runs `fallback`.
    private func withShortLink(_ use: @escaping (String) -> Void, fallback: @escaping () -> Void) {
        Task {
            switch await model.events.shortLink(eventId) {
            case .success(let link):
                fallbackNote = nil
                use(link)
            case .failure(let e):
                fallbackNote = e == .tooLarge
                    ? String(localized: "This plan is too big for a short link, so the full link is used.")
                    : String(localized: "Couldn't make a short link right now, so the full link is used.")
                fallback()
            }
        }
    }

    private func copy(_ inv: PublishedInvite) {
        withShortLink { link in
            UIPasteboard.general.string = link
            model.showToast(String(localized: "Link copied"))
        } fallback: {
            if let link = activeLink(inv) {
                UIPasteboard.general.string = link
                model.showToast(String(localized: "Link copied"))
            } else if inv.stopsOnlyLink != nil {
                confirmStopsOnly = true
            }
        }
    }

    private func share(_ inv: PublishedInvite) {
        withShortLink { link in
            sharing = ShareItems(items: [EventShareMessage.link(inv.doc, link: link)])
        } fallback: {
            shareLongLink(inv)
        }
    }

    /// Without a short link (§5.2.1): the full link, the stops-only link after a confirm, else the open file.
    private func shareLongLink(_ inv: PublishedInvite) {
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
