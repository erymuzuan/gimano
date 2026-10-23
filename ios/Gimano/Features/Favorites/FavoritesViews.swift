import SwiftUI

/// "Save" from the place card: quick-label chips (Kampung, Rumah Mertua, …), free text, icon picker,
/// or "Set as Home / Work".
struct SavePlaceSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let place: PlaceSummary
    @State private var label = ""
    @State private var icon: FavoriteIcon = .star
    @State private var iconTouched = false

    private let iconColumns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 5)
    private let chipColumns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 10) {
                        homeWorkButton(.home, "Home")
                        homeWorkButton(.work, "Work")
                    }

                    LazyVGrid(columns: chipColumns, spacing: 10) {
                        ForEach(QuickLabel.all) { q in
                            Button {
                                label = q.label
                                icon = q.icon
                                iconTouched = true
                            } label: {
                                VStack(spacing: 4) {
                                    GimanoIcon(icon: q.icon.gIcon, size: 34,
                                               stroke: label == q.label ? .brandOn : .brandInk,
                                               fill: label == q.label ? .brandOn : .brandTint)
                                    Text(verbatim: q.label).font(Tokens.Font.label).lineLimit(1).minimumScaleFactor(0.8)
                                }
                                .foregroundStyle(label == q.label ? Color.brandOn : Color.brandInk)
                                .frame(maxWidth: .infinity, minHeight: 76)
                                .background(label == q.label ? Color.brand : Color.brandMist,
                                            in: RoundedRectangle(cornerRadius: Tokens.Radius.shortcutTile, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("quickLabel.\(q.label)")
                        }
                    }

                    TextField(String(localized: "Label, e.g. Sekolah Aina"), text: $label)
                        .font(Tokens.Font.nunito(19, .bold))
                        .padding(.horizontal, 16)
                        .frame(height: 56)
                        .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.searchField, style: .continuous))
                        .accessibilityIdentifier("favoriteLabel")
                        .onChange(of: label) {
                            label = String(label.prefix(40))
                            if !iconTouched, let s = QuickLabel.suggestedIcon(for: label) { icon = s }
                        }

                    IconPicker(selection: Binding(get: { icon }, set: { icon = $0; iconTouched = true }))

                    Button {
                        model.setSaved(kind: .favorite, place: place, label: label.isEmpty ? place.name : label, icon: icon)
                        dismiss()
                    } label: {
                        Label("Save", systemImage: "star.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("saveFavorite")
                }
                .padding(Tokens.Space.gutter)
            }
            .navigationTitle(place.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
        }
    }

    private func homeWorkButton(_ kind: SavedPlaceKind, _ title: LocalizedStringKey) -> some View {
        Button {
            model.setSaved(kind: kind, place: place)
            dismiss()
        } label: {
            HStack(spacing: 10) {
                GimanoIcon(icon: kind == .home ? .home : .work, size: 32)
                Text(kind == .home ? "Set as Home" : "Set as Work").font(Tokens.Font.label).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(Color.brandInk)
            .frame(maxWidth: .infinity, minHeight: 60)
            .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
        .accessibilityIdentifier(kind == .home ? "setAsHome" : "setAsWork")
    }
}

/// Big icon grid for favourite icons.
struct IconPicker: View {
    @Binding var selection: FavoriteIcon
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 5)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 10) {
            ForEach(FavoriteIcon.pickable) { i in
                Button { selection = i } label: {
                    GimanoIcon(icon: i.gIcon, size: 32)
                        .frame(maxWidth: .infinity, minHeight: 60)
                        .selectableTile(selection == i, radius: 16)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(i.title))
                .accessibilityIdentifier("icon.\(i.rawValue)")
            }
        }
    }
}

