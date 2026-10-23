# Rebrand: Gimano → Manotu (plan, decided 2026-10-23)

**User decisions:**
- **Name:** Manotu. From the user's dialect "Ma'no tu?" = "Where are you going?"; pronounced **MA**-no-tu.
- **Marks:** icon 6 "Wau ringkas" + wordmark E "Lengkung suara". Masters are in `docs/brand/manotu/`.
- **Scope: everything, including technical identifiers.** This is a migration, not a find-and-replace.
- **GitHub:** rename the repo in place, `erymuzuan/gimano` → `erymuzuan/manotu`. GitHub redirects old URLs and git
  remotes, as long as no new repo named `gimano` is ever created.

**Constraints that shape the plan:**
- Android can't update an app across a package-name change. Existing Gimano installs need a migration path that
  keeps their data. The backup file carries favourites, private places and the pseudonym key.
- Apps already in the field poll `https://www.bukitbunga.com/gimano/download/version.json` and call
  `https://gimano-api.bukitbunga.com`. Both must keep working for as long as old installs exist.
- The domain is undecided. manotu.com is registered; manotu.my / .com.my were available at the last MYNIC check;
  manotu.app had no registry record. The API host and website move only after the domain decision.
- The privacy rule is unchanged. No user data moves through our servers during migration: backups go phone → file
  (or the user's own Drive/iCloud) → phone.

## Phases

| # | What | Owner | Needs from the user |
|---|---|---|---|
| 0 | Ship Android **0.2.2** as Gimano (search + location fixes), already approved | coordinator + Android | — |
| 1 | Brand masters (done: b1a60ab); canvas page "Manotu brand (options)" | coordinator | — |
| 2 | **Repo rename** to `erymuzuan/manotu` (after 0.2.2 is published). Update remotes, README/badges, docs links, AltStore source and site links. Re-check GitGuardian and CI on the new name | coordinator | Re-link GitGuardian if it asks |
| 3 | **Apps become Manotu** on new IDs: Android `my.manotu.app`, iOS `my.manotu.app`. Display name, icons (adaptive + themed), every string in EN/MS, notifications, TTS, About/licences, privacy copy. Deep links `manotu://` (still accept `gimano://`). Backup files `.manotu` (still import `.gimano`, same format) | Android + iOS agents | **New OAuth clients:** an Android client for `my.manotu.app` (release SHA-1 `6D:9D:F7:…:E6:2A`, same key) plus a debug client, and an iOS client for `my.manotu.app`. Rename the OAuth consent screen to Manotu (logo `manotu-icon.svg`). An App Store Connect app record for `my.manotu.app` under the **individual** team (never STAFY) |
| 4 | **Farewell Gimano 0.2.3** (package `my.gimano.app`): a one-time sheet "Gimano kini Manotu" → **Eksport sandaran** → **Muat turun Manotu**. Manotu's first run offers "Pulihkan dari Gimano" (file picker). The last Gimano `version.json` stays served forever | Android (+ iOS sideload users: same file flow) | Approve the release |
| 5 | **Website:** Manotu branding on `www.bukitbunga.com/gimano` now. When the domain is decided, move to the new site; `/gimano/` redirects there **except** `/gimano/download/version.json` and the existing APK URLs, which stay put | Codex (site owner) | **Domain decision** |
| 6 | **API host:** add the new host when the domain is decided. Keep `gimano-api.bukitbunga.com` as a permanent alias (IIS ARR + Cloudflare) for old installs. New apps use the new host | backend + SERVER.HiRO | Cloudflare DNS for the new host |
| 7 | **Internal names:** images `manotu-*`, compose project, DB, RabbitMQ vhost, OpenSearch `manotu-` prefix, .NET namespaces. Each is done in a maintenance window with a backup and an alias swap, last and lowest priority | backend + SERVER.HiRO | Approve the window |

## Keep forever (compatibility)

- `my.gimano.app` stays a published, signed package name, and its farewell `version.json` stays served.
- `gimano-api.bukitbunga.com` stays as an alias.
- `gimano://` and `.gimano` imports keep working.
- Share links under `/gimano/s/` keep working.
