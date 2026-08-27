# M2-P01 evidence — implemented M1 component inventory and reuse map

Date: 2026-08-26. Baseline inspected: `a7d6e32` (feature/m2). Method: every
item below was read from implemented source in this worktree — not from
plans. Classification per planned responsibility:
**REUSE** unchanged · **GENERALIZE** (extend existing mechanism) ·
**REPLACE/DELETE** · **NEW** (no existing mechanism; must be created).

Scope guard (ADR-009): M2 generalizes these components; no second publisher,
scan state machine, or parallel outbox is proposed anywhere below.

## 1. Android inventory (implemented)

### Room database — `android/core/data/.../core/data/db/`

| Component | File | Notes |
| --- | --- | --- |
| DB shell v3 + migrations 1→2→3 | `RemanenceLocalDatabase.kt` | v2 added statement/signature paths; v3 (FIX-REVIEW-04) added nullable `sender_user_id`, `sender_key_bundle_id`, `sender_signing_public_keyset_b64`. Exported schemas under `android/core/data/schemas/`. |
| `local_account` entity/DAO | `LocalAccountEntity.kt`, `LocalAccountDao.kt` | Single-row current account (`user_id` PK); written by auth use cases via `session/RoomCurrentAccountStore.kt`; cleared on logout. |
| `outbox_capsule` + states | `OutboxCapsuleEntity.kt` | `OutboxCapsuleState`: PREPARING, ENCRYPTED, UPLOADING, FINALIZING, PUBLISHED, RETRYABLE_FAILURE, TERMINAL_FAILURE (= protocol.md §9). Separate sender/recipient IDs and bundles; signed statement/signature file paths survive restart. **No owner-account column yet. No sender retry-material reference yet.** |
| `outbox_blob` + states | `OutboxBlobEntity.kt` | `OutboxBlobUploadState`: PENDING, STORED; unique `(capsule_id, kind, ordinal)`; size/sha256; attempt counter. |
| Outbox DAOs with CAS transitions | `OutboxDaos.kt` | `transitionState(allowedFrom)`, `transitionStateWithError`, `transitionUploadState(allowedFrom)`, `incrementAttemptCount` — guarded compare-and-set already implemented. |
| `incoming_capsule` + material states | `IncomingCapsuleEntity.kt`, `IncomingDaos.kt` | `IncomingMaterialState`: DISCOVERED, INDEX_CACHED, MATERIAL_CACHED, FINGERPRINT_ACCEPTED, CORRUPT; CAS `transitionMaterialState(allowedFrom)`. **Schema+DAO only: no production writer or reader exists yet** (see contradiction C2). |
| `incoming_envelope` | `IncomingEnvelopeEntity.kt`, `IncomingDaos.kt` | One HPKE envelope per capsule with transport hash. Unused by flows yet. |
| `blob_cache` | `BlobCacheEntity.kt`, `BlobCacheDao.kt` | DOWNLOADING/CACHED/CORRUPT; expected size/sha256; app-private path. Unused by flows yet. |
| `recognition_fingerprint` | `RecognitionFingerprintEntity.kt`, `RecognitionFingerprintDao.kt` | side FRONT/BACK × origin SENDER/RECIPIENT; unique baseline; preferred flag; no raw bitmaps. |
| `sync_cursor` | `SyncCursorEntity.kt`, `SyncCursorDao.kt` | Already keyed `(user_id, stream_name)`. Unused by flows yet. |

### Outbox staging — `android/core/data/.../core/data/outbox/`

- `CapsuleOutboxStager.kt`: atomic ciphertext-only staging (unique-temp-then-rename,
  pre-checks inside one Room transaction, PREPARING→ENCRYPTED CAS, owned cleanup on
  failure, replay refusal). Validates 1 recognition + 1 content manifest + 3–5
  sequential photos and the 69-byte signature.

### Crypto — `android/core/crypto/.../core/crypto/`

- Publisher-side: `CapsuleKeysetGenerator`, `CapsuleArtifactCryptor`,
  `PhotoArtifactEncryptor`, `SequentialPhotoEncryptionBatch`,
  `RecognitionManifestCodec`, `ContentManifestCodec`, `PublishStatementSigner`
  (69-byte TINK-prefixed guard), `RecipientEnvelopeCryptor`,
  `CapsuleKeysetParser` (exact AES256_GCM/TINK).
- Acceptance: `CapsuleAcceptanceGate` (+ `CapsuleAcceptanceGateTest` golden/malformed
  matrix). Verify-before-decrypt; canonical-byte checks; ID agreement;
  statement-hash binding; layout via `:core:model` `ArtifactLayoutValidator`.
  **Requires EVERY declared blob delivered today** (exact count match) — see P11/P12.
