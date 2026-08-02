#!/usr/bin/env bash
# Build the FileApex Share Extension with ad-hoc signing (no Apple ID).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MACOS="$ROOT/macos"
OUT="$MACOS/build"
CONFIGURATION="${1:-Release}"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "xcodebuild not found — install Xcode to build macOS extensions."
  exit 1
fi

if [[ "$(xcode-select -p 2>/dev/null)" == "/Library/Developer/CommandLineTools" ]]; then
  if [[ -d /Applications/Xcode.app ]]; then
    echo "Developer dir is Command Line Tools; prefer: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
  else
    echo "Full Xcode.app is required to build the Share extension."
    exit 1
  fi
fi

mkdir -p "$OUT"
common=(
  -project "$MACOS/FileApexExtensions.xcodeproj"
  -configuration "$CONFIGURATION"
  -derivedDataPath "$OUT/DerivedData"
  -destination "platform=macOS,arch=arm64"
  CODE_SIGNING_ALLOWED=NO
  CODE_SIGN_IDENTITY="-"
  CODE_SIGN_STYLE=Manual
  DEVELOPMENT_TEAM=
  PROVISIONING_PROFILE_SPECIFIER=
)

xcodebuild "${common[@]}" -scheme FileApexShareExtension build

PRODUCTS="$OUT/DerivedData/Build/Products/$CONFIGURATION"
SHARE="$PRODUCTS/FileApexShareExtension.appex"

# Ad-hoc codesign so pluginkit / Gatekeeper can load unsigned local builds.
codesign --force --sign - --entitlements "$MACOS/ShareExtension/ShareExtension.entitlements" "$SHARE"

echo "Built + ad-hoc signed:"
ls -la "$SHARE"
codesign -dv --verbose=2 "$SHARE"
