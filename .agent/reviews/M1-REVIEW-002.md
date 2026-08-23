# M1 cumulative review 002

Reviewed at: 2026-08-23T11:58:16Z
Reviewed range: 3eb43efe130d243f5bf16f8b7c93b8e72bb9d2b9..b5e0b0fdc7356f8fff9041e355f7b9eb2b8e7576

STATUS: PASS

## Critical

None unresolved.

## Architecture deviations

- Alembic model registration omission found during review and resolved by b5e0b0f.
- command.check against real temporary PostgreSQL reports no new upgrade operations.
- dumb backend boundary preserved; canonical crypto contexts are reconstructed by clients only.

## Security

- server persists Argon2id PHC field and only SHA-256-sized opaque token hashes; no token plaintext/private keys/capsule plaintext fields.
- one-active key bundle partial uniqueness and refresh lineage/single-successor constraints present.
- deterministic artifact/envelope contexts match independent 99/95-byte vectors and fail invalid ordinals.

## Recognition

- no backend CV/gallery/feed/social code introduced; recognition boundary unchanged.

## Build/tests

- backend full suite with POSTMARK_TEST_DATABASE_URL: 130 passed.
- Android full Gradle test with documented JDK17/SDK env: PASS.
- PostgreSQL upgrade/downgrade/re-upgrade/idempotent-upgrade and Alembic command.check: PASS.
- git diff --check and worktree clean.

## Known limitation

Physical Android device still absent; APK installation/CameraX/CV/physical M1/M2 evidence unverified, not waived.

## Recommended next actions

1. M1-S07 only.

Reviewed commit: b5e0b0fdc7356f8fff9041e355f7b9eb2b8e7576
