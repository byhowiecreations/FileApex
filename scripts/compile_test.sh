#!/bin/bash
set -e

# Quick test build: GitHub Android APK + Mac app (no DMG, no Play APK, no AAB, no Intel).
# Output lands in current/ with the same names the full ship uses.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

if [ -f "signing.local.env" ]; then
    source signing.local.env
fi

if [ -d "$PROJECT_ROOT/.build-jdk/jdk-21.0.11+10/Contents/Home" ]; then
    ARM64_JDK="$PROJECT_ROOT/.build-jdk/jdk-21.0.11+10/Contents/Home"
elif [ -d "$HOME/.jdks/jdk-21.0.11+10/Contents/Home" ]; then
    ARM64_JDK="$HOME/.jdks/jdk-21.0.11+10/Contents/Home"
else
    echo "Error: Apple Silicon ARM64 JDK not found"
    exit 1
fi
export JAVA_HOME="$ARM64_JDK"
export PATH="$JAVA_HOME/bin:$PATH"

VERSION=$(grep '^name=' version.md | cut -d'=' -f2 | tr -d ' \n\r')
APP_DIR="composeApp/build/compose/binaries/main/app"
APK_DIR="composeApp/build/outputs/apk/github/release"

# A stale app bundle would be embedded into again, so start from a clean one.
rm -rf "$APP_DIR" composeApp/build/compose/tmp/main/runtime

./gradlew :composeApp:assembleGithubRelease embedMacExtensions

APK=$(find "$APK_DIR" -name "*.apk" ! -name "*unsigned*" | head -n 1)
if [ -z "$APK" ] || [ ! -d "$APP_DIR/FileApex.app" ]; then
    echo "Error: expected APK in $APK_DIR and FileApex.app in $APP_DIR"
    exit 1
fi

mkdir -p current
rm -rf "current/FileApex.app" "current/FileApex-v${VERSION}.apk"
mv "$APK" "current/FileApex-v${VERSION}.apk"
mv "$APP_DIR/FileApex.app" "current/FileApex.app"

echo "Moved current/FileApex-v${VERSION}.apk and current/FileApex.app"
