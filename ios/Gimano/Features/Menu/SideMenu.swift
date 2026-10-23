import SwiftUI

struct SideMenu: View {
    @Environment(AppModel.self) private var model
    let onSelect: (MenuDestination) -> Void

    var body: some View {
        ZStack(alignment: .leading) {
            Color.black.opacity(0.35)
                .ignoresSafeArea()
                .onTapGesture { model.showMenu = false }

            VStack(alignment: .leading, spacing: 0) {
                Button { onSelect(.profile) } label: {
                    HStack(spacing: 14) {
                        Text(model.user?.mood ?? "🙂")
                            .font(.system(size: 30))
                            .frame(width: 56, height: 56)
                            .background(Color.brand.opacity(0.15), in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Hi, \(model.user?.displayName ?? String(localized: "driver"))")
                                .font(Tokens.Font.title)
                                .lineLimit(1)
                            Text("View profile").font(Tokens.Font.caption).foregroundStyle(Color.brand)
                        }
                    }
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 24)

                Divider()

                actionRow("Favourites", icon: "star.fill", id: "menuFavorites") { model.showMenu = false; model.menuSheet = .favorites }
                actionRow("Add a place", icon: "mappin.and.ellipse", id: "menuAddPlace") { model.showMenu = false; model.addPlaceStart = model.position }
                actionRow("My contributions", icon: "person.crop.rectangle.stack.fill", id: "menuContributions") { model.showMenu = false; model.menuSheet = .contributions }
                row("Planned drives", icon: "calendar", .planned)
                row("Inbox", icon: "tray.fill", .inbox)
                row("Settings", icon: "gearshape.fill", .settings)
                actionRow("Add feedback", icon: "bubble.left.and.text.bubble.right.fill", id: "menuAddFeedback") {
                    model.showMenu = false
                    Task {
                        // Let the menu close so the screenshot shows the map, not the menu.
                        try? await Task.sleep(for: .milliseconds(450))
                        model.openFeedback(from: .home)
                    }
                }
                actionRow("My feedback", icon: "tray.full.fill", id: "menuMyFeedback") { model.showMenu = false; model.menuSheet = .myFeedback }
                actionRow("Backup & restore", icon: "arrow.clockwise.icloud.fill", id: "menuBackup") { model.showMenu = false; model.menuSheet = .backup }
                row("Help and feedback", icon: "questionmark.circle.fill", .help)
                row("Developer settings", icon: "hammer.fill", .debug)

                Spacer()
                VStack(alignment: .leading, spacing: 4) {
                    Text("Gimano \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")")
                    Text("Open source (MIT) · Map data © OpenStreetMap contributors")
                }
                .font(Tokens.Font.nunito(11, .semibold))
                .foregroundStyle(Color.textMuted)
                .padding(20)
            }
            .foregroundStyle(Color.textPrimary)
            .frame(width: 300)
            .frame(maxHeight: .infinity)
            .background(Color.surface.ignoresSafeArea())
        }
    }

