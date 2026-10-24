import SwiftUI
import UniformTypeIdentifiers

// Acara (events) UI: library + "Import invitation", import confirmation, secret code, event screen.
// Everything here reads the on-device EventStore only; nothing is sent anywhere except the coordinates-only
// route request behind "Navigate".

enum EventFormat {
    /// Date and time in the event's own time zone.
    static func dateTime(_ t: EventTime, _ doc: EventDoc) -> String {
        t.date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: doc.timeZone))
    }

    static func time(_ t: EventTime, _ doc: EventDoc) -> String {
        t.date.formatted(Date.FormatStyle(date: .omitted, time: .shortened, timeZone: doc.timeZone))
    }

    static func range(_ from: EventTime, _ to: EventTime, _ doc: EventDoc) -> String {
        var cal = Calendar.current
        cal.timeZone = doc.timeZone
        let sameDay = cal.isDate(from.date, inSameDayAs: to.date)
        return "\(dateTime(from, doc)) – \(sameDay ? time(to, doc) : dateTime(to, doc))"
    }

    /// Drafts (dates not yet written as RFC 3339).
    static func range(_ from: Date, _ to: Date, _ tz: TimeZone) -> String {
        var cal = Calendar.current
        cal.timeZone = tz
        let full = Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: tz)
        let time = Date.FormatStyle(date: .omitted, time: .shortened, timeZone: tz)
        return "\(from.formatted(full)) – \(cal.isDate(from, inSameDayAs: to) ? to.formatted(time) : to.formatted(full))"
    }

    static func expiry(_ doc: EventDoc) -> String {
        doc.expiresAt.date.formatted(date: .abbreviated, time: .shortened)
    }

    static func distance(_ m: UInt32) -> String {
        m >= 1000 ? String(format: "%.1f km", Double(m) / 1000) : "\(m) m"
    }

    static func icon(_ kind: EventStopKind) -> String {
        switch kind {
        case .parking: "parkingsign.circle.fill"
        case .dropoff: "figure.wave"
        case .entrance: "door.left.hand.open"
        case .venue: "building.2.fill"
        case .meal: "fork.knife"
        case .checkpoint: "flag.fill"
        case .fuel: "fuelpump.fill"
        case .water: "drop.fill"
        case .rest: "cup.and.saucer.fill"
        case .prayer: "moon.stars.fill"
        case .accommodation: "bed.double.fill"
        case .start: "play.circle.fill"
        case .finish: "flag.checkered"
        case .other: "mappin.circle.fill"
        }
    }

    static func icon(_ t: EventTemplate) -> String {
        switch t {
        case .kenduri: "heart.circle.fill"
        case .dinner: "fork.knife.circle.fill"
        case .company: "briefcase.circle.fill"
        case .ride: "bicycle.circle.fill"
        case .convoy: "car.2.fill"
        case .trip: "suitcase.fill"
        case .other: "party.popper.fill"
        }
    }
}

// MARK: - Sheet

