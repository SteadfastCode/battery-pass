#!/usr/bin/env bash
# Build the signed release APK and write dist/latest.json beside it; with --publish, tag the commit and
# create the GitHub release carrying both files. Installed copies find the manifest at
# https://github.com/<repo>/releases/latest/download/latest.json (the URL baked into the app).
#
#   scripts/release.sh [--notes "what changed"] [--publish]
#
# Environment: RELEASE_REPO (default SteadfastCode/battery-pass), RELEASE_APK_URL (override the APK URL in the
# manifest when the APK is hosted somewhere other than the GitHub release), JAVA_HOME (defaults to Android
# Studio's JBR on Windows when unset).
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES=""
PUBLISH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1 ;;
    --notes) NOTES="${2:-}"; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

GRADLE_FILE=app/build.gradle.kts
VERSION_CODE=$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE")
VERSION_NAME=$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' "$GRADLE_FILE")
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
  echo "could not read versionCode/versionName from $GRADLE_FILE" >&2
  exit 1
fi
if [ ! -f keystore.properties ]; then
  echo "keystore.properties is missing: releases must be signed with the permanent key (README \"Updates\")" >&2
  exit 1
fi

REPO="${RELEASE_REPO:-SteadfastCode/battery-pass}"
TAG="v$VERSION_NAME"
APK_NAME="battery-pass-$VERSION_NAME.apk"
APK_URL="${RELEASE_APK_URL:-https://github.com/$REPO/releases/download/$TAG/$APK_NAME}"

GRADLEW=./gradlew
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    GRADLEW=./gradlew.bat
    if [ -z "${JAVA_HOME:-}" ] && [ -d "/c/Program Files/Android/Android Studio/jbr" ]; then
      export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"
    fi
    ;;
esac

echo "Building Battery Pass $VERSION_NAME ($VERSION_CODE)"
"$GRADLEW" --quiet assembleRelease

mkdir -p dist
rm -f dist/*.apk dist/latest.json
cp app/build/outputs/apk/release/app-release.apk "dist/$APK_NAME"
SHA=$(sha256sum "dist/$APK_NAME" | cut -d' ' -f1)
PUBLISHED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# JSON-escape the notes: backslash, quote, newline, tab.
n=${NOTES//\\/\\\\}
n=${n//\"/\\\"}
n=${n//$'\n'/\\n}
n=${n//$'\t'/\\t}

cat > dist/latest.json <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apkUrl": "$APK_URL",
  "sha256": "$SHA",
  "notes": "$n",
  "publishedAt": "$PUBLISHED_AT"
}
EOF

echo "Wrote dist/$APK_NAME ($(du -k "dist/$APK_NAME" | cut -f1) KB) and dist/latest.json"
cat dist/latest.json

if [ "$PUBLISH" = 1 ]; then
  command -v gh >/dev/null || { echo "gh is not installed" >&2; exit 1; }
  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "commit your changes before publishing" >&2
    exit 1
  fi
  if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    if [ "$(git rev-parse "refs/tags/$TAG^{commit}")" != "$(git rev-parse HEAD)" ]; then
      echo "tag $TAG exists on another commit; bump versionName first" >&2
      exit 1
    fi
  else
    git tag -a "$TAG" -m "Battery Pass $VERSION_NAME"
  fi
  git push origin HEAD
  git push origin "$TAG"
  gh release create "$TAG" "dist/$APK_NAME" dist/latest.json \
    --repo "$REPO" --title "Battery Pass $VERSION_NAME" --notes "${NOTES:-Battery Pass $VERSION_NAME}"
  echo "Published https://github.com/$REPO/releases/tag/$TAG"
fi
