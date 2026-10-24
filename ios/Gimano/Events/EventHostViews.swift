import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Organizer screens shared by the wizard (EventWizardViews.swift): the stop editor and the place picker; the system
// share sheet; and the guest's "Pelan berubah" update (board 5). Drafts, keys, links and files stay on this phone; the only server calls are
// the app's existing place search and the address shown while choosing a point on the map (no event data).

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

/// System share sheet with several items (the file plus a message).
struct ActivityView: UIViewControllerRepresentable {
    var items: [Any]
    /// Called when the sheet closes: true when the user picked an app and the share went through.
    var onComplete: ((Bool) -> Void)? = nil
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let onComplete { vc.completionWithItemsHandler = { _, completed, _, _ in onComplete(completed) } }
        return vc
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
