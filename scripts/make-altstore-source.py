#!/usr/bin/env python3
"""Generate feed metadata from the actual sideload IPA. Run before publishing the website."""
import argparse, datetime, json, pathlib, plistlib, zipfile
p = argparse.ArgumentParser()
p.add_argument("ipa", type=pathlib.Path)
p.add_argument("--tag", required=True)
p.add_argument("--out", type=pathlib.Path, default=pathlib.Path("site/gimano/altstore.json"))
a = p.parse_args()
with zipfile.ZipFile(a.ipa) as z:
    info = plistlib.loads(z.read("Payload/Gimano.app/Info.plist"))
    assert not any(n.endswith("embedded.mobileprovision") for n in z.namelist()), "Do not publish provisioning profiles"
assert info["CFBundleIdentifier"] == "my.gimano.app"
assert info.get("GimanoICloudEnabled") == "NO"
assert not info.get("GIDClientID")
version = {"version": info["CFBundleShortVersionString"], "buildVersion": info["CFBundleVersion"],
    "date": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "localizedDescription": "Public preview for AltStore Classic. File backup/restore available. iCloud and Google Drive sign-in disabled in this re-signed build.",
    "downloadURL": f"https://github.com/erymuzuan/gimano/releases/download/{a.tag}/{a.ipa.name}",
    "size": a.ipa.stat().st_size, "minOSVersion": info["MinimumOSVersion"]}
versions = [version]
if a.out.exists():
    old = json.loads(a.out.read_text())
    versions += [v for v in old["apps"][0]["versions"] if (v["version"], v.get("buildVersion")) != (version["version"], version["buildVersion"])]
feed = {"name": "Gimano", "identifier": "my.gimano.source", "sourceURL": "https://www.bukitbunga.com/gimano/altstore.json",
    "subtitle": "Community navigation for Malaysia", "website": "https://www.bukitbunga.com/gimano/",
    "iconURL": "https://www.bukitbunga.com/gimano/logo.png", "tintColor": "0047AB",
    "apps": [{"name": "Gimano", "bundleIdentifier": info["CFBundleIdentifier"], "developerName": "Gimano contributors",
        "localizedDescription": "Open-source community navigation for Malaysia. Favourites and planned drives stay on your phone. This preview uses file backups; iCloud and Google Drive sign-in are unavailable in the sideload build. Requires AltStore Classic or Sideloadly. Free accounts need a refresh every seven days.",
        "iconURL": "https://www.bukitbunga.com/gimano/logo.png", "tintColor": "0047AB", "category": "utilities",
        "versions": versions, "appPermissions": {"entitlements": [], "privacy": {k:v for k,v in info.items() if k.startswith("NS") and k.endswith("UsageDescription")}}}], "news": []}
a.out.parent.mkdir(parents=True, exist_ok=True)
a.out.write_text(json.dumps(feed, indent=2, ensure_ascii=False) + "\n")
print(a.out)
