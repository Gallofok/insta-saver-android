#!/usr/bin/env bash
# Compile QuickJS-NG CLI for Android arm64 and bundle in read-only native lib dir.
set -euo pipefail

: "${ANDROID_HOME:?Android SDK environment is required}"
NDK_VERSION="27.2.12479018"
NDK_HOME="${ANDROID_HOME}/ndk/${NDK_VERSION}"
if [[ ! -f "$NDK_HOME/build/cmake/android.toolchain.cmake" ]]; then
  echo "Missing NDK $NDK_VERSION. Install via sdkmanager." >&2
  exit 1
fi
workspace="$(mktemp -d)"
trap 'rm -rf "$workspace"' EXIT
git clone --quiet --depth 1 --branch v0.17.0 \
  https://github.com/quickjs-ng/quickjs.git "$workspace/source"
echo "Compiling QuickJS-NG v0.17.0 for Android arm64"
cmake -S "$workspace/source" -B "$workspace/out" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DQJS_ENABLE_INSTALL=OFF \
  -DQJS_BUILD_CLI_STATIC=OFF
cmake --build "$workspace/out" --target qjs_exe --parallel 2

binary="$(find "$workspace/out" -type f -name qjs -perm /u+x | head -n 1)"
if [[ -z "$binary" ]]; then
  echo "QuickJS binary was not produced" >&2
  exit 1
fi
# Android 10+ disallows executing files from writable app storage.
# An Android-NDK-built PIE executable in nativeLibraryDir is read-only.
dest="app/src/main/jniLibs/arm64-v8a/libqjs.so"
mkdir -p "$(dirname "$dest")"
cp "$binary" "$dest"
chmod 755 "$dest"
readelf -h "$dest" | grep -E 'Class:|Machine:|Type:'
readelf -h "$dest" | grep -q 'AArch64'
file "$dest"
echo "Bundled QuickJS CLI into $dest"
