import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Organizer screens: "Acara baharu" / edit (board 1), "Kongsi jemputan" (board 2), and the guest's
// "Pelan berubah" update (board 5). Drafts, keys, links and files stay on this phone; the only server calls are
// the app's existing place search and the address shown while choosing a point on the map (no event data).

extension EventTemplate {
    var title: LocalizedStringKey {
        switch self {
        case .kenduri: "Kenduri / wedding"
        case .dinner: "Dinner"
        case .company: "Company event"
        case .ride: "Ride"
        case .convoy: "Convoy"
        case .trip: "Trip"
        case .other: "Other"
        }
    }
}

extension EventStopKind {
    var title: LocalizedStringKey {
        switch self {
        case .parking: "Parking"
        case .dropoff: "Drop-off"
        case .entrance: "Entrance"
        case .venue: "Venue"
        case .meal: "Meal"
        case .checkpoint: "Checkpoint"
        case .fuel: "Fuel"
        case .water: "Water"
        case .rest: "Rest"
        case .prayer: "Prayer"
        case .accommodation: "Accommodation"
        case .start: "Start"
        case .finish: "Finish"
        case .other: "Other"
        }
    }

    /// A first guess from the search result's category.
    static func guess(_ c: PlaceCategory?) -> EventStopKind {
        switch c {
        case .parking: .parking
        case .mosque, .surau: .prayer
        case .restaurant, .cafe, .fastFood: .meal
        case .fuel: .fuel
        case .restArea, .toilets: .rest
        case .hotel: .accommodation
        default: .venue
        }
    }
}

// MARK: - Editor (board 1)

struct EventEditorView: View {
    @Environment(AppModel.self) private var model
    let eventId: String

    @State private var draft: HostedEventDraft?
    @State private var multiDay = false
    @State private var editingExpiry = false
    @State private var addingPlace = false
    @State private var editingStop: HostedStopDraft?
    @State private var problems: [EventDraftIssue] = []
    @State private var publishError: String?
    @State private var confirmDelete = false
    @State private var buildingRoute: RouteDraft?
    /// Bumped when "Share invitation" is blocked: the form scrolls to the first problem (`EventDraftIssue.anchor`).
    @State private var blockedTap = 0