    private func actionRow(_ title: LocalizedStringKey, icon: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon).frame(width: 24).foregroundStyle(Color.textMuted)
                Text(title).font(Tokens.Font.body)
                Spacer()
            }
            .padding(.horizontal, 20)
            .frame(height: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }

    private func row(_ title: LocalizedStringKey, icon: String, _ dest: MenuDestination) -> some View {
        Button { onSelect(dest) } label: {
            HStack(spacing: 16) {
                Image(systemName: icon).frame(width: 24).foregroundStyle(Color.textMuted)
                Text(title).font(Tokens.Font.body)
                Spacer()
            }
            .padding(.horizontal, 20)
            .frame(height: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct MenuDestinationView: View {
    let destination: MenuDestination
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch destination {
                case .profile: ProfileView()
                case .planned: PlannedDrivesView()
                case .inbox:
                    ContentUnavailableView("Inbox is empty", systemImage: "tray",
                                           description: Text("Thanks from other drivers and report updates will appear here."))
                case .settings: SettingsView()
                case .help: HelpView()
                case .debug: DebugSettingsView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }
}

struct ProfileView: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    private let moods = ["🙂", "😎", "🤠", "😇", "😴", "🥳", "🤓", "😤"]

    var body: some View {
        Form {
            Section("Display name") {
                TextField("Name", text: $name)
                    .onSubmit { model.updateProfile(UserPatch(displayName: name)) }
            }
            Section("Mood") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(moods, id: \.self) { m in
                            Button { model.updateProfile(UserPatch(mood: m)) } label: {
                                Text(m).font(.system(size: 30))
                                    .padding(6)
                                    .background(model.user?.mood == m ? Color.brand.opacity(0.2) : .clear, in: Circle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Section("Vehicle") {
                Picker("Vehicle", selection: Binding(get: { model.vehicle }, set: { model.updateProfile(UserPatch(vehicle: $0)) })) {
                    Text("Car").tag(Vehicle.car)
                    Text("Motorbike").tag(Vehicle.motorcycle)
                }
                .pickerStyle(.segmented)
            }
            if let u = model.user {
                Section("Stats") {
                    LabeledContent("Points", value: "\(u.points ?? 0)")
                    LabeledContent("Level", value: "\(u.level ?? 1)")
                }
            }
        }
        .navigationTitle("Profile")
        .onAppear { name = model.user?.displayName ?? "" }
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                PrivacyCard().listRowInsets(EdgeInsets())
                NavigationLink("Backup & restore") { BackupView() }.accessibilityIdentifier("settingsBackup")
            }
            Section("Theme") {
                ThemePicker()
            }
            Section("Sound") {
                Picker("Voice guidance", selection: $model.soundMode) {
                    ForEach(SoundMode.allCases, id: \.self) { Text($0.title).tag($0) }
                }
            }
            Section("Language") {
                Button("Change app language (English / Bahasa Melayu)") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
            }
            Section("Units") {
                LabeledContent("Distance", value: "km")
                LabeledContent("Speed", value: "km/h")
            }
            Section {
                NavigationLink("Developer settings") { DebugSettingsView() }
            }
        }
        .navigationTitle("Settings")
    }
}

struct HelpView: View {
    @Environment(\.openURL) private var openURL
    var body: some View {
        List {
            Section("About") {
                Text("Gimano is an open-source, community-driven navigation app for Malaysia, built on OpenStreetMap data.")
            }
            Section("Attribution") {
                Button("© OpenStreetMap contributors (ODbL)") { openURL(URL(string: "https://www.openstreetmap.org/copyright")!) }
                Button("Map tiles: OpenFreeMap") { openURL(URL(string: "https://openfreemap.org")!) }
                Button("Map renderer: MapLibre Native") { openURL(URL(string: "https://maplibre.org")!) }
            }
        }
        .navigationTitle("Help and feedback")
    }
}

struct OnboardingView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "location.north.circle.fill")
                .font(.system(size: 96))
                .foregroundStyle(Color.brandOn, Color.brand)
            Text("Welcome to Gimano").font(Tokens.Font.nunito(30, .extrabold))
            Text("Drive smarter with reports from drivers around you. Gimano needs your location to show where you are and guide you turn by turn.")
                .font(Tokens.Font.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(Color.textMuted)
                .padding(.horizontal, 32)
            PrivacyCard(onBackup: {
                model.finishOnboarding(allowLocation: false)
                model.menuSheet = .backup
            })
            .padding(.horizontal, 24)
            Spacer()
            VStack(spacing: 12) {
                Button("Continue") { model.finishOnboarding(allowLocation: true) }
                    .buttonStyle(PrimaryButtonStyle())
                Button("Not now") { model.finishOnboarding(allowLocation: false) }
                    .buttonStyle(SecondaryButtonStyle())
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .background(Color.surface.ignoresSafeArea())
    }
}

/// Big icon tiles: Light (default) · Dark · Follow phone · Auto day/night.
struct ThemePicker: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 2), spacing: 8) {
            ForEach(ThemeMode.allCases) { t in
                let on = model.theme == t
                Button { model.theme = t } label: {
                    HStack(spacing: 8) {
                        Image(systemName: t.systemImage).font(.system(size: 20, weight: .semibold))
                        Text(t.title).font(Tokens.Font.label).lineLimit(1).minimumScaleFactor(0.8)
                        Spacer(minLength: 0)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .foregroundStyle(on ? Color.brandOn : Color.brandInk)
                    .background(on ? Color.brand : Color.brandMist, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("theme.\(t.rawValue)")
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .listRowBackground(Color.clear)
    }
}
