import SwiftUI

import GoogleSignIn

import UserNotifications

/// Handles taps on planned-drive notifications.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    @MainActor static var onOpenPlannedDrive: ((String) -> Void)?
    @MainActor static var pendingDriveId: String?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        CrashReporter.shared.start()
        return true
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let id = response.notification.request.content.userInfo["plannedDriveId"] as? String else { return }
        await MainActor.run {
            if let open = AppDelegate.onOpenPlannedDrive { open(id) } else { AppDelegate.pendingDriveId = id }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}

@main
struct GimanoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AppModel

    init() {
        let model = AppModel()
        _model = State(initialValue: model)
        // Daily automatic backup (BGProcessingTask), registered before launch completes.
        BackupManager.registerBackgroundTask { bgTask in
            // BGTask isn't Sendable; it is only completed once, from the main actor.
            nonisolated(unsafe) let task = bgTask
            let work = Task { @MainActor in
                let ok = await model.backup.runAutoBackup()
                task.setTaskCompleted(success: ok)
            }
            task.expirationHandler = { work.cancel() }
        }
        if model.backup.autoBackup { BackupManager.schedule() }
    }

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(.brand)
                .font(Tokens.Font.body)
                .task {
                    AppDelegate.onOpenPlannedDrive = { [model] id in model.openPlannedDrive(id: id) }
                    await model.bootstrap()
                    if let id = AppDelegate.pendingDriveId { AppDelegate.pendingDriveId = nil; model.openPlannedDrive(id: id) }
                }
                .onChange(of: scenePhase) { _, phase in model.isForeground = phase == .active }
                .onOpenURL { url in
                    if GIDSignIn.sharedInstance.handle(url) { return }
                    // A .manotu (or older .gimano) backup opened from Files / WhatsApp / Mail.
                    if ["manotu", "gimano"].contains(url.pathExtension.lowercased()) { model.incomingBackup = url }
                }
        }
    }
}

enum MenuDestination: String, Identifiable {
    case profile, planned, inbox, settings, help, debug
    var id: String { rawValue }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var menuDestination: MenuDestination?

    var body: some View {
        @Bindable var model = model
        ZStack {
            GimanoMapView(state: model.map)
                .ignoresSafeArea()

            Group {
                switch model.screen {
                case .home:
                    HomeView()
                case .search(let purpose):
                    SearchView(purpose: purpose)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                case .place(let p):
                    PlaceCardView(summary: p)
                        .id(p.id)
                case .routePreview:
                    RoutePreviewView()
                case .navigating:
                    DriveView()
                }
            }
            .animation(.snappy(duration: 0.25), value: model.screen)

            if model.showMenu {
                SideMenu { dest in
                    model.showMenu = false
                    menuDestination = dest
                }
                .transition(.move(edge: .leading))
                .zIndex(10)
            }

            VStack {
                if model.isDemo && model.screen == .home && model.homeDetent != .full {
                    DemoBadge()
                        .padding(.top, 4)
                }
                Spacer()
                if let toast = model.toast {
                    Toast(text: toast)
                        .padding(.bottom, 180)
                        .transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
            }
            .allowsHitTesting(false)
            .animation(.easeInOut, value: model.toast)
            .zIndex(20)
        }
        .animation(.snappy(duration: 0.25), value: model.showMenu)
        .sheet(isPresented: $model.showReport) {
            ReportSheet()
                .presentationDetents([.fraction(0.62), .large])
                .presentationCornerRadius(Tokens.Radius.sheetTop)
        }
        .sheet(item: $model.selectedAlert) { alert in
            AlertDetailSheet(report: alert)
                .presentationDetents([.height(300)])
                .presentationCornerRadius(Tokens.Radius.sheetTop)
        }
        .sheet(item: $menuDestination) { dest in
            MenuDestinationView(destination: dest)
        }
        .sheet(item: $model.editingDrive) { d in
            PlannedDriveEditor(drive: d)
        }
        .sheet(item: $model.savingPlace) { p in
            SavePlaceSheet(place: p).presentationDetents([.large])
        }
        .sheet(isPresented: $model.showCrashPrompt) { CrashReportSheet() }
        .sheet(item: $model.deviationPrompt) { d in DeviationPromptSheet(event: d).presentationDetents([.medium]) }
        .sheet(item: $model.vettingPrompt) { p in
            VettingPromptSheet(place: p).presentationDetents([.medium, .large])
        }
        .sheet(item: $model.feedbackDraft) { d in
            FeedbackForm(draft: d)
        }
        .sheet(item: $model.menuSheet) { s in
            switch s {
            case .favorites: FavoritesManagerView()
            case .myFeedback: MyFeedbackView()
            case .backup: NavigationStack { BackupView().toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { model.menuSheet = nil } } } }
            case .contributions: MyContributionsView()
            }
        }
        .sheet(item: Binding(get: { model.incomingBackup.map { IncomingFile(url: $0) } }, set: { model.incomingBackup = $0?.url })) { f in
            RestoreSheet(pending: BackupView.PendingRestore(file: f.url, manifest: model.backup.manifest(of: f.url))) {
                model.showToast(String(localized: "Restored"))
            }
        }
        .alert("Are you a passenger?", isPresented: $model.askPassengerForFeedback) {
            Button("I'm a passenger") { model.openFeedback(from: .navigation, passengerConfirmed: true) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("For safety, drivers can send feedback only when stopped.")
        }
        .fullScreenCover(item: Binding(get: { model.addPlaceStart.map(IdentifiedPoint.init) }, set: { model.addPlaceStart = $0?.point })) { p in
            AddPlaceFlow(start: p.point)
        }
        // Long-press on the home map → the pin's actions directly.
        .onChange(of: model.longPressPoint) { _, p in
            if let p { model.pinActions = PickedPoint(location: p); model.longPressPoint = nil }
        }
        .fullScreenCover(item: $model.pinPick) { req in
            SelectOnMapView(request: req) { picked in
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { model.handlePicked(picked, purpose: req.purpose) }
            }
        }
        .sheet(item: $model.pinActions) { p in
            PinActionsSheet(point: p).presentationDetents([.height(380), .medium])
        }
        .fullScreenCover(isPresented: $model.showOnboarding) {
            OnboardingView()
        }
        .preferredColorScheme(model.preferredScheme)
        .onAppear { model.schemeChanged(colorScheme) }
        .onChange(of: colorScheme) { _, s in model.schemeChanged(s) }
        .task {
            // Auto day/night: re-check every minute.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                if model.theme == .auto { model.themeClock = .now }
            }
        }
        .onChange(of: model.live.revision) { model.syncLiveLayers() }
        .onChange(of: model.map.visibleBBox) { _, bbox in
            if let bbox { model.live.viewportChanged(bbox) }
        }
    }
}

struct DemoBadge: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        Label(model.backendStatus == .demoForced ? "Demo mode" : "Demo mode — backend unreachable", systemImage: "play.circle.fill")
            .font(Tokens.Font.nunito(12, .bold))
            .foregroundStyle(Color.brandOn)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color.brand.opacity(0.92), in: Capsule())
            .accessibilityIdentifier("demoBadge")
    }
}


struct IncomingFile: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct IdentifiedPoint: Identifiable {
    let point: LatLng
    var id: String { "\(point.lat),\(point.lng)" }
}
