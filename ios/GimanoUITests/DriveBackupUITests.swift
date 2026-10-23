import XCTest

/// Google Drive backup → restore on a real device (needs the owner's Google account available on the phone).
/// Run on its own: -only-testing:GimanoUITests/DriveBackupUITests
final class DriveBackupUITests: XCTestCase {
    var app: XCUIApplication!
    var springboard: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.springboard") }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "demo", "-GimanoFreshDevice", "YES", "-GimanoSkipOnboarding", "YES",
                               "-GimanoFakeLocation", "3.152613,101.703718", "-AppleLanguages", "(en)"]
        app.launch()
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 30))
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

    /// Taps the first existing element among candidates (web content or native), within `timeout`.
    @discardableResult
    func tapFirst(_ candidates: [XCUIElement], timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for c in candidates where c.exists && c.isHittable { c.tap(); return true }
            usleep(500_000)
        }
        return false
    }

    func test_r_GoogleDriveBackupRestore() {
        // A favourite to back up.
        wait(app.buttons["whereTo"]).tap()
        let f = wait(app.textFields["searchField"])
        f.tap()
        f.typeText("Batu Caves")
        wait(app.buttons.matching(identifier: "searchResult").firstMatch).tap()
        wait(app.buttons["savePlace"]).tap()
        wait(app.buttons["quickLabel.Kampung"]).tap()
        app.buttons["saveFavorite"].tap()
        sleep(1)
        app.buttons["Back"].firstMatch.tap()

        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuBackup"]).tap()
        wait(app.buttons["backupDestination.googleDrive"]).tap()
        sleep(1)
        XCTAssertFalse(app.descendants(matching: .any)["driveNotConfigured"].exists, "Google client id should be configured")
        shot("r-drive-signed-out")

        if app.buttons["Sign in with Google"].exists {
            app.buttons["Sign in with Google"].tap()
            // "Gimano wants to use google.com to sign in" (ASWebAuthenticationSession)
            tapFirst([springboard.buttons["Continue"], app.buttons["Continue"]], timeout: 10)
            sleep(4)
            shot("r-google-sign-in")
            // ASWebAuthenticationSession content lives in Safari's view service, not in the app.
            let safari = XCUIApplication(bundleIdentifier: "com.apple.SafariViewService")
            // Account chooser → "Google hasn't verified this app" (Testing) → consent for drive.appdata.
            let tree = XCTAttachment(string: "SAFARI:\n" + safari.debugDescription + "\n\nAPP:\n" + app.debugDescription)
            tree.name = "r-tree"
            tree.lifetime = .keepAlways
            add(tree)
            for _ in 0..<10 {
                if app.buttons["Sign out"].exists { break }
                guard safari.state == .runningForeground else { sleep(2); continue }
                let web = safari.webViews.firstMatch
                guard web.waitForExistence(timeout: 5) else { break }
                let go = "label IN {'Continue', 'Allow', 'Teruskan', 'Benarkan'}"
                let goButton = web.buttons.matching(NSPredicate(format: go)).firstMatch
                // Granular consent: tick "See, create and delete its own configuration data in your Google Drive".
                for box in web.checkBoxes.allElementsBoundByIndex where box.isHittable && (box.value as? String) != "1" { box.tap() }
                if goButton.exists && goButton.isHittable {
                    goButton.tap()
                } else if goButton.exists || web.staticTexts["Google will allow"].exists {
                    web.swipeUp()
                    continue
                } else if web.staticTexts["Choose an account"].exists {
                    web.links.matching(NSPredicate(format: "label CONTAINS[c] '@gmail.com'")).firstMatch.tap()
                } else {
                    let link = web.links.matching(NSPredicate(format: go)).firstMatch
                    if link.exists && link.isHittable { link.tap() } else { web.swipeUp() }
                }
                sleep(3)
                shot("r-google-step")
            }
        }
        wait(app.buttons["Sign out"], 30)
        for _ in 0..<6 where !(app.buttons["backUpNow"].exists && app.buttons["backUpNow"].isHittable) { app.swipeUp() }
        let backUp = wait(app.buttons["backUpNow"], 10)
        XCTAssertTrue(backUp.isEnabled, "signed in to Google")
        shot("r-drive-signed-in")
        backUp.tap()
        wait(app.staticTexts.matching(NSPredicate(format: "label == 'Backed up' OR label BEGINSWITH 'Last backup'")).firstMatch, 90)
        sleep(2)
        shot("r-drive-backed-up")

        // Restore (Merge) from the newest Drive backup.
        for _ in 0..<4 where !app.buttons.matching(identifier: "remoteBackup").firstMatch.exists { app.swipeUp() }
        let remote = wait(app.buttons.matching(identifier: "remoteBackup").firstMatch, 30)
        remote.tap()
        wait(app.buttons["restoreButton"]).tap()
        wait(app.staticTexts["backupMessage"], 60)
        for _ in 0..<3 where !app.staticTexts["backupMessage"].isHittable { app.swipeDown() }
        shot("r-drive-restored")
    }

    /// Restores a backup made by another device/platform found in the same Drive appDataFolder (e.g. Android).
    func test_s_RestoreBackupFromOtherPlatform() {
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuBackup"]).tap()
        wait(app.buttons["backupDestination.googleDrive"]).tap()
        wait(app.buttons["Sign out"], 30)
        for _ in 0..<5 where !app.buttons.matching(identifier: "remoteBackup").firstMatch.exists { app.swipeUp() }
        let rows = app.buttons.matching(identifier: "remoteBackup")
        wait(rows.firstMatch, 30)
        // Prefer a backup whose name doesn't look like an iOS one ("gimano-backup-…").
        var target = rows.firstMatch
        for i in 0..<rows.count where !rows.element(boundBy: i).label.contains("gimano-backup-") && !rows.element(boundBy: i).label.contains("manotu-backup-") { target = rows.element(boundBy: i); break }
        shot("s-remote-backups")
        target.tap()
        wait(app.buttons["restoreButton"])
        sleep(1)
        shot("s-restore-sheet-manifest")
        let madeOn = app.staticTexts.matching(NSPredicate(format: "label IN {'Android', 'iPhone'}")).firstMatch
        let platform = madeOn.exists ? madeOn.label : "?"
        let note = XCTAttachment(string: "Restored backup '\(target.label)' made on: \(platform)")
        note.name = "s-platform"
        note.lifetime = .keepAlways
        add(note)
        app.buttons["restoreButton"].tap()
        let ok = app.staticTexts["backupMessage"].waitForExistence(timeout: 60)
        let err = app.staticTexts["restoreError"]
        if !ok && err.exists { XCTFail("Restore failed: \(err.label)") }
        sleep(1)
        shot("s-restored")
        app.buttons["Done"].firstMatch.tap()
        wait(app.buttons["menuButton"]).tap()
        wait(app.buttons["menuFavorites"]).tap()
        sleep(2)
        shot("s-favourites-after-restore")
    }
}
