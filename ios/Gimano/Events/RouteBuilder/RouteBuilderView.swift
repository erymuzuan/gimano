import SwiftUI

// "Bina laluan": full-screen map, one short hint, round undo/redo and a ⋮ menu floating above a bottom card with the
// totals, travel mode, "Ikut jalan" and "Simpan". Icons first, text only where an icon alone would be ambiguous.
// Every map gesture has a non-map path in the point list (reorder, delete, add an event stop, add at map centre).

extension EventTravelMode {
    var vehicle: Vehicle { Vehicle(rawValue: rawValue) ?? .car }
    static let all: [EventTravelMode] = [.car, .motorcycle, .bicycle, .walk]
}

extension AppModel {
    /// Routing for the builder: the live server (also in auto mode). Demo mode has none, so legs wait offline.
    var routeBuilderTransport: (any RouteBuilderTransport)? { repo as? any RouteBuilderTransport }
}

enum RouteFormat {
    static func distance(_ m: Double) -> String { ManeuverFormatter.distance(m) }
    static func duration(_ s: Double?) -> String? { s.map(ETAFormatter.duration) }

    /// "12.4 km · 45 min" for lists.
    static func summary(_ r: RouteDraft) -> String {
        guard r.points.count >= 2 else { return String(localized: "No points yet") }
        return ([distance(r.distanceM)] + [duration(r.durationS)].compactMap { $0 }).joined(separator: " · ")
    }
}

