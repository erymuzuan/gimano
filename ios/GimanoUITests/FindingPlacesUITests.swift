import XCTest

/// "Finding places" (canvas): results with chains/chips, never-empty no-results, select on map, pin actions,
/// long-press, add stop and favourites. Language from GIMANO_LANG (en | ms) for screenshots in both languages.
final class FindingPlacesUITests: XCTestCase {
    var app: XCUIApplication!
    var lang: String { ProcessInfo.processInfo.environment["GIMANO_LANG"] ?? "en" }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", ProcessInfo.processInfo.environment["GIMANO_DATA_MODE"] ?? "auto",
                               "-GimanoFreshDevice", "YES", "-GimanoSkipOnboarding", "YES",
                               "-GimanoFakeLocation", "3.1478,101.6153", "-GimanoNoAutoStart", "YES",
                               "-AppleLanguages", "(\(lang))", "-AppleLocale", lang == "ms" ? "ms_MY" : "en_MY"]
        app.launch()
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 30))
    }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = "\(lang)-\(name)"
        a.lifetime = .keepAlways
        add(a)
    }

    func note(_ name: String, _ text: String) {
        let a = XCTAttachment(string: text)
        a.name = "\(lang)-\(name)"
        a.lifetime = .keepAlways
        add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ t: TimeInterval = 25) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: t), "Timed out waiting for \(e)")
        return e
    }

    func search(_ q: String) {
        wait(app.buttons["whereTo"]).tap()
        let f = wait(app.textFields["searchField"])
        f.tap()
        f.typeText(q)
    }

    /// Waits for the address label to settle, then chooses the point.
    func chooseHere(_ tag: String) {
        let addr = wait(app.staticTexts["selectAddress"], 20)
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, addr.label.contains("…") { usleep(500_000) }
        note("\(tag)-address", addr.label)
        shot("\(tag)-select-on-map")
        if app.buttons["selectIsThis"].exists { note("\(tag)-is-this", app.buttons["selectIsThis"].label) }
        app.buttons["selectChoose"].tap()
    }

    func test_1_ResultsChainsAndChooseOnMap() {
        search("famly mart")
        let group = wait(app.buttons["chainGroup"].firstMatch, 30)
        note("1-chain", group.label)
        XCTAssertTrue(app.buttons["sort.nearest"].exists && app.buttons["sort.bestMatch"].exists)
        if app.descendants(matching: .any)["correctedHeader"].exists {
            note("1-corrected", app.descendants(matching: .any)["correctedHeader"].label)
        }
        shot("1-results-chain")
        app.buttons["sort.nearest"].tap()
        sleep(1)
        shot("1-results-nearest")
        wait(app.buttons["chooseOnMap"]).tap()
        chooseHere("1")
        let nav = wait(app.buttons["pinNavigate"], 10)
        note("1-pin", app.staticTexts["pinAddressLine"].label)
        shot("1-pin-actions")
        nav.tap()
        XCTAssertTrue(app.buttons["goNow"].waitForExistence(timeout: 60), "route to the chosen point")
        shot("1-route-to-pin")
    }

    func test_2_NoResultsNeverEmpty() {
        search("kedai runcit pak alix")
        wait(app.staticTexts["noResultsTitle"], 30)
        let sug = app.buttons["searchSuggestion"].firstMatch
        if sug.waitForExistence(timeout: 10) { note("2-suggestion", sug.label) }
        XCTAssertTrue(app.buttons["chooseOnMap"].exists && app.buttons["addPlaceFromSearch"].exists)
        shot("2-no-results")
        if sug.exists {
            sug.tap()
            wait(app.buttons.matching(identifier: "searchResult").firstMatch, 30)
            shot("2-suggestion-results")
        }
    }

    func test_3_LongPressHomeMap() {
        app.otherElements["map"].press(forDuration: 1.3)
        let nav = wait(app.buttons["pinNavigate"], 15)
        sleep(3)
        note("3-pin", app.staticTexts["pinAddressLine"].label)
        shot("3-long-press-pin-actions")
        XCTAssertTrue(app.buttons["pinSave"].exists && app.buttons["pinAddPlace"].exists)
        app.buttons["pinSave"].tap()
        XCTAssertTrue(app.buttons["saveFavorite"].waitForExistence(timeout: 10), "save on device")
        shot("3-save-pin")
        _ = nav
    }

    func test_4_AddStopOnMap() {
        search("KLCC")
        wait(app.buttons.matching(identifier: "searchResult").firstMatch, 30).tap()
        wait(app.buttons["routesButton"]).tap()
        wait(app.buttons["goNow"], 60)
        wait(app.buttons["addStopButton"]).tap()
        let f = wait(app.textFields["searchField"])
        f.tap()
        f.typeText("tempat rahsia xyz")
        wait(app.buttons["chooseOnMap"], 30).tap()
        chooseHere("4")
        XCTAssertTrue(app.buttons["stop.0"].waitForExistence(timeout: 40), "the chosen point became a stop")
        shot("4-stop-from-map")
    }

    func test_5_FavouriteOnMap() {
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuFavorites"]).tap()
        let b = app.buttons["favoriteChooseOnMap"]
        for _ in 0..<6 where !(b.exists && b.isHittable) { app.swipeUp() }
        b.tap()
        chooseHere("5")
        XCTAssertTrue(app.buttons["saveFavorite"].waitForExistence(timeout: 10))
        shot("5-favourite-from-map")
    }

    func test_6_LanguageSetting() {
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons.matching(NSPredicate(format: "label IN {'Settings', 'Tetapan'}")).firstMatch).tap()
        let picker = app.buttons["languagePicker"].exists ? app.buttons["languagePicker"] : app.descendants(matching: .any)["languagePicker"]
        for _ in 0..<6 where !(picker.exists && picker.isHittable) { app.swipeUp() }
        shot("6-language-setting")
    }
}
