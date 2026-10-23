---
name: release-apk
description: >-
  Release the Manotu Android APKs: bump versions, commit and tag on master, build and verify both
  flavors (my.manotu.app and the legacy my.gimano.app), write bilingual release notes, publish GitHub
  Releases, and deploy to manotu.app and bukitbunga.com through SERVER.HiRO with the update
  manifests switched last, then verify publicly. Use it whenever the user asks to release, publish,
  ship or roll out a new APK or Android version (e.g. "release 0.3.2", "publish the APK", "push the
  fixes to users", "bump and tag"), even if they name only one host or only GitHub. It does not cover
  the iOS IPA/TestFlight, or backend deploys.
---

# Releasing the Manotu Android APKs

People drive with this app. A bad manifest, a debug-signed APK or a wrong package strands them, and
in-app updates are the only way to reach them again. Every step below exists to stop that.

## The contract (don't change without the user)

| | Manotu | Gimano (legacy, farewell builds) |
|---|---|---|
| Gradle flavor | `manotu` | `gimano` |
| Package | `my.manotu.app` | `my.gimano.app` |
| Version line | 0.3.x | 0.2.x |
| APK file name | `manotu-<ver>.apk` | `manotu-<ver>.apk` (yes, same prefix since 0.2.3) |
| Update manifest the app polls | `https://manotu.app/download/version.json` | `https://www.bukitbunga.com/gimano/download/version.json` |
| IIS folders on .13 | `C:\apps\websites\manotu.app\download\` and `C:\apps\websites\www.bukitbunga.com\manotu\download\` | `C:\apps\websites\www.bukitbunga.com\gimano\download\` |
| Git tag | `v<ver>` | `v<ver>` |

- **Signing:** both flavors are signed with the release key (cert SHA-256 `b9baddcd…bec9`) from the
  git-ignored `android/keystore.properties` and the macOS Keychain. Android refuses an in-place
  update across keys, so `release.py build` refuses anything else.
- **Branch:** `master` is the only branch. Release from an up-to-date `master` with no tracked
  changes, because the APK must equal a tagged commit.
- **Versions:** they live as per-flavor defaults in `android/app/build.gradle.kts`. Bump the patch for
  a fix release and the minor for features. versionCode is always +1.
- **Never** uninstall, `pm clear` or restore on the user's phones. If a device test is wanted,
  `adb install -r` only.

## Steps

All commands run from the repo root:

```bash
R=.claude/skills/release-apk/scripts/release.py   # run steps as: python3 $R <step> (zsh does not split a quoted "python3 …" variable)
```

1. **Plan.** Run `python3 $R plan`. It shows the branch state, the current and next versions, and the commits
   since the last tag, split into Android (in the APK) and backend (not in the APK). Confirm the
   versions with the user only if the plan is surprising; otherwise use the next patch versions.
2. **Bump.** Run `python3 $R bump --manotu 0.3.2 --gimano 0.2.5`. It edits the Gradle defaults and commits
   `Release Manotu … and Gimano …` on master, without pushing.
3. **Build.** Run `python3 $R build`. It runs the unit tests for both flavors and builds both release APKs from
   that commit. It checks the package, versionCode, signer and embedded manifest URL with
   aapt2/apksigner, then stages each APK under `artifacts/release-staging/<flavor>-<ver>/` with its
   `.sha256` and the source commit. If it fails, fix the cause; don't work around a signer or package
   mismatch.
4. **Notes.** Write them yourself in each staged folder, using
   [references/release-notes.md](references/release-notes.md):
   - `notes-ms.txt` and `notes-en.txt`: short lines for the in-app update sheet;
   - `release.md`: the GitHub release body.

   Then generate the manifest:
   ```bash
   android/tools/publish-apk.py --channel manotu artifacts/release-staging/manotu-0.3.2/manotu-0.3.2.apk \
     --notes-ms …/notes-ms.txt --notes-en …/notes-en.txt --out artifacts/release-staging/manotu-0.3.2/version.json
   ```
   Do the same with `--channel gimano` for the legacy folder. Before going on, search the notes for
   "formerly", "Dahulu" or "Gimano". Users see only the Manotu brand; the legacy package's notes say
   "Manotu" too.
5. **Tag and push.** Run `python3 $R tag`. It refuses unless both APKs were built from HEAD. It tags
   `v<manotu>` and `v<gimano>` on the bump commit, then pushes master and the tags.
6. **GitHub.** Run `python3 $R github`. It creates two pre-releases with the APK, the `.sha256` and
   `version.json` as assets.
7. **Website.** SERVER.HiRO on WS556-SERVER owns the IIS copy to .13. Send it the message in
   [references/hiro-deploy.md](references/hiro-deploy.md) with SendMessage (its composer may hold a
   user draft, so don't type into its terminal). It downloads the GitHub assets, checks the hashes,
   copies the APKs first, saves the old manifest as `version-<old>.json` (never overwriting), and
   switches `version.json` last.
8. **Verify.** Run `python3 $R verify` once HiRO reports. It checks both manifest hosts per flavor and
   downloads each APK from its public `apkUrl` to compare hashes. Cloudflare reports DYNAMIC on these
   paths, so a stale answer means the copy didn't happen. Don't purge Cloudflare: it belongs to the
   user.
9. **Record.** Write `artifacts/marketing/manotu-<ver>-release-published.md`: versions, commit, hashes,
   GitHub links, deploy notes, rollback manifests and anything not released. Then report to the user
   in plain words.

## Why the order matters

- **Bump, then build, then tag.** The tag, the APK and the GitHub source must all be the same
  commit. Anyone can rebuild a release from its tag, and `release.py tag` checks this.
- **APKs before manifests.** An app that sees a new `version.json` before its APK exists downloads a
  404 and shows an update error to every driver that day.
- **Rollback is a manifest copy.** Copying `version-<old>.json` over `version.json` points everyone
  back to the previous APK, which is never deleted. Overwriting an old rollback file loses that, and
  it has happened once (0.2.2), so the deploy message forbids it.
- **Backend changes are not in the APK.** If the release depends on a server change (a new
  endpoint), the backend must be live first, deployed separately by SERVER.HiRO with a DB dump.
  Otherwise the new screens hit 404s. `plan` lists backend commits so you notice.

## Not covered

- iOS (IPA, TestFlight, AltStore): blocked until the individual Apple team is active. Never use
  STAFY.
- Backend deploys: see `deploy/PRODUCTION.md`.
