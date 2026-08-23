# M1 cumulative review 001

Reviewed at: 2026-08-23T06:15:51Z
Reviewed range: 58c988daa7a52eac6f73616052acec38d31aeb5c..3eb43efe130d243f5bf16f8b7c93b8e72bb9d2b9
STATUS: PASS

## Critical

- None unresolved.

## Architecture deviations

- None unresolved.
- Shared root `protocol/` module plus protobuf-lite generation in `:core:model` matches approved boundaries.
- Buf STANDARD exception is only `ENUM_VALUE_PREFIX` because v1 enum names were already frozen.
- ADR-005 freezes capsule artifact `AES256_GCM`/`TINK` framing and 33-byte overhead; scope correction `86ad3e5` keeps the signed publish statement and HPKE envelope separate.

## Security

- No private-key, capsule-key, custom-crypto, backend CV, or plaintext media implementation exists yet.
- Strict UUID and handle parsing uses generic errors; layout and publish-statement build validation fail safely.
- The deterministic 400-byte publish fixture is independently supplied; hashes, sizes, and cardinality are validated before output.

## Recognition

- No gallery, feed, or social surface exists.
- `:core:recognition` remains a local empty boundary at this M1 stage. No CV has leaked to the backend.

## Build/tests

- `scripts/verify-m0.sh` from the repo — PASS; backend 82 tests with DB; Android aggregate clean build 157 actionable tasks; APK 12,287,755 bytes; Compose postgres/api healthy, migrate exited 0, `/healthz` exact `{"status":"ok"}`; device not run.
- Android JUnit XML total 59 tests, failures 0, errors 0, skipped 0.
- Pinned Buf lint/build — PASS.
- Dependency insight: `protobuf-javalite` 4.35.0 only; no `protobuf-java`.
- `git diff --check`, working tree, and tracked artifact/security scans — clean.

## Known limitation

- No physical Android phone is attached, so APK installation, CameraX, CV, and physical M1/M2 evidence remain unverified and are not waived.

## Recommended next actions

1. M1-P08 only.

Reviewed commit: 3eb43efe130d243f5bf16f8b7c93b8e72bb9d2b9