- Key protection: `KekBoundary`/`AndroidKeystoreKekBoundary`,
  `KeysetKekWrapper` + `WrappedKeysetRecord` (versioned wrap of private keysets),
  `IdentityBundleRepository` (refuses silent replacement; RECOVERY_REQUIRED),
  `SessionTokenStore` (sealed refresh token). Wrapper AAD binds only
  format_version+alias today.
- Protocol primitives shared with server fixtures: `protocol/proto/remanence/protocol/v1/remanence_v1.proto`,
  `protocol/fixtures/publish-signature-v1.json`, `recipient-envelope-v1.json`.

### Create/publisher wiring — `android/app/.../create/`, `ui/create/`

- `CapsulePublisher.kt`: request ALREADY carries distinct
  `senderUserId`/`recipientUserId` and bundle IDs (defaults equal; FIX-REVIEW-04);
  every AAD/statement/envelope context uses them separately; recipient public
  keyset is a parameter.
- Self-recipient guard to remove: `ui/create/CreateViewModel.kt` `publishSealed()`
  ("this milestone publishes only to your own account"). Confirmed immutable
  recipient snapshot already exists: `CreateSessionStore.confirmedRecipient`
  (memory-only, ACTIVE-bundle-checked), fed by `CreateRecipientFlow` +
  `RecipientConfirmationScreen`.
- Staging: `PhotoStagingPipeline`, session-owned dirs
  `create-staging/<capsule UUID>/`; `RealStillFingerprintProcessor`;
  `CreateSessionFingerprintRepository`.

### Scan / matching / presentation

- Candidate source today is the sender's own OUTBOX (self-send loopback):
  `ui/scan/ScanViewModel.kt` verifies via `outboxCapsuleDao`/`outboxBlobDao`
  files through `CapsuleAcceptanceGate`; chooser hints decrypt from outbox rows;
  `ui/capsule/CapsuleContentSource.kt` decrypts photos/note from outbox rows.
- Recognition engine (`:core:recognition`): `LocalMatchEngine`, `DescriptorMatcher`,
  `HomographyEstimator`, `HomographyPlausibility`, `SpatialCoverage`, `SideScorer`,
  `FrontCandidateRanker`, `CompositeAcceptance`, `ScanOutcomeClassifier`,
  `MatchCoordinator` (recipient-first / sender-fallback), `ScanGrantManager`
  (memory-only, 10 min, single live grant, `clearAll()` on logout).
- Trust boundaries: `identity/CapsuleRoutingPolicy` (strict routing parse, legacy
  NULL self-send fallback), `identity/TrustedSenderKeyStore` +
  `identity/DirectorySenderKeyStore` (directory-only sender verification).
- Capture: app `capture/` package (`CaptureAttemptController`, `StillCameraAdapter`,
  `CameraXPreviewBinder`, `FrontCaptureFlow`/`BackCaptureFlow`/`PreparedBackGate`),
  camera crash fix at HEAD.

### Auth/session/network — `android/core/data/network/`, `app/auth`, `app/session`

- `AuthRepository` (register/login/refresh/logout DTOs), bare-vs-authenticated
  stack split in `ProductionApiStack` + `RefreshingAuthenticator` (serialized
  one-retry), memory-only access token (`AuthTokenHolder`), cold-start
  `session/SessionBootstrap`, ordered `LogoutUseCase` (server revoke → credentials
  → local_account row → grants; wrapped keys/ciphertext retained per security.md §9).
- Directory clients: `DirectoryRepository` (handle lookup → snapshot incl.
  encryption public keyset + bundle status), `KeyBundleByIdRepository`
  (historical bundle fetch). **No capsule draft/upload/finalize/incoming client
  calls exist yet.**
- Account scoping status: only `local_account` and `sync_cursor` carry user
  identity; outbox/incoming/blob_cache/fingerprint tables and all file roots are
  unscoped; logout does not purge rows/files (retention-for-same-account policy).

### File roots (actual, wired in `RemanenceApplication.AppContainer` /
`wiring/RemanenceViewModelFactory.kt`)

| Root | Producer |
| --- | --- |
| `filesDir/fingerprints/` | `EncryptedFingerprintStore` (sealed `.fpw`, AAD `postmark/local-fp/v1:<id>`) |
| `filesDir/identity/` | `IdentityBundleRepository` wrapped keysets |
| `filesDir/session/` | `SessionTokenStore` sealed refresh token |
| `filesDir/outbox-ciphertext/` | `CapsuleOutboxStager` (envelope/blobs/statement/signature files) |
| `filesDir/create-staging/<capsule UUID>/` | plaintext photo staging, session-owned, swept on death |
| Room DB `remanence.db`; KEK aliases `remanence.identity.v1`, `remanence.session.v1`, `remanence.fingerprint.v1` | |

