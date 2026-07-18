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
xattr -cr "$PLUGINS" || true

echo "Embedded extensions into $PLUGINS"
ls -la "$PLUGINS"
