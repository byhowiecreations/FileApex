#!/bin/bash
set -e

# Change to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "========================================================"
echo " Building FileApex for Intel (x86_64) under Rosetta"
echo "========================================================"

# Source signing env if present
if [ -f "signing.local.env" ]; then
    echo "Sourcing signing.local.env..."
    source signing.local.env
fi

# Locate x86_64 JDK 21
X64_JDK="$HOME/.jdks/jdk-21-x64/Contents/Home"
if [ ! -d "$X64_JDK" ]; then
    echo "Error: Intel x86_64 JDK not found at $X64_JDK"
    exit 1
fi

# Clean staging directories to force fresh x86_64 runtime image generation
rm -rf composeApp/build/compose/binaries/main/app
rm -rf composeApp/build/compose/tmp/main/runtime

# Run under Rosetta arch -x86_64
arch -x86_64 bash -c "export JAVA_HOME='$X64_JDK' && export PATH=\"\$JAVA_HOME/bin:\$PATH\" && ./gradlew --no-daemon packageDmg fixDmgVolumeIcon -x assembleRelease -x verifyReleaseApkSigned -x verifyReleaseSigning"

# Move generated DMG to current/
VERSION=$(grep '^name=' version.md 2>/dev/null | cut -d'=' -f2 | tr -d ' \n\r' || echo "0.6.20a")
DMG_FILE=$(find composeApp/build/compose/binaries/main/dmg -name "*.dmg" | head -n 1)

if [ -n "$DMG_FILE" ]; then
    mkdir -p current
    cp "$DMG_FILE" "current/FileApex-v${VERSION}-Intel.dmg"
    echo "Moved Intel DMG -> current/FileApex-v${VERSION}-Intel.dmg"
else
    echo "Error: Intel DMG not found in composeApp/build/compose/binaries/main/dmg"
    exit 1
fi

echo "=== Intel Build Complete ==="
