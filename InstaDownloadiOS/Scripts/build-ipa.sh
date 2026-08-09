#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

PROJECT="InstaDownload.xcodeproj"
SCHEME="InstaDownload"
APP_NAME="InstaDownload"
BUILD_DIR="$ROOT/build"
DERIVED="$BUILD_DIR/DerivedData"
IPA="$BUILD_DIR/$APP_NAME.ipa"
SOURCE_JSON="$ROOT/altstore/source.json"

UPDATE_SOURCE=0
[[ "${1:-}" == "--update-source" ]] && UPDATE_SOURCE=1

command -v xcodebuild >/dev/null || { echo "error: xcodebuild not found, this script needs macOS + Xcode." >&2; exit 1; }

echo "==> Building $APP_NAME (Release, unsigned)"
rm -rf "$DERIVED" "$BUILD_DIR/Payload" "$IPA"
xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration Release \
  -sdk iphoneos \
  -derivedDataPath "$DERIVED" \
  -quiet \
  ONLY_ACTIVE_ARCH=NO \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO \
  build

APP="$DERIVED/Build/Products/Release-iphoneos/$APP_NAME.app"
[[ -d "$APP" ]] || { echo "error: build succeeded but $APP is missing." >&2; exit 1; }

echo "==> Packaging $(basename "$IPA")"
rm -rf "$BUILD_DIR/Payload"
mkdir -p "$BUILD_DIR/Payload"
cp -R "$APP" "$BUILD_DIR/Payload/"
( cd "$BUILD_DIR" && zip -qry "$APP_NAME.ipa" Payload )
rm -rf "$BUILD_DIR/Payload"

SIZE=$(stat -f%z "$IPA" 2>/dev/null || stat -c%s "$IPA")
VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$APP/Info.plist")
BUILD=$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "$APP/Info.plist")
TODAY=$(date -u +%Y-%m-%d)

echo
echo "    ipa      $IPA"
echo "    version  $VERSION ($BUILD)"
echo "    size     $SIZE bytes"
echo "    sha256   $(shasum -a 256 "$IPA" | cut -d' ' -f1)"

if [[ $UPDATE_SOURCE -eq 1 ]]; then
  echo
  echo "==> Updating altstore/source.json"
  VERSION="$VERSION" BUILD="$BUILD" SIZE="$SIZE" TODAY="$TODAY" SOURCE_JSON="$SOURCE_JSON" python3 - <<'PY'
import json, os

path = os.environ["SOURCE_JSON"]
with open(path) as handle:
    source = json.load(handle)

version, build = os.environ["VERSION"], os.environ["BUILD"]
entries = source["apps"][0]["versions"]
entry = next((e for e in entries if e["version"] == version), None)

if entry is None:
    entry = dict(entries[0])
    entry["version"] = version
    entry["localizedDescription"] = "See the release notes on GitHub."
    entries.insert(0, entry)
    print(f"    added new version entry {version}")

entry["buildVersion"] = build
entry["size"] = int(os.environ["SIZE"])
entry["date"] = os.environ["TODAY"]

with open(path, "w") as handle:
    json.dump(source, handle, indent=2)
    handle.write("\n")

print(f"    {version} ({build}), {entry['size']} bytes, {entry['date']}")
print("    remember to point downloadURL at the release asset before publishing")
PY
fi

echo
echo "Done. Upload the .ipa as a GitHub release asset, make sure the matching"
echo "downloadURL in altstore/source.json points at it, then push."
