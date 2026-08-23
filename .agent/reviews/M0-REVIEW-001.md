# M0 cumulative review 001

Reviewed at: 2026-08-23T04:14:44Z
Reviewed range: b783bf19adacd6998612a4579d77871728db9b00..062213baebf4a312e1f0ceebf4a69b3ca045e85f
STATUS: PASS

## Critical

- None unresolved.

## Architecture deviations

- None unresolved.
- Compose BOM was corrected from 2026.08.00 to 2026.06.01 because the August release requires compileSdk 37 while the approved baseline is API 36.
- The Home semantics test intentionally uses Robolectric API 35 under JDK 17; production compileSdk and targetSdk remain 36.
- Postmark PostgreSQL uses host port 55432 because 5432 belongs to an unrelated VPS service; the container port remains 5432.

## Security

- No capsule plaintext, private-key storage, custom cryptography, backend CV, gallery, feed, or social surface exists in M0.
- Server settings are fail-closed for DEV/PROD and wrap database URLs in SecretStr.
- PostgreSQL is bound immutably to 127.0.0.1; only the numeric host port and explicitly development-only password are overridable.
- Compose interpolation and FastAPI TestClient deprecation issues found during review were corrected before PASS.

## Recognition

- :core:recognition is an empty tested boundary as required for M0. No CV has leaked to the backend.

## Build/tests

- JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ANDROID_SDK_ROOT=/usr/lib/android-sdk ./gradlew clean testDebugUnitTest assembleDebug — PASS; 5 tests; APK produced.
- uv lock --check && uv sync --locked && uv run --locked pytest -q -W error — PASS; 10 tests; 0 warnings.
- sudo docker compose config — PASS.
- PostgreSQL 18.6 health — healthy; SELECT 1 — PASS.
- git diff --check for the reviewed range — PASS.

## Known limitation

- No physical Android device is attached, so APK installation, camera, CV, and physical M1/M2 evidence remain unverified and may not be claimed.

## Recommended next actions

1. M0-15 SQLAlchemy engine/session boundary.
2. M0-16 Alembic empty baseline.
3. M0-17 through M0-20 storage and containerized API foundation.

Reviewed commit: 062213baebf4a312e1f0ceebf4a69b3ca045e85f
