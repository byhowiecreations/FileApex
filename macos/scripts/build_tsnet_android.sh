#!/usr/bin/env bash
# Build tsnetbridge.aar — in-process userspace Tailscale for Android (arm64).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/native/tsnet/build/tsnetbridge.aar"
GO="${FILEAPEX_GO:-$HOME/Library/FileApex-build/go/bin/go}"
export GOBIN="${FILEAPEX_GOBIN:-$HOME/Library/FileApex-build/gobin}"

if [[ ! -x "$GO" ]]; then
  echo "go not found — Android tsnet AAR was not built."
  exit 1
fi

export PATH="$(dirname "$GO"):$GOBIN:$PATH"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.27.1}"
export GOCACHE="${GOCACHE:-$HOME/Library/FileApex-build/gocache}"
export GOMODCACHE="${GOMODCACHE:-$HOME/Library/FileApex-build/gomodcache}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
unset GOOS GOARCH
export CGO_ENABLED=1

mkdir -p "$GOBIN" "$(dirname "$OUT")"
cd "$ROOT/native/tsnet"
GOTOOLCHAIN=go1.27.1 "$GO" install golang.org/x/mobile/cmd/gomobile@latest
GOTOOLCHAIN=go1.27.1 "$GO" install golang.org/x/mobile/cmd/gobind@latest
"$GOBIN/gomobile" init
LDFLAGS="$("$ROOT/native/tsnet/prepare_tsnet.py" "$GO")"
"$GOBIN/gomobile" bind -target android/arm64 -androidapi 26 -ldflags "$LDFLAGS" -o "$OUT" .
echo "Built $OUT"
