#!/usr/bin/env bash
# Build libFileApexTsnet.dylib — in-process userspace Tailscale node (tsnet).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/native/tsnet/cmd/libtsnet"
OUT="$ROOT/macos/build/Tsnet"
GO="${FILEAPEX_GO:-$HOME/Library/FileApex-build/go/bin/go}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Skipping tsnet dylib (not Darwin)."
  exit 0
fi

if [[ ! -x "$GO" ]]; then
  if command -v go >/dev/null 2>&1; then
    GO="$(command -v go)"
  else
    echo "go not found — tsnet dylib was not built."
    exit 1
  fi
fi

export PATH="$(dirname "$GO"):$PATH"
export GOFLAGS="${GOFLAGS:-}"
export GOSUMDB="${GOSUMDB:-sum.golang.org}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.27.1}"
export GOCACHE="${GOCACHE:-$HOME/Library/FileApex-build/gocache}"
export GOMODCACHE="${GOMODCACHE:-$HOME/Library/FileApex-build/gomodcache}"

mkdir -p "$OUT"
cd "$ROOT/native/tsnet"

SDK="$(xcrun --show-sdk-path)"
LDFLAGS="$("$ROOT/native/tsnet/prepare_tsnet.py" "$GO")"

compile_arch() {
  local arch="$1"
  local goarch="$2"
  local output="$3"
  echo "Building tsnet $arch"
  CGO_ENABLED=1 GOOS=darwin GOARCH="$goarch" \
    CC="clang -arch $arch -isysroot $SDK" \
    "$GO" build -buildmode=c-shared -trimpath -ldflags "$LDFLAGS" -o "$output" "$SRC"
}

compile_arch arm64 arm64 "$OUT/libFileApexTsnet_arm64.dylib"
if compile_arch x86_64 amd64 "$OUT/libFileApexTsnet_x86_64.dylib"; then
  lipo -create -output "$OUT/libFileApexTsnet.dylib" \
    "$OUT/libFileApexTsnet_arm64.dylib" \
    "$OUT/libFileApexTsnet_x86_64.dylib"
else
  echo "x86_64 tsnet build failed; shipping the arm64 library only."
  cp "$OUT/libFileApexTsnet_arm64.dylib" "$OUT/libFileApexTsnet.dylib"
fi
rm -f "$OUT/libFileApexTsnet_arm64.dylib" "$OUT/libFileApexTsnet_x86_64.dylib" \
  "$OUT/libFileApexTsnet_arm64.h" "$OUT/libFileApexTsnet_x86_64.h" \
  "$OUT/libFileApexTsnet.h"
install_name_tool -id "@executable_path/../Frameworks/libFileApexTsnet.dylib" \
  "$OUT/libFileApexTsnet.dylib"
codesign --force --sign - "$OUT/libFileApexTsnet.dylib"
echo "Built $OUT/libFileApexTsnet.dylib"
