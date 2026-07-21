#!/usr/bin/env bash
# Build libOmniNodeTray.dylib — native NSStatusItem / NSPopover / SwiftUI tray UI.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TRAY="$ROOT/macos/Tray"
OUT="$ROOT/macos/build/Tray"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Skipping macOS tray bridge (not Darwin)."
  exit 0
fi

if ! command -v swiftc >/dev/null 2>&1; then
  echo "swiftc not found — skipping native tray bridge."
  exit 0
fi

mkdir -p "$OUT"

swiftc -O \
  -emit-library \
  -o "$OUT/libOmniNodeTray.dylib" \
  "$TRAY/OmniNodeTrayBridge.swift" \
  "$TRAY/MacTrayManager.swift" \
  "$TRAY/TrayMenuView.swift" \
  "$TRAY/DropBoxWindowManager.swift" \
  "$TRAY/NativeToast.swift" \
  "$TRAY/TrayDeviceBridge.swift" \
  -framework AppKit \
  -framework SwiftUI \
  -framework Foundation \
  -framework UniformTypeIdentifiers \
  -Xlinker -install_name \
  -Xlinker @executable_path/../Frameworks/libOmniNodeTray.dylib

codesign --force --sign - "$OUT/libOmniNodeTray.dylib"
echo "Built $OUT/libOmniNodeTray.dylib"