    var body: some View {
        Group {
            if draft != nil {
                form
            } else {
                ContentUnavailableView("Event not found", systemImage: "questionmark.circle")
            }
        }
        .background(Color.appBackground)
        .navigationTitle(draft.map { $0.title.isEmpty ? String(localized: "New event") : $0.title } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard draft == nil, let h = model.events.hostedEvent(eventId) else { return }
            draft = h.draft
            var cal = Calendar.current
            cal.timeZone = h.draft.timeZone
            multiDay = !cal.isDate(h.draft.start, inSameDayAs: h.draft.end)
        }
        .onChange(of: draft) { _, d in if let d { model.events.saveDraft(d) } }
        .sheet(isPresented: $addingPlace) {
            EventPlacePicker { label, location, kind in
                let stop = HostedStopDraft(kind: kind, label: String(label.prefix(EventLimits.stopLabelChars)), location: location)
                draft?.stops.append(stop)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { editingStop = stop }
            }
        }
        .sheet(item: $editingStop) { s in
            EventStopEditor(stop: s) { updated in
                if let i = draft?.stops.firstIndex(where: { $0.id == updated.id }) { draft?.stops[i] = updated }
                draft?.syncRoutesWithStops()
            } onDelete: {
                draft?.stops.removeAll { $0.id == s.id }
                draft?.syncRoutesWithStops()
            }
        }
        .fullScreenCover(item: $buildingRoute) { r in
            let d = draft ?? .new()
            RouteBuilderView(route: r, stops: d.stops, context: .event(title: d.title.isEmpty ? String(localized: "New event") : d.title),
                             days: d.days, transport: model.routeBuilderTransport, isDemo: model.routeBuilderIsDemo, start: d.stops.first?.location ?? model.position) { saved, stops in
                guard var nd = draft else { return }
                nd.stops = stops
                if let i = nd.routes.firstIndex(where: { $0.id == saved.id }) { nd.routes[i] = saved } else { nd.routes.append(saved) }
                nd.syncRoutesWithStops()
                draft = nd
            }
        }
        .alert("Can't share yet", isPresented: Binding(get: { publishError != nil }, set: { if !$0 { publishError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(verbatim: publishError ?? "")
        }
        .confirmationDialog("Delete this event from this phone?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { model.events.deleteHosted(eventId) }
        } message: {
            Text("Invitations you already sent keep working until their access ends.")
        }
    }

    private var binding: Binding<HostedEventDraft> {
        Binding(get: { draft ?? .new() }, set: { draft = $0 })
    }

    private var form: some View {
        let d = binding
        let hosted = model.events.hostedEvent(eventId)
        return ScrollViewReader { proxy in Form {
            Section {
                TextField("Event name", text: d.title)
                    .font(Tokens.Font.bodyBold)
                    .accessibilityIdentifier("eventTitleField")
                    .id(EventDraftIssue.Anchor.title)
                Picker("Type", selection: d.template) {
                    ForEach(EventTemplate.allCases, id: \.self) { t in
                        Label { Text(t.title) } icon: { Image(systemName: EventFormat.icon(t)) }.tag(t)
                    }
                }
                TextField("Organizer name (optional)", text: d.alias)
                    .accessibilityIdentifier("eventAliasField")
                    .id(EventDraftIssue.Anchor.alias)
                TextField("Description (optional)", text: d.description, axis: .vertical)
                    .lineLimit(1...5)
                    .id(EventDraftIssue.Anchor.description)
            } header: {
                HStack {
                    Text("New event").font(Tokens.Font.label)
                    Spacer()
                    Label("Private", systemImage: "lock.fill").font(Tokens.Font.small).foregroundStyle(Color.brand)
                }
            } footer: {
                Text("The organizer name is shown to guests instead of yours. It isn't linked to your account.")
            }

            Section {
                DatePicker("Date", selection: shifting(d), displayedComponents: .date)
                    .id(EventDraftIssue.Anchor.time)
                DatePicker("Starts", selection: shifting(d), displayedComponents: .hourAndMinute)
                    .accessibilityIdentifier("eventStartTime")
                Toggle("Ends on another day", isOn: $multiDay)
                DatePicker("Ends", selection: d.end, in: d.wrappedValue.start..., displayedComponents: multiDay ? [.date, .hourAndMinute] : .hourAndMinute)
                    .accessibilityIdentifier("eventEndTime")
            } header: { Text("When").font(Tokens.Font.label) }
            .environment(\.timeZone, d.wrappedValue.timeZone)

            if d.wrappedValue.routesFirst { EventRoutesSection(draft: d, problems: problems, building: $buildingRoute) }

            Section {
                ForEach(d.wrappedValue.stops) { s in
                    Button { editingStop = s } label: { stopRow(s) }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("eventStop.\(s.id)")
                        .id(EventDraftIssue.Anchor.stop(s.id))
                        .contextMenu {
                            Button("Move up", systemImage: "arrow.up") { move(s.id, by: -1) }
                            Button("Move down", systemImage: "arrow.down") { move(s.id, by: 1) }
                            Button("Remove", systemImage: "trash", role: .destructive) {
                                draft?.stops.removeAll { $0.id == s.id }
                                draft?.syncRoutesWithStops()
                            }
                        }
                }
                .onDelete { draft?.stops.remove(atOffsets: $0); draft?.syncRoutesWithStops() }
                Button { addingPlace = true } label: {
                    Label("Add a place (map, search or saved places)", systemImage: "plus")
                        .font(Tokens.Font.label)
                }
                .accessibilityIdentifier("eventAddPlace")
                .id(EventDraftIssue.Anchor.addPlace)
                if problems.contains(.noStops) {
                    Label { Text(verbatim: EventDraftIssue.noStops.message) } icon: { Image(systemName: "exclamationmark.circle.fill") }
                        .font(Tokens.Font.caption).foregroundStyle(Color.speeding)
                }
            } header: {
                Text("Places in the event").font(Tokens.Font.label)
            } footer: {
                if d.wrappedValue.stops.count > 1 { Text("Touch and hold a place to change its order.") }
            }

            if !d.wrappedValue.routesFirst { EventRoutesSection(draft: d, problems: problems, building: $buildingRoute) }

            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Access ends").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                    Text(verbatim: d.wrappedValue.expiresAt.formatted(Date.FormatStyle(date: .complete, time: .shortened,
                                                                                      timeZone: d.wrappedValue.timeZone)))
                        .font(Tokens.Font.bodyBold)
                        .accessibilityIdentifier("eventExpiry")
                        .id(EventDraftIssue.Anchor.expiry)
                    if d.wrappedValue.expiresAtOverride == nil {
                        Text("(24 hours after the event). You can change it.").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                    }
                }
                if editingExpiry {
                    DatePicker("Access ends", selection: Binding(get: { d.wrappedValue.expiresAt },
                                                                 set: { draft?.expiresAtOverride = $0 }),
                               in: d.wrappedValue.end...)
                        .environment(\.timeZone, d.wrappedValue.timeZone)
                    if d.wrappedValue.expiresAtOverride != nil {
                        Button("Reset to 24 hours after the event") { draft?.expiresAtOverride = nil }
                    }
                } else {
                    Button("Change") { editingExpiry = true }
                }
                Picker("Invitation language", selection: d.lang) {
                    Text(verbatim: "Bahasa Melayu").tag(EventLang.ms)
                    Text(verbatim: "English").tag(EventLang.en)
                }
            } footer: {
                Text("After access ends, Manotu deletes the event from phones. Copies outside Manotu may still exist.")
            }

            if !problems.isEmpty {
                Section {
                    ForEach(Array(problems.enumerated()), id: \.offset) { _, p in
                        Label { Text(verbatim: p.message) } icon: { Image(systemName: "exclamationmark.circle.fill") }
                            .font(Tokens.Font.caption).foregroundStyle(Color.speeding)
                    }
                }
                .id(EventDraftIssue.Anchor.problems)
            }

            Section {
                Button { share() } label: {
                    if model.events.publishing == eventId {
                        HStack(spacing: Tokens.Space.s) {
                            ProgressView()
                            Text("Preparing invitation…")
                        }
                    } else {
                        Text("Share invitation")
                    }
                }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.events.publishing != nil)
                    .listRowInsets(EdgeInsets())
                    .accessibilityIdentifier("eventShareInvitation")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    if let rev = hosted?.latestRev {
                        if let h = hosted, model.events.hasUnpublishedChanges(h) {
                            Text("Version \(Int(rev)) was shared. Share again to send your changes as version \(Int(rev) + 1).")
                        } else {
                            Text("Version \(Int(rev)) was shared.")
                        }
                    }
                    Text("Notes for the organizer stay on this phone. Manotu's servers never receive this event.")
                }
            }
            .listRowBackground(Color.clear)

            Section {
                Button(role: .destructive) { confirmDelete = true } label: { Label("Delete event", systemImage: "trash") }
            }
        }
        // "Share invitation" with problems is never a silent tap: go to the first one (its row is marked) and buzz.
        .onChange(of: blockedTap) {
            guard let first = problems.first else { return }
            withAnimation { proxy.scrollTo(first.anchor, anchor: .center) }
        }
        .sensoryFeedback(.error, trigger: blockedTap)
        }
    }

    /// Changing the date or start time moves the end by the same amount (the event keeps its length).
    private func shifting(_ d: Binding<HostedEventDraft>) -> Binding<Date> {
        Binding(get: { d.wrappedValue.start }, set: { new in
            let delta = new.timeIntervalSince(d.wrappedValue.start)
            draft?.start = new
            draft?.end = d.wrappedValue.end.addingTimeInterval(delta)
        })
    }

    private func move(_ id: String, by offset: Int) {
        guard var stops = draft?.stops, let i = stops.firstIndex(where: { $0.id == id }) else { return }
        let j = i + offset
        guard stops.indices.contains(j) else { return }
        stops.swapAt(i, j)
        draft?.stops = stops
    }

    private func stopRow(_ s: HostedStopDraft) -> some View {
        HStack(alignment: .top, spacing: Tokens.Space.m) {
            Image(systemName: EventFormat.icon(s.kind)).font(.system(size: 20)).foregroundStyle(Color.brand)
                .frame(width: 36, height: 36)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.sm, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: s.label.isEmpty ? String(localized: "Unnamed place") : s.label).font(Tokens.Font.bodyBold)
                if !s.notes.isEmpty { Text(verbatim: s.notes).font(Tokens.Font.caption).foregroundStyle(Color.textMuted).lineLimit(2) }
                HStack(spacing: 6) {
                    Text(s.kind.title)
                    if !s.audience.isEmpty { Text(verbatim: "· \(s.audience)") }
                    if s.entrance != nil { Image(systemName: "car.side.arrowtriangle.down") }
                }
                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                if problems.contains(where: { $0.stopId == s.id }) {
                    Text("Check this place").font(Tokens.Font.small).foregroundStyle(Color.speeding)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.textMuted)
        }
        .contentShape(Rectangle())
    }

    private func share() {
        guard let d = draft else { return }
        model.events.saveDraft(d)
        let (_, issues) = d.event(rev: 1, parent: nil, publishedAt: Date())
        problems = issues
        guard issues.isEmpty else { blockedTap += 1; return }
        Task { publishError = await model.events.publish(eventId) }
    }
}

