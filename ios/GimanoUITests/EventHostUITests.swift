import XCTest

/// Organizer flow (wizard boards EwType … EwShare): create an event step by step, add places from the map,
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

        // 1 · Jenis
        wait(app.buttons["eventTemplate.kenduri"]).tap()
        shot(app, "wizard-1-type")
        wait(app.buttons["eventWizardNext"]).tap()

        // 2 · Bila: the name starts as the template's.
        let title = wait(app.textFields["eventTitleField"])
        title.tap()
        title.typeText(" UI Test")
        shot(app, "wizard-2-when")
        app.buttons["eventWizardNext"].tap()

        // 3 · Di mana: main venue, then a parking place.
        wait(app.buttons["eventAddPlace"]).tap()
        wait(app.buttons["eventPickOnMap"]).tap()
        wait(app.buttons["selectChoose"], 20).tap()
        wait(app.buttons["eventAddStop.parking"], 20).tap()
        wait(app.buttons["eventPickOnMap"]).tap()
        wait(app.buttons["selectChoose"], 20).tap()
        let label = wait(app.textFields["eventStopLabel"], 20)
        label.tap()
        label.typeText(" tetamu")
        wait(app.buttons["eventStopDone"]).tap()
        shot(app, "wizard-3-place")
        wait(app.buttons["eventWizardNext"]).tap()

        // 4 · Info
        wait(app.buttons["eventAddInfo.dress"]).tap()
        let dress = wait(app.textFields["eventInfoText.dress"])
        dress.tap()
        dress.typeText("Batik")
        shot(app, "wizard-4-info")
        app.buttons["eventWizardNext"].tap()

        // 5 · Semak → Kongsi
        wait(app.buttons["eventReview.when"])
        wait(app.staticTexts["eventHostSafetyCode"])
        shot(app, "wizard-5-review")
        wait(app.buttons["eventShareInvitation"]).tap()
        wait(app.staticTexts["eventShareVersion"], 20)
        // Link first: one message with the link; no file, no code, no sensitive toggle.
        XCTAssertTrue(app.buttons["eventShareLink"].exists)
        let message = wait(app.staticTexts["eventShareMessage"])
        XCTAssertTrue(message.value.map { "\($0)".contains("…") } ?? false, "link shortened in the preview")
        XCTAssertFalse(app.buttons["eventShareFile"].exists)
        XCTAssertFalse(app.switches["eventShareSensitive"].exists)
        XCTAssertFalse(app.staticTexts["eventSecretCodeValue"].exists, "no code on the share screen")
        wait(app.staticTexts["eventShareHint"])
        shot(app, "event-share")
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
        // Editing an existing event opens on "Semak".
        wait(app.buttons["eventReview.when"])
        shot(app, "event-edit-review")
    }
}
