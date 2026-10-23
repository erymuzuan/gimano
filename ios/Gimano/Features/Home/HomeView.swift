import SwiftUI

/// Idle screen: full-bleed map, menu top-left, round FABs top-right, report button bottom-right,
/// speedometer bottom-left, draggable sheet with the 64pt "Where to?" field and the shortcut grid.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var sheetHeight: CGFloat = 0
    @State private var detent: HomeSheet.Detent = .medium

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                VStack {
                    HStack(alignment: .top) {
                        MenuButton { model.showMenu = true }
                        Spacer()
                        VStack(spacing: 12) {
                            SoundFab()
                            LayersButton()
                        }
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    .padding(.top, 8)
                    Spacer()
                }

                if detent != .large {
                    VStack(spacing: 8) {
                        HStack(alignment: .bottom) {
                            VStack(alignment: .leading, spacing: 10) {
                                if !model.map.isFollowing {
                                    RoundFab(icon: .logo, accessibilityLabel: "Recenter", stroke: .brand, fill: .brand) { model.recenter() }
                                }
                                Speedometer(speedKmh: model.speedKmh)
                            }
                            Spacer()
                            ReportFab { model.showReport = true }
                        }
                        HStack {
                            OSMAttribution()
                            Spacer()
                            VehiclePill()
                        }
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    .padding(.bottom, sheetHeight + 8)
                    .transition(.opacity)
                }

                HomeSheet(detent: $detent, height: $sheetHeight, maxHeight: geo.size.height)
            }
            .ignoresSafeArea(edges: .bottom)
        }
        .animation(.snappy, value: detent)
    }
}

struct SoundFab: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        RoundFab(icon: model.soundMode == .alertsOnly ? .report : .sound,
                 accessibilityLabel: LocalizedStringKey(String(localized: model.soundMode.title)),
                 slashed: model.soundMode == .off) {
            model.soundMode = model.soundMode.next
            model.showToast(String(localized: model.soundMode.title))
        }
    }
}

struct LayersButton: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        Menu {
            Button {
                model.recenter()
            } label: { Label("Center on me", systemImage: "location.fill") }
            Button {
                Task { await model.live.refresh() }
            } label: { Label("Refresh traffic & reports", systemImage: "arrow.clockwise") }
        } label: {
            GimanoIcon(icon: .layers, size: Tokens.Size.fabIcon)
                .frame(width: Tokens.Size.fab, height: Tokens.Size.fab)
                .background(Color.surface, in: Circle())
                .fabShadow()
        }
        .accessibilityLabel(Text("Layers"))
    }
}

struct VehiclePill: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        Button {
            model.vehicle = model.vehicle == .car ? .motorcycle : .car
            model.updateProfile(UserPatch(vehicle: model.vehicle))
        } label: {
            Image(systemName: model.vehicle == .car ? "car.fill" : "motorcycle")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(Color.brandInk)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.surface, in: Capsule())
                .fabShadow()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(model.vehicle == .car ? "Car" : "Motorbike"))
    }
}

/// Draggable bottom sheet with three detents.
struct HomeSheet: View {
    enum Detent { case collapsed, medium, large }

    @Environment(AppModel.self) private var model
    @Binding var detent: Detent
    @Binding var height: CGFloat
    let maxHeight: CGFloat
    @GestureState private var drag: CGFloat = 0

    private func baseHeight(_ d: Detent) -> CGFloat {
        switch d {
        case .collapsed: 104
        case .medium: min(model.favorites.isEmpty ? 330 : 440, maxHeight * 0.55)
        case .large: maxHeight * 0.88
        }
    }

