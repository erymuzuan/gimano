import SwiftUI

/// Driving screen: dark turn banner + "then" strip, speedometer, report button, ETA bottom bar that
/// expands (Add a stop · Share drive · Stop), "Still there? / Not there" alert cards.
struct DriveView: View {
    @Environment(AppModel.self) private var model
    @State private var expanded = false
    @State private var showAlternatives = false

    var body: some View {
        if let nav = model.navigation {
            ZStack(alignment: .bottom) {
                VStack(spacing: 0) {
                    TurnBanner(nav: nav)
                    HStack {
                        Spacer()
                        VStack(spacing: 12) {
                            SoundFab()
                            if !model.map.isFollowing {
                                RoundFab(icon: .logo, accessibilityLabel: "Recenter", stroke: .brand, fill: .brand) { model.recenter() }
                            }
                        }
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    .padding(.top, 12)
                    Spacer()
                }

                VStack(spacing: 12) {
                    if let alert = nav.approachingAlert {
                        AlertCard(report: alert.report, distanceM: nav.distanceToAlert(alert),
                                  onStillThere: { nav.vote(stillThere: true) },
                                  onNotThere: { nav.vote(stillThere: false) },
                                  onClose: { nav.dismissAlert() })
                            .padding(.horizontal, Tokens.Space.gutter)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    HStack(alignment: .bottom) {
                        Speedometer(speedKmh: nav.speedKmh)
                        Spacer()
                        ReportFab { model.showReport = true }
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    HStack {
                        OSMAttribution()
                        Spacer()
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    .padding(.top, -6)
                    BottomBar(nav: nav, expanded: $expanded, showAlternatives: $showAlternatives)
                }
                .animation(.snappy, value: nav.approachingAlert?.id)

                if nav.arrived {
                    ArrivedCard(destination: nav.destination) { model.stopNavigation() }
                        .transition(.move(edge: .bottom))
                }
            }
            .sheet(isPresented: $showAlternatives) {
                DriveAlternativesSheet(nav: nav)
                    .presentationDetents([.medium])
            }
        }
    }
}

struct TurnBanner: View {
    @Environment(AppModel.self) private var model
    let nav: NavigationSession

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 16) {
                if nav.isRerouting {
                    ProgressView().tint(.white).frame(width: 56, height: 56)
                    Text("Recalculating…").font(Tokens.Font.title).foregroundStyle(Color.navBannerOn)
                } else if let m = nav.upcoming {
                    Image(systemName: ManeuverFormatter.symbol(for: m.type))
                        .font(.system(size: 46, weight: .heavy))
                        .foregroundStyle(Color.navBannerOn)
                        .frame(width: 60, height: 60)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ManeuverFormatter.distance(nav.distanceToUpcoming))
                            .font(Tokens.Font.navDistance)
                            .monospacedDigit()
                            .foregroundStyle(Color.navBannerOn)
                            .accessibilityIdentifier("maneuverDistance")
                        Text(ManeuverFormatter.roadName(m) ?? ManeuverFormatter.instruction(for: m))
                            .font(Tokens.Font.nunito(20, .bold))
                            .foregroundStyle(Color.navBannerOn.opacity(0.9))
                            .lineLimit(2)
                            .minimumScaleFactor(0.8)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(Color.navBanner, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous))
            .accessibilityElement(children: .combine)
            .accessibilityLabel(Text(nav.upcoming.map { "\(ManeuverFormatter.distance(nav.distanceToUpcoming)), \(ManeuverFormatter.instruction(for: $0))" } ?? ""))

            if let then = nav.then, !nav.isRerouting {
                HStack(spacing: 8) {
                    Text("then").font(Tokens.Font.nunito(15, .bold))
                    Image(systemName: ManeuverFormatter.symbol(for: then.type)).font(.system(size: 18, weight: .bold))
                }
                .foregroundStyle(Color.navBannerOn)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.navBanner.opacity(0.88), in: UnevenRoundedRectangle(bottomLeadingRadius: 14, bottomTrailingRadius: 14))
                .padding(.leading, 18)
            }
        }
        .padding(.horizontal, 10)
        .padding(.top, 4)
        .shadow(color: .black.opacity(0.25), radius: 6, y: 2)
    }
}

