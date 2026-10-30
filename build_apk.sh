#!/usr/bin/env bash
# PolyLingua Edge - one-command release build (Linux / macOS).
#
#   ./build_apk.sh            build release-output/PolyLingua_POCO_M4_Pro_Release.apk
#   ./build_apk.sh --install  ...and install it on a USB-connected phone with adb
#
# Requirements (checked below): JDK 17+, Android SDK (platform 34, build-tools 34), NDK r26b or
# newer (27.2.12479018 recommended), CMake 3.22.1, curl, tar, python3 (only for release signing).
# Signing: export KEYSTORE_PASSWORD to derive the same release key as CI; otherwise the APK is
# signed with the debug key (installs fine, but cannot update a CI-signed install).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"
APK_NAME="PolyLingua_POCO_M4_Pro_Release.apk"
INSTALL=0
[ "${1:-}" = "--install" ] && INSTALL=1

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }

step "Checking JDK"
if ! command -v java >/dev/null 2>&1; then red "java not found - install JDK 17 (e.g. Temurin 17)"; exit 1; fi
JAVA_MAJOR=$(java -version 2>&1 | awk -F\" '/version/ {split($2, v, "."); print (v[1] == "1") ? v[2] : v[1]}')
if [ "${JAVA_MAJOR:-0}" -lt 17 ]; then red "JDK $JAVA_MAJOR found; JDK 17 or newer is required"; exit 1; fi
green "JDK $JAVA_MAJOR"

step "Locating the Android SDK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then SDK="$(sed -n 's/^sdk.dir=//p' local.properties | sed 's/\\:/:/g;s/\\\\/\\/g')"; fi
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk"; do [ -d "$c" ] && SDK="$c" && break; done
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then red "Android SDK not found. Set ANDROID_HOME or install Android Studio."; exit 1; fi
export ANDROID_HOME="$SDK"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
green "SDK: $SDK"

SDKM=""
for c in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK/tools/bin/sdkmanager"; do [ -x "$c" ] && SDKM="$c" && break; done

need_pkg() {
  local path="$1" pkg="$2"
  if [ ! -d "$SDK/$path" ]; then
    if [ -n "$SDKM" ]; then
      echo "Installing $pkg ..."
      yes | "$SDKM" --licenses >/dev/null 2>&1 || true
      "$SDKM" --install "$pkg"
    else
      red "Missing $pkg and no sdkmanager available. Install it from Android Studio > SDK Manager."
      exit 1
    fi
  fi
}
need_pkg "platforms/android-34" "platforms;android-34"
need_pkg "build-tools/34.0.0" "build-tools;34.0.0"
need_pkg "cmake/3.22.1" "cmake;3.22.1"

step "Selecting the NDK (r26b or newer)"
NDK_VERSION=""
if [ -d "$SDK/ndk" ]; then
  NDK_VERSION="$(ls "$SDK/ndk" | sort -V | awk -F. '$1 >= 26' | tail -n 1)"
fi
if [ -z "$NDK_VERSION" ]; then
  need_pkg "ndk/27.2.12479018" "ndk;27.2.12479018"
  NDK_VERSION="27.2.12479018"
fi
green "NDK $NDK_VERSION"

step "Fetching native dependencies (sherpa-onnx, ONNX Runtime header, llama.cpp)"
./scripts/fetch_native_deps.sh

step "Preparing signing"
if [ -n "${KEYSTORE_PASSWORD:-}" ]; then
  if ! python3 -c "import cryptography" >/dev/null 2>&1; then
    python3 -m pip install --user --quiet "cryptography>=42" || python3 -m pip install --break-system-packages --quiet "cryptography>=42"
  fi
  python3 scripts/derive_release_keystore.py keystore/polylingua-release.p12
  green "Release key derived (same certificate as CI builds)"
else
  echo "KEYSTORE_PASSWORD not set - using the debug signing key."
fi

step "Building (Gradle assembleRelease + unit tests)"
chmod +x gradlew
./gradlew --no-daemon -Ppolylingua.ndkVersion="$NDK_VERSION" :app:assembleRelease :app:testReleaseUnitTest

step "Collecting the APK"
mkdir -p release-output
APK="$(ls app/build/outputs/apk/release/*.apk | head -n 1)"
cp "$APK" "release-output/$APK_NAME"
BT="$SDK/build-tools/34.0.0"
if [ -x "$BT/apksigner" ]; then "$BT/apksigner" verify --print-certs "release-output/$APK_NAME" | head -n 4; fi
if command -v sha256sum >/dev/null 2>&1; then sha256sum "release-output/$APK_NAME"; else shasum -a 256 "release-output/$APK_NAME"; fi
green "Done: $ROOT/release-output/$APK_NAME"

if [ "$INSTALL" = "1" ]; then
  step "Installing with adb"
  ADB="$SDK/platform-tools/adb"
  command -v adb >/dev/null 2>&1 && ADB="adb"
  "$ADB" install -r "release-output/$APK_NAME"
fi
