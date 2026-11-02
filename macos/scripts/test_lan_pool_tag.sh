#!/usr/bin/env bash
# Compiles LanPoolTag.swift with its checks and runs them. Needs swiftc (macOS only).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
cp "$ROOT/macos/scripts/LanPoolTagTests.swift" "$OUT/main.swift"
swiftc -o "$OUT/lan-pool-tag-test" "$ROOT/macos/Tray/LanPoolTag.swift" "$OUT/main.swift"
"$OUT/lan-pool-tag-test"
