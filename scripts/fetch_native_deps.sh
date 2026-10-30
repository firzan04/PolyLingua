#!/usr/bin/env bash
# Fetches the native dependencies needed by app/src/main/cpp/CMakeLists.txt:
#   - sherpa-onnx prebuilt Android libraries (C API + ONNX Runtime, arm64-v8a only)
#   - sherpa-onnx C API header and ONNX Runtime C API header
#   - llama.cpp sources at a pinned release tag
# Idempotent: re-running only downloads what is missing. Used by build_apk.sh and CI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHERPA_VERSION="${SHERPA_VERSION:-1.13.8}"
LLAMA_TAG="${LLAMA_TAG:-b12000}"
ORT_HEADER_TAG="${ORT_HEADER_TAG:-v1.22.0}"

THIRD_PARTY="$ROOT/app/src/main/cpp/third_party"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
CACHE="$ROOT/.deps-cache"
mkdir -p "$THIRD_PARTY/sherpa-onnx" "$THIRD_PARTY/onnxruntime" "$JNI_DIR" "$CACHE"

download() {
  local url="$1" out="$2"
  if [ -s "$out" ]; then
    echo "  cached  $(basename "$out")"
    return 0
  fi
  echo "  fetch   $url"
  curl -fL --retry 5 --retry-delay 3 --connect-timeout 30 -o "$out.part" "$url"
  mv "$out.part" "$out"
}

echo "[1/3] sherpa-onnx v${SHERPA_VERSION} prebuilt Android libraries"
TARBALL="$CACHE/sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"
download "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2" "$TARBALL"
if [ ! -s "$JNI_DIR/libsherpa-onnx-c-api.so" ] || [ ! -s "$JNI_DIR/libonnxruntime.so" ]; then
  tar -xjf "$TARBALL" -C "$CACHE" ./jniLibs/arm64-v8a/libsherpa-onnx-c-api.so ./jniLibs/arm64-v8a/libonnxruntime.so
  cp "$CACHE/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so" "$CACHE/jniLibs/arm64-v8a/libonnxruntime.so" "$JNI_DIR/"
fi

echo "[2/3] C API headers"
download "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v${SHERPA_VERSION}/sherpa-onnx/c-api/c-api.h" \
  "$THIRD_PARTY/sherpa-onnx/c-api.h"
download "https://raw.githubusercontent.com/microsoft/onnxruntime/${ORT_HEADER_TAG}/include/onnxruntime/core/session/onnxruntime_c_api.h" \
  "$THIRD_PARTY/onnxruntime/onnxruntime_c_api.h"

echo "[3/3] llama.cpp ${LLAMA_TAG}"
if [ ! -f "$THIRD_PARTY/llama.cpp/CMakeLists.txt" ]; then
  rm -rf "$THIRD_PARTY/llama.cpp"
  git -c advice.detachedHead=false clone --depth 1 --branch "$LLAMA_TAG" \
    https://github.com/ggml-org/llama.cpp "$THIRD_PARTY/llama.cpp"
else
  echo "  cached  llama.cpp"
fi

echo "Native dependencies ready:"
ls -la "$JNI_DIR"
