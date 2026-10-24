# Releasing Gimano for iOS (TestFlight)

Bundle id `my.gimano.app` · version `0.3.4` (build 10) (`MARKETING_VERSION` in `project.yml`) · build number = `CURRENT_PROJECT_VERSION` in `project.yml`
(override per upload: `scripts/testflight.sh <n>`).

The Release configuration talks to **https://gimano-api.bukitbunga.com/api/v1/** (live hub: `wss://gimano-api.bukitbunga.com/hubs/live`).
It is ATS-strict (no exceptions), has no developer settings or overrides, and ignores test launch arguments.
Demo data is used only when the server is unreachable or returns 5xx.

## App identity and build variants

`Config/Signing.xcconfig` sets `APP_VARIANT` (default **`manotu`**; `gimano` for the legacy app) and derives:

| Setting | manotu (default) | gimano (legacy) |
|---|---|---|
| `PRODUCT_BUNDLE_IDENTIFIER` (`APP_BUNDLE_ID`) | `my.manotu.app` | `my.gimano.app` |
| iCloud container (`APP_ICLOUD_CONTAINER`) | `iCloud.my.manotu.app` | `iCloud.my.gimano.app` |
| Google client (`APP_GOOGLE_CLIENT_ID` / `_REVERSED_`) | `MANOTU_GOOGLE_CLIENT_ID` / `MANOTU_GOOGLE_REVERSED_CLIENT_ID` | `GIMANO_GOOGLE_CLIENT_ID` / `GIMANO_GOOGLE_REVERSED_CLIENT_ID` |
| Background backup task | `my.manotu.app.backup` | `my.gimano.app.backup` |

Google client ids live in the git-ignored `Config/Google.xcconfig` (see `Config/Google.xcconfig.example`).
Build the legacy app with `APP_VARIANT=gimano` (Local.xcconfig or the xcodebuild command line).

### Building for a device once the individual team exists

1. Put the **individual** team id in `Config/Local.xcconfig`: `DEVELOPMENT_TEAM = <individual team id>` (never STAFY).
2. In Xcode → Settings → Accounts, sign in with that Apple ID (or pass an App Store Connect API key, see below).
3. developer.apple.com → Identifiers: register **`my.manotu.app`** with capabilities iCloud (CloudKit container
   `iCloud.my.manotu.app`), Background Modes and App Attest (DeviceCheck); automatic signing then creates the profiles.
   Until the container exists, build with `GIMANO_ICLOUD=NO` (Drive and file backups still work).
4. Google Cloud: the iOS OAuth client for bundle id `my.manotu.app` is `MANOTU_GOOGLE_CLIENT_ID` (already set locally).
5. Build: `xcodebuild -scheme Gimano -destination 'id=<device>' -allowProvisioningUpdates` (Debug talks to WS556).

## One-time setup

> **Team:** TestFlight and the App Store must use Erymuzuan Mustapa's **individual** Apple Developer team, never
> STAFY COMPANY LIMITED (AGENTS.md). The local `Config/Local.xcconfig` used for device testing currently points at
> STAFY (98KY9PMPX3), so `scripts/testflight.sh` passes `DEVELOPMENT_TEAM=$ASC_TEAM_ID` explicitly and refuses to
> upload an archive signed by any other team. Create the App Store Connect app and API key under the individual team.

1. **App Store Connect app**: My Apps → + → New App → iOS, name "Gimano", bundle id `my.gimano.app`, SKU `gimano-ios`.
2. **API key**: App Store Connect → Users and Access → Integrations → App Store Connect API → generate a key with the
   **App Manager** role. Download `AuthKey_<KEY_ID>.p8` (it can be downloaded only once) and note the Issuer ID and Key ID.
3. `cp Config/asc.env.example Config/asc.env` and fill in `ASC_ISSUER_ID`, `ASC_KEY_ID`, `ASC_KEY_PATH`, `ASC_TEAM_ID` (individual team).
   `Config/asc.env` and `*.p8` are git-ignored; never commit them.
4. `Config/Local.xcconfig` (git-ignored) must contain `DEVELOPMENT_TEAM = <your team id>`, as for development builds.
   `Config/Google.xcconfig` (git-ignored) holds the Google Drive client ids (see `Config/Google.xcconfig.example`).
