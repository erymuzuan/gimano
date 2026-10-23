import XCTest

/// Retest of the search fixes on WS556 (b9846f0), EN/MS via GIMANO_LANG.
final class SearchRetestUITests: XCTestCase {
    var app: XCUIApplication!
    var lang: String { ProcessInfo.processInfo.environment["GIMANO_LANG"] ?? "en" }

    func launch(at loc: String = "3.1178,101.6213") {
        app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "live", "-GimanoFreshDevice", "YES", "-GimanoSkipOnboarding", "YES",
                               "-GimanoFakeLocation", loc, "-AppleLanguages", "(\(lang))", "-AppleLocale", lang == "ms" ? "ms_MY" : "en_MY"]
        if let api = ProcessInfo.processInfo.environment["GIMANO_API"] { app.launchArguments += ["-GimanoAPIBaseURL", api] }
        app.launch()
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 30))
    }

    override func setUp() { continueAfterFailure = true }

    func attach(_ name: String, _ text: String? = nil) {
        let a = text.map { XCTAttachment(string: $0) } ?? XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = "\(lang)-\(name)"
        a.lifetime = .keepAlways
        add(a)
    }

    var field: XCUIElement { app.textFields["searchField"] }

    func type(_ q: String) {
        field.tap()
        if app.buttons["searchClear"].exists { app.buttons["searchClear"].tap(); field.tap() }
        field.typeText(q)
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline, !(app.buttons.matching(identifier: "searchResult").firstMatch.exists
            || app.buttons["chainGroup"].firstMatch.exists || app.staticTexts["noResultsTitle"].exists) { usleep(300_000) }
        sleep(1)
    }

    func summary(_ q: String) -> String {
        var line = "\(q) → "
        let header = app.descendants(matching: .any).matching(identifier: "correctedHeader").firstMatch
        if header.exists { line += "[corrected: \(header.label)] " }
        let chain = app.buttons["chainGroup"].firstMatch
        if chain.exists { line += "[chain: \(chain.label)] " }
        if app.staticTexts["noResultsTitle"].exists {
            line += "NO RESULTS; suggestions: " + app.buttons.matching(identifier: "searchSuggestion").allElementsBoundByIndex.map { $0.label }.joined(separator: " | ")
        } else {
            line += app.buttons.matching(identifier: "searchResult").allElementsBoundByIndex.prefix(3).map { $0.label }.joined(separator: " | ")
        }
        return line
    }

    func test_1_Queries() {
        launch()
        app.buttons["whereTo"].tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        var report: [String] = []
        for (tag, q) in [("myddin", "myddin"), ("pakali", "kedai runcit pak ali"), ("pakalix", "kedai runcit pak alix"),
                         ("gibberish", "qwxzzy plorft"), ("surau", "surau"), ("masjid", "masjid"), ("pluscode", "6PM35P56+5W")] {
            type(q)
            report.append(summary(q))
            attach("r-\(tag)")
        }
        attach("report", report.joined(separator: "\n"))
    }

    func test_2_CorrectedHeaderAndExact() {
        launch()
        app.buttons["whereTo"].tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        type("famly mart")
        let header = app.descendants(matching: .any).matching(identifier: "correctedHeader").firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 10), "spelling header")
        attach("famly-header", summary("famly mart"))
        attach("r-famly-corrected")
        let exact = app.buttons["searchExact"]
        XCTAssertTrue(exact.exists)
        exact.tap()
        sleep(3)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "correctedHeader").firstMatch.exists, "exact search has no correction")
        attach("famly-exact", summary("famly mart (exact)"))
        attach("r-famly-exact")
    }

    func test_3_ChooseOnMapNext7Eleven() {
        launch(at: "3.11725,101.62170")     // ~5 m from 7-Eleven, 8 Jalan SS 2/61
        app.buttons["whereTo"].tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        type("qwxzzy plorft")
        XCTAssertTrue(app.buttons["chooseOnMap"].waitForExistence(timeout: 20))
        app.buttons["chooseOnMap"].tap()
        let addr = app.staticTexts["selectAddress"]
        XCTAssertTrue(addr.waitForExistence(timeout: 20))
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, addr.label.contains("…") { usleep(500_000) }
        let chip = app.buttons["selectIsThis"]
        XCTAssertTrue(chip.waitForExistence(timeout: 8), "nearby chip")
        XCTAssertTrue(chip.label.contains("7-Eleven"), chip.label)
        attach("map", "label=\(addr.label) chip=\(chip.label)")
        attach("r-map-7eleven")
        chip.tap()
        XCTAssertTrue(app.buttons["routesButton"].waitForExistence(timeout: 15), "the chip opens the 7-Eleven place card")
        attach("r-map-7eleven-place")
    }
}