    var body: some View {
        let h = max(96, min(maxHeight * 0.92, baseHeight(detent) - drag))
        VStack(spacing: 0) {
            GrabHandle().padding(.top, 8).padding(.bottom, 10)
            Button {
                model.screen = .search(.destination)
            } label: {
                HStack(spacing: 12) {
                    GimanoIcon(icon: .search, size: Tokens.Size.searchIcon)
                    Text("Where to?").font(Tokens.Font.nunito(19, .extrabold))
                    Spacer()
                    GimanoIcon(icon: .mic, size: 26, stroke: .textMuted, fill: .brandMist)
                        .accessibilityLabel(Text("Voice search"))
                }
                .foregroundStyle(Color.textPrimary)
                .padding(.horizontal, 18)
                .frame(height: Tokens.Size.searchField)
                .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.searchField, style: .continuous))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, Tokens.Space.gutter)
            .accessibilityIdentifier("whereTo")

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    FavoritesRow()
                    ShortcutGrid()
                    RecentsList()
                }
                .padding(.top, 16)
                .padding(.bottom, 40)
            }
            .scrollDisabled(detent != .large)
        }
        .frame(maxWidth: .infinity)
        .frame(height: h + bottomInset, alignment: .top)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: Tokens.Radius.sheetTop, topTrailingRadius: Tokens.Radius.sheetTop)
                .fill(Color.surface)
                .shadow(color: Color(red: 11 / 255, green: 27 / 255, blue: 58 / 255).opacity(0.14), radius: 12, y: -2)
        )
        .gesture(
            DragGesture(minimumDistance: 8)
                .updating($drag) { v, s, _ in s = v.translation.height }
                .onEnded { v in
                    let target = baseHeight(detent) - v.predictedEndTranslation.height
                    let options: [Detent] = [.collapsed, .medium, .large]
                    detent = options.min { abs(baseHeight($0) - target) < abs(baseHeight($1) - target) } ?? .medium
                }
        )
        .onAppear { height = baseHeight(detent) + bottomInset }
        .onChange(of: detent) { height = baseHeight(detent) + bottomInset }
    }

    private var bottomInset: CGFloat {
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.keyWindow?.safeAreaInsets.bottom ?? 20
    }
}

/// 4-column grid of 76pt shortcut tiles with 42pt duotone icons and one-word labels.
struct ShortcutGrid: View {
    @Environment(AppModel.self) private var model
    private let columns = Array(repeating: GridItem(.flexible(), spacing: Tokens.Space.gridGap), count: 4)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 14) {
            tile(.home, "Home") { open(saved: model.home, purpose: .setHome) }
            tile(.work, "Work") { open(saved: model.work, purpose: .setWork) }
            tile(.fuel, "Fuel") { search(.fuel) }
            tile(.food, "Food") { search(.restaurant) }
            tile(.surau, "Surau") { search(.surau) }
            tile(.parking, "Parking") { search(.parking) }
            tile(.ev, "EV") { search(.evCharging) }
            tile(.recent, "Recent") { model.screen = .search(.destination) }
        }
        .padding(.horizontal, Tokens.Space.gutter)
    }

    private func open(saved: SavedPlace?, purpose: SearchPurpose) {
        if let saved {
            model.planRoute(to: Destination(name: saved.name, address: saved.address, location: saved.location, placeId: saved.placeId))
        } else {
            model.screen = .search(purpose)
        }
    }

    private func search(_ c: PlaceCategory) {
        model.searchCategory = c
        model.screen = .search(.destination)
    }

    private func tile(_ icon: GIcon, _ title: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                GimanoIcon(icon: icon, size: Tokens.Size.shortcutIcon)
                    .frame(width: Tokens.Size.shortcutTile, height: Tokens.Size.shortcutTile)
                    .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.shortcutTile, style: .continuous))
                Text(title)
                    .font(Tokens.Font.nunito(14, .extrabold))
                    .foregroundStyle(Color.textPrimary)
                    .lineLimit(1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
        .accessibilityIdentifier("shortcut.\(icon.rawValue)")
    }
}

struct RecentsList: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(model.recents) { r in
                Button {
                    model.planRoute(to: Destination(name: r.name, address: r.address, location: r.location, placeId: r.placeId))
                } label: {
                    PlaceRow(gIcon: .recent, title: r.name, subtitle: r.address, distanceM: Geo.distance(model.position, r.location))
                }
                .buttonStyle(.plain)
                Divider().overlay(Color.outline).padding(.leading, 72)
            }
        }
    }
}

/// Shared list row: icon tile · name / address · distance right-aligned.
struct PlaceRow: View {
    var gIcon: GIcon?
    var category: PlaceCategory?
    var title: String
    var subtitle: String
    var distanceM: Double?

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let gIcon {
                    GimanoIcon(icon: gIcon, size: 26)
                        .frame(width: 44, height: 44)
                        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                } else {
                    CategoryIcon(category: category ?? .other)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Tokens.Font.bodyBold).foregroundStyle(Color.textPrimary).lineLimit(1)
                Text(subtitle).font(Tokens.Font.caption).foregroundStyle(Color.textMuted).lineLimit(1)
            }
            Spacer(minLength: 8)
            if let distanceM {
                Text(ManeuverFormatter.distance(distanceM))
                    .font(Tokens.Font.label.monospacedDigit())
                    .foregroundStyle(Color.textMuted)
            }
        }
        .padding(.horizontal, Tokens.Space.gutter)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}
