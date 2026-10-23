# Website deploy message for SERVER.HiRO

Send this with SendMessage to `SERVER.HiRO` (find it with ListAgents). Fill in the versions, the
hashes from the staged `.sha256` files and the previous versions (the current live manifests).
SERVER.HiRO runs on WS556-SERVER, which can reach the .13 IIS shares. Its terminal composer may hold
the user's draft, so only use session messaging.

```
Publish the Android APKs Manotu <M> (<Mcode>) and Gimano <G> (<Gcode>) to IIS on .13. The user approved the release; do it now.
Additive only, in the same pattern as previous releases.

Source: GitHub release assets. Download them, verify SHA-256 BEFORE copying, and re-hash on .13.
- https://github.com/erymuzuan/manotu/releases/download/v<M>/manotu-<M>.apk  sha256 <Msha>  (+ manotu-<M>.apk.sha256, version.json)
- https://github.com/erymuzuan/manotu/releases/download/v<G>/manotu-<G>.apk  sha256 <Gsha>  (+ manotu-<G>.apk.sha256, version.json)

Order: APKs first, manifests LAST.
A) Manotu, in BOTH folders: C:\apps\websites\manotu.app\download\ and C:\apps\websites\www.bukitbunga.com\manotu\download\
   1. Copy manotu-<M>.apk + .sha256.
   2. COPY the current version.json (<Mprev>) to a NEW version-<Mprev>.json. If that file exists, do not overwrite; report it.
   3. Back up version.json as .bak-<ts>, then replace it with v<M>'s version.json.
B) Legacy: C:\apps\websites\www.bukitbunga.com\gimano\download\
   The same 3 steps with manotu-<G>.apk and version-<Gprev>.json. Older version-*.json files stay untouched.
Do NOT touch web.config, HTML, older APKs, other manifests, or Cloudflare.

Verify publicly (cache-busted):
- manotu.app and bukitbunga /manotu/download/version.json: <M> (<Mcode>)
- bukitbunga and manotu.app /gimano/download/version.json: <G> (<Gcode>)
- Download each apkUrl; the sha matches.
- The rollback manifests are served with their own versions, and older APKs still return 200.
Report the files copied, the backups, and every check.
```

When HiRO reports, run `python3 .claude/skills/release-apk/scripts/release.py verify` yourself. Don't rely on the report alone.
