import SwiftUI

@main
struct GimanoApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(.brand)
                .font(Tokens.Font.body)
                .task { await model.bootstrap() }
        }
    }
}

enum MenuDestination: String, Identifiable {
    case profile, planned, inbox, settings, help, debug
    var id: String { rawValue }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
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
                if model.isDemo && model.screen == .home {
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
        .fullScreenCover(isPresented: $model.showOnboarding) {
            OnboardingView()
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

