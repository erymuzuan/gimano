import XCTest

/// Organizer flow (boards "1 · Acara baharu" and "2 · Kongsi jemputan"): create an event, add a place from the map,
/// publish, copy the link, then open that link as the host: it must open the host's own event, not an import.
final class EventHostUITests: XCTestCase {
    var lang: String { ProcessInfo.processInfo.environment["GIMANO_LANG"] ?? "en" }

    func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = "\(lang)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ timeout: TimeInterval = 15, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "\(e) missing", file: file, line: line)
        return e
    }

    func test_CreateShareAndOpenOwnLink() {
        let app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "demo", "-GimanoSkipOnboarding", "YES",
                               "-GimanoFakeLocation", "3.152613,101.703718", "-AppleLanguages", "(\(lang))"]
        app.launch()
        wait(app.buttons["menuButton"], 30).tap()
        wait(app.buttons["menuEvents"]).tap()
        wait(app.buttons["eventNew"]).tap()

        let title = wait(app.textFields["eventTitleField"])
        title.tap()
        title.typeText("Kenduri UI Test")
        shot(app, "event-new")

        wait(app.buttons["eventAddPlace"]).tap()
        wait(app.buttons["eventPickOnMap"]).tap()
        wait(app.buttons["selectChoose"], 20).tap()

        let label = wait(app.textFields["eventStopLabel"], 20)
        label.tap()
        label.typeText("Parkir tetamu")
        wait(app.buttons["eventStopDone"]).tap()
        shot(app, "event-with-place")

        let share = app.buttons["eventShareInvitation"]
        for _ in 0..<6 where !share.isHittable { app.swipeUp() }
        wait(share).tap()
        wait(app.staticTexts["eventShareVersion"], 20)
        shot(app, "event-share")
        app.buttons["eventSecretCodeShow"].tap()
        XCTAssertFalse(app.staticTexts["eventSecretCodeValue"].label.contains("•"))
        wait(app.buttons["eventCopyLink"]).tap()
        wait(app.buttons["eventShareDone"]).tap()

        // Back on the list: the event is listed under "I'm organizing"; paste our own link.
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'eventHosted.'")).firstMatch.waitForExistence(timeout: 10))
        let pasteButton = app.buttons.matching(NSPredicate(format: "label IN {'Paste', 'Tampal'}")).firstMatch
        wait(pasteButton).tap()
        wait(app.buttons["eventImportOpen"]).tap()
        wait(app.buttons["eventOpenOwn"], 20)
        shot(app, "event-own-link")
        app.buttons["eventOpenOwn"].tap()
        wait(app.textFields["eventTitleField"])
    }
}
