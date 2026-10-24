import XCTest

/// The account-free review flow, as any user sees it: Demo mode turned on in Settings from outside Malaysia (a fixed
/// position in London), then search, place card, a simulated drive with the "Still closed?" prompt and the sound
/// selector, a report, a review, a complaint, a favourite, a planned drive, a demo-storage backup → restore round trip,
/// and Demo mode turned off from the badge. Nothing here is reviewer-only; nothing is sent (Demo mode).
/// Environment: GIMANO_LANG (en|ms). Build with GIMANO_ICLOUD=NO GIMANO_DRIVE=NO (the store build).
final class DemoReviewUITests: XCTestCase {
    var app: XCUIApplication!
    var env: [String: String] { ProcessInfo.processInfo.environment }
    var lang: String { env["GIMANO_LANG"] ?? "en" }
    var springboard: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.springboard") }

    override func setUp() { continueAfterFailure = false }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = "\(lang)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    func note(_ name: String, _ text: String) {
        let a = XCTAttachment(string: text); a.name = "\(lang)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    @discardableResult
    func wait(_ e: XCUIElement, _ t: TimeInterval = 25) -> XCUIElement {
        XCTAssertTrue(e.waitForExistence(timeout: t), "Timed out waiting for \(e)")
        return e
    }

    func any(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }

    func dismissSystemAlert() {
        for l in ["Don’t Allow", "Don't Allow", "Jangan Benarkan", "Allow", "Benarkan"] where springboard.buttons[l].waitForExistence(timeout: 2) {
            springboard.buttons[l].tap(); return
        }
    }

    func closeSheet() {
        let done = app.navigationBars.buttons.matching(NSPredicate(format: "label IN {'Done', 'Selesai'}")).firstMatch
        if done.exists { done.tap() } else { app.swipeDown(velocity: .fast) }
        sleep(1)
    }

    var rulesShown = false

    /// The community rules appear once, before the first public contribution: screenshot them and agree.
    func agreeIfAsked(_ t: TimeInterval = 4) {
        let agree = app.buttons["communityAgree"]
        if agree.waitForExistence(timeout: t) {
            if !rulesShown { shot("00-community-rules"); rulesShown = true }
            agree.tap()
            sleep(1)
        }
    }

