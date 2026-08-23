# Test strategy

Status: **DRAFT — Codex-owned.**

Tests prove mechanisms and boundaries, not screen count. A green mocked demo cannot satisfy M1/M2. The minimum evidence pyramid combines fast deterministic tests, real PostgreSQL/storage integration, Android instrumentation, recognition datasets, adversarial crypto fixtures, and a two-device physical flow.

## 1. Test layers

### Pure JVM/Kotlin tests

Run on every Android change where applicable:

- typed IDs, handle normalization, limits, lifecycle transitions;
- deterministic protobuf ordering/encoding and AAD/context bytes;
- recognition score/ranking logic from fixed match reports;
- repository/use-case ordering with fakes at I/O boundaries;
- scan-grant lifetime/consumption with a fake clock;
- navigation guards and ViewModel state.

These tests do not claim to validate Android Keystore, CameraX, OpenCV native behavior, Room on-device behavior, or actual image decoding.

### Android local/instrumentation tests

- Room schemas, DAO constraints, and migration paths;
- Keystore KEK creation and wrapped keyset survival across process/component recreation;
- Tink AEAD/HPKE/signature behavior on Android runtime;
- OpenCV extraction/matching on ARM64 and at least one CI-supported emulator ABI;
- CameraX permission/capture orchestration with fakes plus manual checks on physical camera hardware;
- Compose navigation/accessibility and absence of capsule-ID bypass paths;
- private file/cache cleanup and backup-exclusion rules.

### Backend unit tests

- normalization/validation, password/token helpers, state machines;
- public statement parsing/signature verification;
- storage path validation and structured error mapping;
- authorization policy functions.

### Backend integration tests

Use the same PostgreSQL major version as development/CI, never SQLite as a substitute for constraints/transactions:

- Alembic empty/upgrade/downgrade/upgrade;
- unique/partial indexes and foreign keys;
- session rotation/replay transactions;
- draft/blob/finalize atomicity and idempotency;
- cursor pagination and recipient authorization;
- local `BlobStore` temporary/atomic promotion and restart behavior.

### Contract/golden tests

Checked-in non-production fixtures are consumed by Android and backend where relevant:

- handle/UUID/limits/error JSON fixtures;
- deterministic protobuf and AAD/context bytes;
- public statement and signature;
- artifact encryption/decryption and HPKE envelopes (Android only for secrets);
- malformed, truncated, reordered, duplicate, oversized, and unknown-version inputs.

A golden fixture changes only with explicit protocol review. Regenerating expected output inside the same test is forbidden.

### Recognition evaluation

`recognition.md` defines dataset composition, split, metrics, profile tuning, performance, and privacy. Unit synthetic transforms catch regressions; only the locked physical dataset can justify threshold/recall claims.

### Physical end-to-end

- M1: one physical Android device and one physical postcard.
- M2: two physical Android installations/accounts and one transferred postcard.
- Record APK hash, Git commit, device models/API levels, backend commit/config, commands, and observed pass/fail.

Emulator-only evidence cannot pass CameraX/CV or two-person physical acceptance.

## 2. Security/adversarial matrix

Every crypto artifact is tested against:

- wrong recipient key and wrong sender verification key;
- changed capsule/user/key/blob IDs;
- changed artifact kind/ordinal/AAD/context;
- bit flip, truncation, extension, swapped blobs, reordered statement entries;
- wrong ciphertext size/hash and repeated nonce-format/parser edge cases;
- unknown protocol/key/profile version;
- revoked/retired/stale key state;
- unrelated authenticated user access;
- process death before/after encrypted staging and after scan grant.

Expected result is a stable redacted failure with zero returned/rendered plaintext.

## 3. Plaintext canary test

End-to-end tests inject unique non-secret markers into note, photo bytes, descriptor fixture, and test private/capsule keys. After publish/sync they scan:

- captured HTTP requests and proxy logs;
- PostgreSQL dump;
- object-storage directory/bucket;
- Android Room export and app-private persistent files;
- API/worker/application normal logs.

Expected occurrences are only within explicitly allowed encrypted/plaintext-active test process memory or deliberately staged pre-encryption temp scope. Persisted/network/server matches fail the test. Ciphertext itself is excluded from substring decoding assumptions; the canary scanner checks literal bytes/encodings.

## 4. Failure and restart testing

Inject failure at each durable transition:

- before/after local encrypted outbox transaction;
- before/during/after each blob PUT;
- after storage promotion but before DB state update;
- before/during/after finalize transaction;
- during access-token refresh;
- between incoming page download and local cursor update;
- after envelope/index download but before fingerprint re-encryption;
- during content download/decrypt/render cleanup.

Restart client/API/PostgreSQL as applicable. The result must be safe retry, explicit repair state, or terminal redacted failure—never duplicate ready capsule, half-visible capsule, lost confirmed blob, plaintext persistence, or infinite retry.

## 5. CI and local commands

M0 freezes exact versions and commands. The intended verification groups are:

```text
android:  ./gradlew testDebugUnitTest assembleDebug
server:   locked-runner pytest command
db:       Alembic upgrade plus PostgreSQL integration suite
repo:     formatting/lint, git diff check, secret/build-output check
```

Instrumentation/physical suites are separate labeled evidence because the VPS may not have an Android device. CI failure blocks merge/next milestone; flaky tests are quarantined only with an issue, owner, reason, and expiry—not silently retried until green.

## 6. Test data and privacy

- No real user database/object dump enters fixtures.
- Back-side images use synthetic personal details unless documented consent exists.
- Test private keys are clearly named, scoped to fixtures, and rejected by production-mode configuration.
- Logs/evidence redact email, handles where unnecessary, tokens, key bytes, envelopes, descriptors, note/photo data, and physical addresses.
- Recognition raw dataset access is narrower than repository access; derived distributable fixtures must be privacy-reviewed.

## 7. Review evidence

Each milestone record contains:

- exact reviewed commit and commit range;
- environment/tool versions;
- commands and exit codes;
- skipped tests with reason (a skip is not a pass);
- physical device/API identifiers where relevant;
- acceptance checklist results;
- known risks and next required task.

Agent-written claims without command output or physical observation are not evidence.
