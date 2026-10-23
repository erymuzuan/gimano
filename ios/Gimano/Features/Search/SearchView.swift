import SwiftUI

/// Full-screen search: field pinned on top, autocomplete as you type, distance right-aligned.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    let purpose: SearchPurpose

    @State private var query = ""
    @State private var results: [PlaceSummary] = []
    @State private var isSearching = false
    @State private var category: PlaceCategory?
    @FocusState private var focused: Bool

    private static let quickCategories: [(PlaceCategory, LocalizedStringKey)] = [
        (.fuel, "Fuel"), (.restaurant, "Food"), (.surau, "Surau"), (.parking, "Parking"),
        (.evCharging, "EV"), (.restArea, "R&R"), (.tollBooth, "Toll"), (.hospital, "Hospital"),
    ]

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button {
                    if model.navigation != nil { model.screen = .navigating }
                    else if purpose == .addStop, model.planner != nil { model.screen = .routePreview }
                    else { model.go(.home) }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 20, weight: .semibold))
                        .frame(width: 40, height: 44)
                }
                .foregroundStyle(Color.textPrimary)
                .accessibilityLabel(Text("Back"))

                HStack(spacing: 10) {
                    GimanoIcon(icon: .search, size: 26)
                    TextField(placeholder, text: $query)
                        .font(Tokens.Font.nunito(19, .bold))
                        .focused($focused)
                        .submitLabel(.search)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("searchField")
                    if !query.isEmpty {
                        Button { query = ""; category = nil } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(Color.textMuted)
                        }
                        .accessibilityLabel(Text("Clear"))
                    }
                }
                .padding(.horizontal, 16)
                .frame(height: Tokens.Size.searchField)
                .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.searchField, style: .continuous))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.quickCategories, id: \.0) { cat, title in
                        Button {
                            category = category == cat ? nil : cat
                        } label: {
                            HStack(spacing: 6) {
                                if let g = cat.gIcon {
                                    GimanoIcon(icon: g, size: 22, stroke: category == cat ? .brandOn : .brandInk, fill: category == cat ? .brandOn : .brandTint)
                                } else {
                                    Image(systemName: cat.systemImage).font(.system(size: 15, weight: .bold))
                                }
                                Text(title).font(Tokens.Font.label)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .foregroundStyle(category == cat ? Color.brandOn : Color.brandInk)
                            .background(category == cat ? Color.brand : Color.brandMist, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, Tokens.Space.gutter)
            }
            .padding(.bottom, 8)

            Divider()

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if query.isEmpty && category == nil {
                        suggestions
                    } else if results.isEmpty && !isSearching && model.localMatches(query).isEmpty {
                        Text("No results")
                            .font(Tokens.Font.body)
                            .foregroundStyle(Color.textMuted)
                            .padding(Tokens.Space.gutter)
                    } else {
                        // On-device favourites first (they never leave the phone).
                        ForEach(model.localMatches(query)) { f in
                            Button {
                                pick(PlaceSummary(id: f.placeId ?? "local:\(f.id)", name: f.name, category: .other, address: f.address,
                                                  lat: f.lat, lng: f.lng, distanceM: Geo.distance(model.position, f.location), brand: nil))
                            } label: {
                                PlaceRow(gIcon: model.icon(for: f).gIcon, title: f.name, subtitle: f.address, distanceM: Geo.distance(model.position, f.location))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("localResult")
                            Divider().padding(.leading, 68)
                        }
                        ForEach(results) { r in
                            Button { pick(r) } label: {
                                PlaceRow(category: r.category, title: r.name, subtitle: r.address, distanceM: r.distanceM)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("searchResult")
                            Divider().padding(.leading, 68)
                        }
                    }
                }
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .background(Color.surface.ignoresSafeArea())
        .overlay(alignment: .top) {
            if isSearching { ProgressView().padding(.top, 70) }
        }
        .onAppear {
            #if DEBUG
            if let q = UserDefaults.standard.string(forKey: "GimanoDemoQuery"), query.isEmpty { query = q; return }
            #endif
            if let c = model.searchCategory {
                category = c
                model.searchCategory = nil
            } else {
                focused = true
            }
        }
        .task(id: "\(query)|\(category?.rawValue ?? "")") { await runSearch() }
    }

    private var placeholder: String {
        switch purpose {
        case .destination: String(localized: "Where to?")
        case .addStop: String(localized: "Add a stop")
        case .setHome: String(localized: "Set home address")
        case .setWork: String(localized: "Set work address")
        }
    }

    @ViewBuilder private var suggestions: some View {
        if !model.recents.isEmpty {
            Text("Recent")
                .font(Tokens.Font.label)
                .foregroundStyle(Color.textMuted)
                .textCase(.uppercase)
                .padding(.horizontal, Tokens.Space.gutter)
                .padding(.top, 12)
            ForEach(model.recents) { r in
                Button {
                    pick(PlaceSummary(id: r.placeId ?? r.id, name: r.name, category: .other, address: r.address, lat: r.lat, lng: r.lng,
                                      distanceM: Geo.distance(model.position, r.location), brand: nil))
                } label: {
                    PlaceRow(gIcon: .recent, title: r.name, subtitle: r.address, distanceM: Geo.distance(model.position, r.location))
                }
                .buttonStyle(.plain)
                Divider().padding(.leading, 68)
            }
        }
    }

    private func runSearch() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty || category != nil else { results = []; return }
        try? await Task.sleep(for: .milliseconds(250)) // debounce
        guard !Task.isCancelled else { return }
        isSearching = true
        defer { isSearching = false }
        let repo = model.repo
        let near = model.position
        do {
            let r: [PlaceSummary]
            if q.isEmpty, let category {
                r = try await repo.nearby(near: near, category: category, radiusM: 5000)
            } else {
                r = try await repo.search(query: q, near: near, category: category, limit: 15)
            }
            guard !Task.isCancelled else { return }
            results = r
        } catch {
            if !Task.isCancelled { results = [] }
        }
    }

    private func pick(_ p: PlaceSummary) {
        focused = false
        switch purpose {
        case .destination:
            model.openPlace(p)
        case .addStop:
            model.addStop(p)
        case .setHome:
            model.setSaved(kind: .home, place: p)
            model.go(.home)
        case .setWork:
            model.setSaved(kind: .work, place: p)
            model.go(.home)
        }
    }
}