5. **Capabilities on the App ID** (developer.apple.com → Identifiers → my.gimano.app). Automatic signing with the API key creates the
   Apple Distribution certificate and the App Store profile itself. It can't create an iCloud container that doesn't exist yet, so:
   - enable **iCloud (CloudKit)** with container `iCloud.my.gimano.app`, then build with `GIMANO_ICLOUD=YES` (the default).
     Until this is done, build with `GIMANO_ICLOUD=NO scripts/testflight.sh`: iCloud backup is hidden and Google Drive / file backups still work.
   - Background modes (location, audio, processing) need no setup.

## Upload a build

```sh
cd ios
scripts/testflight.sh                 # archive (Release) → export (app-store-connect) → upload with altool
GIMANO_ICLOUD=NO scripts/testflight.sh  # if the iCloud container isn't set up yet
```

What the script runs (for manual use):

```sh
xcodebuild archive -project Gimano.xcodeproj -scheme Gimano -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/testflight/Gimano.xcarchive \
  -allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"
xcodebuild -exportArchive -archivePath build/testflight/Gimano.xcarchive -exportPath build/testflight/export \
  -exportOptionsPlist Config/ExportOptions-AppStore.plist -allowProvisioningUpdates -authenticationKeyPath … (same three)
xcrun altool --upload-app -f build/testflight/export/Gimano.ipa -t ios \
  --api-key "$ASC_KEY_ID" --api-issuer "$ASC_ISSUER_ID" --p8-file-path "$ASC_KEY_PATH"
```

Every upload needs a new build number: bump `CURRENT_PROJECT_VERSION` in `project.yml` (the sideload IPA uses the same value), or pass one: `scripts/testflight.sh 3`.

## TestFlight public link

1. After processing (10–30 min), App Store Connect → TestFlight → the build → **Export Compliance** is pre-answered by
   `ITSAppUsesNonExemptEncryption = NO` (see below).
2. TestFlight → External Testing → + group "Public" → add the build → fill in **Test Information**
   (beta description, feedback email, privacy policy URL: the website's privacy page (from `docs/privacy-promise.md`)), then submit for **Beta App Review** (the first build only).
3. Once approved: the group → **Public Link** → enable (optionally cap testers).

## Questions App Store Connect will ask

- **Encryption (export compliance)**: `ITSAppUsesNonExemptEncryption = NO` in Info.plist. Gimano uses only standard encryption: HTTPS, plus
  libsodium (XChaCha20-Poly1305, Argon2id, Ed25519) to protect the user's own backups, Share my drive and contribution signatures.
  It has no proprietary algorithms. This falls under the exemption for standard, published algorithms used for data protection and authentication, so no
  documentation upload or annual self-classification report should be needed. **The account holder must confirm this answer** when asked.
- **App Privacy ("nutrition label")**: answer to match `Gimano/Resources/PrivacyInfo.xcprivacy`. **No tracking.** Data collected, not linked to identity,
  used for App Functionality only:
  - Precise location: only while navigating (live traffic pings), not kept as a history;
  - Coarse location and other diagnostic data: only when "Include diagnostics" is ticked in feedback;
  - Photos: those the user adds to public places or reviews;
  - Other user content: reports, public places, reviews, feedback.
  Favourites, recents, private places and planned drives never leave the phone except in the user's own backup.
- **Sign-in required?** No. A pseudonym is created automatically, with no account to give reviewers.
- **Age rating**: no objectionable content. User-generated content is moderated (reports, reviews, places).
- **Location usage**: "When In Use", plus background location during navigation only (Info.plist strings are in en + ms).

## Checklist before each upload

- [ ] `xcodebuild test` (unit tests) green; device UI tests: `ProdUserUITests` against production on a Release build
      (uninstall first: `xcrun devicectl device uninstall app --device <udid> my.gimano.app`, then
      `xcodebuild test -configuration Release ENABLE_TESTABILITY=YES -only-testing:GimanoUITests/ProdUserUITests …`).
- [ ] Bump `MARKETING_VERSION` in `project.yml` for a new user-visible version; `xcodegen generate`.
