#!/bin/bash
set -euo pipefail

# Silicon FileApex.app and DMG only. Android and the Intel DMG are separate commands.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "========================================================"
echo " Building FileApex for Apple Silicon (app + DMG)"
echo "========================================================"

if [ -f "signing.local.env" ]; then
    echo "Sourcing signing.local.env..."
    # shellcheck disable=SC1091
    source signing.local.env
fi
unset JAVA_HOME

./gradlew packageSiliconDmg

echo "=== Apple Silicon Build Complete ==="