struct EventsSheet: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var center = model.events
        NavigationStack(path: $center.path) {
            EventsListView()
                .navigationDestination(for: EventNavRoute.self) { route in
                    switch route {
                    case .confirm: EventImportConfirmView()
                    case .secretCode: EventSecretCodeView()
                    case .detail(let id): EventDetailView(eventId: id)
                    case .host(let id): EventEditorView(eventId: id)
                    case .share(let id): EventShareView(eventId: id)
                    }
                }
        }
        .presentationDetents([.medium, .large], selection: $center.detent)
        .presentationBackgroundInteraction(.enabled(upThrough: .medium))
        .presentationCornerRadius(Tokens.Radius.sheetTop)
        .alert("Can't open invitation", isPresented: Binding(get: { center.errorMessage != nil }, set: { if !$0 { center.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(verbatim: center.errorMessage ?? "")
        }
    }
}

// MARK: - Library

struct EventsListView: View {
    @Environment(AppModel.self) private var model
    @State private var pasted = ""
    @State private var pickingFile = false

    var body: some View {
        let center = model.events
        List {
            Section {
                Button { center.newEvent() } label: {
                    Label("New event", systemImage: "plus.circle.fill").font(Tokens.Font.bodyBold)
                }
                .accessibilityIdentifier("eventNew")
                ForEach(center.hosted) { h in
                    NavigationLink(value: EventNavRoute.host(h.id)) { hostedRow(h) }
                        .accessibilityIdentifier("eventHosted.\(h.id)")
                }
            } header: {
                Text("I'm organizing").font(Tokens.Font.label)
            }

            Section {
                VStack(alignment: .leading, spacing: Tokens.Space.s) {
                    TextField("Paste an invitation link", text: $pasted, axis: .vertical)
                        .lineLimit(1...3)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(Tokens.Font.body)
                        .accessibilityIdentifier("eventImportField")
                    HStack(spacing: Tokens.Space.s) {
                        Button {
                            if let s = UIPasteboard.general.string { pasted = s }
                        } label: { Label("Paste", systemImage: "doc.on.clipboard") }
                            .buttonStyle(SecondaryButtonStyle())
                        Button {
                            center.importPasted(pasted)
                            pasted = ""
                        } label: { Text("Open") }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("eventImportOpen")
                    }
                    Button { pickingFile = true } label: {
                        Label("Choose a .manotu-event file", systemImage: "doc.badge.plus").font(Tokens.Font.label)
                    }
                    .padding(.top, Tokens.Space.xs)
                }
                .padding(.vertical, Tokens.Space.xs)
            } header: {
                Text("Import invitation").font(Tokens.Font.label)
            }

            Section {
                if center.events.isEmpty {
                    ContentUnavailableView("No invitations yet", systemImage: "party.popper",
                                           description: Text("Open an invitation link or file from the organizer."))
                }
                ForEach(center.events) { e in
                    NavigationLink(value: EventNavRoute.detail(e.eventId)) { row(e) }
                        .swipeActions {
                            Button("Delete", role: .destructive) { center.delete(e.eventId) }
                        }
                }
            } header: {
                Text("Invited").font(Tokens.Font.label)
            }

            Section {
                Label("Invitations are kept on this phone only, never in backups, and deleted when the event's access ends.",
                      systemImage: "lock.shield")
                    .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    .listRowBackground(Color.clear)
            }
        }
        .navigationTitle("Events")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { center.isPresented = false } } }
        .onAppear { center.refresh(); model.clearEventFromMap() }
        .fileImporter(isPresented: $pickingFile, allowedContentTypes: [UTType(EventPack.fileUTType) ?? .data]) { result in
            if case .success(let url) = result { center.handle(url: url) }
        }
    }

    private func hostedRow(_ h: HostedEvent) -> some View {
        let d = h.draft
        return HStack(spacing: Tokens.Space.m) {
            Image(systemName: EventFormat.icon(d.template))
                .font(.system(size: 26)).foregroundStyle(Color.brandOn)
                .frame(width: 44, height: 44)
                .background(Color.brand, in: RoundedRectangle(cornerRadius: Tokens.Radius.sm, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: d.title.isEmpty ? String(localized: "Untitled event") : d.title).font(Tokens.Font.bodyBold).lineLimit(2)
                Text(verbatim: EventFormat.range(d.start, d.end, d.timeZone)).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                Group {
                    if let rev = h.latestRev {
                        Text("Published · version \(Int(rev))")
                    } else {
                        Text("Draft · not shared yet")
                    }
                }
                .font(Tokens.Font.small).foregroundStyle(Color.brand)
            }
        }
        .padding(.vertical, 4)
    }

    private func row(_ e: ImportedEvent) -> some View {
        HStack(spacing: Tokens.Space.m) {
            Image(systemName: EventFormat.icon(e.doc.template))
                .font(.system(size: 26)).foregroundStyle(Color.brand)
                .frame(width: 44, height: 44)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.sm, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: e.doc.title).font(Tokens.Font.bodyBold).lineLimit(2)
                Text(verbatim: EventFormat.range(e.doc.start, e.doc.end, e.doc)).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                if e.forkAlternatives > 0 {
                    Label("Two versions: choose one", systemImage: "exclamationmark.triangle.fill")
                        .font(Tokens.Font.small).foregroundStyle(Color.amberInk)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Import confirmation

struct EventImportConfirmView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let center = model.events
        ScrollView {
            if let p = center.pending {
                VStack(alignment: .leading, spacing: Tokens.Space.l) {
                    if p.isOwn {
                        summary(p)
                        banner("This is your event", "You organize this event, so nothing is imported. Open it to edit or share.",
                               tint: .brandMist, icon: "person.crop.circle.badge.checkmark")
                        Button { center.confirmPending() } label: { Text("Open my event") }
                            .buttonStyle(PrimaryButtonStyle())
                            .accessibilityIdentifier("eventOpenOwn")
                    } else if case .newer = p.decision, !p.changes.isEmpty {
                        EventUpdateView(pending: p)
                    } else {
                        summary(p)
                        warning(p)
                        if case .expired = p.decision {} else { safety(p) }
                        actions(p)
                    }
                }
                .padding(Tokens.Space.gutter)
            }
        }
        .background(Color.appBackground)
        .navigationTitle("Invitation")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(center.pending != nil)
        .onDisappear { if center.path.last != .confirm { center.cancelPending() } }
    }

    private func summary(_ p: PendingEventImport) -> some View {
        let e = p.pack.event
        return VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Text(verbatim: e.title).font(Tokens.Font.title)
            if let alias = e.alias {
                Label { Text("Organizer: \(alias)") } icon: { Image(systemName: "person.crop.circle") }
                    .font(Tokens.Font.body)
            }
            Label { Text(verbatim: EventFormat.range(e.start, e.end, e)) } icon: { Image(systemName: "calendar") }
            Label { Text("\(e.stops.count) stops") } icon: { Image(systemName: "mappin.and.ellipse") }
            if !e.routes.isEmpty {
                Label { Text("\(e.routes.count) routes") } icon: { Image(systemName: "point.topleft.down.to.point.bottomright.curvepath") }
            }
            Label { Text("Access ends \(EventFormat.expiry(e))") } icon: { Image(systemName: "hourglass") }
                .foregroundStyle(Color.textMuted)
        }
        .font(Tokens.Font.caption)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Tokens.Space.l)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
    }

    private func safety(_ p: PendingEventImport) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.xs) {
            Text("Safety code").font(Tokens.Font.label).foregroundStyle(Color.textMuted)
            Text(verbatim: p.pack.safetyCode)
                .font(.system(.title2, design: .monospaced).weight(.bold))
                .foregroundStyle(Color.brandInk)
                .textSelection(.enabled)
                .accessibilityIdentifier("eventSafetyCode")
            Text("Check this code with the organizer (for example by phone) before you trust the plan.")
                .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Tokens.Space.l)
        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
    }

    @ViewBuilder
    private func warning(_ p: PendingEventImport) -> some View {
        switch p.decision {
        case .expired:
            banner("Event has ended", "The event has ended. Copies outside Manotu may still exist.", tint: .coralMist, icon: "hourglass.bottomhalf.filled")
        case .alreadyImported:
            banner("Already imported", "This invitation is already on this phone. Nothing changed.", tint: .tealMist, icon: "checkmark.circle.fill")
        case .differentOrganizer:
            banner("Different organizer",
                   "This update is signed by a different organizer than the one you trusted. It was not imported.",
                   tint: .coralMist, icon: "xmark.shield.fill")
        case .older(let rev):
            banner("Older version", "You already have version \(Int(rev)). The newer version is kept.", tint: .amberMist, icon: "clock.arrow.circlepath")
        case .fork(let rev):
            banner("Conflicting version",
                   "There is already a different version \(Int(rev)) of this event. Both will be kept and you can choose one.",
                   tint: .amberMist, icon: "exclamationmark.triangle.fill")
        case .newer(_, .mismatch):
            banner("Update to version \(Int(p.pack.event.rev))",
                   "This version doesn't follow the one on this phone. Check with the organizer.", tint: .amberMist, icon: "exclamationmark.triangle.fill")
        case .newer(_, .missedRevisions):
            banner("Update to version \(Int(p.pack.event.rev))", "Some updates in between were missed.", tint: .amberMist, icon: "info.circle.fill")
        case .newer(_, .matches):
            banner("Update to version \(Int(p.pack.event.rev))", "It replaces the version on this phone.", tint: .tealMist, icon: "arrow.triangle.2.circlepath")
        case .new:
            EmptyView()
        }
    }

    private func banner(_ title: LocalizedStringKey, _ text: LocalizedStringKey, tint: Color, icon: String) -> some View {
        HStack(alignment: .top, spacing: Tokens.Space.m) {
            Image(systemName: icon).font(.system(size: 22))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Tokens.Font.bodyBold)
                Text(text).font(Tokens.Font.caption)
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(Color.textPrimary)
        .padding(Tokens.Space.l)
        .background(tint, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("eventDecision.\(model.events.pending?.decision.code ?? "")")
    }

    @ViewBuilder
    private func actions(_ p: PendingEventImport) -> some View {
        let center = model.events
        VStack(spacing: Tokens.Space.s) {
            if p.decision.canImport {
                Button {
                    if center.confirmPending() != nil { model.showToast(String(localized: "Event imported")) }
                } label: { Text("Import") }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("eventImportConfirm")
                Button { center.cancelPending() } label: { Text("Cancel") }.buttonStyle(SecondaryButtonStyle())
            } else if p.stored != nil, p.decision == .alreadyImported || p.decision.code == "older" {
                Button { center.confirmPending() } label: { Text("Open event") }.buttonStyle(PrimaryButtonStyle())
            } else {
                Button { center.cancelPending() } label: { Text("Close") }.buttonStyle(PrimaryButtonStyle())
            }
        }
    }
}

