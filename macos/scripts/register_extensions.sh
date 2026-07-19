#!/usr/bin/env bash
# Manual helper for /Applications/OmniNode.app ONLY.
# Prefer launching OmniNode from Applications — the app registers the Share extension on startup.
# This script never copies from the project tree; it only re-signs + pluginkit-registers
# PlugIns already present under /Applications/OmniNode.app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="/Applications/OmniNode.app"
SHARE="$DEST/Contents/PlugIns/OmniNodeShareExtension.appex"
LEGACY_FINDER="$DEST/Contents/PlugIns/OmniNodeFinderSync.appex"
SHARE_ENTS="$ROOT/macos/ShareExtension/ShareExtension.entitlements"
HOST_ENTS="$ROOT/composeApp/macos/OmniNode.entitlements"

if [[ ! -d "$DEST" ]]; then
  echo "Missing $DEST — copy current/OmniNode.app there yourself, then launch OmniNode (or re-run this)."
  exit 1
fi
if [[ ! -d "$SHARE" ]]; then
  echo "Missing Share PlugIn under $DEST"
  exit 1
fi

# Remove deprecated Finder Sync from the installed app + pluginkit.
if [[ -d "$LEGACY_FINDER" ]]; then
  echo "Removing deprecated OmniNodeFinderSync.appex from $DEST"
  /usr/bin/pluginkit -r "$LEGACY_FINDER" || true
  rm -rf "$LEGACY_FINDER"
fi

# Ad-hoc re-sign with sandbox entitlements (required or pluginkit silently ignores the appex).
echo "Re-signing Share PlugIn + host under $DEST…"
codesign --force --sign - --entitlements "$SHARE_ENTS" "$SHARE"
codesign --force --sign - --entitlements "$HOST_ENTS" "$DEST"
xattr -cr "$DEST" || true

# Drop any non-Applications OmniNode plugin paths, and all Finder Sync registrations.
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  case "$path" in
    */OmniNodeFinderSync.appex|/Applications/*/OmniNodeFinderSync.appex)
      echo "Unregistering deprecated Finder Sync: $path"
      /usr/bin/pluginkit -r "$path" || true
      ;;
    /Applications/*) ;;
    *)
      echo "Removing non-Applications plugin: $path"
      /usr/bin/pluginkit -r "$path" || true
      ;;
  esac
done < <(/usr/bin/pluginkit -mAvvv 2>/dev/null | awk '
  /com\.omninode\.(FinderSync|ShareExtension)/ { hit=1; next }
  hit && /Path = / { sub(/^.*Path = /,""); print; hit=0 }
')

echo "Registering $SHARE with pluginkit…"
/usr/bin/pluginkit -a "$SHARE" || true
/usr/bin/pluginkit -e use -i com.omninode.ShareExtension || true
/usr/bin/pluginkit -e ignore -i com.omninode.FinderSync || true

echo "=== entitlements check ==="
codesign -d --entitlements - --xml "$SHARE" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null || true

echo "=== pluginkit status ==="
/usr/bin/pluginkit -mAvvv 2>&1 | rg -i 'omninode' -A 6 || echo "(not listed — enable in System Settings → Login Items & Extensions, then relaunch Finder)"
