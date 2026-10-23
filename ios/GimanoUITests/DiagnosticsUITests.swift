import XCTest

/// Feedback with consented device info + optional contact; the next-launch crash prompt; About / version.
final class DiagnosticsUITests: XCTestCase {
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

    func scrollTo(_ e: XCUIElement) {
        for _ in 0..<8 where !(e.exists && e.isHittable) { app.swipeUp() }
    }

    func test_u_FeedbackDeviceInfoAndContact() {
        launch()
        wait(app.buttons["whereTo"], 30)
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuAddFeedback"]).tap()
        wait(app.buttons["feedbackCategory.bug"]).tap()
        let msg = app.textFields["feedbackMessage"].exists ? app.textFields["feedbackMessage"] : app.textViews["feedbackMessage"]
        msg.tap()
        msg.typeText("Ujian maklum balas dengan hubungan")
        let device = app.switches["includeDevice"]
        scrollTo(device)
        XCTAssertEqual(device.value as? String, "1", "device info is on by default")
        XCTAssertEqual(app.switches["includeLocation"].value as? String, "0", "coarse location is off by default")
        app.buttons["deviceInfoPreviewButton"].tap()
        let preview = wait(app.staticTexts["deviceInfoPreview"])
        XCTAssertTrue(preview.label.contains("\"platform\" : \"ios\""), preview.label)
        XCTAssertTrue(preview.label.contains("\"osVersion\" : \"iOS"), preview.label)
        shot("u-feedback-device-preview")
        let email = app.textFields["contactEmail"]
        scrollTo(email)
        XCTAssertTrue(["", "Email"].contains(email.value as? String ?? ""), "contact is never prefilled: \(String(describing: email.value))")
        email.tap()
        email.typeText("ali@example.com")
        scrollTo(app.buttons["sendFeedback"])
        app.buttons["sendFeedback"].tap()
        wait(app.staticTexts["contactError"])          // consent required
        let consent = app.switches["contactConsent"]
        scrollTo(consent)
        consent.switches.firstMatch.exists ? consent.switches.firstMatch.tap() : consent.tap()
        shot("u-feedback-contact")
        scrollTo(app.buttons["sendFeedback"])
        app.buttons["sendFeedback"].tap()
        sleep(3)
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuMyFeedback"]).tap()
        let row = wait(app.descendants(matching: .any)["feedbackRow"].firstMatch, 20)
        row.swipeLeft()
        if app.buttons["deleteContact"].waitForExistence(timeout: 5) {
            shot("u-delete-contact")
            app.buttons["deleteContact"].tap()
            sleep(2)
        } else {
            shot("u-no-contact-action")
        }
    }

    func test_v_CrashPromptOnNextLaunch() {
        launch(["-GimanoFakeCrash", "YES"])
        let send = wait(app.buttons["sendCrashReport"], 40)
        shot("v-crash-prompt")
        app.buttons["crashDetailsButton"].tap()
        let details = wait(app.staticTexts["crashDetails"])
        XCTAssertFalse(details.label.contains("3.152613"), "coordinates are scrubbed")
        XCTAssertFalse(details.label.contains("ali@example.com"), "emails are scrubbed")
        XCTAssertFalse(details.label.contains("/var/mobile/Containers"), "paths are scrubbed")
        XCTAssertTrue(details.label.contains("\"deviceModel\""), "device block is shown")
        shot("v-crash-details")
        send.tap()
        wait(app.buttons["whereTo"], 20)
        sleep(2)
        shot("v-crash-sent")
    }

    func test_w_AboutAndVersion() {
        launch()
        wait(app.buttons["whereTo"], 30)
        wait(app.buttons["menuButton"]).tap()
        let version = wait(app.staticTexts["menuVersion"])
        XCTAssertTrue(version.label.hasPrefix("Manotu 0."), version.label)
        for _ in 0..<7 { version.tap() }
        sleep(1)
        shot("w-menu-version-copied")
        wait(app.buttons["Settings"]).tap()
        let about = app.buttons["settingsAbout"]
        scrollTo(about)
        about.tap()
        wait(app.staticTexts["aboutVersion"])
        shot("w-about")
    }
}