All roots are constructor-injected, so account-scoped re-rooting (P04) is
mechanical; none is account-scoped today.

### WorkManager seam

**None exists.** No `androidx.work` dependency in any Gradle catalog/module, no
worker classes, no unique-work chains. Upload/sync "resume" currently exists only
as durable outbox/blob state plus CAS DAOs — the resumable substrate is real, the
scheduler is not.

## 2. Server inventory (implemented)

Under `server/src/remanence/`:

- App factory + problem+json handlers: `main.py`; settings `settings.py`;
  DB `db/base.py`, `db/session.py`; migrations `migrations/versions/0001_m0_baseline.py`,
  `0002_m1_accounts.py` (users/auth_credentials/auth_sessions/user_key_bundles
  incl. one-active partial unique index and ACTIVE/RETIRED/REVOKED enum).
- Bearer auth: `api/dependencies.py` `get_authenticated_principal` (opaque-token
  hash lookup, disabled-user check) — reusable dependency for every M2 endpoint.
- Auth services: `auth/registration.py`, `login.py`, `logout.py`, `tokens.py`,
  `passwords.py` (Argon2id), `session_repository.py`, `session_rotation.py`
  (lineage replay detection).
- Users/directory: `users/models.py`, `users/key_models.py`,
  `users/handles.py`, `users/key_bundle_validation.py`;
  `api/directory.py` `GET /v1/directory/handles/{handle}` (active bundle +
  `directory_version`) and `GET /v1/directory/key-bundles/{id}` (immutable public
  bundle incl. RETIRED/REVOKED) — authoritative resolution for finalize (M2-S14).
- Storage: `storage/base.py` `BlobStore` protocol (put/open_reader/stat/delete,
  typed errors) + `storage/local.py` (`LocalBlobPathResolver` traversal-safe;
  `LocalFileBlobStore.put` streams with SHA-256/length enforcement,
  temp→fsync→hardlink promote, hash-identical idempotent replay,
  BlobConflictError otherwise). Promotion currently happens inside `put()`; there
  is no separate temporary-object writer, blob-state model, or GC listing.
- Capsules: `capsules/__init__.py` is an empty placeholder — **no capsule tables,
  endpoints, finalize transaction, delivery state, or idempotency records exist**.
- Cross-platform signature proof already green server-side:
  `server/tests/test_publish_signature_verification.py` over
  `protocol/fixtures/publish-signature-v1.json`.

## 3. Reuse map for M2 prerequisites (P02–P14)

| Task | Existing anchor (file above) | Class |
| --- | --- | --- |
| P02 account-owner ID in domain/Room/schema | Migration precedent `RemanenceLocalDatabase.MIGRATION_2_3`; owner value source `local_account` via `RoomCurrentAccountStore` | GENERALIZE (v3→v4 migration; nullable-free new columns for fresh rows) |
| P03 account scope on every query/index used by M2 | Small DAO surface: `OutboxDaos.kt`, `IncomingDaos.kt`, `BlobCacheDao.kt`, `RecognitionFingerprintDao.kt`; CAS patterns already present | GENERALIZE |
| P04 account-scoped file roots + retention/purge | Constructor-injected roots (AppContainer table above); atomic-delete precedents in `CapsuleOutboxStager`, `EncryptedFingerprintStore.deleteBaseline` | GENERALIZE (policy itself has no prior code) |
| P05 WorkManager naming/tag/cancellation contract | Nothing — add first `androidx.work` dependency/workers; cancellation hook point = `LogoutUseCase` teardown step | NEW |
| P06 distinct sender/recipient publisher without crypto-framing change | `CapsulePublishRequest` distinct-ID fields + contexts; regressions `CapsulePublisherTest`, `CrossIdentityCapsuleFlowTest` | GENERALIZE (make recipient explicit end-to-end; drop equal defaults) |
| P07 remove self-recipient guard; feed confirmed snapshot | Guard at `CreateViewModel.publishSealed`; snapshot at `CreateSessionStore.confirmedRecipient`; stale/wrong-recipient tests `CreateStaleDeliveryTest`, `CreateRecipientConfirmFlowTest` | REPLACE/DELETE (guard) + GENERALIZE (wiring) |
| P08 sender-owned durable wrapped K in outbox | Wrapping mechanics `KeysetKekWrapper`/`WrappedKeysetRecord`/`AndroidKeystoreKekBoundary`; record+Room-reference pattern like statement/signature paths | GENERALIZE wrapping (extend AAD to bind account/capsule/sender-bundle/purpose per security.md §6.6) + NEW storage slot/column |
| P09 delete retry material on published/abort/terminal | Cleanup precedents: stager owned-cleanup, `EncryptedFingerprintStore.deleteBaseline`/`deleteFileOf` | GENERALIZE pattern into NEW lifecycle rule |
| P10 canonical statement/layout/ID verification core from the gate | `CapsuleAcceptanceGate` internals (parse/canonical/idsAgree/hash/layout/signature-guard) + `ArtifactLayoutValidator`; matrix `CapsuleAcceptanceGateTest` stays green | GENERALIZE (extract; behavior-neutral) |
| P11 control/index acceptance (envelope + recognition blob only) | Extracted P10 core; recognition AEAD via `RecognitionManifestCodec.decryptAndParse`; `DeliveredBlob` input type | NEW stage over reused core (subset-of-declarations semantics does not exist yet) |
| P12 full presentation acceptance | Today's exact-count `blobsMatch` behavior + content-manifest AEAD/layout (`ContentManifestCodec`) as proven in presentation tests | GENERALIZE (becomes the second stage) |
| P13 pinned local/server material-state CAS transitions | Enums + guarded transitions: `OutboxCapsuleState`/`transitionState`, `IncomingMaterialState`/`transitionMaterialState`, `OutboxBlobUploadState`/`transitionUploadState` | GENERALIZE (exhaustive transition-table pinning; server DRAFT/READY/ABORTED + AVAILABLE/CIPHERTEXT_SYNCED are NEW, S01–S05) |
| P14 physical-device smoke record | Pending-items list in `docs/evidence/m1-implementation-evidence.md`; blocker convention in `.agent/current-milestone.md` | NEW evidence record (no code) |

