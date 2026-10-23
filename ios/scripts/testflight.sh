#!/bin/zsh
# Archive the Release build, export it for App Store Connect and upload it to TestFlight.
# Needs Config/asc.env (see Config/asc.env.example) and Config/Local.xcconfig (DEVELOPMENT_TEAM).
# Usage: scripts/testflight.sh [build-number]      (default: current CURRENT_PROJECT_VERSION + git commit count)
set -euo pipefail
cd "${0:A:h}/.."
source Config/asc.env
: "${ASC_ISSUER_ID:?}" "${ASC_KEY_ID:?}" "${ASC_KEY_PATH:?}"
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
BUILD=${1:-$(git rev-list --count HEAD)}
OUT=${OUT:-build/testflight}
rm -rf "$OUT"; mkdir -p "$OUT"
AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")

xcodegen generate
xcodebuild archive -project Gimano.xcodeproj -scheme Gimano -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$OUT/Gimano.xcarchive" \
  CURRENT_PROJECT_VERSION="$BUILD" ${GIMANO_ICLOUD:+GIMANO_ICLOUD=$GIMANO_ICLOUD} "${AUTH[@]}"
xcodebuild -exportArchive -archivePath "$OUT/Gimano.xcarchive" -exportPath "$OUT/export" \
  -exportOptionsPlist Config/ExportOptions-AppStore.plist "${AUTH[@]}"
xcrun altool --upload-app -f "$OUT/export/Gimano.ipa" -t ios \
  --api-key "$ASC_KEY_ID" --api-issuer "$ASC_ISSUER_ID" --p8-file-path "$ASC_KEY_PATH"
echo "Uploaded build $BUILD. It appears in TestFlight after processing (~10-30 min)."
