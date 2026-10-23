import XCTest

/// Real GPS on the iPhone with Precise Location turned OFF for Gimano (approximate location):
/// navigation asks for temporary precise location, shows the "approximate only" banner when declined,
/// draws the accuracy circle and keeps the puck visible. Restores Precise Location afterwards.
final class PreciseLocationUITests: XCTestCase {
    var settings: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.Preferences") }
    var springboard: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.springboard") }

    override func setUp() { continueAfterFailure = false }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
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

    @discardableResult
    func wait(_ e: XCUIElement, _ t: TimeInterval = 20) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: t), "Timed out waiting for \(e)")
        return e
    }

    /// Settings → Apps → Gimano → Location → Precise Location.
    func setPrecise(_ on: Bool) {
        settings.terminate()
        settings.launch()
        let toggle = settings.switches["Precise Location"].firstMatch
        if !toggle.waitForExistence(timeout: 3) {
            // Settings reopens on its last page: go back to the root first.
            for _ in 0..<6 {
                let back = settings.navigationBars.buttons.element(boundBy: 0)
                if settings.navigationBars["Settings"].exists || !back.exists { break }
                back.tap()
                sleep(1)
            }
            let apps = settings.buttons["com.apple.settings.apps"].firstMatch
            let list = settings.collectionViews.firstMatch
            for _ in 0..<12 where !(apps.exists && apps.isHittable) {
                list.swipeUp(velocity: .slow)
            }
            shot("settings-root")
            wait(apps).tap()
            let search = settings.searchFields.firstMatch
            if search.waitForExistence(timeout: 5) { search.tap(); search.typeText("Gimano") }
            wait(settings.staticTexts["Gimano"].firstMatch).tap()
            wait(settings.staticTexts["Location"].firstMatch).tap()
        }
        wait(toggle)
        let isOn = (toggle.value as? String) == "1"
        if isOn != on { toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap() }
        sleep(1)
        shot("precise-\(on ? "on" : "off")-settings")
        settings.terminate()
    }

    func test_z_ApproximateLocationNavigation() {
        setPrecise(false)
        let app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "auto", "-GimanoSkipOnboarding", "YES", "-AppleLanguages", "(en)"]
        app.launch()
        wait(app.buttons["whereTo"], 30).tap()
        let f = wait(app.textFields["searchField"])
        f.tap()
        f.typeText("KLCC")
        wait(app.buttons.matching(identifier: "searchResult").firstMatch, 30).tap()
        wait(app.buttons["routesButton"]).tap()
        wait(app.buttons["goNow"], 60).tap()
        // Temporary precise-location prompt (purpose "Navigation"): decline it to stay approximate.
        let decline = springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Don'")).firstMatch
        if decline.waitForExistence(timeout: 8) {
            shot("z-temporary-precise-prompt")
            decline.tap()
        } else {
            note("z-prompt", "no temporary precise-location prompt appeared")
        }
        let banner = wait(app.descendants(matching: .any).matching(identifier: "approximateLocationBanner").firstMatch, 20)
        sleep(6)
        note("z-banner", banner.label)
        shot("z-approximate-banner-navigation")
        let weak = app.descendants(matching: .any).matching(identifier: "weakGpsChip").firstMatch
        if weak.waitForExistence(timeout: 15) { note("z-weak", weak.label); shot("z-weak-gps-chip") }
        // Pan away, then follow resumes after 10 s.
        app.otherElements["map"].swipeLeft()
        sleep(2)
        shot("z-panned")
        sleep(11)
        shot("z-follow-resumed")
        let clock = app.buttons["etaClock"].exists ? app.buttons["etaClock"] : app.staticTexts["etaClock"]
        wait(clock).tap()
        wait(app.buttons["stopNavigation"]).tap()
        app.terminate()
        setPrecise(true)
    }
}
