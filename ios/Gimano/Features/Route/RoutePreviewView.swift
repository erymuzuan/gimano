import SwiftUI

/// Trip overview: header, 2–3 alternatives with duration bubbles + "Best", Avoid menu, car/motorbike tabs,
/// route card, "Leave later" and a countdown-filling "Go now" that auto-starts.
struct RoutePreviewView: View {
    @Environment(AppModel.self) private var model
    @State private var countdownFill: CGFloat = 0
    @State private var countdownActive = false
    @State private var countdownToken = 0
    @State private var showLeaveLater = false

    static let countdownSeconds: Double = 10

    var body: some View {
        if let planner = model.planner {
            ZStack {
                bubbles(planner)

                VStack(spacing: 10) {
                    header(planner)
                    HStack {
                        Spacer()
                        avoidMenu(planner)
                    }
                    .padding(.horizontal, Tokens.Space.gutter)
                    Spacer()
                    bottomCard(planner)
                }
            }
            .sheet(isPresented: $showLeaveLater) {
                LeaveLaterSheet(planner: planner) { date in
                    stopCountdown()
                    planner.departAt = date
                    Task { await model.fetchRoutes() }
                }
                .presentationDetents([.medium, .large])
            }
            .onChange(of: planner.isLoading) { _, loading in
                if !loading, planner.selected != nil, planner.departAt == nil { startCountdown() }
            }
            .task(id: countdownToken) {
                guard countdownActive else { return }
                try? await Task.sleep(for: .seconds(Self.countdownSeconds))
                guard !Task.isCancelled, countdownActive, model.screen == .routePreview else { return }
                model.startNavigation()
            }
        }
    }

    // MARK: countdown

    private func startCountdown() {
        countdownActive = true
        countdownFill = 0
        countdownToken += 1
        withAnimation(.linear(duration: Self.countdownSeconds)) { countdownFill = 1 }
    }

    private func stopCountdown() {
        countdownActive = false
        countdownToken += 1
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t) { countdownFill = 0 }
    }

    // MARK: pieces

    private func header(_ planner: RoutePlanner) -> some View {
        HStack(spacing: 12) {
            Button {
                stopCountdown()
                model.planner = nil
                model.go(.home)
            } label: {
                Image(systemName: "arrow.left")
                    .font(.system(size: 20, weight: .semibold))
                    .frame(width: 40, height: 40)
            }
            .accessibilityLabel(Text("Back"))
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Circle().fill(Color.brand).frame(width: 10, height: 10)
                    Text("Your location").font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                }
                HStack(spacing: 8) {
                    Image(systemName: "mappin.circle.fill").foregroundStyle(Color.report).font(.system(size: 12))
                    Text(planner.destination.name).font(Tokens.Font.bodyBold).lineLimit(1)
                }
            }
            Spacer()
        }
        .foregroundStyle(Color.textPrimary)
        .padding(12)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.lg, style: .continuous))
        .fabShadow()
        .padding(.horizontal, Tokens.Space.gutter)
        .padding(.top, 8)
    }

    private func avoidMenu(_ planner: RoutePlanner) -> some View {
        @Bindable var planner = planner
        let count = [planner.avoid.tolls, planner.avoid.highways, planner.avoid.ferries].filter { $0 }.count
        return Menu {
            Toggle("Avoid tolls", isOn: $planner.avoid.tolls)
            Toggle("Avoid highways", isOn: $planner.avoid.highways)
            Toggle("Avoid ferries", isOn: $planner.avoid.ferries)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "slider.horizontal.3")
                Text(count == 0 ? String(localized: "Avoid") : String(localized: "Avoid (\(count))"))
                Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold))
            }
            .font(Tokens.Font.label)
            .foregroundStyle(Color.textPrimary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.surface, in: Capsule())
            .fabShadow()
        }
        .onChange(of: planner.avoid) {
            stopCountdown()
            Task { await model.fetchRoutes() }
        }
    }

    private func bubbles(_ planner: RoutePlanner) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach(planner.routes) { r in
                if let pt = model.map.bubblePoints[r.id] {
                    let selected = r.id == planner.selected?.id
                    Button {
                        stopCountdown()
                        model.selectRoute(r.id)
                    } label: {
                        VStack(spacing: 0) {
                            if r.isBest {
                                Text("Best")
                                    .font(Tokens.Font.nunito(10, .black))
                                    .textCase(.uppercase)
                                    .foregroundStyle(selected ? Color.white.opacity(0.9) : Color.brand)
                            }
                            Text(ETAFormatter.duration(r.effectiveDurationS))
                                .font(Tokens.Font.nunito(15, .extrabold))
                        }
                        .foregroundStyle(selected ? Color.white : Color.textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(selected ? Color.routeActive : Color.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .fabShadow()
                    }
                    .buttonStyle(.plain)
                    .position(x: pt.x, y: pt.y - 22)
                    .accessibilityIdentifier("routeBubble")
                }
            }
        }
        .ignoresSafeArea()
    }

    private func bottomCard(_ planner: RoutePlanner) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            VehicleTabs(selection: Binding(get: { planner.vehicle }, set: { v in
                stopCountdown()
                planner.vehicle = v
                Task { await model.fetchRoutes() }
            }))

            if planner.isLoading && planner.routes.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }.frame(height: 90)
            } else if let error = planner.error, planner.routes.isEmpty {
                Text(error).foregroundStyle(Color.speeding).frame(maxWidth: .infinity, minHeight: 90)
            } else if let r = planner.selected {
                routeSummary(r, planner: planner)
            }

            HStack(spacing: 12) {
                Button {
                    stopCountdown()
                    showLeaveLater = true
                } label: {
                    Text(planner.departAt == nil ? String(localized: "Leave later") : planner.departAt!.formatted(date: .omitted, time: .shortened))
                }
                .buttonStyle(SecondaryButtonStyle())

                Button {
                    stopCountdown()
                    model.startNavigation()
                } label: {
                    Text("Go now")
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(alignment: .leading) {
                            // Fills left→right during the auto-start countdown.
                            GeometryReader { g in
                                Rectangle()
                                    .fill(Color.white.opacity(0.28))
                                    .frame(width: g.size.width * countdownFill)
                            }
                        }
                }
                .buttonStyle(PrimaryButtonStyle())
                .clipShape(RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
                .disabled(planner.selected == nil)
                .accessibilityIdentifier("goNow")
            }
        }
        .padding(Tokens.Space.gutter)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: Tokens.Radius.sheetTop, topTrailingRadius: Tokens.Radius.sheetTop)
                .fill(Color.surface)
                .shadow(color: .black.opacity(0.15), radius: 10, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private func routeSummary(_ r: Route, planner: RoutePlanner) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(ETAFormatter.duration(r.effectiveDurationS))
                    .font(Tokens.Font.nunito(32, .black))
                    .foregroundStyle(r.isBest ? Color.brand : Color.textPrimary)
                Text(ManeuverFormatter.distance(r.distanceM))
                    .font(Tokens.Font.body)
                    .foregroundStyle(Color.textMuted)
                Spacer()
                if r.hasTolls {
                    Label(r.tollEstimateMyr.map { String(format: "RM %.2f", $0) } ?? String(localized: "Toll"), systemImage: "dollarsign.circle.fill")
                        .font(Tokens.Font.label)
                        .foregroundStyle(Color.textMuted)
                }
            }
            Text(r.summary)
                .font(Tokens.Font.body)
                .foregroundStyle(Color.textPrimary)
                .lineLimit(1)
            HStack(spacing: 6) {
                if r.isBest { Text("Best route").fontWeight(.semibold).foregroundStyle(Color.brand); Text("·") }
                Text(r.trafficDescription ?? String(localized: "Typical traffic"))
                Text("·")
                Text("Arrive \(ETAFormatter.arrivalTime(now: planner.departAt ?? .now, remainingS: r.effectiveDurationS))")
            }
            .font(Tokens.Font.caption)
            .foregroundStyle(Color.textMuted)
            .lineLimit(1)
        }
    }
}

