#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

if [ -f "signing.local.env" ]; then
    source signing.local.env
fi

echo "Building Firefox extension XPI..."
./gradlew shipFirefoxExtension

echo "=== Firefox extension shipped to current/ ==="
ls -lh "$PROJECT_ROOT/current/"*.xpi
