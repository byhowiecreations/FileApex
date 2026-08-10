#!/bin/bash
set -e

# Change to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "========================================================"
echo " Building FileApex for Apple Silicon (ARM64) & Android"
echo "========================================================"

# Source signing env if present
if [ -f "signing.local.env" ]; then
    echo "Sourcing signing.local.env..."
    source signing.local.env
fi

# Locate ARM64 JDK 21
ARM64_JDK="$HOME/.jdks/jdk-21.0.11+10/Contents/Home"
if [ ! -d "$ARM64_JDK" ]; then
    echo "Error: Apple Silicon ARM64 JDK not found at $ARM64_JDK"
    exit 1
fi

export JAVA_HOME="$ARM64_JDK"
export PATH="$JAVA_HOME/bin:$PATH"

echo "Using Java: $(java -version 2>&1 | head -n 1) ($JAVA_HOME)"

# Clean staging directories to force fresh ARM64 runtime image generation
rm -rf composeApp/build/compose/binaries/main/app
rm -rf composeApp/build/compose/tmp/main/runtime

# Run Gradle build for Android Release & macOS Silicon DMG / App
./gradlew assembleRelease packageDmg fixDmgVolumeIcon copyReleaseBuilds

echo "=== Apple Silicon & Android Build Complete ==="
