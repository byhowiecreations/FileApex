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

SWIFT_SOURCES=(
  "$TRAY/FileApexTrayBridge.swift"
  "$TRAY/MacTrayManager.swift"
  "$TRAY/TrayMenuView.swift"
  "$TRAY/DropBoxWindowManager.swift"
  "$TRAY/NativeToast.swift"
  "$TRAY/TrayDeviceBridge.swift"
  "$TRAY/LocalNetworkProbe.swift"
  "$TRAY/LanHttpClient.swift"
  "$ROOT/macos/Shared/FileApexPaths.swift"
  "$ROOT/macos/Shared/AppCopy.swift"
)

compile_arch() {
  local arch="$1"
  local output="$2"
  swiftc -O \
    -target "${arch}-apple-macosx14.0" \
    -emit-library \
    -o "$output" \
    "${SWIFT_SOURCES[@]}" \
    -framework AppKit \
    -framework SwiftUI \
    -framework Foundation \
    -framework UniformTypeIdentifiers \
    -framework Network \
    -Xlinker -install_name \
    -Xlinker @executable_path/../Frameworks/libFileApexTray.dylib
}

compile_arch arm64 "$OUT/libFileApexTray_arm64.dylib"
compile_arch x86_64 "$OUT/libFileApexTray_x86_64.dylib"

lipo -create -output "$OUT/libFileApexTray.dylib" "$OUT/libFileApexTray_arm64.dylib" "$OUT/libFileApexTray_x86_64.dylib"
rm -f "$OUT/libFileApexTray_arm64.dylib" "$OUT/libFileApexTray_x86_64.dylib"

codesign --force --sign - "$OUT/libFileApexTray.dylib"
echo "Built Universal (arm64 + x86_64) $OUT/libFileApexTray.dylib"
