#!/usr/bin/env bash
# Embed Finder Sync + Share Extension into OmniNode.app/Contents/PlugIns.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_BUNDLE="${1:-}"
CONFIGURATION="${2:-Release}"

if [[ -z "$APP_BUNDLE" || ! -d "$APP_BUNDLE" ]]; then
  echo "Usage: $0 /path/to/OmniNode.app [Release|Debug]"
  exit 1
fi

PRODUCTS="$ROOT/macos/build/DerivedData/Build/Products/$CONFIGURATION"
FINDER_APPEX="$PRODUCTS/OmniNodeFinderSync.appex"
SHARE_APPEX="$PRODUCTS/OmniNodeShareExtension.appex"

if [[ ! -d "$FINDER_APPEX" || ! -d "$SHARE_APPEX" ]]; then
  echo "Extension products missing — running build_extensions.sh…"
  "$ROOT/macos/scripts/build_extensions.sh" "$CONFIGURATION" || {
    echo "WARNING: Could not build extensions (Xcode required). Skipping PlugIns embed."
    exit 0
  }
fi

PLUGINS="$APP_BUNDLE/Contents/PlugIns"
mkdir -p "$PLUGINS"
rm -rf "$PLUGINS/OmniNodeFinderSync.appex" "$PLUGINS/OmniNodeShareExtension.appex"
ditto "$FINDER_APPEX" "$PLUGINS/OmniNodeFinderSync.appex"
ditto "$SHARE_APPEX" "$PLUGINS/OmniNodeShareExtension.appex"

FINDER_ENTS="$ROOT/macos/FinderSync/FinderSync.entitlements"
SHARE_ENTS="$ROOT/macos/ShareExtension/ShareExtension.entitlements"
HOST_ENTS="$ROOT/composeApp/macos/OmniNode.entitlements"

# Sign nested code first, then the host (do not --deep the host or nested sigs get mangled).
codesign --force --sign - --entitlements "$FINDER_ENTS" "$PLUGINS/OmniNodeFinderSync.appex"
codesign --force --sign - --entitlements "$SHARE_ENTS" "$PLUGINS/OmniNodeShareExtension.appex"
codesign --force --sign - --entitlements "$HOST_ENTS" "$APP_BUNDLE"
xattr -cr "$APP_BUNDLE" || true

# Ship entitlement plists inside the app so launch-time re-sign can restore sandbox
# (required — pluginkit silently ignores appexes with missing sandbox entitlements).
ENTS_RES="$APP_BUNDLE/Contents/Resources/ExtensionEntitlements"
mkdir -p "$ENTS_RES"
cp "$FINDER_ENTS" "$ENTS_RES/FinderSync.entitlements"
cp "$SHARE_ENTS" "$ENTS_RES/ShareExtension.entitlements"
cp "$HOST_ENTS" "$ENTS_RES/OmniNode.entitlements"
# Re-sign host after adding Resources.
codesign --force --sign - --entitlements "$HOST_ENTS" "$APP_BUNDLE"

echo "Embedded + re-signed extensions into $PLUGINS"
ls -la "$PLUGINS"
echo "Host entitlements:"
codesign -d --entitlements - --xml "$APP_BUNDLE" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null || true
echo "Finder entitlements:"
codesign -d --entitlements - --xml "$PLUGINS/OmniNodeFinderSync.appex" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null || true
