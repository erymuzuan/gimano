import SwiftUI

// "Laluan saya" (personal routes, on this phone and in the user's own backup) and the "Laluan" section of the event editor.

struct MyRoutesView: View {
    @Environment(AppModel.self) private var model
    @State private var building: RouteDraft?
    @State private var gpx: URL?

    var body: some View {
        let center = model.events
        List {
            Section {
                Button { building = RouteDraft() } label: {
                    Label("Build a route", systemImage: "plus.circle.fill").font(Tokens.Font.bodyBold)
                }
                .accessibilityIdentifier("myRoutesNew")
                ForEach(center.personalRoutes) { r in
                    Button { building = r } label: { RouteRow(route: r, fallbackName: String(localized: "Route")) }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("myRoute.\(r.id)")
                        .swipeActions {
                            Button("Delete", role: .destructive) { center.deletePersonalRoute(r.id) }
                        }
                        .contextMenu {
                            Button { gpx = center.gpxFileURL(r, name: r.title) } label: { Label("Share GPX", systemImage: "square.and.arrow.up") }
                                .disabled(r.joinedGeometry.count < 2)
                            // A new event with "Mula"/"Tamat" places (Android does the same). To add it to an existing
                            // event, the event editor has "From My routes".
                            Button { center.useRouteInNewEvent(r) } label: { Label("Use in an event", systemImage: "party.popper") }
                                .disabled(r.points.count < 2)
                            Button("Delete", systemImage: "trash", role: .destructive) { center.deletePersonalRoute(r.id) }
                        }
                }
            } footer: {
                Label("Your routes stay on this phone and in your own backup. Routing sends only coordinates and travel mode, and nothing is stored on the server.",
                      systemImage: "lock.shield")
            }
        }
        .navigationTitle("My routes")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { center.isPresented = false } } }
        .onAppear { center.reloadPersonalRoutes() }
        .fullScreenCover(item: $building) { r in
            RouteBuilderView(route: r, context: .personal, transport: model.routeBuilderTransport, isDemo: model.routeBuilderIsDemo,
                             start: model.position) { saved, _ in
                center.savePersonalRoute(saved)
            }
        }
        .sheet(item: Binding(get: { gpx.map(SharedFile.init) }, set: { gpx = $0?.url })) { f in ActivityView(items: [f.url]) }
    }
}

/// One route in a list: name, distance · time, mode, and what still needs doing.
struct RouteRow: View {
    let route: RouteDraft
    let fallbackName: String
    var problem = false

    var body: some View {
        HStack(spacing: Tokens.Space.m) {
            GimanoIcon(icon: route.mode.vehicle.gIcon, size: 26)
                .frame(width: 40, height: 40)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.sm, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: route.title.isEmpty ? fallbackName : route.title).font(Tokens.Font.bodyBold).lineLimit(1)
                Text(verbatim: RouteFormat.summary(route)).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                HStack(spacing: 6) {
                    if route.needsCalculation > 0 || route.hasUnmatched {
                        Label("To calculate", systemImage: "exclamationmark.icloud")
                            .foregroundStyle(problem ? Color.speeding : Color.amberInk)
                    } else if route.hasSketch {
                        Label("Sketch", systemImage: "pencil.line").foregroundStyle(Color.textMuted)
                    }
                    if let d = route.day { Text(verbatim: d).foregroundStyle(Color.textMuted) }
                }
                .font(Tokens.Font.small)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.textMuted)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The editor's "Laluan" section: add, edit and delete the event's routes.
struct EventRoutesSection: View {
    @Environment(AppModel.self) private var model
    @Binding var draft: HostedEventDraft
    var problems: [EventDraftIssue]
    @Binding var building: RouteDraft?

    var body: some View {
        Section {
            ForEach(draft.routes) { r in
                Button { building = r } label: {
                    RouteRow(route: r, fallbackName: String(localized: "Route \((draft.routes.firstIndex(of: r) ?? 0) + 1)"),
                             problem: problems.contains { $0.routeId == r.id })
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("eventRoute.\(r.id)")
                .id(EventDraftIssue.Anchor.route(r.id))
                .contextMenu {
                    Button("Delete route", systemImage: "trash", role: .destructive) { draft.routes.removeAll { $0.id == r.id } }
                }
            }
            .onDelete { draft.routes.remove(atOffsets: $0) }
            Button { building = RouteDraft(modeRaw: defaultMode.rawValue) } label: {
                Label("Build a route", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                    .font(Tokens.Font.label)
            }
            .disabled(draft.routes.count >= EventLimits.maxRoutes)
            .accessibilityIdentifier("eventAddRoute")
            .id(EventDraftIssue.Anchor.routes)
            let mine = model.events.personalRoutes
            if !mine.isEmpty {
                Menu {
                    ForEach(mine) { r in
                        Button(r.title.isEmpty ? String(localized: "Route") : r.title) {
                            var copy = r
                            copy.id = EventRandom.itemId()
                            for i in copy.points.indices { copy.points[i].stopId = nil }
                            draft.routes.append(copy)
                        }
                    }
                } label: { Label("From My routes", systemImage: "tray.and.arrow.down") }
                    .font(Tokens.Font.label)
            }
        } header: {
            Text("Routes").font(Tokens.Font.label)
        } footer: {
            if !draft.routes.isEmpty {
                Text("Long routes can make the link too long; Manotu then offers the event file.")
            }
        }
    }

    private var defaultMode: EventTravelMode {
        switch draft.template {
        case .ride: .bicycle
        default: .car
        }
    }
}

extension HostedEventDraft {
    /// The event's local dates (`YYYY-MM-DD`) when it spans more than one day.
    var days: [String] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let f = DateFormatter()
        f.calendar = cal
        f.timeZone = timeZone
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        var out: [String] = []
        var d = cal.startOfDay(for: start)
        while d <= end, out.count <= EventLimits.maxEventDays {
            out.append(f.string(from: d))
            d = cal.date(byAdding: .day, value: 1, to: d) ?? end.addingTimeInterval(1)
        }
        return out.count > 1 ? out : []
    }
}
