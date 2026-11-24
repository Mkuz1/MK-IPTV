#!/usr/bin/env bash
# Builds a signed release and the in-app update feed.
#
#   tools/make-release.sh "What's new in this version"
#
# Output (dist/):
#   MK-IPTV-<version>.apk   – upload as a release asset
#   version.json            – upload as a release asset (same release)
#
# The app reads:  https://github.com/Mkuz1/MK-IPTV/releases/latest/download/version.json
# so publishing these files as the latest GitHub release (tag android-v<version>) makes the update
# appear in every sideloaded copy (phone, tablet, Google TV). Google Play installs update through Play.
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES="${1:-Bug fixes and improvements.}"
REPO_URL="${RELEASES_REPO:-https://github.com/Mkuz1/MK-IPTV}"

[ -f keystore.properties ] || { echo "keystore.properties missing (see README)"; exit 1; }
./gradlew -q :app:assembleDirectRelease :app:bundlePlayRelease

VERSION_NAME=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
VERSION_CODE=$(grep 'versionCode =' app/build.gradle.kts | grep -oE '[0-9]+\s*$' | tr -d ' ')
TAG="android-v${VERSION_NAME}"
mkdir -p dist
APK="dist/MK-IPTV-${VERSION_NAME}-android.apk"
cp app/build/outputs/apk/direct/release/app-direct-release.apk "$APK"
cp app/build/outputs/bundle/playRelease/app-play-release.aab "dist/MK-IPTV-${VERSION_NAME}-android.aab"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")

python3 - "$VERSION_CODE" "$VERSION_NAME" "$REPO_URL/releases/download/${TAG}/MK-IPTV-${VERSION_NAME}-android.apk" "$SHA" "$NOTES" "$SIZE" > dist/version.json <<'PY'
import json, sys
code, name, url, sha, notes, size = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "apkUrl": url, "sha256": sha, "notes": notes, "sizeBytes": int(size)}, indent=2))
PY
echo "Built $APK (direct) and dist/MK-IPTV-${VERSION_NAME}-android.aab (Google Play)"
echo "Feed  dist/version.json:"; cat dist/version.json
echo
echo "Next: create release ${TAG} in ${REPO_URL} with the APK, the AAB and version.json (mark it latest)."
