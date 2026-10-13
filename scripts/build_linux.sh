#!/usr/bin/env bash
# Build the Linux .deb, .rpm and AppImage into current/ (Linux host only).
#
# The packages must be built with a self-contained JDK (Eclipse Temurin 21). A distro JDK links the
# system libjpeg/giflib/lcms/harfbuzz, which makes jpackage add distro-specific dependencies such as
# Ubuntu's libjpeg-turbo8 (not on Debian 13, Fedora or openSUSE). This script downloads Temurin 21
# into .build-jdk/ (no sudo) and builds with it.
#
# Override with FILEAPEX_JDK=/path/to/jdk to use your own self-contained JDK 21.
# Needs: curl, tar, Go (see linux/scripts/build_tsnet.sh), appimagetool on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "build_linux.sh builds the Linux packages and must run on Linux."
  exit 1
fi

case "$(uname -m)" in
  x86_64) ADOPTIUM_ARCH=x64 ;;
  aarch64|arm64) ADOPTIUM_ARCH=aarch64 ;;
  *) echo "Unsupported CPU $(uname -m)"; exit 1 ;;
esac

JDK="${FILEAPEX_JDK:-$ROOT/.build-jdk/temurin-21-linux-$ADOPTIUM_ARCH}"

if [[ ! -x "$JDK/bin/java" ]]; then
  echo "Downloading Temurin 21 ($ADOPTIUM_ARCH) to $JDK ..."
  mkdir -p "$JDK"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$ADOPTIUM_ARCH/jdk/hotspot/normal/eclipse" \
    | tar xz -C "$JDK" --strip-components=1
fi

export JAVA_HOME="$JDK"

# jlink output from an earlier build with another JDK is cached; start clean so it is rebuilt.
rm -rf composeApp/build/compose composeApp/build/linux-appimage

./gradlew shipLinuxPackages --no-daemon "-Porg.gradle.java.home=$JAVA_HOME" "$@"
