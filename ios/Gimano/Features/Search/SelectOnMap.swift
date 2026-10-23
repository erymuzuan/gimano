import SwiftUI

/// Where a "Choose on map" was started from (decides what the chosen point becomes).
enum PinPurpose: String, Equatable, Sendable {
    case destination, addStop, favorite, setHome, setWork, setOrigin
}

struct PinPickRequest: Identifiable, Equatable {
    let id = UUID()
    var purpose: PinPurpose
    var start: LatLng
}

/// A point the user chose on the map (optionally snapped to a known place via "Is this …?").
struct PickedPoint: Identifiable, Hashable, Sendable {
    let id = UUID()
    var location: LatLng
    var address: String?
    /// Ready-made line from the server ("Near 18, Jalan SS 2/24, …"); shown as-is.
    var label: String?
    var placeName: String?
    var placeId: String?

    var title: String { placeName ?? String(localized: "Point on map") }

    var summary: PlaceSummary {
        PlaceSummary(id: placeId ?? "pin:\(location.lat),\(location.lng)", name: title, category: .other,
                     address: address ?? String(format: "%.5f, %.5f", location.lat, location.lng),
                     lat: location.lat, lng: location.lng)
    }

    /// For "Save": an unnamed pin starts with an empty name.
    var favoriteSummary: PlaceSummary {
        var s = summary
        if placeName == nil { s.name = "" }
        return s
    }

    var destination: Destination {
        Destination(name: title, address: address ?? "", location: location, placeId: placeId, isPin: placeId == nil)
    }

    /// Coordinate link that needs no account.
    var shareText: String {
        let link = "https://www.openstreetmap.org/?mlat=\(String(format: "%.6f", location.lat))&mlon=\(String(format: "%.6f", location.lng))#map=18/\(String(format: "%.6f", location.lat))/\(String(format: "%.6f", location.lng))"
        return [placeName ?? address, link].compactMap { $0 }.joined(separator: "\n")
    }
}

/// Board "Select on map": full-screen map, a fixed centre pin, the nearest address (debounced) and "Is this …?".
struct SelectOnMapView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let request: PinPickRequest
    let onPick: (PickedPoint) -> Void

    @State private var map: MapState?
    @State private var center: LatLng
    @State private var info: ReverseGeocode?
    @State private var looking = false

    init(request: PinPickRequest, onPick: @escaping (PickedPoint) -> Void) {
        self.request = request
        self.onPick = onPick
        _center = State(initialValue: request.start)
    }

    var body: some View {
        ZStack {
            if let map { GimanoMapView(state: map).ignoresSafeArea() }
            // The map moves; the pin stays in the centre.
            VStack(spacing: 0) {
                Image(systemName: "mappin")
                    .font(.system(size: 50, weight: .bold))
                    .foregroundStyle(Color.report)
                    .shadow(color: .black.opacity(0.25), radius: 4, y: 3)
                Ellipse().fill(Color.black.opacity(0.25)).frame(width: 16, height: 6)
                Color.clear.frame(height: 52)
            }
            .allowsHitTesting(false)
            .accessibilityIdentifier("selectCenterPin")

            VStack {
                topBar
                HStack {
                    Spacer()
                    Button {
                        map?.setCamera(.center(model.position, zoom: 17))
                    } label: {
                        Image(systemName: "location.fill").font(.system(size: 20, weight: .bold)).foregroundStyle(Color.brand)
                            .frame(width: 52, height: 52).background(Color.surface, in: Circle()).fabShadow()
                    }
                    .accessibilityLabel(Text("My location"))
                    .accessibilityIdentifier("selectRecenter")
                    .padding(.trailing, 12)
                }
                Spacer()
                bottomCard
            }
        }
        .onAppear {
            guard map == nil else { return }
            let m = MapState(styleURL: model.effectiveScheme == .dark ? model.config.darkStyleURL : model.config.styleURL)
            m.puck = model.position
            m.setCamera(.center(request.start, zoom: 17))
            map = m
        }
        .task(id: map?.visibleBBox) {
            guard let b = map?.visibleBBox else { return }
            center = LatLng(lat: (b.minLat + b.maxLat) / 2, lng: (b.minLng + b.maxLng) / 2)
            looking = true
            try? await Task.sleep(for: .milliseconds(450))      // after the map stops
            guard !Task.isCancelled else { return }
            let rg = try? await model.repo.reverseGeocode(center)
            guard !Task.isCancelled else { return }
            info = rg
            looking = false
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Button { dismiss() } label: {
                Image(systemName: "arrow.left").font(.system(size: 20, weight: .bold)).foregroundStyle(Color.brand)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(Text("Back"))
            .accessibilityIdentifier("selectBack")
            VStack(alignment: .leading, spacing: 1) {
                Text("Move the map to the place").font(Tokens.Font.bodyBold)
                Text("The pin stays in the centre · pinch to zoom").font(Tokens.Font.small).foregroundStyle(Color.textMuted)
            }
            Spacer()
        }
        .padding(.horizontal, 10)
        .frame(height: 60)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .fabShadow()
        .padding(.horizontal, 12)
        .padding(.top, 4)
    }

    /// Street address for saving; a POI name returned by older servers counts as "nearby".
    private var addressText: String? { info?.address?.isEmpty == false ? info?.address : nil }
    /// What the card shows: the server's `label` ("Dekat 18, Jalan SS 2/24, …"), else "Near <address>".
    private var labelText: String? {
        if let l = info?.label, !l.isEmpty { return l }
        return addressText.map { String(localized: "Near \($0)") }
    }

    /// The nearest known place within 50 m: `nearby[0]`, or (older servers) the reverse name when its point is that close.
    private var nearby: (id: String?, name: String, location: LatLng, distanceM: Double)? {
        if let p = info?.nearby?.min(by: { Geo.distance(center, $0.location) < Geo.distance(center, $1.location) }) {
            let d = Geo.distance(center, p.location)
            return d <= 50 ? (p.id, p.name, p.location, d) : nil
        }
        if let name = info?.name, let i = info {
            let loc = LatLng(lat: i.lat, lng: i.lng)
            let d = Geo.distance(center, loc)
            return d <= 50 ? (nil, name, loc, d) : nil
        }
        return nil
    }

    private var bottomCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Capsule().fill(Color.outline).frame(width: 40, height: 5).frame(maxWidth: .infinity)
            Text(labelText ?? (looking ? String(localized: "Finding the address…") : String(localized: "Point on map")))
                .font(Tokens.Font.nunito(18, .black))
                .lineLimit(2)
                .accessibilityIdentifier("selectAddress")
            Text("\(ManeuverFormatter.distance(Geo.distance(model.position, center))) from you")
                .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
            if let n = nearby {
                Button {
                    finish(PickedPoint(location: n.location, address: addressText, label: labelText, placeName: n.name, placeId: n.id))
                } label: {
                    Text("Is this \(n.name) (\(Int(n.distanceM.rounded())) m)?")
                        .font(Tokens.Font.nunito(13, .black)).foregroundStyle(Color.brandInk)
                        .padding(.horizontal, 12).frame(height: 34)
                        .background(Color.brandMist, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("selectIsThis")
            }
            Button("Choose this point") {
                finish(PickedPoint(location: center, address: addressText, label: labelText))
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("selectChoose")
        }
        .padding(16)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28).fill(Color.surface).ignoresSafeArea(edges: .bottom)
        )
    }

    private func finish(_ p: PickedPoint) {
        dismiss()
        onPick(p)
    }
}

