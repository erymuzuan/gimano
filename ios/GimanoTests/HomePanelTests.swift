import CoreGraphics
import Foundation
import Testing
@testable import Gimano

/// Home panel redesign (boards HomeFlow / HomeIntai / HomeSejarah / HomePenuh).
@Suite("Home panel")
struct HomePanelTests {
    /// (screen height, bottom safe area) of current iPhones: 16/17, 16e/14, Pro Max.
    let screens: [(CGFloat, CGFloat)] = [(874, 34), (844, 34), (932, 34), (956, 34)]

    @Test("Peek is 150 pt plus the safe area: the map keeps ~80 % of the screen")
    func peekDefault() {
        #expect(HomePanelMetrics.peekHeight(accessibilitySize: false) == 150)
        #expect(HomePanelMetrics.chipRows(accessibilitySize: false) == 1)
        for (h, inset) in screens {
            #expect(HomePanelMetrics.peekFraction(screenHeight: h, bottomInset: inset, accessibilitySize: false) <= 0.22)
        }
    }

    @Test("Largest Dynamic Type: chips wrap to 2 rows at most and the peek stays within 25 % of the screen")
    func peekAccessibility() {
        #expect(HomePanelMetrics.chipRows(accessibilitySize: true) == 2)
        for (h, inset) in screens {
            #expect(HomePanelMetrics.peekFraction(screenHeight: h, bottomInset: inset, accessibilitySize: true) <= 0.25, "screen \(h)")
        }
        // Text is capped so it fits the fixed 44 pt chips and 52 pt field.
        #expect(HomePanelMetrics.chipFontMax * 1.4 < HomePanelMetrics.chipHeight)
        #expect(HomePanelMetrics.searchFontMax * 1.4 < HomePanelMetrics.searchHeight)
    }

    @Test("Detents grow peek < half < full; the handle cycles and VoiceOver expands/collapses")
    func detents() {
        let avail: CGFloat = 874 - 62 - 34
        let peek = HomePanelMetrics.height(.peek, available: avail, accessibilitySize: false)
        let half = HomePanelMetrics.height(.half, available: avail, accessibilitySize: false)
        let full = HomePanelMetrics.height(.full, available: avail, accessibilitySize: false)
        #expect(peek < half && half < full)
        #expect(full == avail)
        #expect(HomeDetent.peek.next == .half && HomeDetent.half.next == .full && HomeDetent.full.next == .peek)
        #expect(HomeDetent.peek.expanded == .half && HomeDetent.half.expanded == .full && HomeDetent.full.expanded == .full)
        #expect(HomeDetent.full.collapsed == .half && HomeDetent.half.collapsed == .peek && HomeDetent.peek.collapsed == .peek)
    }

    @Test("Swipe up from peek opens half; a long swipe down from full returns to peek")
    func snapping() {
        let avail: CGFloat = 778
        #expect(HomePanelMetrics.snap(from: .peek, predictedTranslation: -250, available: avail, accessibilitySize: false) == .half)
        #expect(HomePanelMetrics.snap(from: .peek, predictedTranslation: -900, available: avail, accessibilitySize: false) == .full)
        #expect(HomePanelMetrics.snap(from: .full, predictedTranslation: 900, available: avail, accessibilitySize: false) == .peek)
        #expect(HomePanelMetrics.snap(from: .half, predictedTranslation: 10, available: avail, accessibilitySize: false) == .half)
    }

    @Test("Every flat two-tone icon parses to a closed base and detail shape")
    func flatIcons() {
        for icon in FlatIcon.allCases {
            #expect(!SVGPath.parse(icon.paths.base).fill.isEmpty, "\(icon) base")
            #expect(!SVGPath.parse(icon.paths.detail).fill.isEmpty, "\(icon) detail")
            let box = SVGPath.parse(icon.paths.base).fill.boundingBoxOfPath
            #expect(box.minX >= 0 && box.minY >= 0 && box.maxX <= 24 && box.maxY <= 24, "\(icon) fits the 24 grid")
        }
    }

    @MainActor
    private func makeModel() throws -> (AppModel, LocalStore) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("hp-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = try LocalStore(path: dir.appendingPathComponent("db.sqlite").path, photosDirectory: dir.appendingPathComponent("p"))
        return (AppModel(store: store), store)
    }

    @Test("Peek on launch, after navigation ends, and when the map is tapped")
    @MainActor
    func returnsToPeek() throws {
        let (model, _) = try makeModel()
        #expect(model.homeDetent == .peek)

        model.homeDetent = .full
        model.map.onMapTap?()
        #expect(model.homeDetent == .peek)

        model.homeDetent = .half
        model.screen = .search(.destination)
        model.map.onMapTap?()
        #expect(model.homeDetent == .half, "a map tap only folds the home panel")

        model.homeDetent = .full
        model.stopNavigation()
        #expect(model.screen == .home)
        #expect(model.homeDetent == .peek)
    }

    @Test("Chips: Rumah, Kerja, saved favourites (gold star), then categories")
    @MainActor
    func chipOrder() throws {
        let (model, store) = try makeModel()
        _ = try store.addFavorite(name: "Kampung", icon: .kampung, lat: 5.76, lng: 102.2, address: "Machang", placeId: nil)
        model.reloadLocal()
        let chips = model.homeChips
        #expect(chips.map(\.id).prefix(3) == ["shortcut.home", "shortcut.work", "favoriteTile.Kampung"])
        #expect(chips[2].icon == .star && chips[2].style == .favorite)
        #expect(chips.dropFirst(3).map(\.icon) == [.fuel, .food, .surau, .parking, .ev, .atm, .shop])
        #expect(chips.dropFirst(3).allSatisfy { $0.style == .category })
        // The 4×2 grid is the seven categories plus "More".
        #expect(model.homeCategories.count == 7)
    }
}
