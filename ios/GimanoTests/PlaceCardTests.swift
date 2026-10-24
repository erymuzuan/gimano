import CoreGraphics
import Foundation
import Testing
@testable import Gimano

/// Place card from search (boards TempatFlow / TempatIntai / TempatBaru / TempatPenuh).
@Suite("Place card")
struct PlaceCardTests {
    /// (screen height, bottom safe area) of current iPhones: 16/17, 16e/14, Pro Max.
    let screens: [(CGFloat, CGFloat)] = [(852, 34), (874, 34), (844, 34), (932, 34), (956, 34)]

    @Test("Peek never covers more than 40 % of the screen, whatever the content height")
    func peekCap() {
        for (h, inset) in screens {
            for natural in [CGFloat(250), 300, 420, 900] {
                let peek = PlaceCardMetrics.peekHeight(natural: natural, bottomInset: inset, screenHeight: h)
                #expect(peek / h <= 0.40, "screen \(h), natural \(natural)")
            }
            // The default content (≈ 280 pt) is not inflated: the sheet hugs it.
            #expect(PlaceCardMetrics.peekHeight(natural: 280, bottomInset: inset, screenHeight: h) == 314)
        }
    }

    @Test("Full leaves a strip of map at the top; swipe up opens it, swipe down returns to peek")
    func detents() {
        let full = PlaceCardMetrics.fullHeight(available: 852 - 59 - 34, bottomInset: 34)
        #expect(full == 852 - 59 - PlaceCardMetrics.fullTopGap)
        #expect(PlaceCardMetrics.snap(from: .peek, predictedTranslation: -200) == .full)
        #expect(PlaceCardMetrics.snap(from: .peek, predictedTranslation: -20) == .peek)
        #expect(PlaceCardMetrics.snap(from: .full, predictedTranslation: 300) == .peek)
        #expect(PlaceCardMetrics.snap(from: .full, predictedTranslation: 40) == .full)
        #expect(PlaceDetent.peek.toggled == .full && PlaceDetent.full.toggled == .peek)
    }

    @Test("The pin is centred in the visible map above the card, not in the whole screen")
    func visibleCentre() {
        let peek = PlaceCardMetrics.peekHeight(natural: 280, bottomInset: 34, screenHeight: 852)
        let y = PlaceCardMetrics.visibleMapCentreY(screenHeight: 852, sheetHeight: peek)
        #expect(y == CGFloat(852 - 314) / 2)
        #expect(y < 852 / 2 - 100, "well above the screen centre, which the card would cover")
    }

    @Test("Rating line: average and count from the server; no reviews → New with grey stars")
    func ratingLine() {
        let r = PlaceRatingLine.make(serverAverage: 4.26, serverCount: 12)
        #expect(!r.isNew && r.average == 4.3 && r.count == 12)
        #expect(PlaceRatingLine.make(serverAverage: nil, serverCount: nil).isNew)
        #expect(PlaceRatingLine.make(serverAverage: nil, serverCount: 0).isNew)
        // A single review already shows its stars and "1 review" (the user asked for the average up front).
        let one = PlaceRatingLine.make(serverAverage: 5, serverCount: 1)
        #expect(!one.isNew && one.count == 1)
        // Reviews loaded on the phone fill in when the server summary has none yet.
        let loaded = RatingSummary(average: 4, count: 2, distribution: [0, 0, 0, 2, 0])
        let l = PlaceRatingLine.make(serverAverage: nil, serverCount: nil, loaded: loaded)
        #expect(!l.isNew && l.average == 4 && l.count == 2)
    }

    @Test("Stars: full, half and empty")
    func stars() {
        #expect((1...5).map { PlaceRatingLine.starFill($0, rating: 4.3) } == [1, 1, 1, 1, 0.5])
        #expect((1...5).map { PlaceRatingLine.starFill($0, rating: 4.0) } == [1, 1, 1, 1, 0])
        #expect((1...5).map { PlaceRatingLine.starFill($0, rating: 0) } == [0, 0, 0, 0, 0])
        #expect((1...5).map { PlaceRatingLine.starFill($0, rating: 4.8) } == [1, 1, 1, 1, 1])
    }

    @Test("Place card actions use the flat two-tone set")
    func icons() {
        for icon in [FlatIcon.save, .call, .share, .website, .camera, .review, .edit] {
            #expect(!SVGPath.parse(icon.paths.base).fill.isEmpty, "\(icon)")
            #expect(!SVGPath.parse(icon.paths.detail).fill.isEmpty, "\(icon)")
        }
        #expect(PlaceCategory.mosque.flatIcon == .surau)
        #expect(PlaceCategory.fuel.flatIcon == .fuel)
    }

    @Test("Opening a card never sends the user's position: details without near, reviews, nothing else")
    func noPositionOnOpen() async {
        let src = RecordingCardSource()
        _ = await PlaceCardLoader.detail(id: "w480717734", source: src)
        _ = await PlaceCardLoader.reviews(placeId: "w480717734", source: src)
        let calls = await src.calls
        #expect(calls == ["place(w480717734, near: nil)", "reviews(w480717734)"])
        #expect(!calls.contains { $0.contains("near: LatLng") || $0.contains("route") })
    }
}

/// Records what a place card asks for (the card's data source has no routing call at all).
actor RecordingCardSource: PlaceCardDataSource {
    var calls: [String] = []

    func place(id: String, near: LatLng?) async throws -> Place {
        calls.append("place(\(id), near: \(near.map { "LatLng(\($0.lat), \($0.lng))" } ?? "nil"))")
        return Place(summary: PlaceSummary(id: id, name: "Masjid Jamek", category: .mosque, address: "", lat: 3.1488, lng: 101.6957))
    }

    func reviews(placeId: String, sort: ReviewSort, cursor: String?) async throws -> ReviewsPage {
        calls.append("reviews(\(placeId))")
        return ReviewsPage(reviews: [], next: nil)
    }
}
