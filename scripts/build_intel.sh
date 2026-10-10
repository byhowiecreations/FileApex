#!/bin/bash
set -euo pipefail

# Intel DMG only. Silicon packaging is packageSiliconDmg / build_silicon.sh.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "========================================================"
echo " Building FileApex Intel DMG"
echo "========================================================"

if [ -f "signing.local.env" ]; then
    echo "Sourcing signing.local.env..."
    # shellcheck disable=SC1091
    source signing.local.env
fi
unset JAVA_HOME

./gradlew packageIntelDmg

echo "=== Intel Build Complete ==="