/// Manage favourites: tap to edit, swipe to delete, drag to reorder, promote to Home/Work.
struct FavoritesManagerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var editing: SavedPlace?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    row(model.home, kind: .home)
                    row(model.work, kind: .work)
                }
                Section("Favourites") {
                    if model.favorites.isEmpty {
                        Text("Save places from a place card with the Save button.").foregroundStyle(Color.textMuted)
                    }
                    ForEach(model.favorites) { f in
                        Button { editing = f } label: {
                            HStack(spacing: 12) {
                                GimanoIcon(icon: model.icon(for: f).gIcon, size: 30)
                                    .frame(width: 48, height: 48)
                                    .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(f.name).font(Tokens.Font.bodyBold)
                                    Text(f.address).font(Tokens.Font.caption).foregroundStyle(Color.textMuted).lineLimit(1)
                                }
                            }
                            .foregroundStyle(Color.textPrimary)
                        }
                        .accessibilityIdentifier("favoriteRow.\(f.name)")
                        .swipeActions(edge: .leading) {
                            Button("Home") { model.promote(f, to: .home) }.tint(.brand)
                            Button("Work") { model.promote(f, to: .work) }.tint(.brandInk)
                        }
                    }
                    .onDelete { idx in for i in idx { model.deleteFavorite(model.favorites[i]) } }
                    .onMove { model.moveFavorites(from: $0, to: $1) }
                }
                Section {
                    Button {
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { model.startPinPick(.favorite) }
                    } label: {
                        Label("Choose on map", systemImage: "map")
                    }
                    .accessibilityIdentifier("favoriteChooseOnMap")
                } footer: {
                    Text("Put the pin exactly at the gate. Saved on this phone only.")
                }
            }
            .navigationTitle("Favourites")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { EditButton() }
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .sheet(item: $editing) { f in EditFavoriteSheet(place: f) }
        }
    }

    @ViewBuilder private func row(_ place: SavedPlace?, kind: SavedPlaceKind) -> some View {
        HStack(spacing: 12) {
            GimanoIcon(icon: kind == .home ? .home : .work, size: 30)
                .frame(width: 48, height: 48)
                .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(kind == .home ? "Home" : "Work").font(Tokens.Font.bodyBold)
                Text(place?.address ?? String(localized: "Set location")).font(Tokens.Font.caption).foregroundStyle(Color.textMuted).lineLimit(1)
            }
        }
    }
}

struct EditFavoriteSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let place: SavedPlace
    @State private var name = ""
    @State private var icon: FavoriteIcon = .star

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    TextField("Label", text: $name)
                        .font(Tokens.Font.nunito(19, .bold))
                        .padding(.horizontal, 16)
                        .frame(height: 56)
                        .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.searchField, style: .continuous))
                        .accessibilityIdentifier("editFavoriteLabel")
                    IconPicker(selection: $icon)
                    HStack(spacing: 10) {
                        Button("Set as Home") { model.promote(place, to: .home); dismiss() }.buttonStyle(SecondaryButtonStyle())
                        Button("Set as Work") { model.promote(place, to: .work); dismiss() }.buttonStyle(SecondaryButtonStyle())
                    }
                    Button("Save") {
                        model.updateFavorite(place, name: name, icon: icon)
                        dismiss()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    Button("Delete", role: .destructive) {
                        model.deleteFavorite(place)
                        dismiss()
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(Tokens.Space.gutter)
            }
            .navigationTitle(place.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
            .onAppear {
                name = place.name
                icon = model.icon(for: place)
            }
        }
    }
}

/// Home-screen row of favourite tiles (76pt, big icon, one-word label).
struct FavoritesRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if !model.favorites.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Tokens.Space.gridGap) {
                    ForEach(model.favorites) { f in
                        Button { model.navigate(to: f) } label: {
                            VStack(spacing: 6) {
                                GimanoIcon(icon: model.icon(for: f).gIcon, size: Tokens.Size.shortcutIcon)
                                    .frame(width: Tokens.Size.shortcutTile, height: Tokens.Size.shortcutTile)
                                    .background(Color.brandMist, in: RoundedRectangle(cornerRadius: Tokens.Radius.shortcutTile, style: .continuous))
                                Text(f.name)
                                    .font(Tokens.Font.nunito(13, .extrabold))
                                    .foregroundStyle(Color.textPrimary)
                                    .lineLimit(1)
                                    .frame(width: Tokens.Size.shortcutTile + 8)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text(f.name))
                        .accessibilityIdentifier("favoriteTile.\(f.name)")
                    }
                    Button { model.menuSheet = .favorites } label: {
                        VStack(spacing: 6) {
                            Image(systemName: "slider.horizontal.3")
                                .font(.system(size: 26, weight: .bold))
                                .foregroundStyle(Color.brandInk)
                                .frame(width: Tokens.Size.shortcutTile, height: Tokens.Size.shortcutTile)
                                .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.shortcutTile, style: .continuous))
                            Text("Edit").font(Tokens.Font.nunito(13, .extrabold)).foregroundStyle(Color.textPrimary)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Edit favourites"))
                    .accessibilityIdentifier("editFavorites")
                }
                .padding(.horizontal, Tokens.Space.gutter)
            }
        }
    }
}

extension FavoriteIcon {
    /// VoiceOver name (Kampung / Rumah Mertua stay in Malay in both languages).
    var title: LocalizedStringKey {
        switch self {
        case .home: "Home"
        case .work: "Work"
        case .kampung: "Kampung"
        case .mertua: "Rumah Mertua"
        case .star: "Star"
        case .heart: "Heart"
        case .school: "School"
        case .surau: "Surau"
        case .food: "Food"
        case .gym: "Gym"
        case .shop: "Shop"
        case .hospital: "Hospital"
        }
    }
}
