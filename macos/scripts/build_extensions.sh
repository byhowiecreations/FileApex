#!/usr/bin/env bash
# Build FileApex macOS Share extensions with ad-hoc signing (no Apple ID).
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
    echo "Full Xcode.app is required to build Share extensions."
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
xcodebuild "${common[@]}" -scheme FileApexBulletinShareExtension build

PRODUCTS="$OUT/DerivedData/Build/Products/$CONFIGURATION"
SHARE="$PRODUCTS/FileApexShareExtension.appex"
BULLETIN="$PRODUCTS/FileApexBulletinShareExtension.appex"

copy_extension_catalogs() {
  local appex="$1"
  local plist_src="$2"
  local res="$appex/Contents/Resources"
  mkdir -p "$res"
  # Xcode Resources phase is unreliable for these .appex targets; copy after build.
  cp "$ROOT/shared/src/desktopMain/resources/i18n/en.xml" "$res/"
  cp "$ROOT/shared/src/desktopMain/resources/i18n/es.xml" "$res/"
  cp "$ROOT/shared/src/desktopMain/resources/i18n/zh-rCN.xml" "$res/"
  for loc in en es zh-Hans; do
    mkdir -p "$res/${loc}.lproj"
    cp "$plist_src/${loc}.lproj/InfoPlist.strings" "$res/${loc}.lproj/"
  done
}

copy_extension_catalogs "$SHARE" "$MACOS/ShareExtension"
copy_extension_catalogs "$BULLETIN" "$MACOS/BulletinShareExtension"

# PluginKit / ShareKit cannot load a 0700 .appex (Info.plist unreadable to helpers).
chmod -R a+rX "$SHARE" "$BULLETIN"

codesign --force --sign - --entitlements "$MACOS/ShareExtension/ShareExtension.entitlements" "$SHARE"
codesign --force --sign - --entitlements "$MACOS/BulletinShareExtension/BulletinShareExtension.entitlements" "$BULLETIN"
chmod -R a+rX "$SHARE" "$BULLETIN"

echo "Built + ad-hoc signed:"
ls -la "$SHARE" "$BULLETIN"
