import XCTest

/// End-to-end scenarios (round 2) against the live backend (auto mode: demo fallback if unreachable).
/// Run on the iPhone: `xcodebuild test -scheme Gimano -destination id=<udid> -only-testing:GimanoUITests`.
/// Every step attaches a screenshot (exported from the .xcresult for the report).
final class GimanoUITests: XCTestCase {
    var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = [
            "-GimanoDataMode", "auto",
            "-GimanoFreshDevice", "YES",
            "-GimanoSkipOnboarding", "YES",
            // Fixed start on Jalan Sultan Ismail, KL (the phone may be anywhere) + simulated drive along the route.
            "-GimanoFakeLocation", "3.152613,101.703718",
            "-GimanoSimulateDrive", "YES",
            "-GimanoNoAutoStart", "YES",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_MY",
        ]
        app.launch()
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 30))
    }

    // MARK: helpers

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ timeout: TimeInterval = 25, _ msg: String = "") -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "Timed out waiting for \(e) \(msg)")
        return e
    }

    /// Opens search from home (or from the "add stop" button) and picks the first result for `query`.
    func searchAndPick(_ query: String, local: Bool = false) {
        let field = wait(app.textFields["searchField"])
        field.tap()
        field.typeText(query)
        let first = (local ? app.buttons.matching(identifier: "localResult") : app.buttons.matching(identifier: "searchResult")).firstMatch
        wait(first, 25, "results for \(query)")
        shot("search-\(query)")
        first.tap()
    }

    func openPlace(_ query: String) {
        wait(app.buttons["whereTo"]).tap()
        searchAndPick(query)
        wait(app.buttons["routesButton"])
    }

    func routeTo(_ query: String) {
        openPlace(query)
        app.buttons["routesButton"].tap()
        wait(app.buttons["goNow"], 40)
        // Wait for the route to finish loading (bubble or error).
        _ = app.buttons["routeBubble"].firstMatch.waitForExistence(timeout: 30)
    }

    func startNavigation() {
        wait(app.buttons["goNow"]).tap()
        wait(app.staticTexts["maneuverDistance"], 30)
    }

    func stopNavigation() {
        wait(app.buttons["etaClock"].exists ? app.buttons["etaClock"] : app.staticTexts["etaClock"]).tap()
        wait(app.buttons["stopNavigation"]).tap()
        wait(app.buttons["whereTo"])
    }

    func backToHome() {
        for _ in 0..<4 where !app.buttons["whereTo"].isHittable {
            let back = app.buttons["Back"].firstMatch
            if back.exists { back.tap() } else { break }
        }
    }

    func navDebug() -> [String: Int] {
        let label = wait(app.staticTexts["navDebug"]).label
        var out: [String: Int] = [:]
        for part in label.split(separator: ";") {
            let kv = part.split(separator: "=")
            if kv.count == 2, let v = Int(kv[1]) { out[String(kv[0])] = v }
        }
        return out
    }

    // MARK: scenarios

    func test_a_SingleDestinationCar() {
        routeTo("Mid Valley")
        app.buttons["mode.car"].tap()
        shot("a-route-preview-car")
        startNavigation()
        sleep(4)
        shot("a-navigation-car")
        stopNavigation()
    }

    func test_b_TwoStopTrip() {
        routeTo("Mid Valley")
        wait(app.buttons["addStopButton"]).tap()
        searchAndPick("KLCC")
        wait(app.buttons["stop.0"])
        wait(app.buttons["addStopButton"]).tap()
        searchAndPick("KL Sentral")
        wait(app.buttons["stop.1"])
        _ = app.staticTexts["stopEta.1"].waitForExistence(timeout: 30)
        shot("b-route-preview-2-stops")
        app.buttons["editStops"].tap()
        sleep(1)
        shot("b-stops-editor")
        app.buttons["Done"].firstMatch.tap()
        startNavigation()
        sleep(4)
        shot("b-navigation-2-stops")
        XCTAssertEqual(navDebug()["stopsLeft"], 2)
        stopNavigation()
    }

    func test_c_d_e_TravelModes() {
        routeTo("Pavilion Kuala Lumpur")
        for (mode, name) in [("motorcycle", "c-motorcycle"), ("bicycle", "d-bicycle"), ("walk", "e-walk")] {
            app.buttons["mode.\(mode)"].tap()
            sleep(3)
            _ = app.buttons["routeBubble"].firstMatch.waitForExistence(timeout: 20)
            shot("\(name)-route-preview")
            if mode != "motorcycle" {
                XCTAssertFalse(app.buttons["avoidMenu"].exists, "Avoid menu must be hidden for \(mode)")
                XCTAssertFalse(app.buttons["tollBadge"].exists, "No toll badge for \(mode)")
            }
        }
        // Walk: start, check guidance, stop.
        if !app.staticTexts["routeError"].exists {
            startNavigation()
            sleep(3)
            shot("e-walk-navigation")
            stopNavigation()
        }
        // The chosen mode is remembered.
        routeTo("KLCC")
        XCTAssertTrue(app.buttons["mode.walk"].isSelected, "travel mode should be remembered")
        app.buttons["mode.car"].tap()
    }

    func test_f_TollsAndAvoidTolls() {
        routeTo("Sunway Pyramid")
        app.buttons["mode.car"].tap()
        let badge = wait(app.buttons["tollBadge"], 30, "toll badge")
        shot("f-toll-badge-\(badge.label)")
        badge.tap()
        wait(app.staticTexts["tollTotal"])
        shot("f-toll-plazas")
        app.swipeDown(velocity: .fast)
        sleep(1)
        wait(app.buttons["avoidMenu"]).tap()
        wait(app.switches["avoidTolls"].exists ? app.switches["avoidTolls"] : app.buttons["avoidTolls"]).tap()
        sleep(1)
        if app.buttons["avoidMenu"].exists { app.tap() }
        sleep(4)
        shot("f-avoid-tolls")
    }

    func test_g_Favourites() {
        func save(_ query: String, _ action: () -> Void) {
            openPlace(query)
            wait(app.buttons["savePlace"]).tap()
            action()
            sleep(1)
            backToHome()
        }
        save("Taman Tun Dr Ismail") { self.wait(self.app.buttons["setAsHome"]).tap() }
        save("Bangsar South") { self.wait(self.app.buttons["setAsWork"]).tap() }
        save("Batu Caves") {
            self.wait(self.app.buttons["quickLabel.Kampung"]).tap()
            self.shot("g-save-sheet-kampung")
            self.app.buttons["saveFavorite"].tap()
        }
        save("Shah Alam") {
            self.wait(self.app.buttons["quickLabel.Rumah Mertua"]).tap()
            self.app.buttons["saveFavorite"].tap()
        }
        let mertua = wait(app.buttons["favoriteTile.Rumah Mertua"])
        XCTAssertTrue(app.buttons["favoriteTile.Kampung"].exists)
        shot("g-home-favourites")
        // Local search finds favourites first.
        app.buttons["whereTo"].tap()
        searchAndPick("Mertua", local: true)
        wait(app.buttons["routesButton"])
        backToHome()
        wait(app.buttons["favoriteTile.Rumah Mertua"]).tap()
        wait(app.buttons["goNow"], 40)
        _ = app.buttons["routeBubble"].firstMatch.waitForExistence(timeout: 30)
        shot("g-route-to-mertua")
        startNavigation()
        sleep(3)
        shot("g-navigation-to-mertua")
        stopNavigation()
        _ = mertua
        // Manage: favourites list
        wait(app.buttons["editFavorites"]).tap()
        wait(app.buttons["favoriteRow.Kampung"])
        shot("g-manage-favourites")
        app.buttons["Done"].firstMatch.tap()
    }

    func test_h_PoiSearchAndSurauChip() {
        routeTo("petronas")
        shot("h-petronas-route")
        startNavigation()
        sleep(3)
        shot("h-petronas-navigation")
        stopNavigation()
        wait(app.buttons["shortcut.surau"]).tap()
        let first = wait(app.buttons.matching(identifier: "searchResult").firstMatch, 30, "surau nearby")
        shot("h-surau-chip-results")
        first.tap()
        wait(app.buttons["routesButton"]).tap()
        wait(app.buttons["goNow"], 40)
        _ = app.buttons["routeBubble"].firstMatch.waitForExistence(timeout: 30)
        shot("h-surau-route")
        startNavigation()
        sleep(3)
        shot("h-surau-navigation")
        stopNavigation()
    }

    func test_i_Feedback() {
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuAddFeedback"]).tap()
        wait(app.buttons["feedbackCategory.bug"]).tap()
        let msg = app.textFields["feedbackMessage"].exists ? app.textFields["feedbackMessage"] : app.textViews["feedbackMessage"]
        msg.tap()
        msg.typeText("UI test: the toll badge looks great. (automated)")
        app.buttons["feedbackStar.5"].tap()
        shot("i-feedback-form")
        app.buttons["sendFeedback"].tap()
        sleep(3)
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuMyFeedback"]).tap()
        wait(app.descendants(matching: .any)["feedbackRow"].firstMatch, 20)
        sleep(2)
        shot("i-my-feedback")
    }

    func test_j_SimulatedDriveStaysOnRoad() {
        routeTo("Mid Valley")
        startNavigation()
        sleep(25)
        shot("j-drive-after-25s")
        let d = navDebug()
        XCTAssertLessThanOrEqual(d["offroad"] ?? 999, 15, "puck left the road")
        XCTAssertEqual(d["reroutes"], 0, "no reroutes while following the route")
        stopNavigation()
    }

    func test_k_BackupRestore() {
        // A favourite to back up.
        openPlace("Batu Caves")
        wait(app.buttons["savePlace"]).tap()
        wait(app.buttons["quickLabel.Kampung"]).tap()
        app.buttons["saveFavorite"].tap()
        sleep(1)
        backToHome()
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuBackup"]).tap()
        wait(app.buttons["backupDestination.icloud"]).tap()
        sleep(3)
        shot("k-backup-icloud")
        if app.buttons["backUpNow"].exists && app.buttons["backUpNow"].isEnabled {
            app.buttons["backUpNow"].tap()
            _ = app.staticTexts["lastBackup"].waitForExistence(timeout: 60)
            sleep(2)
            shot("k-backup-icloud-done")
        }
        wait(app.buttons["backupDestination.file"]).tap()
        let selfTest = app.buttons["backupSelfTest"]
        for _ in 0..<5 where !selfTest.isHittable { app.swipeUp() }
        selfTest.tap()
        let result = wait(app.staticTexts["backupSelfTestResult"], 60)
        shot("k-backup-file-selftest")
        XCTAssertTrue(result.label.hasPrefix("PASS plain"), result.label)
        // Encrypted variant.
        for _ in 0..<5 where !app.switches["backupPassphraseToggle"].isHittable { app.swipeDown() }
        app.switches["backupPassphraseToggle"].switches.firstMatch.tap()
        app.secureTextFields["backupPassphrase"].tap()
        app.secureTextFields["backupPassphrase"].typeText("kampung-2026!")
        app.secureTextFields["backupPassphraseConfirm"].tap()
        app.secureTextFields["backupPassphraseConfirm"].typeText("kampung-2026!")
        for _ in 0..<5 where !selfTest.isHittable { app.swipeUp() }
        selfTest.tap()
        let r2 = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'PASS encrypted' OR label BEGINSWITH 'FAIL'")).firstMatch
        wait(r2, 90)
        shot("k-backup-file-selftest-encrypted")
        XCTAssertTrue(r2.label.hasPrefix("PASS encrypted"), r2.label)
    }
}