struct RouteBuilderView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var b: RouteBuilderModel
    /// Event days (`YYYY-MM-DD`) for the "Hari" picker; empty for personal routes and one-day events.
    let days: [String]
    let onSave: (RouteDraft, [HostedStopDraft]) -> Void

    @State private var center: LatLng
    @State private var showPoints = false
    @State private var showSave = false
    @State private var confirmClear = false
    @State private var confirmDiscard = false
    private let start: LatLng

    init(route: RouteDraft, stops: [HostedStopDraft] = [], context: RouteBuilderContext, days: [String] = [],
         transport: (any RouteBuilderTransport)?, start: LatLng, onSave: @escaping (RouteDraft, [HostedStopDraft]) -> Void) {
        _b = State(initialValue: RouteBuilderModel(route: route, stops: stops, context: context, transport: transport))
        self.days = days
        self.onSave = onSave
        self.start = route.points.first?.location ?? stops.first?.location ?? start
        _center = State(initialValue: self.start)
    }

    var body: some View {
        ZStack {
            RouteBuilderMapView(model: b, styleURL: app.map.styleURL, start: start) { c in center = c }
                .ignoresSafeArea()
            VStack(spacing: Tokens.Space.s) {
                topBar
                toolPicker
                hint
                Spacer(minLength: 0)
                HStack(alignment: .bottom) {
                    OSMAttribution()
                    Spacer()
                    floatingButtons
                }
                .padding(.horizontal, Tokens.Space.gutter)
                bottomCard
            }
        }
        .onAppear { b.resolvePending() }
        .sheet(isPresented: $showPoints) { RoutePointList(b: b, center: center) }
        .sheet(isPresented: $showSave) {
            RouteSaveSheet(b: b, days: days) { route in
                onSave(route, b.stops)
                showSave = false
                dismiss()
            }
        }
        .confirmationDialog(pointTitle, isPresented: Binding(get: { b.selectedPoint != nil }, set: { if !$0 { b.selectedPoint = nil } }),
                            titleVisibility: .visible) { pointActions }
        .confirmationDialog("Remove all points?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { b.clear() }
        }
        .confirmationDialog("Discard your changes to this route?", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button("Discard", role: .destructive) { dismiss() }
        }
    }

    // MARK: top

    private var topBar: some View {
        HStack(spacing: Tokens.Space.m) {
            roundButton("xmark", label: "Close", id: "routeBuilderClose") {
                if b.canUndo { confirmDiscard = true } else { dismiss() }
            }
            VStack(spacing: 0) {
                Text(verbatim: b.route.title.isEmpty ? String(localized: "New route") : b.route.title)
                    .font(Tokens.Font.bodyBold).lineLimit(1)
                if case .event(let title) = b.context {
                    Text(verbatim: title).font(Tokens.Font.small).foregroundStyle(Color.textMuted).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6).padding(.horizontal, 12)
            .background(.regularMaterial, in: Capsule())
            GimanoIcon(icon: b.route.mode.vehicle.gIcon, size: 26)
                .frame(width: 44, height: 44)
                .background(Color.surface, in: Circle())
                .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
                .accessibilityElement()
                .accessibilityLabel(Text(b.route.mode.vehicle.title))
        }
        .padding(.horizontal, Tokens.Space.gutter)
        .padding(.top, Tokens.Space.s)
    }

    private var toolPicker: some View {
        HStack(spacing: 0) {
            toolSegment(.points, icon: "point.topleft.down.to.point.bottomright.curvepath", label: "Point to point")
            toolSegment(.draw, icon: "scribble.variable", label: "Draw")
        }
        .padding(3)
        .background(Color.surface, in: Capsule())
        .shadow(color: .black.opacity(0.12), radius: 4, y: 2)
    }

    private func toolSegment(_ t: RouteBuilderModel.Tool, icon: String, label: LocalizedStringKey) -> some View {
        Button { b.tool = t; b.selectedPoint = nil } label: {
            Image(systemName: icon).font(.system(size: 18, weight: .bold))
                .frame(width: 56, height: 36)
                .foregroundStyle(b.tool == t ? Color.brandOn : Color.brand)
                .background(b.tool == t ? Color.brand : Color.clear, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(b.tool == t ? .isSelected : [])
        .accessibilityIdentifier("routeTool.\(t.rawValue)")
    }

    private var hint: some View {
        let text: LocalizedStringKey = b.tool == .draw ? "Draw with one finger"
            : (b.route.points.isEmpty ? "Tap to start" : "Tap to add the next point")
        return Text(text)
            .font(Tokens.Font.label)
            .foregroundStyle(Color.navBannerOn)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(Color.navBanner.opacity(0.88), in: Capsule())
            .accessibilityIdentifier("routeBuilderHint")
    }

    // MARK: floating

    private var floatingButtons: some View {
        HStack(spacing: Tokens.Space.s) {
            roundButton("arrow.uturn.backward", label: "Undo", id: "routeUndo") { b.undo() }.disabled(!b.canUndo)
            roundButton("arrow.uturn.forward", label: "Redo", id: "routeRedo") { b.redo() }.disabled(!b.canRedo)
            Menu {
                Button { b.reverse() } label: { Label("Reverse", systemImage: "arrow.left.arrow.right") }
                Button { b.outAndBack() } label: { Label("Out and back", systemImage: "arrow.uturn.left") }
                Button { b.closeLoop() } label: { Label("Back to start", systemImage: "arrow.triangle.capsulepath") }
                Divider()
                Button { showPoints = true } label: { Label("Point list", systemImage: "list.number") }
                Button { b.tap(center) } label: { Label("Add point at map centre", systemImage: "plus.viewfinder") }
                Divider()
                Button(role: .destructive) { confirmClear = true } label: { Label("Clear", systemImage: "trash") }
            } label: {
                roundIcon("ellipsis")
            }
            .accessibilityLabel(Text("More"))
            .accessibilityIdentifier("routeMenu")
        }
    }

    private func roundIcon(_ name: String) -> some View {
        Image(systemName: name).font(.system(size: 18, weight: .bold))
            .foregroundStyle(Color.brand)
            .frame(width: 48, height: 48)
            .background(Color.surface, in: Circle())
            .shadow(color: .black.opacity(0.18), radius: 5, y: 2)
    }

    private func roundButton(_ icon: String, label: LocalizedStringKey, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { roundIcon(icon) }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(label))
            .accessibilityIdentifier(id)
    }

    // MARK: bottom card

    private var bottomCard: some View {
        VStack(alignment: .leading, spacing: Tokens.Space.m) {
            RouteStatsRow(route: b.route)
            status
            if b.isEvent, b.route.points.isEmpty, let first = b.stops.first {
                Button { b.tap(first.location, stopId: first.id) } label: {
                    Label { Text("Start at \(first.label)").lineLimit(1) } icon: { Image(systemName: "flag.fill") }
                        .font(Tokens.Font.label)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Color.brandMist, in: Capsule())
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.brandInk)
                .accessibilityIdentifier("routeStartAtStop")
            }
            HStack(spacing: 6) {
                ForEach(EventTravelMode.all, id: \.self) { m in
                    Button { b.setMode(m) } label: {
                        GimanoIcon(icon: m.vehicle.gIcon, size: 26)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .selectableTile(b.route.mode == m, radius: 14)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(m.vehicle.title))
                    .accessibilityAddTraits(b.route.mode == m ? .isSelected : [])
                    .accessibilityIdentifier("routeMode.\(m.rawValue)")
                }
            }
            HStack(spacing: Tokens.Space.m) {
                Toggle(isOn: Binding(get: { b.route.followRoads }, set: { b.setFollowRoads($0) })) {
                    Image(systemName: "road.lanes").font(.system(size: 20, weight: .semibold)).foregroundStyle(Color.brand)
                }
                .toggleStyle(.switch)
                .fixedSize()
                .accessibilityLabel(Text("Follow roads"))
                .accessibilityIdentifier("routeFollowRoads")
                Button { showPoints = true } label: {
                    Label { Text(verbatim: "\(b.route.points.count)") } icon: { Image(systemName: "list.number") }
                        .font(Tokens.Font.label)
                }
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityLabel(Text("Point list, \(b.route.points.count) points"))
                .accessibilityIdentifier("routePointList")
                Button { showSave = true } label: { Text("Save") }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(b.route.points.count < 2)
                    .accessibilityIdentifier("routeSave")
            }
        }
        .padding(Tokens.Space.l)
        .background(Color.surface, in: UnevenRoundedRectangle(topLeadingRadius: Tokens.Radius.sheetTop, topTrailingRadius: Tokens.Radius.sheetTop))
        .shadow(color: .black.opacity(0.15), radius: 10, y: -2)
        .ignoresSafeArea(edges: .bottom)
    }

    @ViewBuilder
    private var status: some View {
        let r = b.route
        if let i = r.legs.firstIndex(where: { $0.state == .matched && !$0.unmatched.isEmpty }) {
            VStack(alignment: .leading, spacing: 6) {
                Label("No road found here", systemImage: "exclamationmark.triangle.fill")
                    .font(Tokens.Font.label).foregroundStyle(Color.amberInk)
                HStack(spacing: Tokens.Space.s) {
                    chip("Redraw", icon: "scribble", id: "routeRedraw") { b.redraw(i); b.tool = .draw }
                    chip("Add point", icon: "plus.circle", id: "routeSplit") { b.splitAtUnmatched(i) }
                    chip("Sketch", icon: "pencil.line", id: "routeKeepSketch") { b.keepAsSketch(i) }
                }
            }
            .accessibilityElement(children: .contain)
        } else if b.isCalculating {
            Label { Text("Calculating…") } icon: { ProgressView().controlSize(.small) }
                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
        } else if r.needsCalculation > 0 {
            HStack {
                Label { Text("\(r.needsCalculation) to calculate") } icon: { Image(systemName: "exclamationmark.icloud") }
                    .font(Tokens.Font.small).foregroundStyle(Color.amberInk)
                    .accessibilityIdentifier("routeNeedsCalculation")
                Spacer()
                chip("Retry", icon: "arrow.clockwise", id: "routeRetry") { b.retryAll() }
            }
        } else if let n = b.notice {
            Text(verbatim: n).font(Tokens.Font.small).foregroundStyle(Color.amberInk)
                .onTapGesture { b.notice = nil }
        } else if r.hasSketch {
            Label("Sketch: not a road", systemImage: "pencil.line").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
        }
    }

    private func chip(_ title: LocalizedStringKey, icon: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(Tokens.Font.small)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(Color.brandMist, in: Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.brandInk)
        .accessibilityIdentifier(id)
    }

    // MARK: point actions

    private var pointTitle: Text {
        guard let i = b.selectedPoint else { return Text(verbatim: "") }
        if let s = b.stop(b.route.points[safe: i]?.stopId) { return Text(verbatim: s.label) }
        return Text("Point \(i + 1)")
    }

    @ViewBuilder
    private var pointActions: some View {
        if let i = b.selectedPoint, let p = b.route.points[safe: i] {
            if b.isEvent {
                if p.stopId == nil {
                    Button("Make it an event place") { b.makeStop(i) }
                    let linked = Set(b.route.points.compactMap(\.stopId))
                    ForEach(b.stops.filter { !linked.contains($0.id) }
                        .sorted { Geo.distance($0.location, p.location) < Geo.distance($1.location, p.location) }
                        .prefix(4)) { s in
                        Button("Snap to: \(s.label)") { b.link(i, to: s.id) }
                    }
                } else {
                    Button("Unlink from the event place") { b.unlink(i) }
                }
            }
            if i > 0 { Button("Move up in the list") { b.moveInList(i, by: -1) } }
            if i < b.route.points.count - 1 { Button("Move down in the list") { b.moveInList(i, by: 1) } }
            Button("Delete point", role: .destructive) { b.delete(i) }
        }
    }
}