/// Car / motorbike segmented tabs.
struct VehicleTabs: View {
    @Binding var selection: Vehicle

    var body: some View {
        HStack(spacing: 4) {
            tab(.car, title: "Car", icon: "car.fill")
            tab(.motorcycle, title: "Motorbike", icon: "motorcycle")
        }
        .padding(4)
        .background(Color.surfaceAlt, in: Capsule())
    }

    private func tab(_ v: Vehicle, title: LocalizedStringKey, icon: String) -> some View {
        Button { selection = v } label: {
            Label(title, systemImage: icon)
                .font(Tokens.Font.label)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .foregroundStyle(selection == v ? Color.brandOn : Color.textPrimary)
                .background(selection == v ? Color.brand : Color.clear, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selection == v ? .isSelected : [])
    }
}

/// "Leave later" — departure-time slots from `GET /routes/departure-times`.
struct LeaveLaterSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let planner: RoutePlanner
    let onPick: (Date?) -> Void
    @State private var slots: [DepartureSlot] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        onPick(nil); dismiss()
                    } label: { Label("Leave now", systemImage: "bolt.fill") }
                }
                Section("Best time to leave") {
                    if loading { ProgressView() }
                    let maxD = slots.map(\.durationS).max() ?? 1
                    let minD = slots.map(\.durationS).min() ?? 0
                    ForEach(slots) { s in
                        Button {
                            onPick(s.departAt); dismiss()
                        } label: {
                            HStack {
                                Text(s.departAt.formatted(date: .omitted, time: .shortened))
                                    .font(Tokens.Font.body.monospacedDigit())
                                    .frame(width: 90, alignment: .leading)
                                GeometryReader { g in
                                    Capsule()
                                        .fill(s.durationS <= minD * 1.05 ? Color.brand : Color.routeAlt)
                                        .frame(width: max(12, g.size.width * s.durationS / maxD), height: 10)
                                        .frame(maxHeight: .infinity)
                                }
                                Text(ETAFormatter.duration(s.durationS))
                                    .font(Tokens.Font.caption.monospacedDigit())
                                    .foregroundStyle(Color.textMuted)
                                    .frame(width: 70, alignment: .trailing)
                            }
                            .foregroundStyle(Color.textPrimary)
                        }
                    }
                }
            }
            .navigationTitle("Leave later")
            .navigationBarTitleDisplayMode(.inline)
        }
        .task {
            slots = (try? await model.repo.departureTimes(origin: model.position, destination: planner.destination.location, date: .now)) ?? []
            loading = false
        }
    }
}
