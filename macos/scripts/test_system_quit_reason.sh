#!/usr/bin/env bash
# Compiles SystemQuitReason.swift with its checks and runs them. Needs swiftc (macOS only).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
cp "$ROOT/macos/scripts/SystemQuitReasonTests.swift" "$OUT/main.swift"
swiftc -o "$OUT/system-quit-reason-test" "$ROOT/macos/Tray/SystemQuitReason.swift" "$OUT/main.swift"
"$OUT/system-quit-reason-test"
