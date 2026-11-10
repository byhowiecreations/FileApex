#!/bin/sh
# Builds the browser client of a guest drop inside a Linux container and writes it next to the page (docs/website/drop/wasm/).
# Nothing is compiled or run on the Mac, so macOS raises no "could not verify" prompts. Needs Docker Desktop running.
# Runs the crate's tests first. The licence list is made afterwards by licenses.py (a Python script, no new programs).
set -e
cd "$(dirname "$0")"
mkdir -p ../../docs/website/drop/wasm
docker run --rm --platform linux/arm64 \
  -v "$PWD:/src:ro" -v dropweb-target:/target -v dropweb-cargo:/usr/local/cargo/registry -v dropweb-tools:/tools \
  -v "$PWD/../../docs/website/drop/wasm:/out" -e CARGO_TARGET_DIR=/target -w /src rust:1.99-bookworm sh -c '
    set -e
    apt-get update -qq >/dev/null && apt-get install -y -qq clang lld llvm >/dev/null
    rustup target add wasm32-unknown-unknown >/dev/null 2>&1
    [ -x /tools/bin/wasm-bindgen ] || cargo install wasm-bindgen-cli --version 0.2.130 --locked --root /tools
    cargo test --lib --locked
    export CC_wasm32_unknown_unknown=clang AR_wasm32_unknown_unknown=llvm-ar
    cargo build --target wasm32-unknown-unknown --release --locked
    /tools/bin/wasm-bindgen --target web --out-dir /out --out-name dropweb /target/wasm32-unknown-unknown/release/dropweb.wasm'
python3 -I licenses.py ../../docs/website/drop/licenses.txt
ls -la ../../docs/website/drop/wasm
