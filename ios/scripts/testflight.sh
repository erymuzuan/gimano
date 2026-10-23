#!/bin/zsh
# Archive the Release build, export it for App Store Connect and upload it to TestFlight.
# Needs Config/asc.env (see Config/asc.env.example) and Config/Local.xcconfig (DEVELOPMENT_TEAM).
# Usage: scripts/testflight.sh [build-number]      (default: CURRENT_PROJECT_VERSION from project.yml)
set -euo pipefail
cd "${0:A:h}/.."
source Config/asc.env
: "${ASC_ISSUER_ID:?}" "${ASC_KEY_ID:?}" "${ASC_KEY_PATH:?}" "${ASC_TEAM_ID:?set ASC_TEAM_ID to Erymuzuan Mustapa's individual team id}"
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
BUILD=${1:-}
OUT=${OUT:-build/testflight}
rm -rf "$OUT"; mkdir -p "$OUT"
AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")

xcodegen generate
xcodebuild archive -project Gimano.xcodeproj -scheme Gimano -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$OUT/Gimano.xcarchive" \
  DEVELOPMENT_TEAM="$ASC_TEAM_ID" ${BUILD:+CURRENT_PROJECT_VERSION=$BUILD} ${GIMANO_ICLOUD:+GIMANO_ICLOUD=$GIMANO_ICLOUD} "${AUTH[@]}"
# AGENTS.md: TestFlight/App Store must use the individual team, never STAFY COMPANY LIMITED.
APP="$OUT/Gimano.xcarchive/Products/Applications/Gimano.app"
TEAM_NAME=$(security cms -D -i "$APP/embedded.mobileprovision" | plutil -extract TeamName raw -)
TEAM_ID=$(security cms -D -i "$APP/embedded.mobileprovision" | plutil -extract TeamIdentifier.0 raw -)
echo "Signed by team: $TEAM_NAME ($TEAM_ID)"
if [[ "$TEAM_ID" != "$ASC_TEAM_ID" || "$TEAM_NAME" == *STAFY* ]]; then
  echo "Refusing: archive is not signed by the individual team $ASC_TEAM_ID." >&2; exit 1
fi
xcodebuild -exportArchive -archivePath "$OUT/Gimano.xcarchive" -exportPath "$OUT/export" \
  -exportOptionsPlist Config/ExportOptions-AppStore.plist "${AUTH[@]}"
xcrun altool --upload-app -f "$OUT/export/Gimano.ipa" -t ios \
  --api-key "$ASC_KEY_ID" --api-issuer "$ASC_ISSUER_ID" --p8-file-path "$ASC_KEY_PATH"
echo "Uploaded build ${BUILD:-from project.yml}. It appears in TestFlight after processing (~10-30 min)."
