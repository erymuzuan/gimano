import SwiftUI

/// Flat two-tone icons of the home panel (boards HomeIntai / HomeSejarah / HomePenuh): a pale cobalt base
/// shape (#8FB0E8) with a cobalt detail (#0047AB, even-odd), drawn on the 24 grid. Saved places use the gold star.
/// Same paths as the boards; rendered with the shared SVG path parser (`SVGPath`).
enum FlatIcon: String, CaseIterable, Sendable {
    case home, work, star, fuel, food, surau, parking, ev, atm, shop, more, recent
    // Place card (boards TempatIntai / TempatPenuh).
    case save, call, share, website, camera, review, edit
    // Sound button and selector (boards NavBunyi / NavBunyiKeadaan).
    case soundOn, soundAlerts, soundOff

    /// (base, detail) path data.
    var paths: (base: String, detail: String) {
        switch self {
        case .home: ("M3 11 12 3l9 8v10H3z", "M10 15h4v6h-4z")
        case .work: ("M3 8h18v12H3z", "M9 4h6v4h-2V6h-2v2H9zM3 12h18v2H3z")
        case .star: ("m12 2 3 6.3 6.9.9-5 4.8 1.2 6.8L12 17.6l-6.1 3.2L7.1 14l-5-4.8 6.9-.9z",
                     "M12 7.5 13.6 11l3.6.4-2.6 2.5.6 3.6L12 15.8l-3.2 1.7.6-3.6-2.6-2.5 3.6-.4z")
        case .fuel: ("M4 3h10v18H4z", "M6 5h6v5H6zM14 8h2l3 3v7a1.5 1.5 0 0 1-3 0v-4h-2v-2h4v-2.2L15.2 10H14z")
        case .food: ("M4 8h12v6a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5z", "M16 9h2a3 3 0 0 1 0 6h-2v-2h2a1 1 0 0 0 0-2h-2zM3 20h14v1.5H3z")
        case .surau: ("M5 21v-8a7 7 0 0 1 14 0v8z", "M11.25 2h1.5v4h-1.5zM10 17a2 2 0 0 1 4 0v4h-4z")
        case .parking: ("M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
                        "M9 7h4a3.5 3.5 0 0 1 0 7h-2v3H9zm2 2v3h2a1.5 1.5 0 0 0 0-3z")
        case .ev: ("M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z", "M13 5 7 13h4l-1 6 6-8h-4z")
        case .atm: ("M2 6h20v12H2z", "M2 9h20v2H2zM5 14h5v2H5z")
        case .shop: ("M5 6h16l-2 8H7z",
                     "M2 3h3l3 13h11v2H6.4L3.4 5H2zM8 20a1.5 1.5 0 1 0 3 0 1.5 1.5 0 0 0-3 0zM16 20a1.5 1.5 0 1 0 3 0 1.5 1.5 0 0 0-3 0z")
        case .more: ("M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z", "M11 7h2v4h4v2h-4v4h-2v-4H7v-2h4z")
        case .recent: ("M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z", "M11 6h2v5.4l3.8 2.2-1 1.7-4.8-2.8z")
        case .save: ("M6 3h12v18l-6-4-6 4z", "M9 7h6v2H9z")
        case .call: ("M5 3h4l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2z",
                     "M14 3a7 7 0 0 1 7 7h-2a5 5 0 0 0-5-5z")
        case .share: ("M8.6 10.7 15 7.4l.9 1.8-6.4 3.3zM9.5 13.5l6.4 3.3-.9 1.8-6.4-3.3z",
                      "M18 2a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM6 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM18 16a3 3 0 1 1 0 6 3 3 0 0 1 0-6z")
        case .website: ("M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20z", "M2.5 11h19v2h-19zM11 2.2h2c2.6 2.6 2.6 17 0 19.6h-2c-2.6-2.6-2.6-17 0-19.6z")
        case .camera: ("M3 7h4l2-3h6l2 3h4v13H3z", "M12 9a4 4 0 1 1 0 8 4 4 0 0 1 0-8z")
        case .review: ("M3 4h18v13H8l-5 4z", "m12 6.5 1.4 2.9 3.1.4-2.3 2.2.6 3.1L12 13.6l-2.8 1.5.6-3.1-2.3-2.2 3.1-.4z")
        case .edit: ("M4 16 15 5l4 4L8 20H4z", "m16 4 2-2 4 4-2 2z")
        case .soundOn: ("M3 9h4l5-4v14l-5-4H3z", "M15 8.5a5 5 0 0 1 0 7l-1.4-1.4a3 3 0 0 0 0-4.2zM17.8 5.7a9 9 0 0 1 0 12.6l-1.4-1.4a7 7 0 0 0 0-9.8z")
        case .soundAlerts: ("M12 3a6 6 0 0 1 6 6v4l2 3H4l2-3V9a6 6 0 0 1 6-6z", "M10 18h4a2 2 0 0 1-4 0zM11 7h2v5h-2z")
        case .soundOff: ("M3 9h4l5-4v14l-5-4H3z",
                         "m15 9 1.4-1.4 2.1 2.1 2.1-2.1L22 9l-2.1 2.1L22 13.2l-1.4 1.4-2.1-2.1-2.1 2.1-1.4-1.4 2.1-2.1z")
        }
    }

    var isGold: Bool { self == .star }

    nonisolated(unsafe) fileprivate static let cache: [FlatIcon: (CGPath, CGPath)] = Dictionary(uniqueKeysWithValues: FlatIcon.allCases.map {
        ($0, (SVGPath.parse($0.paths.base).fill, SVGPath.parse($0.paths.detail).fill))
    })
}

private struct FlatIconShape: Shape {
    let icon: FlatIcon
    let detail: Bool
    func path(in rect: CGRect) -> Path {
        guard let pair = FlatIcon.cache[icon] else { return Path() }
        let path = detail ? pair.1 : pair.0
        let scale = min(rect.width, rect.height) / 24
        var t = CGAffineTransform(translationX: rect.midX - 12 * scale, y: rect.midY - 12 * scale).scaledBy(x: scale, y: scale)
        return Path(path.copy(using: &t) ?? path)
    }
}

/// Flat two-tone icon view (no stroke).
struct FlatIconView: View {
    let icon: FlatIcon
    var size: CGFloat = 26

    var body: some View {
        ZStack {
            FlatIconShape(icon: icon, detail: false)
                .fill(icon.isGold ? Color.starGold : Color.brandTint)
            FlatIconShape(icon: icon, detail: true)
                .fill(icon.isGold ? Color.amberInk.opacity(0.35) : icon == .soundOff ? Color.muteInk : Color.brand, style: FillStyle(eoFill: true))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

extension Color {
    /// Saved-place star (#FFD466) on the amber mist chip.
    static let starGold = Color(light: 0xFFD466, dark: 0xE8B83A)
    /// Category tile label (#4E5D78).
    static let tileLabel = Color(light: 0x4E5D78, dark: 0xA9B6CF)
    /// Section header (#6B7A94).
    static let sectionLabel = Color(light: 0x6B7A94, dark: 0x8C9AB4)
    /// Panel row divider (#EEF2F8).
    static let rowDivider = Color(light: 0xEEF2F8, dark: 0x1A2540)
    /// Mute: the cross on the speaker and the button's ring (#C2410C), on a pale orange fill.
    static let muteInk = Color(light: 0xC2410C, dark: 0xF08A5D)
    static let muteMist = Color(light: 0xFFF1EA, dark: 0x3A1E12)
    /// Recent-row icon tile (#ECEEF1).
    static let recentTile = Color(light: 0xECEEF1, dark: 0x1F2A40)
}
