#!/bin/bash
set -e

# Change to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "========================================================"
echo " Starting Complete Release Build (Silicon + Intel + Android)"
echo "========================================================"

# Step 1: Build Apple Silicon DMG + App + Android Release APK
bash "$SCRIPT_DIR/build_silicon.sh"

echo ""
# Step 2: Build Intel x86_64 DMG
bash "$SCRIPT_DIR/build_intel.sh"

echo ""
# Step 3: Firefox extension (once, full build only)
bash "$SCRIPT_DIR/build_firefox_extension.sh"

echo ""
echo "========================================================"
echo " Verifying Output Artifacts in current/"
echo "========================================================"

ls -lh "$PROJECT_ROOT/current"

echo ""
echo "========================================================"
echo " Complete Build Finished Successfully!"
echo "========================================================"
