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
# Play requires 16 KB page alignment for native libraries; gomobile links at 4 KB by default.
LDFLAGS="$LDFLAGS -extldflags=-Wl,-z,max-page-size=16384"
# Drop the symbol table and DWARF: about half of the library.
LDFLAGS="$LDFLAGS -s -w"
# Features this app never uses (CLI, SSH, file sharing, exit nodes, cloud and OS integrations, debug and
# telemetry support). Login, status, logout, the userspace network stack and DNS stay.
OMIT="aws bird kube synology cloud drive ssh taildrop webclient relayserver appconnectors conn25 \
advertiseexitnode advertiseroutes useexitnode completion completion_scripts cli colorable qrcodes doctor \
flashappliance cliconndiag debug debugeventbus debugportmapper capture tap tpm posture clientupdate systray \
desktop_sessions dbus networkmanager resolved iptables linuxdnsfight linkspeed tundevstats listenrawdisco \
sdnotify syslog syspolicy wakeonlan webbrowser unixsocketidentity identityfederation oauthkey hujsonconf \
remoteconfig runtimemetrics clientmetrics netlog logtail portlist"
TAGS=""
for feature in $OMIT; do TAGS="$TAGS,ts_omit_$feature"; done
"$GOBIN/gomobile" bind -target android/arm,android/arm64 -androidapi 26 -tags "${TAGS#,}" -ldflags "$LDFLAGS" -o "$OUT" .
echo "Built $OUT"
