#!/usr/bin/env bash
# Builds the browser client of a guest drop and writes it next to the page: docs/website/drop/wasm/.
# Needs: Rust with the wasm32-unknown-unknown target and the llvm-tools component, and wasm-bindgen-cli at the version in
# Cargo.lock (cargo install wasm-bindgen-cli --version <that version> --locked). Apple's ar cannot pack wasm objects, so ring's
# C code is archived with the llvm-ar that ships with rustup.
set -euo pipefail
cd "$(dirname "$0")"
[ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
AR="$(find "$HOME/.rustup/toolchains" -name llvm-ar | head -1)"
[ -n "$AR" ] || { echo "missing llvm-ar: rustup component add llvm-tools"; exit 1; }
export AR_wasm32_unknown_unknown="$AR"
cargo build --target wasm32-unknown-unknown --release
wasm-bindgen --target web --out-dir ../../docs/website/drop/wasm --out-name dropweb target/wasm32-unknown-unknown/release/dropweb.wasm
python3 -I licenses.py ../../docs/website/drop/licenses.txt
