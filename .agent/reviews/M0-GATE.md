# M0 foundation gate

Reviewed at: 2026-08-23T05:32:44Z
Reviewed range: e694bcbba473552facff2dfdcc1a28fe896a5c4d..f62a71de439982a92b37cfba648ef6c5d6ff680c
STATUS: PASS

## Android

| Acceptance item | Result |
| --- | --- |
| `./gradlew --version` uses documented toolchain: Gradle 9.4.1, launcher/daemon JDK 17.0.19 | PASS |
| Exactly the included leaf modules `:app`, `:core:model`, `:core:data`, `:core:crypto`, `:core:recognition` (`:core` is the required Gradle parent/container, not an extra product module) | PASS |
| Canonical `scripts/verify-m0.sh` from `/tmp` exit 0; `clean testDebugUnitTest assembleDebug`: 146 actionable tasks; APK 12,287,755 bytes | PASS |
| aapt evidence: package `app.postmark.memory`, minSdk 26, target/compileSdk 36 | PASS |
| `adb install -r` / reverse / launch commands documented and correct | PASS (docs) |
| Physical `adb install` | UNVERIFIED (no physical device) |
| Compose Home semantics tests and real API-stack `HealthRepository` integration prove a configurable typed health path | PASS |
| Physical app launch on device | UNVERIFIED (unclaimed) |

## Backend

| Acceptance item | Result |
| --- | --- |
| Locked uv clean container build and host verifier; 82 tests with DB; warnings-as-errors | PASS |
| `docker compose config` with only the local-development password; no production secret | PASS |
| Exact `docker compose up -d --build` | PASS |
| postgres/api healthy; migrate Exited(0) | PASS |
| Empty baseline upgrade and idempotent second-upgrade integration coverage; current DB `0001_m0_baseline` | PASS |
| Exact `GET /healthz` body `{"status":"ok"}` | PASS |
| BlobStore traversal/symlink rejection, atomic put/get/hash, idempotent replay, conflict, and generic error coverage | PASS |

## Repository

| Acceptance item | Result |
| --- | --- |
| Exact docs and verifier/ADB path present | PASS |
| Tracked-artifact audit: no SDK/build/`.env`/DB/blob/APK outputs | PASS |
| Prohibited product/security surface audit: no app/server gallery, feed, social, plaintext, or private-key implementation | PASS |
| `git diff --check` and working tree clean at the reviewed implementation commit | PASS |

## Known limitation

- No physical Android phone is attached, so APK installation and all CameraX/CV/M1/M2 physical evidence remain unverified.
- This does not waive M1/M2 gates.

## Verdict

M0 reproducible foundation passes. This is a testable engineering sample, not mechanism/product proof.

## Recommended next actions

1. M1-P01 only.

Reviewed commit: f62a71de439982a92b37cfba648ef6c5d6ff680c
