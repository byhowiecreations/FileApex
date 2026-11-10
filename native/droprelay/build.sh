#!/bin/sh
# Builds the drop relay for the Oracle VM (Linux aarch64, static musl) inside a Docker container and runs the tests there.
# Needs Docker Desktop running. Output: ./out/droprelay-linux-arm64 (not tracked).
set -e
cd "$(dirname "$0")"
mkdir -p out
docker run --rm --platform linux/arm64 \
  -v "$PWD:/src:ro" -v droprelay-target:/target -v droprelay-cargo:/usr/local/cargo/registry -v "$PWD/out:/out" \
  -e CARGO_TARGET_DIR=/target -w /src rust:1.99-alpine sh -c '
    set -e
    apk add --no-cache musl-dev >/dev/null
    cargo test --release --locked
    cargo build --release --locked
    cp /target/release/droprelay /out/droprelay-linux-arm64'
shasum -a 256 out/droprelay-linux-arm64
