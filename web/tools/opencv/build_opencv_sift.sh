#!/usr/bin/env bash
# T312 — reproducible recipe for the vendored custom OpenCV.js WASM build used by
# the browser SIFT extractor. It is NOT run during normal CI/gates (the built
# artifacts are committed under web/public/opencv/); this documents exactly how
# they were produced and how to reproduce them with the pinned toolchain.
#
# It performs task-local downloads/builds only on the NLVM under the global
# /tmp/remanence-web.lock; no system/apt/cloud changes. Recorded provenance:
#
#   OpenCV source tag              4.12.0
#   OpenCV resolved commit         49486f61fb25722cbcf586b7f4320921d46fb38e
#   OpenCV source archive SHA-256  44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d
#   Emscripten SDK tag             4.0.10  (emscripten-releases pin 8103ffedfb0c42d231c6af6859a5a1a832260b43)
#   CMake                          3.31.6 (linux-x86_64 archive SHA-256 5a1133ff103c71eb5120e2cc3de922733e7d8a26a98ae716397e8676adb367bf)
#   whitelist                      tools/opencv/opencv_js_sift.config.py (official config + features2d SIFT + core dct)
#   compiler flags                 -s WASM=1 -s USE_PTHREADS=0 -s EXPORTED_FUNCTIONS="['_malloc','_free']"
#   build flags                    --build_wasm --disable_single_file --config <whitelist>
#   outputs                        public/opencv/opencv.js + public/opencv/opencv_js.wasm
set -euo pipefail

ROOT="${1:?usage: build_opencv_sift.sh <work-root>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src/opencv-4.12.0"
BUILD="$ROOT/build"

# --- fetch (official HTTPS release/GitHub URLs only) ---
mkdir -p "$ROOT/downloads" "$ROOT/src"
(
  cd "$ROOT/downloads"
  curl -fsSL -o cmake-3.31.6-SHA-256.txt https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-SHA-256.txt
  curl -fsSL -o cmake-3.31.6-linux-x86_64.tar.gz https://github.com/Kitware/CMake/releases/download/v3.31.6/cmake-3.31.6-linux-x86_64.tar.gz
  sha256sum -c cmake-3.31.6-SHA-256.txt --ignore-missing
  curl -fsSL -o opencv-4.12.0.tar.gz https://github.com/opencv/opencv/archive/refs/tags/4.12.0.tar.gz
  echo "44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d  opencv-4.12.0.tar.gz" | sha256sum -c -
)
EMSDK="$ROOT/downloads/emsdk"
[ -d "$EMSDK" ] || git clone --depth 1 --branch 4.0.10 https://github.com/emscripten-core/emsdk.git "$EMSDK"
# Install/activate the exact pin (never the `latest` alias) before sourcing env.
"$EMSDK/emsdk" install 4.0.10
"$EMSDK/emsdk" activate 4.0.10
tar -xzf "$ROOT/downloads/cmake-3.31.6-linux-x86_64.tar.gz" -C "$ROOT"
tar -xzf "$ROOT/downloads/opencv-4.12.0.tar.gz" -C "$ROOT/src"

# --- build-compat correction (explicit, one change; no algorithm/upstream drift) ---
# OpenCV 4.12.0's modules/js/CMakeLists.txt hardcodes `-s DEMANGLE_SUPPORT=1`,
# which emsdk 4.0.10 rejects ("No longer supported"). Remove just that token.
if grep -q " -s DEMANGLE_SUPPORT=1" "$SRC/modules/js/CMakeLists.txt"; then
  sed -i "s/ -s DEMANGLE_SUPPORT=1//" "$SRC/modules/js/CMakeLists.txt"
fi

# --- build ---
# shellcheck disable=SC1091
source "$EMSDK/emsdk_env.sh" >/dev/null 2>&1
# The CMake archive extracts to $ROOT/cmake-3.31.6-linux-x86_64/ (no extra
# `cmake/` component); put the pinned CMake on PATH before the system one.
export PATH="$ROOT/cmake-3.31.6-linux-x86_64/bin:$PATH"
# OPENCV_JS_WHITELIST is read by CMake at CONFIGURE time only. build_js.py sets it
# from --config; it must also be present for any later reconfigure (a bare
# `cmake --build` that triggers reconfigure without it silently regenerates the
# DEFAULT whitelist and drops SIFT/dct). Export it explicitly for the whole build.
export OPENCV_JS_WHITELIST="$HERE/opencv_js_sift.config.py"
mkdir -p "$BUILD"
cd "$SRC"
python3 platforms/js/build_js.py "$BUILD" \
  --build_wasm --disable_single_file \
  --config "$HERE/opencv_js_sift.config.py" \
  --cmake_option="-DBUILD_TESTS=OFF" \
  --cmake_option="-DBUILD_PERF_TESTS=OFF" \
  --cmake_option="-DBUILD_EXAMPLES=OFF" \
  --config_only
cd "$BUILD"
cmake --build . --target opencv.js --parallel 2

echo "artifacts in $BUILD/bin:"
ls -l "$BUILD/bin/opencv.js" "$BUILD/bin/opencv_js.wasm"