// MARK: - Totals

struct RouteStatsRow: View {
    let route: RouteDraft

    var body: some View {
        HStack(spacing: Tokens.Space.l) {
            stat("ruler", RouteFormat.distance(route.distanceM), a11y: "Distance")
                .accessibilityIdentifier("routeDistance")
            stat("clock", RouteFormat.duration(route.durationS) ?? String(localized: "Unavailable"), a11y: "Time",
                 muted: route.durationS == nil)
            // Distance and time only: the phone shows no climb/descent.
            Spacer(minLength: 0)
        }
    }

    private func stat(_ icon: String, _ value: String, a11y: LocalizedStringKey, muted: Bool = false) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 14, weight: .bold)).foregroundStyle(Color.brand)
            Text(verbatim: value).font(muted ? Tokens.Font.small : Tokens.Font.bodyBold)
                .foregroundStyle(muted ? Color.textMuted : Color.textPrimary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(a11y))
        .accessibilityValue(Text(verbatim: value))
    }
}

// MARK: - Point list (the non-map path)

struct RoutePointList: View {
    @Environment(\.dismiss) private var dismiss
    let b: RouteBuilderModel
    let center: LatLng

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if b.route.points.isEmpty {
                        Text("No points yet").foregroundStyle(Color.textMuted)
                    }
                    ForEach(Array(b.route.points.enumerated()), id: \.element.id) { i, p in
                        row(i, p)
                    }
                } footer: {
                    Text("Only the coordinates and travel mode are sent to the routing server. Nothing is stored there.")
                }
                Section {
                    Button { b.tap(center) } label: { Label("Add point at map centre", systemImage: "plus.viewfinder") }
                        .accessibilityIdentifier("routeAddCenter")
                    if b.isEvent {
                        ForEach(b.stops) { s in
                            Button { b.tap(s.location, stopId: s.id) } label: {
                                Label { Text(verbatim: s.label) } icon: { Image(systemName: EventFormat.icon(s.kind)) }
                            }
                        }
                    }
                } header: { Text("Add") }
            }
            .navigationTitle("Points")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack {
                        Button { b.undo() } label: { Image(systemName: "arrow.uturn.backward") }.disabled(!b.canUndo).accessibilityLabel(Text("Undo"))
                        Button { b.redo() } label: { Image(systemName: "arrow.uturn.forward") }.disabled(!b.canRedo).accessibilityLabel(Text("Redo"))
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func name(_ i: Int, _ p: BuilderPoint) -> String {
        if let s = b.stop(p.stopId) { return s.label }
        if i == 0 { return String(localized: "Start") }
        if i == b.route.points.count - 1 { return String(localized: "Finish") }
        return String(localized: "Point \(i + 1)")
    }

    private func row(_ i: Int, _ p: BuilderPoint) -> some View {
        let n = b.route.points.count
        return HStack(spacing: Tokens.Space.m) {
            Text(verbatim: "\(i + 1)").font(Tokens.Font.label)
                .frame(width: 28, height: 28)
                .foregroundStyle(i == 0 || i == n - 1 ? Color.brandOn : Color.brand)
                .background(i == 0 || i == n - 1 ? Color.brand : Color.brandMist,
                            in: p.stopId != nil ? AnyShape(RoundedRectangle(cornerRadius: 7)) : AnyShape(Circle()))
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: name(i, p)).font(Tokens.Font.body)
                Text(verbatim: String(format: "%.5f, %.5f", p.lat, p.lng)).font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            }
            Spacer()
            Button { b.moveInList(i, by: -1) } label: { Image(systemName: "chevron.up") }
                .disabled(i == 0).accessibilityLabel(Text("Move up"))
            Button { b.moveInList(i, by: 1) } label: { Image(systemName: "chevron.down") }
                .disabled(i == n - 1).accessibilityLabel(Text("Move down"))
            Button(role: .destructive) { b.delete(i) } label: { Image(systemName: "trash") }
                .accessibilityLabel(Text("Delete point"))
        }
        .buttonStyle(.borderless)
        .accessibilityElement(children: .combine)
        .accessibilityActions {
            if i > 0 { Button("Move up") { b.moveInList(i, by: -1) } }
            if i < n - 1 { Button("Move down") { b.moveInList(i, by: 1) } }
            Button("Delete point") { b.delete(i) }
        }
        .accessibilityIdentifier("routePoint.\(i)")
    }
}