struct BottomBar: View {
    @Environment(AppModel.self) private var model
    let nav: NavigationSession
    @Binding var expanded: Bool
    @Binding var showAlternatives: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { model.screen = .search(.addStop) } label: {
                    GimanoIcon(icon: .search, size: 28)
                        .frame(width: 56, height: 56)
                        .background(Color.brandMist, in: Circle())
                }
                .accessibilityLabel(Text("Add a stop"))
                Spacer()
                Button { withAnimation(.snappy) { expanded.toggle() } } label: {
                    VStack(spacing: 0) {
                        Text(ETAFormatter.arrivalTime(remainingS: nav.remainingS))
                            .font(Tokens.Font.etaClock)
                            .monospacedDigit()
                            .accessibilityIdentifier("etaClock")
                        Text(ETAFormatter.tripSummary(remainingS: nav.remainingS, remainingM: nav.remainingM))
                            .font(Tokens.Font.caption)
                            .foregroundStyle(Color.textMuted)
                    }
                }
                .buttonStyle(.plain)
                Spacer()
                Button { showAlternatives = true } label: {
                    Image(systemName: "arrow.triangle.branch")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(Color.brandInk)
                        .frame(width: 56, height: 56)
                        .background(Color.brandMist, in: Circle())
                }
                .accessibilityLabel(Text("Alternative routes"))
            }
            .foregroundStyle(Color.textPrimary)
            .padding(.horizontal, Tokens.Space.gutter)
            .padding(.top, 10)
            .padding(.bottom, expanded ? 8 : 4)

            if expanded {
                VStack(alignment: .leading, spacing: 12) {
                    Divider()
                    VStack(alignment: .leading, spacing: 2) {
                        Text(nav.destination.name).font(Tokens.Font.bodyBold)
                        Text(nav.route.summary).font(Tokens.Font.caption).foregroundStyle(Color.textMuted).lineLimit(1)
                    }
                    HStack(spacing: 10) {
                        Button { expanded = false; model.screen = .search(.addStop) } label: {
                            Label("Add a stop", systemImage: "plus.circle.fill")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                        ShareLink(item: shareText) {
                            Label("Share drive", systemImage: "square.and.arrow.up")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }
                    HStack(spacing: 10) {
                        Button { expanded = false } label: { Text("Continue") }
                            .buttonStyle(SecondaryButtonStyle())
                        Button { expanded = false; model.stopNavigation() } label: { Text("Stop") }
                            .buttonStyle(PrimaryButtonStyle(color: .speeding, foreground: .white))
                            .accessibilityIdentifier("stopNavigation")
                    }
                }
                .padding(.horizontal, Tokens.Space.gutter)
                .padding(.bottom, 8)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(
            UnevenRoundedRectangle(topLeadingRadius: Tokens.Radius.sheetTop, topTrailingRadius: Tokens.Radius.sheetTop)
                .fill(Color.surface)
                .shadow(color: .black.opacity(0.15), radius: 10, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private var shareText: String {
        let eta = ETAFormatter.arrivalTime(remainingS: nav.remainingS)
        return String(localized: "I'm driving to \(nav.destination.name) with Gimano. ETA \(eta).")
    }
}

/// "Still there? / Not there" card for an alert ahead or one tapped on the map.
struct AlertCard: View {
    let report: Report
    var distanceM: Double?
    let onStillThere: () -> Void
    let onNotThere: () -> Void
    var onClose: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                GimanoIcon(icon: report.type.gIcon, size: 34, stroke: report.type.strokeColor, fill: report.type.fillColor)
                    .frame(width: 56, height: 56)
                    .background(report.type.tileColor, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Text(report.type.title).font(Tokens.Font.bodyBold)
                        if let sub = report.type.subtypeTitle(report.subtype) {
                            Text("· \(String(localized: sub))").font(Tokens.Font.body)
                        }
                    }
                    HStack(spacing: 4) {
                        if let distanceM { Text("\(ManeuverFormatter.distance(distanceM)) ahead"); Text("·") }
                        if let street = report.street { Text(street).lineLimit(1) }
                    }
                    .font(Tokens.Font.caption)
                    .foregroundStyle(Color.textMuted)
                    Text("\(report.reportedBy?.displayName ?? String(localized: "A driver")) · \(report.createdAt.formatted(.relative(presentation: .named)))")
                        .font(Tokens.Font.nunito(12, .semibold))
                        .foregroundStyle(Color.textMuted)
                }
                Spacer()
                if let onClose {
                    Button(action: onClose) {
                        Image(systemName: "xmark").font(.system(size: 14, weight: .bold)).foregroundStyle(Color.textMuted)
                    }
                    .accessibilityLabel(Text("Close"))
                }
            }
            Text("Still there?").font(Tokens.Font.label).foregroundStyle(Color.textMuted)
            HStack(spacing: 10) {
                Button(action: onStillThere) { Label("Still there", systemImage: "hand.thumbsup.fill") }
                    .buttonStyle(PrimaryButtonStyle())
                Button(action: onNotThere) { Label("Not there", systemImage: "hand.thumbsdown.fill") }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
        .foregroundStyle(Color.textPrimary)
        .padding(Tokens.Space.gutter)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous))
        .fabShadow()
        .accessibilityIdentifier("alertCard")
    }
}

