# T312 — vendored custom OpenCV.js WASM provenance

The browser SIFT extractor (`src/scan/siftExtractor.ts`, `src/scan/opencvRuntime.ts`)
runs the **real** OpenCV SIFT detector and `Core.dct` from a custom build of the
official OpenCV source. The stock OpenCV.js 4.12.0 JS whitelist
(`platforms/js/opencv_js.config.py`) does **not** export `features2d.SIFT` and the
`core` list omits `dct`, so this task builds its own WASM with a two-line
whitelist addition.

## Pinned inputs (recorded, not independently signed)

| Input | Value |
| --- | --- |
| OpenCV source tag | `4.12.0` (`https://github.com/opencv/opencv/archive/refs/tags/4.12.0.tar.gz`) |
| OpenCV resolved commit | `49486f61fb25722cbcf586b7f4320921d46fb38e` |
| OpenCV source archive SHA-256 | `44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d` |
| Emscripten SDK (`emsdk`) tag | `4.0.10` (`https://github.com/emscripten-core/emsdk`) |
| emscripten-releases pin for 4.0.10 | `8103ffedfb0c42d231c6af6859a5a1a832260b43` |
| CMake | `3.31.6` linux-x86_64 (SHA-256 `5a1133ff103c71eb5120e2cc3de922733e7d8a26a98ae716397e8676adb367bf`) |
| Whitelist file | `tools/opencv/opencv_js_sift.config.py` |
| License | Apache-2.0 (`public/opencv/LICENSE.txt`, source `LICENSE` SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`) |

## Whitelist delta vs. the official 4.12.0 config

Only two additions, both take-off-the-shelf symbols:

- `core['']` gains `'dct'` (before `'dft'`) for the 8x8 coarse-hash DCT.
- `features2d` gains `'SIFT': ['create', 'detect', 'compute', 'detectAndCompute',
  'descriptorSize', 'descriptorType', 'defaultNorm', 'empty', 'getDefaultName']`
  (SIFT is a normal C++ `features2d` class in 4.12.0, not nonfree/contrib).

No upstream source/algorithm modification; no fork drift. The build uses the
default module set of `build_js.py` with tests/perf/examples disabled.

## Build

`tools/opencv/build_opencv_sift.sh <work-root>` performs the full fetch + build on
the NLVM (task-local, under the global build lock). Flags: `--build_wasm
--disable_single_file --config <whitelist>`, Emscripten flags
`-s WASM=1 -s USE_PTHREADS=0` (single-thread, no SIMD, so no cross-origin
isolation requirement). Outputs: separate `opencv.js` + `opencv_js.wasm`.

Two explicit, recorded build-compatibility corrections were required (no upstream
algorithm/source modification, no fork drift):

1. **`-s DEMANGLE_SUPPORT=1` removed.** OpenCV 4.12.0's
   `modules/js/CMakeLists.txt` hardcodes it; emsdk 4.0.10 rejects it
   (`em++: error: invalid command line setting ... No longer supported`). The
   first link failed with exactly that error (`logs/build.log.fail1-demangle` on
   the build host, preserved). The correction removes only that token.
2. **`OPENCV_JS_WHITELIST` must be exported for the whole build.** CMake reads it
   at configure time (`modules/js/generator/CMakeLists.txt`). If a later
   `cmake --build` triggers a reconfigure *without* it (e.g. after editing a
   `CMakeLists.txt`), CMake silently regenerates the **default** whitelist and the
   produced wasm loses SIFT/dct. The recipe exports it explicitly.

## Runtime loading contract (verified via the pinned Chromium gate)

- The build sets `-s MODULARIZE=1 -s EXPORT_NAME="'cv'"`, so the UMD assigns
  `window.cv` to the module **factory result, a `Promise<Module>`** — the loader
  awaits it rather than using `onRuntimeInitialized`.
- OpenCV's JS binding encodes per-module whitelists; the wasm only exposes the
  whitelisted `core`/`imgproc`/`features2d` symbols. `SIFT` and `dct` are present
  in the compiled module (confirmed at runtime).
- `SIFT::create` factories are exposed by `embindgen` as **JS constructors**, so
  the detector is built with `new cv.SIFT(nfeatures, octaveLayers,
  contrastThreshold, edgeThreshold, sigma)` (same as `new cv.ORB()`), not a static
  `SIFT.create`.
- `cv::KeyPoint` is an embind **value_object** (plain JS object, no `.delete()`);
  `KeyPointVector.get` returns snapshots that stay valid after the vector is
  released and can be passed back to `push_back`.

## Outputs

Committed runtime assets live at `public/opencv/` (same-origin fixed path
`/opencv/`), with `MANIFEST.sha256` listing their hashes and the upstream
provenance summary. The `.js` glue and `.wasm` are the only runtime artifacts;
toolchains, archives and build trees are not committed.
