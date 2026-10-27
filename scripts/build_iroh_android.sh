#!/usr/bin/env bash
# Rebuild computer.iroh:iroh-android 1.1.0 with 16 KB aligned native libraries (Google Play needs it) and publish the
# result as 1.1.0-16k into a local Maven folder that settings.gradle.kts picks up.
#
# Same iroh-ffi source as the published 1.1.0 (tag v1.1.0), plus the linker flag upstream added in #283. Only the
# four libiroh_ffi.so files differ from the Maven Central AAR, so the Kotlin side stays as it is.
# Needs: Rust (rustup, with cargo), cargo-ndk 3.5.4, the Android NDK (r27), curl, unzip, zip.
# Output: ~/.fileapex/maven/computer/iroh/iroh-android/1.1.0-16k/
set -euo pipefail

TAG=v1.1.0
VERSION=1.1.0
OUT_VERSION=1.1.0-16k
SRC="${FILEAPEX_IROH_SRC:-$HOME/iroh-ffi-build/iroh-ffi}"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.2.12479018}"
MAVEN="${FILEAPEX_MAVEN:-$HOME/.fileapex/maven}"
DEST="$MAVEN/computer/iroh/iroh-android/$OUT_VERSION"
CENTRAL="https://repo1.maven.org/maven2/computer/iroh/iroh-android/$VERSION"
READELF="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)"

[ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
command -v cargo >/dev/null || { echo "Rust is not installed (rustup)"; exit 1; }
command -v cargo-ndk >/dev/null || cargo install --version 3.5.4 cargo-ndk --locked
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$(dirname "$SRC")"
  git clone --depth 1 --branch "$TAG" https://github.com/n0-computer/iroh-ffi.git "$SRC"
fi
mkdir -p "$SRC/.cargo"
{
  echo "# 16 KB LOAD alignment for Android 15+ (upstream iroh-ffi #283)"
  for t in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
    printf '[target.%s]\nrustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]\n\n' "$t"
  done
} > "$SRC/.cargo/config.toml"

(cd "$SRC" && ANDROID_NDK_HOME="$NDK" cargo ndk -o ./android-jniLibs -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
  build --release --lib -p iroh-ffi)

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  lib="$SRC/android-jniLibs/$abi/libiroh_ffi.so"
  bad=$("$READELF" -lW "$lib" | awk '$1=="LOAD" && $NF != "0x4000" {print $NF}' | head -1)
  [ -z "$bad" ] || { echo "$abi is not 16 KB aligned ($bad)"; exit 1; }
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -sSfL -o "$work/orig.aar" "$CENTRAL/iroh-android-$VERSION.aar"
curl -sSfL -o "$work/orig.pom" "$CENTRAL/iroh-android-$VERSION.pom"
mkdir "$work/aar"
(cd "$work/aar" && unzip -q ../orig.aar)
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  cp "$SRC/android-jniLibs/$abi/libiroh_ffi.so" "$work/aar/jni/$abi/libiroh_ffi.so"
done
mkdir -p "$DEST"
(cd "$work/aar" && zip -q -r -X "$DEST/iroh-android-$OUT_VERSION.aar" .)
# Only the first <version> (the artifact itself) changes; the iroh dependency stays on $VERSION.
# JNA moves to $JNA_VERSION: 5.15.0 ships a libjnidispatch.so whose RELRO segment crashes 16 KB devices.
JNA_VERSION="${JNA_VERSION:-5.18.1}"
python3 - "$work/orig.pom" "$DEST/iroh-android-$OUT_VERSION.pom" "$VERSION" "$OUT_VERSION" "$JNA_VERSION" <<'PY'
import re
import sys
src, dst, old, new, jna = sys.argv[1:6]
s = open(src).read()
s = s.replace("<version>%s</version>" % old, "<version>%s</version>" % new, 1)
s = re.sub(r"(<artifactId>jna</artifactId>\s*<version>)[^<]+(</version>)", r"\g<1>%s\g<2>" % jna, s)
s = s.replace("<!-- do_not_remove: published-with-gradle-metadata -->", "")
open(dst, "w").write(s)
PY
echo "Published $DEST"
