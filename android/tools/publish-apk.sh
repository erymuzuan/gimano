#!/bin/sh
# Writes version.json for a release APK (see publish-apk.py for options):
#   tools/publish-apk.sh app/build/outputs/apk/release/gimano-0.2.0.apk --notes-ms ms.txt --notes-en en.txt --out /tmp/x/version.json
exec python3 "$(dirname "$0")/publish-apk.py" "$@"
