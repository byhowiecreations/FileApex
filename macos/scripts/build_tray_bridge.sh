#!/usr/bin/env bash
# Build libFileApexTray.dylib — native NSStatusItem / NSPopover / SwiftUI tray UI.
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
  -target arm64-apple-macosx14.0 \
  -emit-library \
  -o "$OUT/libFileApexTray_arm64.dylib" \
  "$TRAY/FileApexTrayBridge.swift" \
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
  -Xlinker @executable_path/../Frameworks/libFileApexTray.dylib

swiftc -O \
  -target x86_64-apple-macosx14.0 \
  -emit-library \
  -o "$OUT/libFileApexTray_x86_64.dylib" \
  "$TRAY/FileApexTrayBridge.swift" \
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
  -Xlinker @executable_path/../Frameworks/libFileApexTray.dylib


lipo -create -output "$OUT/libFileApexTray.dylib" "$OUT/libFileApexTray_arm64.dylib" "$OUT/libFileApexTray_x86_64.dylib"
rm -f "$OUT/libFileApexTray_arm64.dylib" "$OUT/libFileApexTray_x86_64.dylib"

codesign --force --sign - "$OUT/libFileApexTray.dylib"
echo "Built Universal (arm64 + x86_64) $OUT/libFileApexTray.dylib"