// MARK: - Stop editor

struct EventStopEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var stop: HostedStopDraft
    let onSave: (HostedStopDraft) -> Void
    let onDelete: () -> Void

    private enum Pick: Identifiable { case pin, entrance; var id: Self { self } }
    @State private var picking: Pick?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name guests see", text: $stop.label)
                        .accessibilityIdentifier("eventStopLabel")
                    Picker("Kind", selection: Binding(get: { stop.kind }, set: { stop.kind = $0 })) {
                        ForEach(EventStopKind.allCases, id: \.self) { k in
                            Label { Text(k.title) } icon: { Image(systemName: EventFormat.icon(k)) }.tag(k)
                        }
                    }
                    TextField("For whom (e.g. Semua, Keluarga)", text: $stop.audience)
                } footer: {
                    if EventValidation.scalars(stop.label) > EventLimits.stopLabelChars { Text("80 characters at most.") }
                }

                Section {
                    TextField("Notes for guests", text: $stop.notes, axis: .vertical).lineLimit(2...6)
                } header: { Text("Guests see") } footer: {
                    Text("For example: “Enter through the back gate of the school.”")
                }

                Section {
                    TextField("Only you see this", text: $stop.organizerNote, axis: .vertical).lineLimit(1...4)
                } header: { Text("Organizer note") } footer: {
                    Text("Stays on this phone. It is never put in the invitation.")
                }

                Section {
                    Button { picking = .pin } label: {
                        Label { VStack(alignment: .leading) {
                            Text("Location")
                            Text(verbatim: String(format: "%.5f, %.5f", stop.lat, stop.lng)).font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                        } } icon: { Image(systemName: "mappin.and.ellipse") }
                    }
                    if let e = stop.entrance {
                        Button { picking = .entrance } label: {
                            Label { VStack(alignment: .leading) {
                                Text("Vehicle entrance")
                                Text(verbatim: String(format: "%.5f, %.5f", e.lat, e.lng)).font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                            } } icon: { Image(systemName: "car.side.arrowtriangle.down") }
                        }
                        Button("Remove vehicle entrance", role: .destructive) { stop.entrance = nil }
                    } else {
                        Button { picking = .entrance } label: {
                            Label("Add a vehicle entrance (optional)", systemImage: "car.side.arrowtriangle.down")
                        }
                    }
                } footer: {
                    Text("Guests navigate to the vehicle entrance when there is one, else to the pin. Only the coordinates are sent for routing.")
                }

                Section {
                    Button("Remove this place", role: .destructive) { onDelete(); dismiss() }
                }
            }
            .navigationTitle(stop.label.isEmpty ? String(localized: "Place") : stop.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { onSave(stop); dismiss() }
                        .accessibilityIdentifier("eventStopDone")
                }
            }
            .fullScreenCover(item: $picking) { p in
                SelectOnMapView(request: PinPickRequest(purpose: .destination, start: p == .entrance ? (stop.entrance ?? stop.location) : stop.location)) { picked in
                    let loc = LatLng(lat: HostedEventDraft.round6(picked.location.lat), lng: HostedEventDraft.round6(picked.location.lng))
                    if p == .pin { stop.location = loc } else { stop.entrance = loc }
                }
            }
        }
    }
}

