#!/usr/bin/env bash
# Build libFileApexTsnet.so, in-process userspace Tailscale node (tsnet) for desktop Linux.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/native/tsnet/cmd/libtsnet"
OUT="$ROOT/linux/build/Tsnet"
GO="${FILEAPEX_GO:-$HOME/toolchain/go/bin/go}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Skipping tsnet .so (not Linux)."
  exit 0
fi

if [[ ! -x "$GO" ]]; then
  if command -v go >/dev/null 2>&1; then
    GO="$(command -v go)"
  else
    echo "go not found, tsnet .so was not built."
    exit 1
  fi
fi

export PATH="$(dirname "$GO"):$PATH"
export GOSUMDB="${GOSUMDB:-sum.golang.org}"
# go.mod needs 1.27.1; any newer local Go satisfies it without downloading a toolchain.
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"
export GOCACHE="${GOCACHE:-$HOME/.cache/fileapex-build/gocache}"
export GOMODCACHE="${GOMODCACHE:-$HOME/.cache/fileapex-build/gomodcache}"

case "$(uname -m)" in
  x86_64) GOARCH_VALUE=amd64 ;;
  aarch64|arm64) GOARCH_VALUE=arm64 ;;
  *) echo "Unsupported CPU $(uname -m)"; exit 1 ;;
esac

mkdir -p "$OUT"
cd "$ROOT/native/tsnet"

# prepare_tsnet.py patches a copy of the downloaded Tailscale source, so the modules must be present first.
"$GO" mod download
LDFLAGS="$("$ROOT/native/tsnet/prepare_tsnet.py" "$GO")"
export GOFLAGS="${GOFLAGS:-} -modfile=$ROOT/native/tsnet/build/tsnet-patched/tsnet.mod"

echo "Building tsnet linux/$GOARCH_VALUE"
CGO_ENABLED=1 GOOS=linux GOARCH="$GOARCH_VALUE" \
  "$GO" build -buildmode=c-shared -trimpath -ldflags "$LDFLAGS -s -w" -o "$OUT/libFileApexTsnet.so" "$SRC"
rm -f "$OUT/libFileApexTsnet.h"
echo "Built $OUT/libFileApexTsnet.so"