// MARK: - Save

struct RouteSaveSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let b: RouteBuilderModel
    let days: [String]
    let onSave: (RouteDraft) -> Void
    @State private var name = ""
    @State private var day: String?
    @State private var gpx: URL?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Route name", text: $name)
                        .accessibilityIdentifier("routeNameField")
                    if !days.isEmpty {
                        Picker(selection: $day) {
                            Text("—").tag(String?.none)
                            ForEach(days, id: \.self) { d in Text(verbatim: d).tag(String?.some(d)) }
                        } label: { Label("Day", systemImage: "calendar") }
                    }
                } footer: {
                    RouteStatsRow(route: b.route).padding(.top, 6)
                }
                Section {
                    switch b.context {
                    case .event(let title):
                        Label { VStack(alignment: .leading, spacing: 2) {
                            Text("In the event: \(title)").font(Tokens.Font.bodyBold)
                            Text("Shared with the invitation when you publish a new version.").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                        } } icon: { Image(systemName: "party.popper.fill") }
                    case .personal:
                        Label { VStack(alignment: .leading, spacing: 2) {
                            Text("My routes").font(Tokens.Font.bodyBold)
                            Text("On this phone and in your own backup. You can share it as GPX.").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                        } } icon: { Image(systemName: "lock.fill") }
                    }
                    if b.route.hasSketch {
                        Label("Part of it is a sketch, not a road. Guests see it labelled “sketch”, without turn-by-turn directions.",
                              systemImage: "pencil.line")
                            .font(Tokens.Font.caption).foregroundStyle(Color.amberInk)
                    }
                    if b.route.needsCalculation > 0 || b.route.hasUnmatched {
                        Label("Some legs aren't calculated yet. You can save now and finish later.", systemImage: "exclamationmark.icloud")
                            .font(Tokens.Font.caption).foregroundStyle(Color.amberInk)
                    }
                }
                Section {
                    Button {
                        gpx = app.events.gpxFileURL(b.route, name: currentName, stops: b.stops)
                    } label: { Label("Share GPX", systemImage: "square.and.arrow.up") }
                        .disabled(b.route.joinedGeometry.count < 2)
                        .accessibilityIdentifier("routeShareGPX")
                } footer: {
                    Text("A GPX file isn't encrypted. Anyone who gets it can read the route.")
                }
            }
            .navigationTitle("Save route")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var r = b.route
                        r.title = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        r.day = day
                        onSave(r)
                    }
                    .accessibilityIdentifier("routeSaveConfirm")
                }
            }
            .onAppear { name = b.route.title; day = b.route.day }
            .sheet(item: Binding(get: { gpx.map(SharedFile.init) }, set: { gpx = $0?.url })) { f in ActivityView(items: [f.url]) }
        }
        .presentationDetents([.medium, .large])
    }

    private var currentName: String {
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? String(localized: "Route") : t
    }
}

struct SharedFile: Identifiable {
    var url: URL
    var id: URL { url }
}
