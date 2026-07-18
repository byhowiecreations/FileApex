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

# Re-sign PlugIns + host with empty entitlements (no App Sandbox).
FINDER_ENTS="$ROOT/macos/FinderSync/FinderSync.entitlements"
SHARE_ENTS="$ROOT/macos/ShareExtension/ShareExtension.entitlements"
HOST_ENTS="$ROOT/composeApp/macos/OmniNode.entitlements"
codesign --force --deep --sign - --entitlements "$FINDER_ENTS" "$PLUGINS/OmniNodeFinderSync.appex"
codesign --force --deep --sign - --entitlements "$SHARE_ENTS" "$PLUGINS/OmniNodeShareExtension.appex"
codesign --force --deep --sign - --entitlements "$HOST_ENTS" "$APP_BUNDLE"
xattr -cr "$APP_BUNDLE" || true

echo "Embedded + re-signed (unsandboxed) extensions into $PLUGINS"
ls -la "$PLUGINS"
codesign -d --entitlements :- "$APP_BUNDLE" 2>/dev/null | plutil -p - 2>/dev/null || true

