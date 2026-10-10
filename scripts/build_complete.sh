#!/bin/bash
set -euo pipefail

# GitHub APK, Play APK + AAB, Silicon app/DMG, Intel DMG, Firefox XPI.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "========================================================"
echo " Starting Complete Release Build"
echo "========================================================"

if [ -f "signing.local.env" ]; then
    echo "Sourcing signing.local.env..."
    # shellcheck disable=SC1091
    source signing.local.env
fi
unset JAVA_HOME

# shipAndroidBuilds + packageBothDmg + Firefox XPI
./gradlew copyCompleteBuilds

echo ""
echo "========================================================"
echo " Verifying Output Artifacts in current/"
echo "========================================================"

ls -lh "$PROJECT_ROOT/current"

echo ""
echo "========================================================"
echo " Complete Build Finished Successfully!"
echo "========================================================"
