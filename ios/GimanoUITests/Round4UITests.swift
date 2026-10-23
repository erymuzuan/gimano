import XCTest

/// Theme, planned drives and share-my-drive.
final class Round4UITests: XCTestCase {
    var app: XCUIApplication!

    var dataMode: String { ProcessInfo.processInfo.environment["GIMANO_DATA_MODE"] ?? "auto" }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = [
            "-GimanoDataMode", dataMode,
            "-GimanoFreshDevice", "YES",
            "-GimanoSkipOnboarding", "YES",
            "-GimanoFakeLocation", "3.152613,101.703718",
            "-GimanoSimulateDrive", "YES",
            "-GimanoNoAutoStart", "YES",
            "-AppleLanguages", "(en)",
        ]
        // System permission prompts (notifications).
        addUIInterruptionMonitor(withDescription: "System alert") { alert in
            for label in ["Allow", "OK", "Allow While Using App"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
        app.launch()
        dismissSystemAlerts(timeout: 2)
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 30))
    }

    /// Answers SpringBoard permission alerts (notifications) directly; they can outlive an app relaunch.
    func dismissSystemAlerts(timeout: TimeInterval) {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow", "Allow While Using App", "OK"] {
            let b = sb.buttons[label]
            if b.waitForExistence(timeout: timeout) { b.tap(); return }
        }
    }

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

    func openSettings() {
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["Settings"]).tap()
    }

    func routeToMidValley() {
        wait(app.buttons["whereTo"]).tap()
        let f = wait(app.textFields["searchField"])
        f.tap()
        f.typeText("Mid Valley")
        wait(app.buttons.matching(identifier: "searchResult").firstMatch).tap()
        wait(app.buttons["routesButton"]).tap()
        wait(app.buttons["goNow"], 40)
        _ = app.buttons["routeBubble"].firstMatch.waitForExistence(timeout: 30)
    }

    func test_o_ThemeSettings() {
        openSettings()
        let dark = wait(app.buttons["theme.dark"])
        XCTAssertTrue(app.buttons["theme.light"].isSelected, "Light is the default")
        shot("o-theme-settings-light-default")
        dark.tap()
        sleep(2)
        shot("o-theme-dark-settings")
        app.buttons["Done"].firstMatch.tap()
        sleep(3)
        shot("o-theme-dark-home")
        openSettings()
        wait(app.buttons["theme.auto"]).tap()
        sleep(1)
        shot("o-theme-auto")
        app.buttons["theme.light"].tap()
        app.buttons["Done"].firstMatch.tap()
    }

    func test_p_PlannedDrive() {
        routeToMidValley()
        wait(app.buttons["Leave later"]).tap()
        wait(app.buttons["planThisDrive"]).tap()
        for d in ["Mo", "Tu", "We", "Th", "Fr"] { wait(app.buttons["repeat.\(d)"]).tap() }
        shot("p-planned-drive-editor")
        app.buttons["savePlannedDrive"].tap()
        dismissSystemAlerts(timeout: 8)
        sleep(3)
        for _ in 0..<5 where !app.buttons["menuButton"].exists {
            let back = app.buttons["Back"].firstMatch
            if back.exists && back.isHittable { back.tap() } else { app.tap() }
            sleep(2)
        }
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["Planned drives"]).tap()
        wait(app.buttons["plannedDrive.Mid Valley Megamall"].exists ? app.buttons["plannedDrive.Mid Valley Megamall"]
             : app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'plannedDrive.'")).firstMatch)
        _ = app.staticTexts["leaveBy"].waitForExistence(timeout: 20)
        shot("p-planned-drives-list")
        XCTAssertTrue(app.staticTexts["leaveBy"].exists, "a Leave-by time from /routes/departure-times")
    }

    func test_q_ShareMyDrive() {
        routeToMidValley()
        wait(app.buttons["goNow"]).tap()
        wait(app.staticTexts["maneuverDistance"], 30)
        let eta = app.buttons["etaClock"].exists ? app.buttons["etaClock"] : app.staticTexts["etaClock"]
        wait(eta).tap()
        wait(app.buttons["shareDrive"]).tap()
        wait(app.buttons["sendShareLink"], 30)
        let link = app.staticTexts["shareLink"].label
        XCTAssertTrue(link.hasPrefix("https://www.bukitbunga.com/gimano/s/#"), link)
        XCTAssertEqual(link.split(separator: "#").last?.split(separator: ".").count, 2)
        shot("q-share-sheet")
        app.swipeDown(velocity: .fast)
        wait(app.buttons["sharingChip"])
        sleep(2)
        shot("q-sharing-chip-in-navigation")
        app.buttons["stopSharingChip"].tap()
        sleep(1)
        XCTAssertFalse(app.buttons["sharingChip"].exists)
        shot("q-sharing-stopped")
    }
}
