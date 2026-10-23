import SwiftUI

/// Place card: name, category, open state, distance, address, Save · Call · Share, hours, amenities, Routes CTA.
struct PlaceCardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    let summary: PlaceSummary
    @State private var place: Place?
    @State private var showSaveOptions = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { model.go(.home) } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Color.textPrimary)
                        .frame(width: 48, height: 48)
                        .background(Color.surface, in: Circle())
                        .fabShadow()
                }
                .accessibilityLabel(Text("Back"))
                Spacer()
            }
            .padding(.horizontal, Tokens.Space.gutter)
            .padding(.top, 8)

            Spacer()

            VStack(alignment: .leading, spacing: 14) {
                GrabHandle().frame(maxWidth: .infinity)
                HStack(alignment: .top, spacing: 12) {
                    CategoryIcon(category: summary.category, size: 48)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(summary.name).font(Tokens.Font.title).foregroundStyle(Color.textPrimary)
                        HStack(spacing: 6) {
                            if let open = place?.openNow {
                                Text(open ? "Open now" : "Closed")
                                    .foregroundStyle(open ? Color.brand : Color.speeding)
                                    .fontWeight(.semibold)
                                Text("·")
                            }
                            Text(ManeuverFormatter.distance(Geo.distance(model.position, summary.location)))
                        }
                        .font(Tokens.Font.caption)
                        .foregroundStyle(Color.textMuted)
                        Text(summary.address)
                            .font(Tokens.Font.caption)
                            .foregroundStyle(Color.textMuted)
                            .lineLimit(2)
                    }
                }

                HStack(spacing: 0) {
                    action("Save", icon: "bookmark") { showSaveOptions = true }
                    action("Call", icon: "phone") {
                        if let phone = place?.phone?.filter({ $0.isNumber || $0 == "+" }), let url = URL(string: "tel:\(phone)") { openURL(url) }
                    }
                    .disabled(place?.phone == nil)
                    ShareLink(item: shareText) {
                        actionLabel("Share", icon: "square.and.arrow.up")
                    }
                    .buttonStyle(.plain)
                    if let site = place?.website, let url = URL(string: site) {
                        action("Website", icon: "safari") { openURL(url) }
                    }
                }

                if let hours = place?.openingHours {
                    Label(hours, systemImage: "clock")
                        .font(Tokens.Font.caption)
                        .foregroundStyle(Color.textPrimary)
                }
                if let amenities = place?.amenities, !amenities.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(amenities, id: \.self) { a in
                                Text(a.replacingOccurrences(of: "_", with: " ").capitalized)
                                    .font(Tokens.Font.nunito(12, .bold))
                                    .padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(Color.surfaceAlt, in: Capsule())
                            }
                        }
                    }
                }

                Button {
                    model.planRoute(to: Destination(name: summary.name, address: summary.address, location: summary.location,
                                                    placeId: summary.id.hasPrefix("pin:") ? nil : summary.id, category: summary.category))
                } label: {
                    Label("Routes", systemImage: "arrow.triangle.turn.up.right.diamond.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("routesButton")

                Text("Place data © OpenStreetMap contributors")
                    .font(Tokens.Font.nunito(11, .semibold))
                    .foregroundStyle(Color.textMuted)
                    .frame(maxWidth: .infinity)
            }
            .padding(Tokens.Space.gutter)
            .padding(.bottom, 8)
            .background(
                UnevenRoundedRectangle(topLeadingRadius: Tokens.Radius.sheetTop, topTrailingRadius: Tokens.Radius.sheetTop)
                    .fill(Color.surface)
                    .shadow(color: .black.opacity(0.15), radius: 10, y: -2)
                    .ignoresSafeArea(edges: .bottom)
            )
        }
        .confirmationDialog("Save place", isPresented: $showSaveOptions) {
            Button("Set as Home") { model.setSaved(kind: .home, place: summary) }
            Button("Set as Work") { model.setSaved(kind: .work, place: summary) }
            Button("Add to favourites") { model.setSaved(kind: .favorite, place: summary) }
        }
        .task {
            guard !summary.id.hasPrefix("pin:") else { return }
            place = try? await model.repo.place(id: summary.id)
        }
    }

    private var shareText: String {
        "\(summary.name)\n\(summary.address)\nhttps://www.openstreetmap.org/?mlat=\(summary.lat)&mlon=\(summary.lng)#map=17/\(summary.lat)/\(summary.lng)"
    }

    private func action(_ title: LocalizedStringKey, icon: String, perform: @escaping () -> Void) -> some View {
        Button(action: perform) { actionLabel(title, icon: icon) }
            .buttonStyle(.plain)
    }

    private func actionLabel(_ title: LocalizedStringKey, icon: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(Color.surfaceAlt, in: Circle())
            Text(title).font(Tokens.Font.nunito(12, .bold))
        }
        .foregroundStyle(Color.brand)
        .frame(maxWidth: .infinity)
    }
}
