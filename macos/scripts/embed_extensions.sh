#!/usr/bin/env bash
# Embed FileApex Share extensions into FileApex.app/Contents/PlugIns.
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
BULLETIN_APPEX="$PRODUCTS/FileApexBulletinShareExtension.appex"

echo "Rebuilding Share extensions…"
# Always rebuild. Reusing DerivedData .appex left Contents/Resources empty
# (Xcode skipped the resource phase; embed then ditto'd the stale bundle).
bash "$ROOT/macos/scripts/build_extensions.sh" "$CONFIGURATION"

PLUGINS="$APP_BUNDLE/Contents/PlugIns"
mkdir -p "$PLUGINS"
rm -rf "$PLUGINS/FileApexFinderSync.appex" "$PLUGINS/FileApexShareExtension.appex" "$PLUGINS/FileApexBulletinShareExtension.appex"
ditto "$SHARE_APPEX" "$PLUGINS/FileApexShareExtension.appex"
ditto "$BULLETIN_APPEX" "$PLUGINS/FileApexBulletinShareExtension.appex"

SHARE_ENTS="$ROOT/macos/ShareExtension/ShareExtension.entitlements"
BULLETIN_ENTS="$ROOT/macos/BulletinShareExtension/BulletinShareExtension.entitlements"
HOST_ENTS="$ROOT/composeApp/macos/FileApex.entitlements"

codesign --force --sign - --entitlements "$SHARE_ENTS" "$PLUGINS/FileApexShareExtension.appex"
codesign --force --sign - --entitlements "$BULLETIN_ENTS" "$PLUGINS/FileApexBulletinShareExtension.appex"
codesign --force --sign - --entitlements "$HOST_ENTS" "$APP_BUNDLE"
xattr -cr "$APP_BUNDLE" || true

ENTS_RES="$APP_BUNDLE/Contents/Resources/ExtensionEntitlements"
mkdir -p "$ENTS_RES"
rm -f "$ENTS_RES/FinderSync.entitlements"
cp "$SHARE_ENTS" "$ENTS_RES/ShareExtension.entitlements"
cp "$BULLETIN_ENTS" "$ENTS_RES/BulletinShareExtension.entitlements"
cp "$HOST_ENTS" "$ENTS_RES/FileApex.entitlements"
codesign --force --sign - --entitlements "$HOST_ENTS" "$APP_BUNDLE"

echo "Embedded + re-signed Share extensions into $PLUGINS"
ls -la "$PLUGINS"
