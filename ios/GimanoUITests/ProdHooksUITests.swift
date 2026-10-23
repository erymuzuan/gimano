import XCTest

/// Debug build pointed at PRODUCTION: a crash report reaches POST /crash-reports (201 + signature) and a fresh
/// pseudonym survives a recovery-code round trip (sign-in by key). Run on the iPhone.
final class ProdHooksUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    func note(_ name: String, _ text: String) {
        let a = XCTAttachment(string: text)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    func test_CrashReportAndRecoveryOnProd() {
        let app = XCUIApplication()
        app.launchArguments = ["-GimanoAPIBaseURL", "https://gimano-api.bukitbunga.com/api/v1/", "-GimanoDataMode", "live",
                               "-GimanoFreshDevice", "YES", "-GimanoSkipOnboarding", "YES", "-GimanoFakeCrash", "YES",
                               "-GimanoRecoveryRoundTrip", "YES", "-GimanoFakeLocation", "3.152613,101.703718", "-AppleLanguages", "(en)"]
        app.launch()
        let send = app.buttons["sendCrashReport"]
        XCTAssertTrue(send.waitForExistence(timeout: 60), "crash prompt")
        shot(app, "prod-crash-prompt")
        send.tap()
        sleep(5)
        XCTAssertTrue(app.buttons["menuButton"].waitForExistence(timeout: 20))
        app.buttons["menuButton"].tap()
        XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 10))
        app.buttons["Settings"].tap()
        let about = app.buttons["settingsAbout"]
        for _ in 0..<8 where !(about.exists && about.isHittable) { app.swipeUp() }
        about.tap()
        let crash = app.staticTexts["debugCrashLastSent"]
        XCTAssertTrue(crash.waitForExistence(timeout: 10), "crash report was not accepted")
        let recovery = app.staticTexts["debugRecovery"]
        XCTAssertTrue(recovery.waitForExistence(timeout: 10), "recovery round trip did not run")
        note("prod-crash", crash.label)
        note("prod-recovery", recovery.label)
        shot(app, "prod-about-debug")
        XCTAssertTrue(recovery.label.contains("PASS"), recovery.label)
    }
}
