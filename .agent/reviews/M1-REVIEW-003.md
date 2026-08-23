# M1 cumulative review 003

Reviewed at: 2026-08-23T12:39:40Z
Reviewed range: b5e0b0fdc7356f8fff9041e355f7b9eb2b8e7576..df417de50abe1bf445ce8588100de0cd58ecd8a8

STATUS: NEEDS_ATTENTION

## Critical

Rotation locks only the presented session row; concurrent replay of an ancestor refresh and rotation of an active descendant lock different rows, so lineage revoke is not serialized with descendant creation and may race/deadlock; require one deterministic lineage-wide serialization boundary before lifecycle decisions.

## Architecture deviations

None otherwise; repository remains caller-transaction-owned.

## Security

Argon2id exact params, CSPRNG opaque tokens, SHA-256 full-token hashes, replay result redacts secrets; no plaintext token persistence/logging, JWT, custom crypto, backend CV/gallery/feed/social.

## Recognition

Unchanged local boundary.

## Build/tests

- backend full PostgreSQL suite 178 passed.
- Android `./gradlew test assembleDebug` BUILD SUCCESSFUL.
- `git diff --check` clean; worktree clean.

## Recommended next actions

1. M1-S10D lineage serialization and true concurrency integration test.
2. Repeat cumulative review before M1-S11.

Reviewed commit: df417de50abe1bf445ce8588100de0cd58ecd8a8