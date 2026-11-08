#!/usr/bin/env bash
# Builds a signed release and the in-app update feed.
#
#   tools/make-release.sh "What's new in this version"
#
# Output (dist/):
#   MK-IPTV-<version>.apk   – upload as a release asset
#   version.json            – upload as a release asset (same release)
#
# The app reads:  https://github.com/<owner>/mk-iptv-releases/releases/latest/download/version.json
# so publishing a new GitHub release in that PUBLIC repo with these two files makes the update
# appear in every installed copy (phone, tablet, Google TV).
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES="${1:-Bug fixes and improvements.}"
REPO_URL="${RELEASES_REPO:-https://github.com/mkuz1/mk-iptv-releases}"

[ -f keystore.properties ] || { echo "keystore.properties missing (see README)"; exit 1; }
./gradlew -q :app:assembleRelease

VERSION_NAME=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
VERSION_CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
mkdir -p dist
APK="dist/MK-IPTV-${VERSION_NAME}.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")

python3 - "$VERSION_CODE" "$VERSION_NAME" "$REPO_URL/releases/download/v${VERSION_NAME}/MK-IPTV-${VERSION_NAME}.apk" "$SHA" "$NOTES" "$SIZE" > dist/version.json <<'PY'
import json, sys
code, name, url, sha, notes, size = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "apkUrl": url, "sha256": sha, "notes": notes, "sizeBytes": int(size)}, indent=2))
PY
echo "Built $APK"
echo "Feed  dist/version.json:"; cat dist/version.json
echo
echo "Next: create release tag v${VERSION_NAME} in ${REPO_URL} and upload both files."