/// Board "Pin actions": Navigate here / Save (on this phone) / Add a place / Share.
struct PinActionsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var point: PickedPoint

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(point.title).font(Tokens.Font.nunito(20, .black)).accessibilityIdentifier("pinTitle")
                Spacer()
                Text(ManeuverFormatter.distance(Geo.distance(model.position, point.location)))
                    .font(Tokens.Font.label).foregroundStyle(Color.textMuted)
            }
            Text(point.label ?? point.address.map { String(localized: "Near \($0)") } ?? String(format: "%.5f, %.5f", point.location.lat, point.location.lng))
                .font(Tokens.Font.caption).foregroundStyle(Color.textMuted)
                .accessibilityIdentifier("pinAddressLine")
            Button {
                dismiss()
                model.planRoute(to: point.destination)
            } label: { Label("Navigate here", systemImage: "location.north.fill") }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("pinNavigate")
            HStack(spacing: 10) {
                tile("Save", icon: .star, id: "pinSave") { dismiss(); model.savingPlace = point.favoriteSummary }
                tile("Add a place", icon: .addStop, id: "pinAddPlace") { dismiss(); model.addPlaceStart = point.location }
                ShareLink(item: point.shareText) { tileLabel("Share", system: "square.and.arrow.up") }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("pinShare")
            }
            Text("“Save” stays on your phone only. “Add a place” asks whether it's just for you, or shared and confirmed by people nearby.")
                .font(Tokens.Font.small).foregroundStyle(Color.textMuted)
        }
        .padding(Tokens.Space.gutter)
        .task {
            guard point.address == nil && point.label == nil else { return }
            if let rg = try? await model.repo.reverseGeocode(point.location) {
                point.address = rg.address ?? rg.name
                point.label = rg.label
            }
        }
    }

    private func tile(_ title: LocalizedStringKey, icon: GIcon, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                GimanoIcon(icon: icon, size: 30)
                Text(title).font(Tokens.Font.nunito(13, .black)).multilineTextAlignment(.center)
            }
            .foregroundStyle(Color.brandInk)
            .frame(maxWidth: .infinity, minHeight: 86)
            .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }

    private func tileLabel(_ title: LocalizedStringKey, system: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: system).font(.system(size: 26, weight: .bold)).foregroundStyle(Color.brand)
            Text(title).font(Tokens.Font.nunito(13, .black))
        }
        .foregroundStyle(Color.brandInk)
        .frame(maxWidth: .infinity, minHeight: 86)
        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
