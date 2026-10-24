import XCTest

/// The store/TestFlight build's backup screen (GIMANO_ICLOUD=NO GIMANO_DRIVE=NO, owner decision 2026-10-24): a file
/// backup, no Google Drive destination or sign-in, iCloud and Drive both shown as unavailable. Screenshots are attached
/// for review evidence. Environment: GIMANO_LANG (en|ms). Simulator only; no account needed.
/// Run on its own: -only-testing:GimanoUITests/StoreBackupUITests
final class StoreBackupUITests: XCTestCase {
    var app: XCUIApplication!
    var lang: String { ProcessInfo.processInfo.environment["GIMANO_LANG"] ?? "en" }

    override func setUp() { continueAfterFailure = false }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = "\(lang)-\(name)"; a.lifetime = .keepAlways; add(a)
    }

    func any(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }

    func test_StoreBackupScreenHasNoDrive() throws {
        app = XCUIApplication()
        app.launchArguments = ["-GimanoDataMode", "auto", "-GimanoSkipOnboarding", "YES", "-GimanoNoAutoStart", "YES",
                               "-GimanoFakeLocation", "3.152613,101.703718", "-AppleLanguages", "(\(lang))"]
        app.launch()
        XCTAssertTrue(app.buttons["whereTo"].waitForExistence(timeout: 40))
        app.buttons["menuButton"].tap()
        XCTAssertTrue(app.buttons["menuBackup"].waitForExistence(timeout: 10))
        app.buttons["menuBackup"].tap()
        XCTAssertTrue(app.buttons["backupDestination.file"].waitForExistence(timeout: 10))
        if app.buttons["backupDestination.googleDrive"].exists {
            throw XCTSkip("Built with GIMANO_DRIVE=YES: this test documents the store build only")
        }
        sleep(1)
        shot("store-backup-top")
        XCTAssertTrue(any("backupDestination.googleDriveUnavailable").waitForExistence(timeout: 5), "Drive shown as unavailable")
        XCTAssertFalse(app.buttons["Sign in with Google"].exists)
        XCTAssertFalse(app.buttons["Log masuk dengan Google"].exists)
        app.swipeUp()
        sleep(1)
        XCTAssertTrue(any("backupDestination.icloudUnavailable").exists, "iCloud shown as unavailable")
        XCTAssertTrue(app.buttons["exportBackup"].exists, "file export present")
        XCTAssertTrue(app.buttons["importBackup"].exists, "restore from file present")
        XCTAssertFalse(app.switches["autoBackup"].exists, "no automatic cloud backup")
        shot("store-backup-file")
    }
}