    /// A finger-length drag up from the card handle (XCUIElement.swipeUp stays inside the 24 pt handle).
    func openFullCard() {
        let h = wait(app.buttons["placeCardHandle"])
        let start = h.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -300)), withVelocity: .fast, thenHoldForDuration: 0)
        sleep(2)
    }

    func openSettings() {
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menu.settings"]).tap()
        sleep(1)
    }

    func search(_ q: String) {
        wait(app.buttons["whereTo"]).tap()
        let f = wait(app.textFields["searchField"]); f.tap(); f.typeText(q)
        wait(app.buttons.matching(identifier: "searchResult").firstMatch, 30).tap()
        wait(app.buttons["routesButton"])
        sleep(2)
    }

    func test_DemoModeAccountFree() {
        app = XCUIApplication()
        // A phone in London with no account: the store build has no reviewer switch, only the public Demo mode.
        app.launchArguments = ["-GimanoDataMode", "auto", "-GimanoSkipOnboarding", "YES", "-GimanoNoAutoStart", "YES",
                               "-GimanoFakeLocation", "51.5007,-0.1246", "-GimanoResetCommunityRules", "YES", "-AppleLanguages", "(\(lang))"]
        app.launch()
        wait(app.buttons["whereTo"], 40)
        sleep(3)
        shot("01-london-before-demo")

        // 1. Turn on Demo mode in Settings.
        openSettings()
        let toggle = app.switches["demoModeToggle"]
        for _ in 0..<5 where !toggle.isHittable { app.swipeUp() }
        shot("02-settings-demo-off")
        if (toggle.value as? String) != "1" { toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap() }
        sleep(1)
        closeSheet()
        let badge = wait(app.buttons["demoBadge"], 15)
        sleep(2)
        shot("03-home-demo-badge")
        badge.tap()
        wait(any("demoTurnOff"), 10)
        shot("04-demo-explained")
        closeSheet()

        // 2. Search + place card + a simulated drive with the demo closure.
        search("Mid Valley")
        shot("05-demo-place-card")
        app.buttons["routesButton"].tap()
        wait(app.buttons["goNow"], 40)
        sleep(2)
        shot("06-demo-route")
        app.buttons["goNow"].tap()
        wait(app.staticTexts["maneuverDistance"], 30)
        let card = any("stillClosedCard")
        let prompted = card.waitForExistence(timeout: 120)
        if prompted { shot("07-demo-still-closed"); app.buttons["issueVote.cleared"].tap(); agreeIfAsked(); sleep(2); shot("08-demo-voted") }
        // Sound selector.
        app.buttons["soundButton"].tap()
        wait(app.buttons["soundMode.alerts_only"], 5).tap()
        sleep(1)
        shot("09-demo-sound")
        // Report while driving (stays in the demo store).
        app.buttons["reportButton"].tap()
        wait(app.buttons["reportType.traffic"], 10).tap()
        if app.buttons["sendReport"].waitForExistence(timeout: 5) { app.buttons["sendReport"].tap() }
        agreeIfAsked()
        sleep(2)
        shot("10-demo-report")
        if !app.staticTexts["maneuverDistance"].exists { closeSheet() }
        let eta = app.buttons["etaClock"].exists ? app.buttons["etaClock"] : app.staticTexts["etaClock"]
        wait(eta).tap()
        wait(app.buttons["stopNavigation"]).tap()
        wait(app.buttons["whereTo"], 30)
        // Leave Full sound for later runs.
        app.buttons["soundButton"].tap()
        wait(app.buttons["soundMode.on"], 5).tap()

        // 3. Review + complaint + favourite on a demo place.
        search("KLCC")
        openFullCard()
        let write = app.buttons["writeReview"]
        for _ in 0..<4 where !write.isHittable { app.swipeUp() }
        write.tap()
        wait(app.buttons["reviewStar.4"], 10).tap()
        let t = app.textViews["reviewText"].exists ? app.textViews["reviewText"] : app.textFields["reviewText"]
        t.tap(); t.typeText("Demo review")
        app.buttons["postReview"].tap()
        agreeIfAsked()
        sleep(3)
        shot("11-demo-review-posted")
        wait(app.buttons["placeMore"]).tap()
        wait(app.buttons["suggestEditMenu"], 5).tap()
        app.buttons.matching(NSPredicate(format: "label IN {'Temporarily closed', 'Tutup sementara'}")).firstMatch.tap()
        wait(app.buttons["sendPlaceIssue"], 8).tap()
        agreeIfAsked()
        sleep(2)
        shot("12-demo-complaint-sent")

        wait(app.buttons["savePlace"]).tap()
        let lbl = wait(app.textFields["favoriteLabel"], 10); lbl.tap(); lbl.typeText("Demo KLCC")
        app.buttons["saveFavorite"].tap()
        sleep(2)
        app.buttons["placeClose"].tap()
        sleep(1)
        // Block the sample contributor from their review's menu: their review disappears.
        search("Pavilion")
        openFullCard()
        sleep(2)
        let sample = app.staticTexts["Kancil 2041"]
        for _ in 0..<4 where !sample.isHittable { app.swipeUp() }
        let flags = app.buttons.matching(identifier: "flagReview")
        // Still blocked from an earlier, interrupted run (the list lives on the phone): counts as blocked.
        var blocked = !sample.exists
        for i in 0..<flags.count {
            let f = flags.element(boundBy: i)
            guard f.isHittable else { continue }
            f.tap()
            if app.buttons["blockReviewAuthor"].waitForExistence(timeout: 3) {
                shot("12b-review-menu")
                app.buttons["blockReviewAuthor"].tap()
                app.buttons.matching(NSPredicate(format: "label IN {'Block', 'Sekat'}")).firstMatch.tap()
                blocked = true
                break
            } else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.05)).tap() }
        }
        sleep(2)
        shot("12c-review-blocked")
        note("12-block", "blocked: \(blocked); sample review still visible: \(app.staticTexts["Kancil 2041"].exists)")
        XCTAssertTrue(blocked, "block from the review menu")
        XCTAssertFalse(app.staticTexts["Kancil 2041"].exists, "blocked contributor's review is hidden")
        app.buttons["placeClose"].firstMatch.tap()
        sleep(1)

        // 4. Planned drive (demo departure times).
        search("Mid Valley")
        app.buttons["routesButton"].tap()
        wait(app.buttons["goNow"], 40)
        app.buttons.matching(NSPredicate(format: "label IN {'Leave later', 'Bertolak kemudian'}")).firstMatch.tap()
        wait(app.buttons["planThisDrive"], 15).tap()
        wait(app.buttons["savePlannedDrive"], 10).tap()
        dismissSystemAlert()
        sleep(3)
        shot("13-demo-planned-drive")
        for _ in 0..<5 where !app.buttons["whereTo"].isHittable {
            let back = app.buttons.matching(NSPredicate(format: "label IN {'Back', 'Kembali'}")).firstMatch
            if back.exists && back.isHittable { back.tap() } else { closeSheet() }
            sleep(1)
        }

        // 5. Backup: explicit destinations; demo storage round trip; iCloud unavailable in this build.
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuBackup"]).tap()
        wait(app.buttons["backupDestination.demo"], 10)
        sleep(1)
        shot("14-backup-destinations")
        let driveRow = app.buttons["backupDestination.googleDrive"]
        let driveOff = any("backupDestination.googleDriveUnavailable")
        let drive = driveRow.exists ? driveRow.label : driveOff.exists ? "unavailable: \(driveOff.label)" : "-"
        let rows = "drive '\(drive)'; file '\(app.buttons["backupDestination.file"].label)'; demo '\(app.buttons["backupDestination.demo"].label)'"
        app.swipeUp()
        sleep(1)
        let icloud = any("backupDestination.icloudUnavailable")
        let icloudShown = icloud.waitForExistence(timeout: 3)
        shot("14b-backup-icloud-unavailable")
        note("14-backup-rows", "\(rows); icloud '\(icloudShown ? icloud.label : "-")'")
        XCTAssertTrue(icloudShown, "iCloud shown as unavailable")
        app.swipeDown()
        sleep(1)
        app.buttons["backupDestination.demo"].tap()
        sleep(1)
        for _ in 0..<5 where !app.buttons["backUpNow"].exists { app.swipeUp(); sleep(1) }
        let now = wait(app.buttons["backUpNow"])
        for _ in 0..<4 where !now.isHittable { app.swipeUp() }
        now.tap()
        XCTAssertTrue(app.staticTexts["backupStatus"].waitForExistence(timeout: 20), "backed up to demo storage")
        sleep(2)
        shot("15-demo-backup-done")
        let remote = app.buttons.matching(identifier: "remoteBackup").firstMatch
        for _ in 0..<4 where !remote.isHittable { app.swipeUp() }
        wait(remote, 10).tap()
        wait(app.buttons["restoreButton"], 15)
        shot("16-demo-restore-sheet")
        app.buttons["restoreButton"].tap()
        let restored = app.staticTexts["backupMessage"].waitForExistence(timeout: 30)
        shot("17-demo-restored")
        XCTAssertTrue(restored, "restored from demo storage")
        closeSheet()

        // 6. Settings: blocked users (unblock) and Account: delete account (demo explains it has no server account).
        openSettings()
        let blockedLink = app.buttons["settingsBlocked"]
        for _ in 0..<5 where !blockedLink.isHittable { app.swipeUp() }
        blockedLink.tap()
        sleep(1)
        shot("19-blocked-users")
        let unblock = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'unblock.'")).firstMatch
        if unblock.exists { unblock.tap(); sleep(1) }
        XCTAssertTrue(any("blockedEmpty").waitForExistence(timeout: 5), "unblocked")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        let account = app.buttons["settingsAccount"]
        for _ in 0..<5 where !account.isHittable { app.swipeUp() }
        account.tap()
        sleep(2)
        for _ in 0..<4 { app.swipeUp() }
        shot("20-account-delete-demo")
        XCTAssertTrue(any("deleteAccountDemoNote").exists, "demo mode explains there is no server account")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        closeSheet()

        // 7. Turn Demo mode off from the badge.
        wait(app.buttons["demoBadge"], 10).tap()
        wait(any("demoTurnOff"), 10).tap()
        sleep(3)
        XCTAssertFalse(app.buttons["demoBadge"].exists && app.buttons["demoBadge"].label.hasPrefix("Demo") && !app.buttons["demoBadge"].label.contains("·"),
                       "Demo mode is off")
        shot("18-demo-off")
        note("summary", "still-closed prompt in demo drive: \(prompted)")
    }

    /// Delete my Manotu account against the dev server (WS556; the Debug build's server, never production).
    func test_DeleteAccountOnDev() {
        app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "live", "-GimanoSkipOnboarding", "YES", "-GimanoFakeLocation", "3.152613,101.703718",
                               "-AppleLanguages", "(\(lang))"]
        app.launch()
        wait(app.buttons["whereTo"], 40)
        sleep(3)
        openSettings()
        let account = app.buttons["settingsAccount"]
        for _ in 0..<5 where !account.isHittable { app.swipeUp() }
        account.tap()
        sleep(3)
        let before = any("accountPseudonym").exists ? any("accountPseudonym").label : "-"
        let del = app.buttons["deleteAccount"]
        for _ in 0..<5 where !del.isHittable { app.swipeUp() }
        shot("21-account-delete")
        del.tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label IN {'Delete account', 'Padam akaun'}")).firstMatch
        wait(confirm, 5)
        shot("22-account-delete-confirm")
        confirm.tap()
        sleep(4)
        let err = any("deleteAccountError")
        shot("23-account-deleted")
        note("23-delete", "pseudonym before '\(before)'; error: \(err.exists ? err.label : "none")")
        XCTAssertFalse(err.exists, "account deleted on the dev server")
    }
}