struct AlertDetailSheet: View {
    @Environment(AppModel.self) private var model
    let report: Report

    var body: some View {
        VStack(spacing: 12) {
            AlertCard(report: report, distanceM: Geo.distance(model.position, report.location),
                      onStillThere: { model.vote(report, stillThere: true) },
                      onNotThere: { model.vote(report, stillThere: false) })
            HStack {
                Label("\(report.confirmations)", systemImage: "checkmark.seal.fill")
                Spacer()
                Button { model.thank(report) } label: { Label("Thanks \(report.thanks)", systemImage: "heart.fill") }
            }
            .font(Tokens.Font.label)
            .foregroundStyle(Color.textMuted)
            .padding(.horizontal, 8)
        }
        .padding(Tokens.Space.gutter)
    }
}

struct ArrivedCard: View {
    let destination: Destination
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "flag.checkered.circle.fill").font(.system(size: 54)).foregroundStyle(Color.brand)
            Text("You've arrived").font(Tokens.Font.title)
            Text(destination.name).font(Tokens.Font.body).foregroundStyle(Color.textMuted)
            Button("Done", action: onDone).buttonStyle(PrimaryButtonStyle())
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: Tokens.Radius.sheetTop, topTrailingRadius: Tokens.Radius.sheetTop)
                .fill(Color.surface)
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// Alternate routes while driving.
struct DriveAlternativesSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let nav: NavigationSession
    @State private var routes: [Route] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            List {
                if loading { ProgressView() }
                ForEach(routes) { r in
                    Button {
                        nav.setRoute(r)
                        dismiss()
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(r.summary).font(Tokens.Font.bodyBold).lineLimit(1)
                                Text(r.trafficDescription ?? "").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                            }
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text(ETAFormatter.duration(r.effectiveDurationS)).font(Tokens.Font.bodyBold)
                                Text(ManeuverFormatter.distance(r.distanceM)).font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                            }
                        }
                        .foregroundStyle(Color.textPrimary)
                    }
                }
            }
            .navigationTitle("Alternative routes")
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            let req = RouteRequest(origin: RouteEndpoint(lat: model.position.lat, lng: model.position.lng),
                                   destination: RouteEndpoint(lat: nav.destination.location.lat, lng: nav.destination.location.lng, placeId: nav.destination.placeId),
                                   waypoints: nav.waypoints, vehicle: nav.vehicle, avoid: nav.avoid, alternatives: 2)
            routes = (try? await model.repo.routes(req)) ?? []
            loading = false
        }
    }
}
