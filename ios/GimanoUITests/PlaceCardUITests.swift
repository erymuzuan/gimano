import XCTest

/// Place card from search (boards TempatIntai / TempatBaru / TempatPenuh): peek ≤ 40 %, the pin centred above the card,
/// full with reviews and photos, and every action opened then cancelled (nothing is sent).
///
/// Environment: GIMANO_LANG (en|ms), GIMANO_DATA_MODE (demo|auto|live), GIMANO_PLACE_QUERY (default KLCC),
/// GIMANO_AX=YES for the largest Dynamic Type. Pin centring is measured from the screenshots afterwards.
final class PlaceCardUITests: XCTestCase {
    var app: XCUIApplication!
    var env: [String: String] { ProcessInfo.processInfo.environment }
    var lang: String { env["GIMANO_LANG"] ?? "en" }
    var tag: String { "\(lang)\(env["GIMANO_AX"] == "YES" ? "-ax" : "")-\(env["GIMANO_PLACE_TAG"] ?? "place")" }

    override func setUp() { continueAfterFailure = false }

    func launch() {
        app = XCUIApplication()
        var args = ["-GimanoDataMode", env["GIMANO_DATA_MODE"] ?? "demo", "-GimanoSkipOnboarding", "YES",
                    "-GimanoFakeLocation", env["GIMANO_FAKE_LOCATION"] ?? "3.152613,101.703718", "-GimanoNoAutoStart", "YES",
                    "-AppleLanguages", "(\(lang))"]
        if env["GIMANO_AX"] == "YES" { args += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launchArguments = args
        app.launch()
    }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = "\(tag)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    func note(_ name: String, _ text: String) {
        let a = XCTAttachment(string: text); a.name = "\(tag)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ t: TimeInterval = 20) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: t), "Timed out waiting for \(e)")
        return e
    }

    func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    func openPlace() {
        launch()
        wait(app.buttons["whereTo"], 40).tap()
        let f = wait(app.textFields["searchField"]); f.tap(); f.typeText(env["GIMANO_PLACE_QUERY"] ?? "KLCC")
        let r = app.buttons.matching(identifier: "searchResult").firstMatch
        wait(r, 25).tap()
        wait(app.buttons["routesButton"], 15)
        sleep(3)   // place detail, reviews, ETA and the camera settle
    }

    var handle: XCUIElement { app.buttons["placeCardHandle"] }
    func isFull() -> Bool { (handle.value as? String) == "full" }

    /// A real finger-length drag from the handle (XCUIElement.swipe* only moves within the 24 pt handle).
    func dragHandle(by dy: CGFloat) {
        let start = handle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: dy)), withVelocity: .fast, thenHoldForDuration: 0)
    }

    func measure(_ name: String) {
        let win = app.windows.firstMatch.frame
        let sheet = handle.frame
        let sheetH = win.maxY - sheet.minY
        note(name, String(format: "window %.0fx%.0f, sheet top %.1f, sheet height %.1f (%.1f %% of screen), visible map centre y %.1f, rating '%@', routes '%@'",
                          win.width, win.height, sheet.minY, sheetH, sheetH / win.height * 100, sheet.minY / 2,
                          any("placeRatingLine").exists ? any("placeRatingLine").label : "-", app.buttons["routesButton"].label))
    }

    /// Swipe up / down only (quick check of the gestures on a real device).
    func test_0_SwipeOnly() {
        openPlace()
        dragHandle(by: -300)
        sleep(2)
        shot("0-after-handle-swipe")
        let afterHandle = isFull()
        if !afterHandle {
            any("placeTitle").swipeUp()
            sleep(2)
            shot("0-after-title-swipe")
        }
        note("swipe", "handle swipe → \(afterHandle ? "full" : "peek"); now \(isFull() ? "full" : "peek")")
        XCTAssertTrue(isFull())
        dragHandle(by: 300)
        sleep(2)
        XCTAssertFalse(isFull())
    }

    func test_1_PeekFullAndActions() {
        openPlace()
        measure("peek-metrics")
        let win = app.windows.firstMatch.frame
        let sheet = handle.frame
        XCTAssertLessThanOrEqual((win.maxY - sheet.minY) / win.height, 0.405, "peek ≤ 40 % of the screen")
        shot("1-peek")

        // Share sheet (cancel). No screenshot: the system sheet shows the user's own contacts.
        app.buttons["sharePlace"].tap()
        sleep(3)
        XCTAssertFalse(app.buttons["routesButton"].isHittable, "the share sheet covers the card")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
        sleep(2)
        XCTAssertTrue(app.buttons["routesButton"].isHittable, "share sheet cancelled")

        // Save (cancel).
        wait(app.buttons["savePlace"]).tap()
        wait(app.buttons["saveFavorite"], 10)
        shot("3-save-sheet")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(2)

        // Swipe up → Full.
        dragHandle(by: -300)
        sleep(2)
        XCTAssertTrue(isFull(), "swipe up opens Full")
        measure("full-metrics")
        shot("4-full")
        app.swipeUp()
        sleep(1)
        shot("5-full-scrolled")

        // Write a review (open, cancel).
        let write = app.buttons["writeReview"]
        for _ in 0..<4 where !write.isHittable { app.swipeUp() }
        write.tap()
        wait(app.buttons["postReview"], 10)
        shot("6-write-review")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)

        // Add photo (sheet only; the system picker would show personal photos, so it is opened and closed without a screenshot).
        let add = app.buttons["placeAddPhoto"]
        for _ in 0..<4 where !add.isHittable { app.swipeUp() }
        add.tap()
        wait(app.buttons["uploadPhotos"], 10)
        shot("7-add-photo-sheet")
        XCTAssertFalse(app.buttons["uploadPhotos"].isEnabled, "nothing to upload until a photo is picked")
        if app.buttons["addPhotoMenu"].exists {
            app.buttons["addPhotoMenu"].tap()
            if app.buttons["pickPhotos"].waitForExistence(timeout: 3) {
                app.buttons["pickPhotos"].tap()
                sleep(3)
                let cancel = app.buttons.matching(NSPredicate(format: "identifier == %@", "Cancel")).firstMatch
                if cancel.waitForExistence(timeout: 5) { cancel.tap() } else { app.swipeDown(velocity: .fast) }
                sleep(1)
            }
        }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)

        // Suggest an edit row → its six options (cancel).
        let suggest = app.buttons["suggestEditRow"]
        for _ in 0..<4 where !suggest.isHittable { app.swipeUp() }
        if suggest.exists {
            suggest.tap()
            sleep(1)
            shot("8-suggest-options")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04)).tap()   // dismiss the menu
            sleep(1)
        }

        // Swipe down → Peek.
        for _ in 0..<4 where isFull() { dragHandle(by: 300); sleep(1) }
        XCTAssertFalse(isFull(), "swipe down returns to Peek")
        sleep(2)
        shot("9-back-to-peek")

        // Pan away, then Recenter returns to the pin.
        app.otherElements.matching(identifier: "map").allElementsBoundByIndex.last?.swipeLeft()
        sleep(2)
        shot("10-panned")
        wait(app.buttons["placeRecenter"]).tap()
        sleep(2)
        shot("11-recentred")

        // Routes → route preview.
        app.buttons["routesButton"].tap()
        sleep(4)
        shot("12-routes")
    }
}
