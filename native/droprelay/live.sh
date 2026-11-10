#!/bin/sh
# Runs the live probe against a running relay from a container (nothing is built or run on the Mac).
# usage: DROPRELAY_URL=... DROPRELAY_TOKEN=... [DROPRELAY_REGISTER_URL=...] ./live.sh
set -e
cd "$(dirname "$0")"
docker run --rm --platform linux/arm64 -v "$PWD:/src:ro" -v droprelay-target:/target -v droprelay-cargo:/usr/local/cargo/registry \
  -e CARGO_TARGET_DIR=/target -e DROPRELAY_URL -e DROPRELAY_TOKEN -e DROPRELAY_REGISTER_URL -w /src rust:1.99-alpine sh -c '
    apk add --no-cache musl-dev >/dev/null
    cargo test --release --locked --test live_probe -- --nocapture'
