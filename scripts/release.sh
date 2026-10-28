#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub release; installed apps
# offer the update on their next start.
#   scripts/release.sh "Что нового: …"
# Needs: versionName bumped in app/build.gradle.kts, signing/keystore.properties, gh logged in.
set -euo pipefail
cd "$(dirname "$0")/.."

notes="${1:?usage: scripts/release.sh \"release notes\"}"
version=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
tag="v$version"
apk="Priemka-$version.apk"

if gh release view "$tag" >/dev/null 2>&1; then
    echo "release $tag already exists: bump versionName/versionCode first" >&2
    exit 1
fi
[ -f signing/keystore.properties ] || { echo "signing/keystore.properties missing" >&2; exit 1; }

./gradlew.bat -q testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$apk"
gh release create "$tag" "$apk" --title "Приёмка $version" --notes "$notes"
echo "published $tag ($apk)"
