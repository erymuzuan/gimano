import SwiftUI

/// Developer settings: API base URL, data mode (auto/live/demo), map style, drive simulation.
struct DebugSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var apiURL = ""
    @State private var styleURL = ""
    @State private var testing = false
    @State private var testResult: String?

    private let presets: [(String, String)] = [
        ("WS556 (Tailscale)", "http://100.101.150.9:5080/api/v1/"),
        ("localhost", "http://localhost:5080/api/v1/"),
    ]

    var body: some View {
        @Bindable var config = model.config
        Form {
            Section {
                TextField(model.config.bundleAPIBaseURL, text: $apiURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
                ForEach(presets, id: \.1) { name, url in
                    Button(name) { apiURL = url }
                }
                Button("Use build default") { apiURL = "" }
                LabeledContent("Effective", value: model.config.apiBaseURL.absoluteString)
                    .font(.caption)
            } header: {
                Text("API base URL")
            } footer: {
                Text("Build default comes from the GimanoAPIBaseURL Info.plist key. The iPhone must be on Tailscale to reach WS556.")
            }

            Section("Data") {
                Picker("Mode", selection: $config.dataMode) {
                    ForEach(DataMode.allCases) { Text($0.title).tag($0) }
                }
                LabeledContent("Backend", value: statusText)
                LabeledContent("Live hub", value: hubText)
                Button {
                    testing = true
                    Task {
                        let ok = await LiveRepository(baseURL: URL(string: apiURL.isEmpty ? model.config.bundleAPIBaseURL : apiURL) ?? model.config.apiBaseURL).health()
                        testResult = ok ? String(localized: "Reachable ✓") : String(localized: "Unreachable ✗")
                        testing = false
                    }
                } label: {
                    HStack {
                        Text("Test connection (/healthz)")
                        Spacer()
                        if testing { ProgressView() } else if let testResult { Text(testResult).foregroundStyle(Color.textMuted) }
                    }
                }
            }

            Section("Map") {
                TextField(model.config.bundleStyleURL, text: $styleURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.footnote, design: .monospaced))
            }

            Section("Simulation") {
                Toggle("Simulate drive (fake GPS)", isOn: $config.simulateDrive)
                VStack(alignment: .leading) {
                    Text("Speed-up ×\(Int(config.simulationSpeedup))")
                    Slider(value: $config.simulationSpeedup, in: 1...10, step: 1)
                }
            }

            Section("Device") {
                LabeledContent("Device ID", value: String(TokenStore.deviceId.prefix(8)) + "…")
                Button("Reset device token", role: .destructive) {
                    TokenStore.save(nil, for: model.config.apiBaseURL)
                    model.rebuildRepository()
                }
            }

            Section {
                Button("Apply") { apply() }
                    .bold()
            }
        }
        .navigationTitle("Developer settings")
        .onAppear {
            apiURL = model.config.apiBaseURLOverride
            styleURL = model.config.styleURLOverride
        }
        .onChange(of: model.config.dataMode) { model.rebuildRepository() }
    }

    private func apply() {
        model.config.apiBaseURLOverride = apiURL.trimmingCharacters(in: .whitespaces)
        model.config.styleURLOverride = styleURL.trimmingCharacters(in: .whitespaces)
        model.rebuildRepository()
        model.showToast(String(localized: "Settings applied"))
    }

    private var statusText: String {
        switch model.backendStatus {
        case .unknown: String(localized: "Unknown")
        case .online: String(localized: "Online")
        case .demoFallback: String(localized: "Unreachable — using demo data")
        case .demoForced: String(localized: "Demo data")
        }
    }

    private var hubText: String {
        switch model.live.hubStatus {
        case .connected: String(localized: "Connected")
        case .connecting: String(localized: "Connecting…")
        case .disconnected: String(localized: "Disconnected")
        }
    }
}
