import CoreGraphics
import Foundation

/// Place card detents (boards TempatIntai / TempatPenuh). Peek is the default whenever a card opens.
enum PlaceDetent: Equatable, Sendable {
    /// Name, rating line, category · address · open, "Routes · eta", Save · Call · Share · Website (≤ 40 % of the screen).
    case peek
    /// Everything: reviews, photos (Add photo at the end), Suggest an edit.
    case full

    var toggled: PlaceDetent { self == .peek ? .full : .peek }
}

/// Pure geometry of the place card, so the "peek ≤ 40 %" rule and the snapping are unit-tested.
enum PlaceCardMetrics {
    /// Peek (including the bottom safe area) never covers more than this share of the screen.
    static let peekMaxFraction: CGFloat = 0.40
    /// Gap kept between the top of the safe area and the Full sheet, so the pin stays visible above it.
    static let fullTopGap: CGFloat = 56
    /// Grab handle row (8 pt above, 5 pt bar, 11 pt below).
    static let handleBlock: CGFloat = 24

    /// Peek height including the bottom safe area: the content's natural height, capped at 40 % of the screen.
    static func peekHeight(natural: CGFloat, bottomInset: CGFloat, screenHeight: CGFloat) -> CGFloat {
        min(natural + bottomInset, (screenHeight * peekMaxFraction).rounded(.down))
    }

    /// Full height including the bottom safe area, for a safe-area height of `available` (top inset excluded).
    static func fullHeight(available: CGFloat, bottomInset: CGFloat) -> CGFloat {
        max(0, available - fullTopGap) + bottomInset
    }

    /// Detent after a vertical drag (negative = up), using the predicted end translation.
    static func snap(from d: PlaceDetent, predictedTranslation dy: CGFloat) -> PlaceDetent {
        switch d {
        case .peek: dy < -60 ? .full : .peek
        case .full: dy > 90 ? .peek : .full
        }
    }

    /// Screen y of the centre of the map left visible above a sheet of `sheetHeight` (map full-bleed, top at 0).
    static func visibleMapCentreY(screenHeight: CGFloat, sheetHeight: CGFloat) -> CGFloat {
        max(0, screenHeight - sheetHeight) / 2
    }
}

/// The one-line rating under the place name: "★★★★☆ 4.3 · 12 reviews · 450 m", or grey stars + "New · 450 m".
struct PlaceRatingLine: Equatable, Sendable {
    /// No reviews yet: grey stars and "New".
    var isNew: Bool
    var average: Double
    var count: Int

    /// Server values win; reviews loaded on the device fill in when the server sends none.
    static func make(serverAverage: Double?, serverCount: Int?, loaded: RatingSummary? = nil) -> PlaceRatingLine {
        let count = max(serverCount ?? 0, loaded?.count ?? 0)
        let avg = serverAverage ?? (loaded.flatMap { $0.count > 0 ? $0.average : nil }) ?? 0
        guard count > 0, avg > 0 else { return PlaceRatingLine(isNew: true, average: 0, count: 0) }
        return PlaceRatingLine(isNew: false, average: (avg * 10).rounded() / 10, count: count)
    }

    /// Star fill (0, 0.5 or 1) of star `i` (1…5).
    static func starFill(_ i: Int, rating: Double) -> Double {
        if Double(i) <= rating + 0.25 { return 1 }
        if Double(i) - 0.75 <= rating { return 0.5 }
        return 0
    }
}

/// The only requests a place card makes when it opens. It can't reach routing, so opening a card never sends the
/// user's position: the location leaves the phone only once Routes is tapped (route preview), as on Android.
protocol PlaceCardDataSource: Sendable {
    func place(id: String, near: LatLng?) async throws -> Place
    func reviews(placeId: String, sort: ReviewSort, cursor: String?) async throws -> ReviewsPage
}

enum PlaceCardLoader {
    /// `GET /places/{id}` without `lat`/`lng` (the server would only use them for `distanceM`; the card measures the
    /// distance on the phone from its own fix).
    static func detail(id: String, source: any PlaceCardDataSource) async -> Place? {
        try? await source.place(id: id, near: nil)
    }

    /// `GET /places/{id}/reviews` (no position).
    static func reviews(placeId: String, source: any PlaceCardDataSource) async -> [Review]? {
        try? await source.reviews(placeId: placeId, sort: .recent, cursor: nil).reviews
    }
}
