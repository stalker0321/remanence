# M0 cumulative review 002

Reviewed at: 2026-08-23T05:18:45Z
Reviewed range: 062213baebf4a312e1f0ceebf4a69b3ca045e85f..58c988daa7a52eac6f73616052acec38d31aeb5c
STATUS: PASS

## Critical

- None unresolved.

## Architecture deviations

- None unresolved.
- OkHttp is pinned to 5.4.0 because 5.5 requires minCompileSdk 37 while the approved baseline is API 36.
- HTTP cleartext is debug-only and ApiBaseUrl restricts it to exact loopback; the production/main manifest is fail-closed.

## Security

- No capsule plaintext, private-key storage, custom cryptography, backend CV, gallery, feed, or social regressions were found.
- BlobStore uses atomic create-if-absent, idempotent replay, symlink rejection, and generic errors.
- Unexpected read OSError classification was the sole issue and was corrected by 58c988d.

## Recognition

- :core:recognition remains a local empty boundary as required for M0. No CV has leaked to the backend.

## Build/tests

- JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ANDROID_SDK_ROOT=/usr/lib/android-sdk ./gradlew clean testDebugUnitTest assembleDebug — PASS; 146 actionable tasks; APK 12,287,755 bytes.
- uv lock --check && uv sync --locked && uv run --locked pytest -q -W error — PASS; 80 passed, 2 skipped after correction.
- Compose API and Postgres healthy; migrate exited 0; GET /healthz exact {"status":"ok"}; Alembic 0001_m0_baseline; API uid/gid 10001; blob volume write at mode 0600.
- git diff --check — PASS.

## Known limitation

- No physical Android device is attached, so APK installation, camera, CV, and physical M1/M2 evidence remain unverified and may not be claimed.

## Recommended next actions

1. M0-24 verifier.
2. M0-25 docs.
3. M0-26 evidence/gate.

Reviewed commit: 58c988daa7a52eac6f73616052acec38d31aeb5c
