import XCTest

/// Camera / library menu (permission denied and allowed), Manotu icon on the home screen, About.
/// Run after a fresh install so the camera permission prompt appears.
final class PhotoAndBrandUITests: XCTestCase {
    var app: XCUIApplication!
    var springboard: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.springboard") }
    var lang: String { ProcessInfo.processInfo.environment["GIMANO_LANG"] ?? "en" }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "auto", "-GimanoSkipOnboarding", "YES", "-GimanoFakeLocation", "3.1178,101.6213",
                               "-AppleLanguages", "(\(lang))", "-AppleLocale", lang == "ms" ? "ms_MY" : "en_MY"]
        app.launch()
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 30))
    }

    func shot(_ name: String, _ target: XCUIApplication? = nil) {
        let a = XCTAttachment(screenshot: (target ?? app).screenshot())
        a.name = "\(lang)-\(name)"
        a.lifetime = .keepAlways
        add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ t: TimeInterval = 20) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: t), "Timed out waiting for \(e)")
        return e
    }

    func openAddPhotos() {
        app.buttons["whereTo"].tap()
        let f = wait(app.textFields["searchField"])
        f.tap()
        f.typeText("7 eleven")
        wait(app.buttons.matching(identifier: "searchResult").firstMatch, 30).tap()
        let add = app.buttons["placeAddPhoto"]
        for _ in 0..<8 where !(add.exists && add.isHittable) { app.swipeUp() }
        wait(add).tap()
        wait(app.buttons["addPhotoMenu"]).tap()
        sleep(1)
        shot("camera-menu")
    }

    func test_1_CameraDeniedThenLibrary() {
        openAddPhotos()
        wait(app.buttons["takePhoto"]).tap()
        let deny = springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Don'")).firstMatch
        if deny.waitForExistence(timeout: 8) { shot("camera-permission-prompt", springboard); deny.tap() }
        let alert = app.alerts.firstMatch
        wait(alert, 10)
        shot("camera-denied-alert")
        alert.buttons.element(boundBy: 1).tap()          // "Choose from library"
        sleep(3)
        shot("library-picker")
        XCTAssertTrue(app.state == .runningForeground)
    }

    func test_2_BrandIconAndAbout() {
        wait(app.buttons["menuButton"]).tap()
        let v = wait(app.staticTexts["menuVersion"])
        XCTAssertTrue(v.label.hasPrefix("Manotu 0.2.3"), v.label)
        app.buttons.matching(NSPredicate(format: "label IN {'Settings', 'Tetapan'}")).firstMatch.tap()
        let about = app.buttons["settingsAbout"]
        for _ in 0..<8 where !(about.exists && about.isHittable) { app.swipeUp() }
        about.tap()
        wait(app.staticTexts["formerlyGimano"])
        shot("about")
        XCUIDevice.shared.press(.home)
        sleep(2)
        let icon = springboard.icons["Manotu"]
        XCTAssertTrue(icon.waitForExistence(timeout: 5), "Manotu on the home screen")
        shot("home-screen", springboard)
    }
}
final class CameraAllowedUITests: XCTestCase {
    func test_CameraAllowed() {
        let app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "auto", "-GimanoSkipOnboarding", "YES", "-GimanoFakeLocation", "3.1178,101.6213", "-AppleLanguages", "(en)"]
        app.launch()
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 30))
        app.buttons["whereTo"].tap()
        let f = app.textFields["searchField"]; XCTAssertTrue(f.waitForExistence(timeout: 10)); f.tap(); f.typeText("7 eleven")
        let r = app.buttons.matching(identifier: "searchResult").firstMatch; XCTAssertTrue(r.waitForExistence(timeout: 30)); r.tap()
        let addBtn = app.buttons["placeAddPhoto"]
        for _ in 0..<8 where !(addBtn.exists && addBtn.isHittable) { app.swipeUp() }
        addBtn.tap()
        XCTAssertTrue(app.buttons["addPhotoMenu"].waitForExistence(timeout: 10)); app.buttons["addPhotoMenu"].tap()
        XCTAssertTrue(app.buttons["takePhoto"].waitForExistence(timeout: 5)); app.buttons["takePhoto"].tap()
        let allow = sb.buttons.matching(NSPredicate(format: "label IN {'Allow', 'OK'}")).firstMatch
        if allow.waitForExistence(timeout: 8) { allow.tap() }
        let shutter = app.buttons["PhotoCapture"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 15), "camera opened")
        let s = XCTAttachment(screenshot: app.screenshot()); s.name = "en-camera-open"; s.lifetime = .keepAlways; add(s)
        shutter.tap()
        let use = app.buttons.matching(NSPredicate(format: "label IN {'Use Photo', 'Done'}")).firstMatch
        XCTAssertTrue(use.waitForExistence(timeout: 10)); use.tap()
        XCTAssertTrue(app.descendants(matching: .any)["pickedPhoto"].waitForExistence(timeout: 10), "photo added")
        let s2 = XCTAttachment(screenshot: app.screenshot()); s2.name = "en-camera-photo-added"; s2.lifetime = .keepAlways; add(s2)
    }
}
