import CoreGraphics

/// Home panel detents (boards HomeIntai / HomeSejarah / HomePenuh).
/// Peek is the default: after navigation ends, on relaunch, and when the map is tapped.
enum HomeDetent: CaseIterable, Equatable, Sendable {
    /// "Ke mana?" + one row of chips (~150 pt + safe area, ~18–22 % of the screen).
    case peek
    /// Search, chips, then Terkini (recents) first.
    case half
    /// Search, Terkini, saved places, category grid, privacy card.
    case full

    /// Next detent when the handle is tapped (peek → half → full → peek).
    var next: HomeDetent {
        switch self {
        case .peek: .half
        case .half: .full
        case .full: .peek
        }
    }

    var expanded: HomeDetent { self == .peek ? .half : .full }
    var collapsed: HomeDetent { self == .full ? .half : .peek }
}

/// Panel geometry in points, excluding the bottom safe-area inset (added by the view).
/// Pure so the Dynamic Type rule (peek ≤ 25 % of the screen) is unit-tested.
enum HomePanelMetrics {
    static let handleTop: CGFloat = 8
    static let handleHeight: CGFloat = 5
    static let searchHeight: CGFloat = 52
    static let chipHeight: CGFloat = 44
    static let chipGap: CGFloat = 8
    /// Chip label size; at accessibility sizes it scales up to `chipFontMax` and the chips wrap to 2 rows.
    static let chipFont: CGFloat = 13
    static let chipFontMax: CGFloat = 20
    /// "Ke mana?" placeholder size and its accessibility-size cap (the field stays 52 pt).
    static let searchFont: CGFloat = 18
    static let searchFontMax: CGFloat = 24

    /// Chip rows at the given Dynamic Type: 1 normally, 2 at accessibility sizes (never more).
    static func chipRows(accessibilitySize: Bool) -> Int { accessibilitySize ? 2 : 1 }

    /// Vertical gap between the handle, search field and chips.
    static func gap(accessibilitySize: Bool) -> CGFloat { accessibilitySize ? 6 : 10 }

    /// Space under the chips before the safe area.
    static func bottomPad(accessibilitySize: Bool) -> CGFloat { accessibilitySize ? 4 : 21 }

    /// Peek content height: 150 pt by default (as on the board).
    static func peekHeight(accessibilitySize: Bool) -> CGFloat {
        let rows = CGFloat(chipRows(accessibilitySize: accessibilitySize))
        let chips = rows * chipHeight + (rows - 1) * 6
        let g = gap(accessibilitySize: accessibilitySize)
        return handleTop + handleHeight + g + searchHeight + g + chips + bottomPad(accessibilitySize: accessibilitySize)
    }

    /// Content height for a detent in a safe-area height of `available`.
    static func height(_ d: HomeDetent, available: CGFloat, accessibilitySize: Bool) -> CGFloat {
        let peek = peekHeight(accessibilitySize: accessibilitySize)
        switch d {
        case .peek: return peek
        case .half: return max(peek, min(440, available * 0.55))
        case .full: return max(peek, available)
        }
    }

    /// Share of the whole screen the peek covers (including the bottom safe area).
    static func peekFraction(screenHeight: CGFloat, bottomInset: CGFloat, accessibilitySize: Bool) -> CGFloat {
        (peekHeight(accessibilitySize: accessibilitySize) + bottomInset) / screenHeight
    }

    /// Detent nearest to where a drag would end.
    static func snap(from d: HomeDetent, predictedTranslation dy: CGFloat, available: CGFloat, accessibilitySize: Bool) -> HomeDetent {
        let target = height(d, available: available, accessibilitySize: accessibilitySize) - dy
        return HomeDetent.allCases.min {
            abs(height($0, available: available, accessibilitySize: accessibilitySize) - target)
                < abs(height($1, available: available, accessibilitySize: accessibilitySize) - target)
        } ?? .peek
    }
}