// MARK: - Secret code (.manotu-event files)

struct EventSecretCodeView: View {
    @Environment(AppModel.self) private var model
    @State private var code = ""
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                TextField(text: $code, prompt: Text(verbatim: "XXXX-XXXX-XXXX-…"), axis: .vertical) { Text("Secret code") }
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .lineLimit(2...4)
                    .accessibilityIdentifier("eventSecretCode")
                if let error {
                    Text(verbatim: error).font(Tokens.Font.caption).foregroundStyle(Color.speeding)
                }
            } header: {
                Text("Secret code")
            } footer: {
                Text("Enter the secret code the organizer sent you separately.")
            }
            Section {
                Button { error = model.events.openPendingFile(secretCode: code) } label: { Text("Open invitation") }
                    .buttonStyle(PrimaryButtonStyle())
                    .listRowInsets(EdgeInsets())
                    .disabled(code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("eventSecretCodeOpen")
            }
            .listRowBackground(Color.clear)
        }
        .navigationTitle("Secret code")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Event screen

struct EventDetailView: View {
    @Environment(AppModel.self) private var model
    let eventId: String
    @State private var confirmDelete = false

    var body: some View {
        let center = model.events
        Group {
            if let e = center.event(eventId) {
                content(e)
            } else {
                ContentUnavailableView {
                    Label("Event has ended", systemImage: "hourglass.bottomhalf.filled")
                } description: {
                    Text("The event has ended. Copies outside Manotu may still exist.")
                }
            }
        }
        .background(Color.appBackground)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func content(_ e: ImportedEvent) -> some View {
        let doc = e.doc
        let next = EventCenter.nextStop(doc, now: .now)
        let focus = model.events.focusStopId.flatMap { doc.stop($0) }
        return ScrollViewReader { proxy in
            List {
                Section { header(e) }
                if e.forkAlternatives > 0 { forkSection(e) }
                if let stop = focus ?? next {
                    Section {
                        stopRow(stop, doc: doc, prominent: true)
                    } header: { Text(focus != nil ? "Selected stop" : "Next").font(Tokens.Font.label) }
                }
                Section {
                    ForEach(doc.stops) { s in
                        stopRow(s, doc: doc, prominent: false)
                            .id(s.id)
                            .listRowBackground(s.id == focus?.id ? Color.brandMist : Color.surface)
                    }
                } header: { Text("Stops").font(Tokens.Font.label) } footer: {
                    Text("Navigation sends only the stop's coordinates, never its name.")
                }
                if !doc.sessions.isEmpty {
                    Section {
                        ForEach(doc.sessions) { s in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: s.title).font(Tokens.Font.bodyBold)
                                Text(verbatim: EventFormat.range(s.start, s.end, doc)
                                     + (s.stopId.flatMap { doc.stop($0)?.label }.map { " · \($0)" } ?? ""))
                                    .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                            }
                        }
                    } header: { Text("Agenda").font(Tokens.Font.label) }
                }
                if !doc.routes.isEmpty { routesSection(doc) }
                Section {
                    Button(role: .destructive) { confirmDelete = true } label: { Label("Delete event", systemImage: "trash") }
                        .accessibilityIdentifier("eventDelete")
                } footer: {
                    Text("Access ends \(EventFormat.expiry(doc)). The event is then deleted from this phone.")
                }
            }
            .onAppear {
                model.showEventOnMap(e)
                if let f = focus { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { proxy.scrollTo(f.id, anchor: .center) } }
            }
        }
        .navigationTitle(Text(verbatim: doc.title))
        .confirmationDialog("Delete this event from this phone?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                model.clearEventFromMap()
                model.events.delete(e.eventId)
                model.showToast(String(localized: "Event deleted"))
            }
        }
    }

    private func header(_ e: ImportedEvent) -> some View {
        let doc = e.doc
        return VStack(alignment: .leading, spacing: Tokens.Space.s) {
            HStack(spacing: Tokens.Space.m) {
                Image(systemName: EventFormat.icon(doc.template)).font(.system(size: 30)).foregroundStyle(Color.brand)
                Text(verbatim: doc.title).font(Tokens.Font.title)
            }
            if let alias = doc.alias { Text("Organizer: \(alias)").font(Tokens.Font.body) }
            Text(verbatim: EventFormat.range(doc.start, doc.end, doc)).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            if let d = doc.description, !d.isEmpty { Text(verbatim: d).font(Tokens.Font.body) }
            HStack {
                Text("Safety code").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                Text(verbatim: e.safetyCode).font(.system(.footnote, design: .monospaced).weight(.semibold))
            }
            if let w = e.parentWarning {
                Label(w == .mismatch ? "This version doesn't follow the one on this phone. Check with the organizer."
                                     : "Some updates in between were missed.", systemImage: "exclamationmark.triangle.fill")
                    .font(Tokens.Font.small).foregroundStyle(Color.amberInk)
            }
            if !doc.routes.isEmpty {
                Button { model.events.detent = .medium; model.showEventOnMap(e) } label: {
                    Label("Show on map", systemImage: "map")
                }
                .font(Tokens.Font.label)
            }
        }
        .padding(.vertical, 4)
    }

    private func forkSection(_ e: ImportedEvent) -> some View {
        Section {
            Text("Two versions of this event have the same number. Choose the one the organizer confirms.")
                .font(Tokens.Font.caption)
            ForEach([e] + model.events.forkAlternatives(e.eventId), id: \.hash) { v in
                Button { model.events.chooseFork(v) } label: {
                    VStack(alignment: .leading) {
                        Text(verbatim: v.doc.title).font(Tokens.Font.bodyBold)
                        Text(verbatim: "\(EventFormat.range(v.doc.start, v.doc.end, v.doc)) · \(v.doc.stops.count) · \(B64u.encode(Array(v.hash.prefix(4))))")
                            .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                        Text("Choose this version").font(Tokens.Font.label).foregroundStyle(Color.brand)
                    }
                }
            }
        } header: { Text("Conflicting version").font(Tokens.Font.label) }
    }

    private func stopRow(_ s: EventStop, doc: EventDoc, prominent: Bool) -> some View {
        HStack(alignment: .top, spacing: Tokens.Space.m) {
            Image(systemName: EventFormat.icon(s.kind)).font(.system(size: 22)).foregroundStyle(Color.brand).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: s.label).font(prominent ? Tokens.Font.bodyBold : Tokens.Font.body)
                if let a = s.arrive {
                    Text("Arrive \(EventFormat.range(a.from, a.to, doc))").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                }
                if let n = s.notes, !n.isEmpty { Text(verbatim: n).font(Tokens.Font.caption) }
                if let a = s.audience, !a.isEmpty { Text(verbatim: a).font(Tokens.Font.small).foregroundStyle(Color.textMuted) }
                if s.entrance != nil {
                    Label("Navigates to the vehicle entrance", systemImage: "car.side.arrowtriangle.down")
                        .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                }
            }
            Spacer(minLength: 0)
            Button { model.navigate(toEventStop: s, in: doc) } label: {
                Text("Navigate").font(Tokens.Font.label)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .foregroundStyle(prominent ? Color.brandOn : Color.brand)
                    .background(prominent ? Color.brand : Color.brandMist, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("eventNavigate.\(s.id)")
        }
        .padding(.vertical, 4)
    }

    private func routesSection(_ doc: EventDoc) -> some View {
        Section {
            ForEach(doc.routes) { r in
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: r.title ?? doc.title).font(Tokens.Font.bodyBold)
                    let extra = [r.day, r.ascentM.map { "↑ \($0) m" }, r.descentM.map { "↓ \($0) m" }].compactMap { $0 }
                    Text(verbatim: ([EventFormat.distance(r.distanceM)] + extra).joined(separator: " · "))
                        .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    if r.source != .routed {
                        Text("Sketch: not for turn-by-turn navigation").font(Tokens.Font.small).foregroundStyle(Color.amberInk)
                    }
                }
            }
        } header: { Text("Routes").font(Tokens.Font.label) }
    }
}
