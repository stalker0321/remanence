# Native Android feasibility: SIFT / RootSIFT / KNN / USAC-MAGSAC on OpenCV 4.10.0

Status: compile + artifact evidence COMPLETE; on-device runtime BLOCKED (no
device/emulator in this environment — see §7). Nothing here enters product
code, thresholds, ORB classes, manifests, permissions, or release
dependencies. Slice 0 (`README.md`, oracle scope and license note) still
governs: this probe does not select SIFT and changes no ADR.

## 1. Resolved artifact (offline inspection, no downloads)

Source: Gradle cache
`modules-2/files-2.1/org.opencv/opencv/4.10.0/.../opencv-4.10.0.aar`
(the exact artifact `android/app/build.gradle.kts` declares).

| Property | Value |
| --- | --- |
| Version | 4.10.0 (AAR entries dated 2024-06-02) |
| License metadata | POM declares `Apache-2.0`; **no LICENSE/NOTICE file inside the AAR** |
| Packaged ABIs (`jni/`) | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — both required ABIs present |
| `libopencv_java4.so` size | arm64-v8a 20,897,272 B (~19.9 MiB); x86_64 55,024,304 B (~52.5 MiB); x86 40,783,704 B; armeabi-v7a 14,955,212 B |
| Companion | `libc++_shared.so` per ABI (~0.6–1.0 MiB) |
| AAR `minSdkVersion` | 21 — compatible with project `minSdk = 26` |
| Project ABI filters | none — all four ABIs ship in the APK (verified §5) |

## 2. Java API bindings (verified via `javap` on AAR `classes.jar`)

- `org.opencv.features2d.SIFT` with `create()` … `create(int,int,double,double,double,int,boolean)` overloads — main `features2d` package, no contrib needed. JNI entry points present in arm64 `.so` (e.g. `Java_org_opencv_features2d_SIFT_create_10/11/12`).
- `org.opencv.calib3d.Calib3d.USAC_MAGSAC = 38` (sibling constants `USAC_DEFAULT` 32 … `USAC_ACCURATE` 36, `USAC_PROSAC` 37 all present). `findHomography` overloads up to the 7-arg form `(src, dst, method, ransacReprojThreshold, mask, maxIters, confidence)` — the exact signature the probe uses. JNI symbols `...Calib3d_findHomography_10/11/12` present; native `cv::findHomography(…UsacParams)` and `UsacParams` constructor symbols plus `Magsac` strings present in the arm64 `.so` (MAGSAC is an enum value through the USAC framework, so no standalone `MAGSAC` native symbol is expected). NEEDED entries are system libraries only (`libdl`, `libm`, `liblog`, `libjnigraphics`, `libz`, …).

## 3. Probe layout (androidTest-only, cannot enter the release graph)

- `android/app/src/androidTest/kotlin/dev/hryshyn/remanence/probe/RootSiftProbe.kt` — probe-local RootSIFT (L1 + sqrt, epsilon guard, zero/empty rows yield zeros; fail closed). No production helper touched or depended on.
- `android/app/src/androidTest/kotlin/dev/hryshyn/remanence/probe/SiftNativeCapabilityProbeTest.kt` — 10 instrumented tests: finite 128-dim CV_32F descriptors; blank-image empty-descriptor fail-closed; RootSIFT L1-normalized, unit-L2-energy/non-negative/finite rows plus a known-vector case; empty and wrong-type input contracts; KNN exact self-matches (`trainIdx`, distance 0); MAGSAC homography recovering a known (+17,+11) translation with 16/16 inliers; <4-point degenerate homography returns empty; translated fixture stays matchable. Fixtures are synthetic and deterministic — **capability-only, never accuracy evidence**.
- `android/app/build.gradle.kts`: two added lines, `androidTestImplementation` scope only (`androidx.test.ext:junit` via catalog, `androidx.test:runner:1.5.0` literal — both resolved from the offline cache). No manifest, permission, release-dependency, or production-source change. Probe strings verified **absent (0 refs)** from `app-debug.apk` DEX.

## 4. Compile evidence (single serialized Gradle process per run)

Flags every run (from `android/`):
`JAVA_HOME=java-17`, `--offline --no-daemon --max-workers=1
-Dorg.gradle.jvmargs=-Xmx1024m -Pkotlin.compiler.execution.strategy=in-process`,
`ANDROID_HOME=/usr/lib/android-sdk`. No network.

| Run | Command | Result |
| --- | --- | --- |
| 1 | `:app:assembleDebugAndroidTest` | FAIL at `compileDebugAndroidTestKotlin`: 2 probe-code type errors (`MatOfDMatch.toArray` shape, `Imgproc.circle` radius `Double` vs `Int`) — probe code only, fixed without touching product code |
| 2 | `:app:compileDebugAndroidTestKotlin` (error grep) | surfaced the 2 errors above |
| 3 | `:app:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** (116 tasks: 41 executed) |
| 4 | `:app:assembleDebug` | **BUILD SUCCESSFUL** (101 tasks: 17 executed) |

## 5. APK ABI evidence

`app-debug.apk` `lib/` ships all four ABIs (no `abiFilters` in project):
arm64-v8a and x86_64 each carry `libopencv_java4.so` (+ `libc++_shared.so`,
CameraX JNIs, graphics-path). `app-debug-androidTest.apk` contains the probe
in DEX (`SiftNativeCapabilityProbe` strings present) and no native libs of
its own — it executes inside the app-under-test process, which owns the
OpenCV `.so`.

## 6. Runtime status: BLOCKED (declared, not faked)

`/usr/lib/android-sdk/platform-tools/adb devices` → empty list; no emulator
binary, no `~/Android` tree. JVM/API compile (§4) and AAR/APK inspection
(§1–2, §5) are the complete evidence in this slice. The 10 tests have never
executed; no runtime numbers are claimed.

## 7. Next step: physical arm64 execution

1. Attach an arm64-v8a device with USB debugging (or install an x86_64
   emulator system image — the x86_64 `.so` ships, so emulation is a valid
   fallback for capability purposes, not for performance claims).
2. From `android/`, same serialized flags, run
   `:app:connectedDebugAndroidTest` (optionally filtered:
   `--tests "dev.hryshyn.remanence.probe.SiftNativeCapabilityProbeTest"`).
3. Pass criteria: 10/10 green on-device; any red is a capability finding, not a
   product regression (probe is isolated). Record `adb shell getprop
   ro.product.cpu.abi` alongside results.