// MARK: - Add a place: map, search, saved places

struct EventPlacePicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// label, location, kind guess
    let onPick: (String, LatLng, EventStopKind) -> Void

    @State private var query = ""
    @State private var results: [PlaceSummary] = []
    @State private var searching = false
    @State private var choosingOnMap = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { choosingOnMap = true } label: { Label("Choose on map", systemImage: "map") }
                        .accessibilityIdentifier("eventPickOnMap")
                }
                if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Section {
                        if searching && results.isEmpty { ProgressView() }
                        ForEach(results) { r in
                            Button { pick(r.name, r.location, .guess(r.category)) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: r.name).font(Tokens.Font.bodyBold)
                                    if !r.address.isEmpty { Text(verbatim: r.address).font(Tokens.Font.small).foregroundStyle(Color.textMuted) }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    } header: { Text("Search results") }
                }
                let saved = model.savedPlaces.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
                if !saved.isEmpty {
                    Section {
                        ForEach(saved) { p in
                            Button { pick(p.name, p.location, .venue) } label: {
                                Label { VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: p.name).font(Tokens.Font.bodyBold)
                                    if !p.address.isEmpty { Text(verbatim: p.address).font(Tokens.Font.small).foregroundStyle(Color.textMuted) }
                                } } icon: { Image(systemName: p.kind == .home ? "house.fill" : p.kind == .work ? "briefcase.fill" : "star.fill") }
                            }
                            .buttonStyle(.plain)
                        }
                    } header: { Text("Saved places") } footer: {
                        Text("Guests will see the name you give the place in the event, not your saved label.")
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search for a place"))
            .task(id: query) { await search() }
            .navigationTitle("Add a place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .fullScreenCover(isPresented: $choosingOnMap) {
                SelectOnMapView(request: PinPickRequest(purpose: .destination, start: model.position)) { p in
                    pick(p.placeName ?? "", p.location, .venue)
                }
            }
        }
    }

    private func pick(_ name: String, _ loc: LatLng, _ kind: EventStopKind) {
        onPick(name, LatLng(lat: HostedEventDraft.round6(loc.lat), lng: HostedEventDraft.round6(loc.lng)), kind)
        dismiss()
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { results = []; return }
        try? await Task.sleep(for: .milliseconds(300))
        guard !Task.isCancelled else { return }
        searching = true
        defer { searching = false }
        let r = (try? await model.repo.search(query: q, near: model.position, category: nil, limit: 20)) ?? []
        guard !Task.isCancelled else { return }
        results = r
    }
}

