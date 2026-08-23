# Acceptance criteria

Status: **APPROVED.**

Every item is pass/fail. A screenshot, agent statement, or successful command from an unrelated commit is not evidence. Evidence records command, exit code, environment/device, and reviewed commit.

## Architecture Gate

- [ ] All required docs exist and are marked `APPROVED` with the same protocol/recognition versions.
- [ ] `rg` finds no unresolved `TODO`, `TBD`, placeholder algorithm, or contradictory state/identifier name in normative sections.
- [ ] Database, REST, protobuf, Room, security, and recognition docs agree on cardinality: one recognition manifest, one content manifest, 3–5 photos, one recipient envelope.
- [ ] First-receipt bootstrap can be traced without server CV or plaintext descriptors.
- [ ] No documented navigation/API requires a gallery, inbox, history, feed, public profile, or capsule count.
- [ ] Device loss, stale key, key substitution limitation, metadata leakage, and raw-back privacy are explicit.
- [ ] Initial M0 implementation tasks each have one narrow artifact, verification command, and expected duration at most ten minutes.
- [ ] Architecture changes after gate require `docs/decisions/ADR-XXX.md`.

## M0 — Foundation

### Android

- [ ] `cd android && ./gradlew --version` uses the documented JDK/Gradle toolchain.
- [ ] `cd android && ./gradlew clean testDebugUnitTest assembleDebug` exits 0 on the VPS without Android Studio.
- [ ] Exactly `:app`, `:core:model`, `:core:data`, `:core:crypto`, and `:core:recognition` are included.
- [ ] Generated APK exists at the documented path and `apkanalyzer`/`aapt` can inspect its package/min/target SDK.
- [ ] `adb install -r <apk>` instructions are correct; if no device is attached, installation remains explicitly unverified.
- [ ] Minimal app launches to a Compose screen and can issue a typed call to configurable backend health endpoint.

### Backend

- [ ] Dependency lock/install command succeeds from a clean environment.
- [ ] `docker compose config` exits 0 without embedding production secrets.
- [ ] `docker compose up -d --build` starts API and PostgreSQL reproducibly.
- [ ] Alembic upgrades an empty database to head and a second upgrade is a no-op.
- [ ] `curl -fsS http://127.0.0.1:<port>/healthz` returns documented JSON/status.
- [ ] Backend unit/integration test command exits 0.
- [ ] Local filesystem `BlobStore` prevents traversal and passes put/get/hash/idempotent-replace tests.

### Repository

- [ ] README/development docs list exact setup/build/test/APK/install commands.
- [ ] Git contains no SDK, build output, environment secrets, database files, or uploaded blobs.
- [ ] Working tree is clean at the reviewed M0 commit.

## M1 — Single-account real vertical slice

### Account and identity

- [ ] Registering a normalized unique handle/email persists a real account; duplicate normalized handle fails atomically.
- [ ] Login/refresh/logout use real hashed credentials and opaque rotating tokens.
- [ ] Device generates separate HPKE and Ed25519 Tink keysets; server contains public keysets only.
- [ ] Private keyset markers are absent from captured requests, PostgreSQL, object storage, Room, logs, and Android backup rules.
- [ ] Wrapped identity survives process/app restart and opens only through the Android Keystore KEK.
- [ ] Self handle resolves through the same directory endpoint and explicit confirmation used for another user.

### Creation and crypto

- [ ] Sender cannot continue with an unresolved/unconfirmed handle.
- [ ] Front and fully prepared back are separate CameraX still captures with quality/crop handling.
- [ ] Exactly 3–5 Photo Picker images are accepted; EXIF is removed and size limits enforced.
- [ ] Note is optional and UTF-8 size limit is enforced.
- [ ] Capsule produces one recognition ciphertext, one content ciphertext, 3–5 photo ciphertexts, signed statement, and HPKE envelope.
- [ ] Stored/server bytes contain no known plaintext markers from note/photos/descriptors.
- [ ] Unit/golden tests reject wrong key, context, AAD, signature, hash, ordinal, truncation, and substitution.

### Scan gate and persistence

- [ ] Local ORB matching uses both sides and emits raw match evidence/classification.
- [ ] Capsule presentation cannot be navigated/deep-linked by capsule ID.
- [ ] Successful scan plus verified crypto issues a memory-only ten-minute grant.
- [ ] Leaving the flow, logout, expiry, or process death invalidates the grant.
- [ ] Closing/restarting the app requires a new two-side scan to present the capsule again.
- [ ] Raw scans and persistent plaintext thumbnails do not remain after staging/presentation cleanup.

### Physical evidence

- [ ] `assembleDebug` APK installs and launches on one documented physical Android device.
- [ ] That device completes create → close/process restart → scan both sides → decrypt/display with one physical postcard.

