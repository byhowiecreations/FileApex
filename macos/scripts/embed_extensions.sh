#!/usr/bin/env bash
# Embed the FileApex Share Extension into FileApex.app/Contents/PlugIns.
# Also removes the deprecated Finder Sync appex if a previous build left it behind.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_BUNDLE="${1:-}"
CONFIGURATION="${2:-Release}"

if [[ -z "$APP_BUNDLE" || ! -d "$APP_BUNDLE" ]]; then
  echo "Usage: $0 /path/to/FileApex.app [Release|Debug]"
  exit 1
fi

PRODUCTS="$ROOT/macos/build/DerivedData/Build/Products/$CONFIGURATION"
SHARE_APPEX="$PRODUCTS/FileApexShareExtension.appex"

if [[ ! -d "$SHARE_APPEX" ]]; then
  echo "Extension product missing — running build_extensions.sh…"
  "$ROOT/macos/scripts/build_extensions.sh" "$CONFIGURATION" || {
    echo "WARNING: Could not build Share extension (Xcode required). Skipping PlugIns embed."
    exit 0
  }
fi

PLUGINS="$APP_BUNDLE/Contents/PlugIns"
mkdir -p "$PLUGINS"
# Drop deprecated Finder Sync and replace Share.
rm -rf "$PLUGINS/FileApexFinderSync.appex" "$PLUGINS/FileApexShareExtension.appex"
ditto "$SHARE_APPEX" "$PLUGINS/FileApexShareExtension.appex"

SHARE_ENTS="$ROOT/macos/ShareExtension/ShareExtension.entitlements"
HOST_ENTS="$ROOT/composeApp/macos/FileApex.entitlements"

# Sign nested code first, then the host (do not --deep the host or nested sigs get mangled).
codesign --force --sign - --entitlements "$SHARE_ENTS" "$PLUGINS/FileApexShareExtension.appex"
codesign --force --sign - --entitlements "$HOST_ENTS" "$APP_BUNDLE"
xattr -cr "$APP_BUNDLE" || true

# Ship entitlement plists inside the app so launch-time re-sign can restore sandbox
# (required — pluginkit silently ignores appexes with missing sandbox entitlements).
ENTS_RES="$APP_BUNDLE/Contents/Resources/ExtensionEntitlements"
mkdir -p "$ENTS_RES"
rm -f "$ENTS_RES/FinderSync.entitlements"
cp "$SHARE_ENTS" "$ENTS_RES/ShareExtension.entitlements"
cp "$HOST_ENTS" "$ENTS_RES/FileApex.entitlements"
# Re-sign host after adding Resources.
codesign --force --sign - --entitlements "$HOST_ENTS" "$APP_BUNDLE"

echo "Embedded + re-signed Share extension into $PLUGINS"
ls -la "$PLUGINS"
echo "Host entitlements:"
codesign -d --entitlements - --xml "$APP_BUNDLE" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null || true
echo "Share entitlements:"
codesign -d --entitlements - --xml "$PLUGINS/FileApexShareExtension.appex" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null || true
