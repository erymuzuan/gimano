import SwiftUI

/// 64pt round floating action button with a duotone icon.
struct RoundFab: View {
    let icon: GIcon
    let accessibilityLabel: LocalizedStringKey
    var stroke: Color = .brandInk
    var fill: Color = .brandTint
    /// Draws a diagonal slash (e.g. sound off).
    var slashed = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            GimanoIcon(icon: icon, size: Tokens.Size.fabIcon, stroke: stroke, fill: fill)
                .overlay {
                    if slashed {
                        Capsule().fill(stroke).frame(width: 3, height: 36).rotationEffect(.degrees(-45))
                    }
                }
                .frame(width: Tokens.Size.fab, height: Tokens.Size.fab)
                .background(Color.surface, in: Circle())
                .fabShadow()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(accessibilityLabel))
    }
}

/// Rounded-square menu button (top-left on the home map).
struct MenuButton: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            GimanoIcon(icon: .menu, size: Tokens.Size.fabIcon)
                .frame(width: Tokens.Size.fab, height: Tokens.Size.fab)
                .background(Color.surface, in: RoundedRectangle(cornerRadius: Tokens.Radius.menuFab, style: .continuous))
                .fabShadow()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Menu"))
        .accessibilityIdentifier("menuButton")
    }
}

/// Coral 84pt report button with Gimano's duotone report glyph.
struct ReportFab: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            GimanoIcon(icon: .report, size: Tokens.Size.reportFabIcon, stroke: .reportOn, fill: .reportTint)
                .frame(width: Tokens.Size.reportFab, height: Tokens.Size.reportFab)
                .background(Color.report, in: RoundedRectangle(cornerRadius: Tokens.Radius.reportFab, style: .continuous))
                .reportShadow()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Report"))
        .accessibilityIdentifier("reportButton")
    }
}

/// 72pt speedometer; turns red above the limit.
struct Speedometer: View {
    let speedKmh: Double
    var limitKmh: Double?

    private var isSpeeding: Bool {
        guard let limitKmh else { return false }
        return speedKmh > limitKmh + 2
    }

    var body: some View {
        HStack(spacing: 6) {
            VStack(spacing: -4) {
                Text("\(Int(max(0, speedKmh).rounded()))")
                    .font(Tokens.Font.nunito(30, .black).monospacedDigit())
                Text("km/h").font(Tokens.Font.nunito(11, .extrabold))
            }
            .foregroundStyle(isSpeeding ? Color.white : Color.textPrimary)
            .frame(width: Tokens.Size.speedometer, height: Tokens.Size.speedometer)
            .background(isSpeeding ? Color.speeding : Color.surface, in: Circle())
            .fabShadow()
            if let limitKmh {
                Text("\(Int(limitKmh))")
                    .font(Tokens.Font.nunito(17, .black).monospacedDigit())
                    .foregroundStyle(Color.black)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(.white))
                    .overlay(Circle().stroke(Color.speeding, lineWidth: 4))
                    .fabShadow()
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct GrabHandle: View {
    var body: some View {
        Capsule()
            .fill(Color.outline)
            .frame(width: Tokens.Size.handle.width, height: Tokens.Size.handle.height)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var color: Color = .brand
    var foreground: Color = .brandOn
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Tokens.Font.button)
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(color, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Tokens.Font.button)
            .foregroundStyle(Color.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Color.surfaceAlt, in: RoundedRectangle(cornerRadius: Tokens.Radius.md, style: .continuous))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// Small "toast" capsule.
struct Toast: View {
    let text: String
    var body: some View {
        Text(text)
            .font(Tokens.Font.label)
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.8), in: Capsule())
    }
}

struct OSMAttribution: View {
    var body: some View {
        Text("© OpenStreetMap contributors")
            .font(Tokens.Font.nunito(10, .bold))
            .foregroundStyle(Color.textPrimary.opacity(0.8))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.surface.opacity(0.7), in: Capsule())
            .accessibilityIdentifier("osmAttribution")
    }
}

extension PlaceCategory {
    /// Gimano duotone icon when the shared set has one.
    var gIcon: GIcon? {
        switch self {
        case .fuel: .fuel
        case .evCharging: .ev
        case .restArea, .restaurant, .cafe, .fastFood: .food
        case .tollBooth: .toll
        case .parking: .parking
        case .mosque, .surau: .surau
        case .police: .police
        case .tourism: .camera
        default: nil
        }
    }

    /// SF Symbol fallback for categories outside the shared icon set.
    var systemImage: String {
        switch self {
        case .mall: "bag"
        case .supermarket, .convenience: "cart"
        case .temple, .church: "building.columns"
        case .hospital, .clinic: "cross.case"
        case .pharmacy: "pills"
        case .school, .university: "graduationcap"
        case .hotel: "bed.double"
        case .atm, .bank: "banknote"
        case .carRepair: "wrench.and.screwdriver"
        case .carWash: "drop"
        case .transitStation: "tram"
        case .airport: "airplane"
        case .government: "building.2"
        case .toilets: "toilet"
        case .street: "road.lanes"
        case .address: "house"
        case .postcode: "envelope"
        case .point: "mappin.circle"
        default: "mappin"
        }
    }
}

/// Category glyph on a pale-cobalt tile.
struct CategoryIcon: View {
    let category: PlaceCategory
    var size: CGFloat = 44
    var body: some View {
        Group {
            if let g = category.gIcon {
                GimanoIcon(icon: g, size: size * 0.6)
            } else {
                Image(systemName: category.systemImage)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(Color.brandInk)
            }
        }
        .frame(width: size, height: size)
        .background(Color.brandMist, in: RoundedRectangle(cornerRadius: size * 0.32, style: .continuous))
    }
}

/// Wrapping row of chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0, x + s.width > width { x = 0; y += rowH + spacing; rowH = 0 }
            x += s.width + spacing
            rowH = max(rowH, s.height)
            maxX = max(maxX, x - spacing)
        }
        return CGSize(width: min(maxX, width), height: y + rowH)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX, x + s.width > bounds.maxX { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            rowH = max(rowH, s.height)
        }
    }
}
