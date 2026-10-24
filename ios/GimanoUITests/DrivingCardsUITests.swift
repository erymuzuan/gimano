import XCTest

/// Driving: confirm card + sound selector on the iPhone (boards NavSahkan / NavBunyi / NavBunyiKeadaan), in a simulated
/// drive. `-GimanoFakeStillClosed` shows a sample "Still closed?" prompt that is never sent.
/// Environment: GIMANO_LANG (en|ms), GIMANO_AX=YES (largest Dynamic Type), GIMANO_DATA_MODE (default live = dev server).
final class DrivingCardsUITests: XCTestCase {
    var app: XCUIApplication!
    var env: [String: String] { ProcessInfo.processInfo.environment }
    var lang: String { env["GIMANO_LANG"] ?? "en" }
    var tag: String { "\(lang)\(env["GIMANO_AX"] == "YES" ? "-ax" : "")" }

    override func setUp() { continueAfterFailure = false }

    func launch(_ extra: [String] = []) {
        app = XCUIApplication()
        var args = ["-GimanoDataMode", env["GIMANO_DATA_MODE"] ?? "live", "-GimanoSkipOnboarding", "YES", "-GimanoNoAutoStart", "YES",
                    "-GimanoFakeLocation", "3.152613,101.703718", "-GimanoSimulateDrive", "YES", "-AppleLanguages", "(\(lang))"]
        if env["GIMANO_AX"] == "YES" { args += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launchArguments = args + extra
        app.launch()
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 40))
    }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = "\(tag)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    func note(_ name: String, _ text: String) {
        let a = XCTAttachment(string: text); a.name = "\(tag)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ t: TimeInterval = 25) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: t), "Timed out waiting for \(e)")
        return e
    }

    func startDrive() {
        wait(app.buttons["whereTo"]).tap()
        let f = wait(app.textFields["searchField"]); f.tap(); f.typeText("Mid Valley")
        wait(app.buttons.matching(identifier: "searchResult").firstMatch, 30).tap()
        wait(app.buttons["routesButton"]).tap()
        wait(app.buttons["goNow"], 40).tap()
        wait(app.staticTexts["maneuverDistance"], 30)
    }

    func test_1_ConfirmCard() {
        launch(["-GimanoFakeStillClosed", "YES"])
        startDrive()
        let card = app.descendants(matching: .any).matching(identifier: "stillClosedCard").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20), "Still closed? card")
        sleep(1)
        shot("1-confirm-card")
        let ids = ["issueVote.still", "issueVote.cleared", "issueVote.unsure"]
        let frames = ids.map { app.buttons[$0].frame }
        let labels = ids.map { app.buttons[$0].label }
        let ring = app.buttons["confirmCountdown"]
        note("1-confirm-metrics", "buttons \(frames.map { String(format: "%.0fx%.0f @%.0f", $0.width, $0.height, $0.minY) }) labels \(labels) ring '\(ring.exists ? (ring.value as? String ?? "") : "-")'")
        for f in frames { XCTAssertGreaterThanOrEqual(f.height, 83.5, "84 pt buttons") }
        XCTAssertEqual(frames[0].height, frames[1].height, accuracy: 0.5)
        XCTAssertEqual(frames[1].height, frames[2].height, accuracy: 0.5)
        XCTAssertEqual(frames[0].width, frames[2].width, accuracy: 1)
        // No answer: gone after the 10 s ring.
        sleep(11)
        XCTAssertFalse(card.exists, "the ring dismisses the card with no answer")
        shot("2-confirm-timed-out")
    }

    func test_2_SoundSelector() {
        launch()
        startDrive()
        let sound = wait(app.buttons["soundButton"])
        let before = sound.value as? String ?? ""
        sound.tap()
        wait(app.buttons["soundMode.alerts_only"], 5)
        sleep(1)
        shot("3-sound-panel")
        let slider = app.sliders["voiceVolume"]
        if slider.exists { slider.adjust(toNormalizedSliderPosition: 0.7) }
        sleep(1)
        shot("4-sound-panel-volume")
        app.buttons["soundMode.alerts_only"].tap()
        sleep(1)
        shot("5-alerts-only-toast")
        let afterPick = sound.value as? String ?? ""
        // Long-press: mute, then back.
        sound.press(forDuration: 0.8)
        sleep(1)
        shot("6-muted")
        let muted = sound.value as? String ?? ""
        sound.press(forDuration: 0.8)
        sleep(1)
        let back = sound.value as? String ?? ""
        // Remembered across drives (and app launches).
        app.terminate()
        launch()
        let remembered = wait(app.buttons["soundButton"]).value as? String ?? ""
        app.buttons["soundButton"].tap()
        sleep(1)
        shot("7-remembered-panel")
        let percent = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH '%'")).firstMatch
        note("2-sound", "before '\(before)' → picked '\(afterPick)' → long-press '\(muted)' → long-press '\(back)'; after relaunch '\(remembered)'; volume '\(percent.exists ? percent.label : "-")'")
        XCTAssertEqual(afterPick, back, "long-press returns to the mode before")
        XCTAssertNotEqual(muted, afterPick)
        XCTAssertEqual(remembered, afterPick, "remembered across launches")
        // Close by tapping outside.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.85)).tap()
        sleep(1)
        XCTAssertFalse(app.buttons["soundMode.alerts_only"].exists, "tap outside closes the panel")
        // Leave Full sound for the next run.
        app.buttons["soundButton"].tap()
        wait(app.buttons["soundMode.on"], 5).tap()
    }
}
