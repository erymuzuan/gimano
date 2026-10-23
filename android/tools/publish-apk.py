#!/usr/bin/env python3
"""
Produce version.json for a release APK (served next to it at https://www.bukitbunga.com/gimano/download/).

  tools/publish-apk.py app/build/outputs/apk/release/gimano-0.2.0.apk \
      --notes-ms notes-ms.txt --notes-en notes-en.txt [--min-supported 1] [--out version.json]

versionCode / versionName / package are read from the APK with aapt2 (Android SDK build-tools),
sha256 and size from the file. The app downloads apkUrl, checks sha256, package, versionCode and
that the signing certificate matches the installed app before handing it to PackageInstaller.
"""
import argparse, datetime, glob, hashlib, json, os, re, subprocess, sys

BASE = "https://www.bukitbunga.com/gimano/download/"
PACKAGE = "my.gimano.app"

def aapt2():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/opt/homebrew/share/android-commandlinetools"
    tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")), key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"[.-]", p.split(os.sep)[-2])])
    if not tools: sys.exit("aapt2 not found; set ANDROID_HOME")
    return tools[-1]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--notes-ms", help="file with Bahasa Melayu release notes")
    ap.add_argument("--notes-en", help="file with English release notes")
    ap.add_argument("--min-supported", type=int, default=1, help="versionCodes below this get the blocking 'Update required' gate")
    ap.add_argument("--out", default="version.json")
    a = ap.parse_args()

    badging = subprocess.run([aapt2(), "dump", "badging", a.apk], capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not m: sys.exit("could not read package info from the APK")
    pkg, code, name = m.group(1), int(m.group(2)), m.group(3)
    if pkg != PACKAGE: sys.exit(f"unexpected package {pkg}")
    if a.min_supported > code: sys.exit("--min-supported cannot exceed the APK's own versionCode")

    h = hashlib.sha256()
    with open(a.apk, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    read = lambda p: open(p, encoding="utf-8").read().strip() if p else None

    manifest = {
        "versionCode": code,
        "versionName": name,
        # Published next to version.json under the APK's own file name (manotu-<ver>.apk from 0.2.3; gimano-<ver>.apk before).
        "apkUrl": f"{BASE}{os.path.basename(a.apk)}",
        "minSupported": a.min_supported,
        "sha256": h.hexdigest(),
        "sizeBytes": os.path.getsize(a.apk),
        "notes": {k: v for k, v in (("ms", read(a.notes_ms)), ("en", read(a.notes_en))) if v},
        "publishedUtc": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }
    with open(a.out, "w", encoding="utf-8") as f: json.dump(manifest, f, ensure_ascii=False, indent=2); f.write("\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    if not re.fullmatch(rf"(manotu|gimano)-{re.escape(name)}\.apk", os.path.basename(a.apk)):
        sys.exit(f"APK file name must be manotu-{name}.apk (apkUrl uses the file name)")

if __name__ == "__main__":
    main()
