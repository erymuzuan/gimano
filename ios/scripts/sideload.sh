#!/bin/bash
# Build a device IPA for re-signing with AltStore Classic/Sideloadly. No Apple team required.
set -euo pipefail
cd "$(dirname "$0")/../.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
OUT="${OUT:-artifacts/sideload}"
mkdir -p "$OUT"
xcodebuild -project ios/Gimano.xcodeproj -scheme Gimano -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath "$OUT/DerivedData" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO GIMANO_ICLOUD=NO \
  GIMANO_GOOGLE_CLIENT_ID= GIMANO_GOOGLE_REVERSED_CLIENT_ID=gimano-google-not-configured build
APP="$OUT/DerivedData/Build/Products/Release-iphoneos/Gimano.app"
VERSION=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$APP/Info.plist")
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir "$STAGE/Payload"
ditto "$APP" "$STAGE/Payload/Gimano.app"
# Strip upstream framework signatures; the installer signs everything for the user's account.
find "$STAGE" -type d -name _CodeSignature -prune -exec rm -rf {} +
find "$STAGE" -name embedded.mobileprovision -delete
for framework in "$STAGE/Payload/Gimano.app/Frameworks/"*.framework; do
  codesign --force --sign - --timestamp=none "$framework"
done
codesign --force --sign - --timestamp=none "$STAGE/Payload/Gimano.app"
IPA="$PWD/$OUT/gimano-$VERSION.ipa"
(cd "$STAGE" && /usr/bin/zip -qry "$IPA" Payload)
codesign --verify --deep --strict "$STAGE/Payload/Gimano.app"
echo "$IPA"
