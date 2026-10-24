import XCTest

/// "Bina laluan" from "Laluan saya": tap three points on the map, wait for the legs to be routed (auto mode: the live
/// server with `store:false`; offline they stay "to calculate"), check the point list, then save the route.
final class RouteBuilderUITests: XCTestCase {
    var lang: String { ProcessInfo.processInfo.environment["GIMANO_LANG"] ?? "en" }

    func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = "\(lang)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ timeout: TimeInterval = 15, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "\(e) missing", file: file, line: line)
        return e
    }

    func test_BuildThreePointRoute() {
        let app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", ProcessInfo.processInfo.environment["GIMANO_DATA_MODE"] ?? "auto",
                               "-GimanoSkipOnboarding", "YES", "-GimanoFakeLocation", "3.152613,101.703718",
                               "-AppleLanguages", "(\(lang))"]
        app.launch()
        wait(app.buttons["menuButton"], 30).tap()
        wait(app.buttons["menuMyRoutes"]).tap()
        wait(app.buttons["myRoutesNew"]).tap()

        let map = wait(app.otherElements["routeBuilderMap"], 20)
        wait(app.staticTexts["routeBuilderHint"])
        shot(app, "route-start")
        sleep(2)   // let the style load
        for (x, y) in [(0.25, 0.30), (0.70, 0.36), (0.45, 0.52)] {
            map.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
            sleep(1)
            shot(app, "route-tap-\(x)")
        }
        // Each leg is routed on its own; wait until nothing is being calculated.
        let calculating = app.staticTexts.matching(NSPredicate(format: "label IN {'Calculating…', 'Mengira…'}")).firstMatch
        for _ in 0..<30 where calculating.exists { sleep(1) }
        shot(app, "route-three-points")

        wait(app.buttons["routePointList"]).tap()
        for i in 0..<3 { wait(app.descendants(matching: .any)["routePoint.\(i)"]) }
        XCTAssertFalse(app.descendants(matching: .any)["routePoint.3"].exists)
        shot(app, "route-point-list")
        app.buttons.matching(NSPredicate(format: "label IN {'Done', 'Selesai'}")).firstMatch.tap()

        wait(app.buttons["routeSave"]).tap()
        let name = wait(app.textFields["routeNameField"])
        name.tap()
        name.typeText("Kayuh UI Test")
        shot(app, "route-save")
        wait(app.buttons["routeSaveConfirm"]).tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'myRoute.'")).firstMatch.waitForExistence(timeout: 10))
        shot(app, "route-saved")
    }

    /// "Lukis": a one-finger drag draws a line. Servers without `/routes/match` keep it as a labelled sketch.
    func test_DrawLine() {
        let app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", ProcessInfo.processInfo.environment["GIMANO_DATA_MODE"] ?? "auto",
                               "-GimanoSkipOnboarding", "YES", "-GimanoFakeLocation", "3.152613,101.703718",
                               "-AppleLanguages", "(\(lang))"]
        app.launch()
        wait(app.buttons["menuButton"], 30).tap()
        wait(app.buttons["menuMyRoutes"]).tap()
        wait(app.buttons["myRoutesNew"]).tap()
        let map = wait(app.otherElements["routeBuilderMap"], 20)
        sleep(2)
        map.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.32)).tap()
        sleep(1)
        wait(app.buttons["routeTool.draw"]).tap()
        let from = map.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.32))
        from.press(forDuration: 0.05, thenDragTo: map.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
        let calculating = app.staticTexts.matching(NSPredicate(format: "label IN {'Calculating…', 'Mengira…'}")).firstMatch
        sleep(1)
        for _ in 0..<30 where calculating.exists { sleep(1) }
        shot(app, "route-drawn")
        wait(app.buttons["routePointList"]).tap()
        wait(app.descendants(matching: .any)["routePoint.1"])
    }
}