Server queue note: M2-S01–S21 build on REUSEd `get_authenticated_principal`,
directory endpoints, `UserKeyBundle`, Alembic/Base, and `BlobStore`; everything
under `capsules/` is NEW. M2-A01–A03/A09 client calls have no existing HTTP
seams beyond the repositories listed in §1.

## 4. Old-assumption contradictions found in implemented M1

- **C1 — WorkManager is not an "existing seam".** Architecture §3/§11 name
  WorkManager, but zero worker code or dependencies exist. P05/M2-A04/M2-A10 are
  greenfield scheduler work built on the real CAS/outbox substrate; they must not
  be described or estimated as generalizing an existing worker.
- **C2 — Incoming material is schema-only.** `incoming_capsule`,
  `incoming_envelope`, `blob_cache`, and `sync_cursor` have correct shapes and
  tested DAOs but no production reader/writer; the entire scan/chooser/
  presentation candidate universe reads the sender's own OUTBOX rows and files.
  M2-A09–A14 must build the population path, and the P10 core must take its
  inputs from incoming cache state, not outbox file paths as `ScanViewModel` does
  today.
- **C3 — "M1 publisher is same-account-hardwired" is false.** Distinct
  identities were plumbed end to end (FIX-REVIEW-04): request fields, AAD,
  statement, envelope, strict routing parse, and directory-based sender trust.
  Only the UI equality guard and equal-default arguments remain. Any proposal of
  a separate two-user publisher would duplicate an existing generalized path.
- **C4 — The acceptance gate cannot be stage-split without extraction.** It
  hard-requires exactly all declared blobs delivered and its `DeliveredBlob`
  inputs are assembled from outbox files; P10's extraction is a prerequisite,
  not an optional refactor (consistent with ADR-009; pinned here against code).
- **C5 — Naming mismatch to reconcile in upload tasks.** Android
  `OutboxBlobUploadState` uses PENDING/STORED while protocol.md §7 declares
  DECLARED/STORED server blob states. Mapping must be explicit in M2-A02/S12.
- **C6 — Repository verify script is stale vs implemented M1.**
  `scripts/verify-m0.sh` asserts `alembic_version == 0001_m0_baseline`, but the
  implemented schema migrates to `0002_m1_accounts`; the repo's own verification
  script would fail today. Needs a FIX task (tooling), not part of this docs
  commit.

## 5. Validation performed

- Documentation-only change; no production/test sources touched.
- Every file/class referenced above was confirmed present in this worktree at
  inspection time (paths checked programmatically; see commit).
- `git diff --check` clean.
- No dedicated docs-check tooling exists in the repository (no markdownlint/doc
  config; only the full-stack `scripts/verify-m0.sh`, which is stale per C6 and
  not runnable as a docs check here).