## M2 — Two-user transfer

### Sender A

- [ ] A and B are separate server users with distinct UUIDs, handles, and key bundles.
- [ ] A resolves B’s current handle, sees confirmation, and published route/envelope bind B’s immutable IDs.
- [ ] A uploads only ciphertext and a public signed statement; finalize reaches `READY` atomically.
- [ ] Interruption after any blob can resume without duplicate capsule/blob or reupload of completed blobs.

### Recipient B first receipt

- [ ] B’s incoming sync receives only routed metadata, envelope, signed statement, and encrypted artifacts.
- [ ] No incoming list/count/thumbnail/sender identity appears before a plausible scan.
- [ ] B scans front/back and matches locally against pending sender fingerprints.
- [ ] Duplicate-front test produces back disambiguation or explicit plausible chooser; it never silently guesses.
- [ ] Wrong postcard produces no match/retry, not a random capsule.
- [ ] B verifies envelope, IDs, signed statement, hashes, and AEAD before plaintext.
- [ ] B sees the correct 3–5 photos and note fullscreen.
- [ ] Successful receipt stores a preferred encrypted recipient front/back fingerprint pair and retains sender fallback.
- [ ] Server state reveals at most `CIPHERTEXT_SYNCED`, never opened/recognized timestamps.

### Recipient B later use

- [ ] After leaving and force-stopping/restarting the app, no capsule presentation route is available.
- [ ] Rescanning the delivered postcard prefers recipient fingerprint and opens the same capsule.
- [ ] With cached ciphertext and valid local keys, later scan works offline.
- [ ] Without cached required material, UI explains connectivity rather than showing partial plaintext.

### End-to-end physical evidence

- [ ] Two physical Android installations run the reviewed APK and distinct accounts.
- [ ] One real physical postcard completes A create/publish → physical handoff/mail simulation → B first receipt → restart → B later scan.
- [ ] API/DB/object/log inspection for that run finds no plaintext photo/note/back/descriptors/private/capsule keys.

## M3 — Recognition hardening

- [ ] Dataset satisfies the instance/design/duplicate-front/transformation composition in `recognition.md`.
- [ ] Train/evaluation split is by physical instance/design and evaluation remains locked during tuning.
- [ ] Recognition profile is one versioned asset; no threshold is duplicated as a magic number.
- [ ] Locked evaluation reports zero false automatic accepts with comparison count/statistical bound.
- [ ] First receipt reaches at least 85% automatic recall and 95% automatic-or-chooser recall on quality-passing captures.
- [ ] Later scan reaches at least 95% automatic recall and 98% automatic-or-chooser recall.
- [ ] P95 post-capture matching is under 2 seconds for 100 candidates on documented reference hardware.
- [ ] Encrypted fingerprint pair median is under 256 KiB and hard max under 1 MiB.
- [ ] Any SIFT adoption has an approved ADR and measured advantage; otherwise ORB remains sole v1 algorithm.

## M4 — Security/failure hardening

- [ ] Complete authorization matrix denies unrelated user/capsule/blob operations.
- [ ] Refresh replay revokes lineage; auth and rate-limit tests pass.
- [ ] Every corruption/substitution/context case in `security.md` fails closed without plaintext/log leak.
- [ ] Database/API/object-store restart during upload/finalize resumes idempotently.
- [ ] Stale recipient key fails finalize and re-envelope/re-sign succeeds without photo re-encryption.
- [ ] Room and Alembic upgrade tests cover every released prior schema.
- [ ] Automated plaintext canary scan covers API capture, DB dump, object files, Room export, and normal logs.
- [ ] Device-loss UX accurately distinguishes auth recovery from E2EE recovery.
- [ ] Recovery package either passes export/import/wrong-code/tamper tests or remains explicitly unavailable with no false recovery claim.

## M5 — UX polish

- [ ] Onboarding communicates physical-first/no-recovery limitations without a long tutorial.
- [ ] Accessibility checks cover labels, focus order, dynamic type, contrast, and non-color-only errors.
- [ ] Capture failures provide actionable reason-specific guidance.
- [ ] Current upload resume/error is understandable without creating sent history or engagement counters.
- [ ] Search/navigation/resource inspection confirms no gallery, inbox, feed, social graph, achievements, streaks, reminders, deep links, or memory counts.
- [ ] Music remains absent/null and no provider dependency is present.

## Review record format

Each cumulative supervisor review is stored under `.agent/reviews/`:

```text
STATUS: PASS | NEEDS_ATTENTION | BLOCKED

Critical:
- ...

Architecture deviations:
- ...

Security:
- ...

Recognition:
- ...

Build/tests:
- command, result

Recommended next actions:
1. ...

Reviewed commits: <last_reviewed_exclusive>..<head_inclusive>
Reviewed commit: <head>
```
