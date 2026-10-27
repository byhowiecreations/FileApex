#!/usr/bin/env bash
# Runs on the Ubuntu build VM: builds the .deb, .rpm and AppImage for one branch and keeps only that version.
set -euo pipefail
BRANCH="$1"
export PATH="$HOME/toolchain/go/bin:/usr/local/bin:$PATH"
export FILEAPEX_JDK="$HOME/toolchain/temurin21"
cd "$HOME/FileApex"
git fetch origin
git checkout -B "$BRANCH" "origin/$BRANCH"
git log --oneline -1
rm -rf current
bash scripts/build_linux.sh