// MARK: - Share (board 2)

struct EventShareView: View {
    @Environment(AppModel.self) private var model
    let eventId: String
    @State private var showCode = false
    @State private var fileShare: ShareItems?
    @State private var confirmStopsOnly = false
    @State private var showStopsOnly = false

    struct ShareItems: Identifiable {
        let id = UUID()
        var items: [Any]
    }

    var body: some View {
        let center = model.events
        ScrollView {
            if let inv = center.invites[eventId] {
                VStack(alignment: .leading, spacing: Tokens.Space.l) {
                    header(inv)
                    linkCard(inv)
                    fileCard(inv)
                    notes
                }
                .padding(Tokens.Space.gutter)
            } else {
                ContentUnavailableView("Share the event again from its page", systemImage: "square.and.arrow.up")
            }
        }
        .background(Color.appBackground)
        .navigationTitle("Share invitation")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { center.path = [] }.accessibilityIdentifier("eventShareDone")
            }
        }
        .sheet(item: $fileShare) { s in ActivityView(items: s.items) }
    }

    private func header(_ inv: PublishedInvite) -> some View {
        let doc = inv.doc
        return VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: doc.title).font(Tokens.Font.title)
            Text("Places: \(doc.stops.count) · access ends \(EventFormat.expiry(doc))").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            Group {
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
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Tokens.Space.l)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous).stroke(Color.outline, lineWidth: 1))
    }

    private func linkCard(_ inv: PublishedInvite) -> some View {
        card {
            HStack {
                Label("Invitation link", systemImage: "link").font(Tokens.Font.bodyBold)
                Spacer()
                Text("Recommended").font(Tokens.Font.small).foregroundStyle(Color.brandInk)
                    .padding(.horizontal, 8).padding(.vertical, 3).background(Color.brandMist, in: Capsule())
            }
            Text("Opens on any phone, with or without Manotu. Good for e-cards and WhatsApp.")
                .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            if let link = inv.link {
                HStack(spacing: Tokens.Space.s) {
                    Button {
                        UIPasteboard.general.string = link
                        model.showToast(String(localized: "Link copied"))
                    } label: { Label("Copy link", systemImage: "doc.on.doc") }
                        .buttonStyle(SecondaryButtonStyle())
                        .accessibilityIdentifier("eventCopyLink")
                    ShareLink(item: EventShareMessage.link(inv.doc, link: link)) { Label("Share…", systemImage: "square.and.arrow.up") }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("eventShareLink")
                }
            } else {
                Label("This plan is too long for a link. Share the file instead.", systemImage: "exclamationmark.triangle.fill")
                    .font(Tokens.Font.caption).foregroundStyle(Color.amberInk)
                if let stopsOnly = inv.stopsOnlyLink {
                    // §5.5: offered only when the full link is too long and this one fits; after a confirm.
                    if showStopsOnly {
                        Text("Link without routes: guests see the places, times and route distances, but no lines on the map.")
                            .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                        HStack(spacing: Tokens.Space.s) {
                            Button {
                                UIPasteboard.general.string = stopsOnly
                                model.showToast(String(localized: "Link copied"))
                            } label: { Label("Copy link", systemImage: "doc.on.doc") }
                                .buttonStyle(SecondaryButtonStyle())
                                .accessibilityIdentifier("eventCopyStopsOnlyLink")
                            ShareLink(item: EventShareMessage.link(inv.doc, link: stopsOnly)) { Label("Share…", systemImage: "square.and.arrow.up") }
                                .buttonStyle(PrimaryButtonStyle())
                                .accessibilityIdentifier("eventShareStopsOnlyLink")
                        }
                    } else {
                        Button { confirmStopsOnly = true } label: { Label("Link without routes", systemImage: "link.badge.plus") }
                            .buttonStyle(SecondaryButtonStyle())
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

    private func fileCard(_ inv: PublishedInvite) -> some View {
        card {
            Label("Event file (.manotu-event)", systemImage: "lock.doc.fill").font(Tokens.Font.bodyBold)
            Text("For large or sensitive events: guests need Manotu and the secret code you send separately.")
                .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            Button {
                if let url = model.events.inviteFileURL(eventId) {
                    fileShare = ShareItems(items: [EventShareMessage.file(inv.doc), url])
                }
            } label: { Label("Share file…", systemImage: "square.and.arrow.up") }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityIdentifier("eventShareFile")
            Divider()
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
    }

    private var notes: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            Text("What you need to know").font(Tokens.Font.bodyBold)
            ForEach(Array(Self.bullets.enumerated()), id: \.offset) { _, b in
                HStack(alignment: .top, spacing: 8) {
                    Text(verbatim: "•")
                    Text(b)
                }
                .font(Tokens.Font.caption)
            }
        }
        .padding(Tokens.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
    }

    static let bullets: [LocalizedStringKey] = [
        "Manotu's servers don't store this event. The plan is inside the link itself.",
        "Anyone with the link can see the plan. Share it in groups you trust.",
        "Copies already sent can't be taken back after the event ends.",
    ]
}

/// System share sheet with several items (the file plus a message).
struct ActivityView: UIViewControllerRepresentable {
    var items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Guest: the plan changed (board 5)

struct EventUpdateView: View {
    @Environment(AppModel.self) private var model
    let pending: PendingEventImport

    var body: some View {
        let e = pending.pack.event
        let trip = model.currentEventTrip
        let affected = model.events.pendingAffectsTrip(pending, trip: trip)
        VStack(alignment: .leading, spacing: Tokens.Space.l) {
            VStack(alignment: .leading, spacing: 4) {
                Text("The plan has changed").font(Tokens.Font.title)
                Text(verbatim: e.title).font(Tokens.Font.bodyBold)
                Text("Version \(Int(e.rev)) from the organizer · \(e.publishedAt.date.formatted(date: .abbreviated, time: .shortened)) · verified")
                    .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                if case .newer(_, let parent) = pending.decision, parent != .matches {
                    Label(parent == .mismatch ? "This version doesn't follow the one on this phone. Check with the organizer."
                                              : "Some updates in between were missed.", systemImage: "exclamationmark.triangle.fill")
                        .font(Tokens.Font.small).foregroundStyle(Color.amberInk)
                }
            }
            .accessibilityIdentifier("eventDecision.newer")
            ForEach(pending.changes) { c in changeCard(c) }
            if let a = affected, let t = trip {
                let label = model.events.event(t.eventId)?.doc.stop(t.stopId)?.label ?? ""
                HStack(alignment: .top, spacing: Tokens.Space.m) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 20))
                    Text(a.kind == .stopRemoved
                         ? "You're on your way to “\(label)”, which is no longer in the plan. Your destination won't change unless you agree."
                         : "You're on your way to the old location of “\(label)”. Your destination won't change unless you agree.")
                        .font(Tokens.Font.caption)
                }
                .padding(Tokens.Space.l)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.amberMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
                .accessibilityIdentifier("eventTripWarning")
            }
            HStack(spacing: Tokens.Space.s) {
                Button { model.events.cancelPending() } label: { Text("Keep") }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("eventUpdateKeep")
                Button { model.acceptEventUpdate() } label: { Text("Use new plan") }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("eventUpdateAccept")
            }
            Text("“Keep” leaves the plan on this phone as it is. You can open the new invitation again later.")
                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
        }
    }

    private func title(_ c: EventChange) -> Text {
        let name = (c.after ?? c.before)?.first ?? ""
        switch c.kind {
        case .time: return Text("Time changed")
        case .title: return Text("Event name changed")
        case .stopMoved: return Text("“\(name)” moved \(ManeuverFormatter.distance(c.movedM ?? 0))")
        case .stopRenamed: return Text("Place renamed")
        case .stopAdded: return Text("New place: \(name)")
        case .stopRemoved: return Text("Removed: \(name)")
        case .stopDetails: return Text("“\(name)” has new details")
        case .routes: return Text("Routes changed")
        }
    }

    private func changeCard(_ c: EventChange) -> some View {
        VStack(alignment: .leading, spacing: Tokens.Space.s) {
            title(c).font(Tokens.Font.bodyBold)
            if let b = c.before {
                VStack(alignment: .leading, spacing: 2) {
                    Text("BEFORE").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
                    ForEach(Array(b.enumerated()), id: \.offset) { i, l in
                        Text(verbatim: l).font(i == 0 ? Tokens.Font.body : Tokens.Font.caption).foregroundStyle(Color.textMuted)
                            .strikethrough(c.kind == .stopRemoved)
                    }
                }
            }
            if let a = c.after {
                VStack(alignment: .leading, spacing: 2) {
                    Text("NOW").font(Tokens.Font.small).foregroundStyle(Color.brand)
                    ForEach(Array(a.enumerated()), id: \.offset) { i, l in
                        Text(verbatim: l).font(i == 0 ? Tokens.Font.bodyBold : Tokens.Font.caption).foregroundStyle(Color.textPrimary)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Tokens.Space.l)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("eventChange.\(c.kind.rawValue)")
    }
}
