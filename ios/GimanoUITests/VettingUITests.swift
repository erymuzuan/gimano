import XCTest

/// Nearby verify prompt (demo: a pending place 250 m away), Account (pseudonym, tier, recovery code).
final class VettingUITests: XCTestCase {
    var app: XCUIApplication!

    func launch(_ extra: [String] = []) {
        app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", ProcessInfo.processInfo.environment["GIMANO_DATA_MODE"] ?? "demo",
                               "-GimanoFreshDevice", "YES", "-GimanoSkipOnboarding", "YES",
                               "-GimanoFakeLocation", "3.152613,101.703718", "-AppleLanguages", "(en)"] + extra
        app.launch()
    }

    override func setUp() { continueAfterFailure = false }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ t: TimeInterval = 25) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: t), "Timed out waiting for \(e)")
        return e
    }

    func test_x_NearbyVerifyPrompt() {
        launch(["-GimanoVetNow", "YES"])
        wait(app.staticTexts["vettingPlaceName"], 40)
        XCTAssertTrue(app.staticTexts["badgeUnverifiedCount"].exists)
        shot("x-vetting-prompt")
        app.buttons["vote.yes"].tap()
        wait(app.staticTexts["vettingThanks"], 20)
        shot("x-vetting-thanks")
        app.buttons["Done"].firstMatch.tap()
    }

    func test_y_AccountAndRecoveryCode() {
        launch()
        wait(app.buttons["whereTo"], 30)
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["Settings"]).tap()
        let account = app.buttons["settingsAccount"]
        for _ in 0..<8 where !(account.exists && account.isHittable) { app.swipeUp() }
        wait(account).tap()
        wait(app.staticTexts["accountPseudonym"])
        shot("y-account")
        app.buttons["showRecoveryCode"].tap()
        // Face ID / passcode prompt is system UI; on a device without enrolment it falls back to the passcode.
        if app.otherElements["recoveryWords"].waitForExistence(timeout: 20) || app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH '1. '")).firstMatch.waitForExistence(timeout: 5) {
            shot("y-recovery-code")
        } else {
            shot("y-recovery-auth")
        }
    }
}
