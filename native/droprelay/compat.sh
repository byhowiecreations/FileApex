#!/bin/sh
# Runs relay clients built on older iroh-relay versions against this crate's relay, in a Docker container (nothing is built on the Mac).
# usage: ./compat.sh           (every client in compat/v*)
# Each client pairs two devices with the shared token, moves a datagram between them and expects strangers to be refused.
set -e
cd "$(dirname "$0")"
docker run --rm --platform linux/arm64 \
  -v "$PWD:/src:ro" -v "$PWD/compat:/src/compat" -v droprelay-target:/target -v droprelay-cargo:/usr/local/cargo/registry \
  -e CARGO_TARGET_DIR=/target -w /src rust:1.99-alpine sh -c '
    set -e
    apk add --no-cache musl-dev >/dev/null
    cargo build --release --locked
    cat > /tmp/relay.toml <<CONF
http_bind_addr = "127.0.0.1:3340"

[access]
shared_token = ["compat-token"]

[drop]
registry_url = "https://registry.invalid/check"
CONF
    /target/release/droprelay --config-path /tmp/relay.toml >/tmp/relay.log 2>&1 &
    relay=$!
    trap "kill $relay 2>/dev/null; cat /tmp/relay.log" EXIT
    sleep 2
    for dir in compat/v*; do
      echo "== $dir"
      (cd "$dir" && CARGO_TARGET_DIR=/target/compat-$(basename "$dir") cargo run --release --quiet -- http://127.0.0.1:3340 compat-token)
    done'
