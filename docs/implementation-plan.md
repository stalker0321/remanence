# Atomic implementation plan

Status: **APPROVED; Architecture Gate passed and Grok may receive one listed task at a time.**

This queue maps approved architecture to implementation-sized commits. It is intentionally more granular than milestones. The supervisor gives only one task at a time and may split any task further if repository state makes ten minutes unrealistic.

## 1. Implementation-agent contract

Every Grok task prompt includes:

- one task ID and one outcome;
- exact files/components allowed to change;
- architecture sections that are normative;
- explicit non-goals;
- one or more verification commands;
- exact commit message;
- instruction to stop after reporting summary, checks, full commit hash, and clean status.

Rules:

1. Expected execution time is at most ten minutes.
2. One task equals one commit; no bundled opportunistic cleanup.
3. Start only from a clean tree. Never amend/reset/rewrite another commit.
4. Do not choose architecture. Ambiguity or a needed deviation stops the task for supervisor input.
5. Add the smallest test that proves the task. A later test task may expand coverage but does not excuse unverified behavior.
6. Do not start the next queue item autonomously.
7. A failed task may produce no commit; the follow-up task is narrowed or fixes one reported blocker.

Operational completion checks between tasks confirm commit/status/declared command. Cumulative content review happens after a coherent batch or 45–60 minutes, using the last reviewed commit range.

## 2. M0 queue — reproducible foundation

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M0-01 | Add root `.gitignore` and `.editorconfig` for Android/Python/env/blob outputs only. | `git diff --check` |
| M0-02 | Record detected JDK/Android SDK/Python/Docker tooling and required versions in `docs/development.md`. | Every command/version is reproducible; no install action. |
| M0-03 | Add Android Gradle settings, wrapper, repositories, and version catalog with no app code. | `./gradlew --version` |
| M0-04 | Add `:core:model` minimal Kotlin/JVM module. | `./gradlew :core:model:test` |
| M0-05 | Add empty Android-library `:core:data` module with one smoke test. | `./gradlew :core:data:testDebugUnitTest` |
| M0-06 | Add empty Android-library `:core:crypto` module with one smoke test. | `./gradlew :core:crypto:testDebugUnitTest` |
| M0-07 | Add empty Android-library `:core:recognition` module with one smoke test. | `./gradlew :core:recognition:testDebugUnitTest` |
| M0-08 | Add `:app` manifest/application theme and empty Compose activity. | `./gradlew :app:assembleDebug` |
| M0-09 | Render placeholder Home with only disabled Create/Scan actions and architecture-gate build label. | Compose test or screenshot test plus assemble. |
| M0-10 | Add Android unit-test convention/config so all module unit tests run from one command. | `./gradlew testDebugUnitTest` |
| M0-11 | Add server `pyproject.toml`, package skeleton, locked dependency workflow, and import smoke test. | clean dependency sync; `python -c` import via chosen runner. |
| M0-12 | Add typed server settings with test/dev/prod mode validation. | settings unit tests |
| M0-13 | Add FastAPI app factory and `/healthz` without database dependency. | endpoint test |
| M0-14 | Add PostgreSQL service to Docker Compose with healthcheck and no hardcoded production secret. | `docker compose config` |
| M0-15 | Add SQLAlchemy engine/session boundary using settings. | isolated connection/config test |
| M0-16 | Add Alembic configuration and empty baseline revision. | upgrade empty DB twice |
| M0-17 | Add `BlobStore` protocol and typed errors only. | interface/import unit test |
| M0-18 | Add safe local-filesystem blob path resolver with traversal rejection. | path unit tests |
| M0-19 | Add atomic local blob put/get/hash behavior. | adapter tests |
| M0-20 | Add API Dockerfile and Compose wiring to PostgreSQL/local blob volume. | image builds; health endpoint responds |
| M0-21 | Add Android HTTP client configuration and health response DTO only. | serialization/client unit test |
| M0-22 | Add health repository implementation with configurable base URL. | MockWebServer test |
| M0-23 | Wire Home build label to backend health state without adding another screen. | Compose/repository test; assemble |
| M0-24 | Add one `scripts/verify-m0.sh` orchestration script that invokes existing checks without hidden setup. | script exits 0 |
| M0-25 | Finish exact Linux build/test/APK/adb instructions and troubleshooting. | follow commands in a clean shell |
| M0-26 | Run M0 evidence commands and commit only the evidence record/current milestone update. | M0 checklist reviewed |

## 3. M1 queue — contracts and domain primitives

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M1-P01 | Add normative `remanence_v1.proto` field numbers matching `protocol.md`. | protobuf lint/compile |
| M1-P02 | Configure protobuf-lite generation for Android model/crypto consumers. | generated-source compile |
| M1-P03 | Add typed UUID domain wrappers and strict REST/protobuf conversions. | valid/invalid conversion tests |
| M1-P04 | Add normalized handle value object and shared fixture cases. | normalization/boundary tests |
| M1-P05 | Add protocol v1 limits/constants in one model object. | boundary tests |
| M1-P06 | Add artifact kind/ordinal/cardinality validator. | 3/5 valid; 2/6/duplicate invalid tests |
| M1-P07 | Add deterministic publish-statement builder/sorter. | fixed-byte fixture test |
| M1-P08 | Add deterministic AAD/context encoder. | fixed-byte fixture test |

## 4. M1 queue — server accounts and directory

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M1-S01 | Add `users` SQLAlchemy model only. | metadata constraint test |
| M1-S02 | Add `auth_credentials` model only. | metadata/FK test |
| M1-S03 | Add `auth_sessions` model only. | expiry/hash/lineage fields test |
| M1-S04 | Add `user_key_bundles` model with one-active partial uniqueness. | constraint test |
| M1-S05 | Add Alembic revision for account/auth/key tables. | upgrade/downgrade/upgrade |
| M1-S06 | Add server handle normalization/validation service. | shared fixture tests |
| M1-S07 | Add Argon2id password hash/verify service. | valid/wrong/rehash tests |
| M1-S08 | Add opaque access/refresh token generator and hash function. | entropy/format/no-plaintext-persist unit tests |
| M1-S09 | Add session creation/expiry repository methods. | DB integration test |
| M1-S10 | Add refresh rotation and lineage-reuse detection service. | replay integration test |
| M1-S11 | Add public key-bundle structural validator with exact suite/version. | malformed/private-material rejection tests |
| M1-S12 | Add registration request/response schemas and redacted validation errors. | schema tests |
| M1-S13 | Add atomic registration service. | duplicate email/handle rollback test |
| M1-S14 | Expose registration endpoint only. | endpoint integration tests |
| M1-S15 | Add login service/endpoint only. | success/generic failure tests |
| M1-S16 | Add refresh endpoint only. | rotation/replay endpoint tests |
| M1-S17 | Add bearer authentication dependency using opaque access-token hash. | valid/expired/revoked tests |
| M1-S18 | Add logout endpoint only. | repeated logout test |
| M1-S19 | Add `/me` endpoint only. | authorization/redaction test |
| M1-S20 | Add handle change endpoint with atomic normalized uniqueness. | mutation/relationship-stability test |
| M1-S21 | Add handle-directory endpoint returning active public bundle. | found/not-found/no-email tests |
| M1-S22 | Add immutable public key-bundle-by-ID endpoint including retired/revoked status. | historical lookup/no-private-material tests |

## 5. M1 queue — Android identity, auth, and local data

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M1-A01 | Register required Tink AEAD/hybrid/signature primitives behind one initializer. | primitive smoke test |
| M1-A02 | Add Android Keystore AES-256-GCM KEK creation/load boundary. | instrumented roundtrip when device/emulator available; JVM contract test |
| M1-A03 | Add versioned wrapped-keyset file format and parser bounds. | malformed/roundtrip tests |
| M1-A04 | Add private keyset wrap/unwrap implementation through KEK. | process-reload roundtrip test |
| M1-A05 | Generate independent HPKE and Ed25519 account keysets and public export. | suite/public-private separation tests |
| M1-A06 | Add identity bundle repository that refuses silent replacement when missing. | missing/recovery-required tests |
| M1-A07 | Add secure opaque session-token storage boundary. | store/load/clear test |
| M1-A08 | Add Room database shell and `local_account` entity/DAO. | Room migration/DAO test |
| M1-A09 | Add incoming capsule/envelope entities and DAO only. | upsert/idempotency test |
| M1-A10 | Add blob-cache entities and DAO only. | state transition test |
| M1-A11 | Add outbox capsule/blob entities and DAO only. | transition/cardinality tests |
| M1-A12 | Add fingerprint entity and DAO with no enumerable UI projection. | origin/preferred constraint test |
| M1-A13 | Add sync-cursor entity and replay-safe update. | cursor DAO test |
| M1-A14 | Add first complete Room schema version and migration test. | create/export/reopen test |
| M1-A15 | Add auth/register API DTO and client call. | MockWebServer contract test |
| M1-A16 | Add login/refresh/logout API calls. | MockWebServer contract tests |
| M1-A17 | Add serialized refresh interceptor/authenticator with one retry. | concurrent 401 test |
| M1-A18 | Add directory lookup API call and mapper to domain IDs/key bundle. | contract/no-email test |
| M1-A19 | Add registration use case: wrap identity before network registration. | fake-repository ordering/failure test |
| M1-A20 | Add login use case with `RECOVERY_REQUIRED` state on missing keys. | state test |
| M1-A21 | Add historical public key-bundle-by-ID API call for signature verification. | active/retired/revoked contract tests |

## 6. M1 queue — authentication UI and recipient confirmation

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M1-U01 | Add auth navigation state and route guards, no capsule routes. | navigation unit test |
| M1-U02 | Add registration form fields/validation without network wiring. | Compose tests |
| M1-U03 | Wire registration form to registration use case and errors. | ViewModel test |
| M1-U04 | Add login form and wire login/recovery-required states. | Compose/ViewModel tests |
| M1-U05 | Enable Home Create/Scan only for authenticated crypto-ready account. | state tests |
| M1-U06 | Add recipient handle input/lookup state. | ViewModel tests |
| M1-U07 | Add explicit resolved-recipient confirmation showing handle and stable account cue. | Compose tests |
| M1-U08 | Persist confirmed immutable recipient/key snapshot only for current create session. | process/state test |

## 7. M1 queue — capture and fingerprint extraction

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M1-R01 | Add versioned `mvp-orb-v1` profile asset/parser with every documented threshold. | exact parse/default rejection tests |
| M1-R02 | Add bounded fingerprint binary/protobuf schema/parser. | roundtrip/malformed length tests |
| M1-R03 | Add EXIF orientation and bounded bitmap decode helper. | fixture orientation tests |
| M1-R04 | Add four-corner ordering/validation math. | rotation/self-intersection tests |
| M1-R05 | Add postcard contour candidate detector. | fixed fixture tests |
| M1-R06 | Add detector ranking/confidence output. | multi-contour fixture tests |
| M1-R07 | Add manual four-corner crop model/validation, no UI yet. | bounds/convexity tests |
| M1-R08 | Add perspective warp preserving aspect at canonical long edge. | golden geometry test |
| M1-R09 | Add blur/exposure/glare measurement. | synthetic image tests |
| M1-R10 | Add quality reason classification from profile. | exact threshold tests |
| M1-R11 | Add grayscale/CLAHE ORB extraction and keypoint deduplication. | deterministic fixture property test |
| M1-R12 | Add normalized fingerprint serialization. | size/coordinate roundtrip test |
| M1-R13 | Add CameraX permission/preview shell for one still. | assemble plus UI state test |
| M1-R14 | Add still capture result into bounded normalization pipeline. | fake capture test |
| M1-R15 | Add crop-confirm/manual-corner Compose surface. | Compose test |
| M1-R16 | Add quality failure guidance surface by reason code. | Compose parameterized test |
| M1-R17 | Add create-session front capture/persist encrypted fingerprint. | repository integration test |
| M1-R18 | Add prepared-back checklist gate. | state/Compose test |
| M1-R19 | Add create-session back capture/persist encrypted fingerprint. | ordering/cleanup test |

## 8. M1 queue — capsule content and cryptography

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M1-C01 | Add Android Photo Picker selection state enforcing 3–5 images. | state tests |
| M1-C02 | Add one-photo orientation/EXIF-strip/resize/JPEG normalizer. | metadata/size fixture tests |
| M1-C03 | Add bounded sequential photo staging pipeline. | 3/5 success, overflow/failure cleanup tests |
| M1-C04 | Add optional note editor with UTF-8 byte limit. | multibyte boundary tests |
| M1-C05 | Add fresh Tink AES256_GCM capsule keyset generator. | uniqueness/roundtrip test |
| M1-C06 | Add one-artifact AEAD encrypt/decrypt using canonical AAD. | wrong-field rejection tests |
| M1-C07 | Build/encrypt recognition manifest from staged fingerprints/hint. | decrypt/field test |
| M1-C08 | Build/encrypt content manifest from note/photo entries; track absent. | decrypt/cardinality test |
| M1-C09 | Encrypt one normalized photo and calculate ciphertext binding. | roundtrip/hash test |
| M1-C10 | Add bounded loop that encrypts 3–5 photos one at a time. | memory/order/cleanup test |
| M1-C11 | Build/sign deterministic publish statement. | golden signature test |
| M1-C12 | Create/open HPKE recipient envelope with exact context. | wrong recipient/context test |
| M1-C13 | Verify statement/IDs/hashes/signature before artifact decrypt. | substitution tests |
| M1-C14 | Add high-level capsule staging transaction writing ciphertext-only outbox. | plaintext-canary/local failure test |

## 9. M1 queue — local matching and scan gate

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M1-M01 | Add Hamming KNN ratio and reverse-mutual matcher. | fixed descriptor tests |
| M1-M02 | Add normalized homography/RANSAC inlier report. | synthetic transform/outlier tests |
| M1-M03 | Add spatial hull/grid coverage calculation. | clustered/distributed tests |
| M1-M04 | Add homography plausibility gates. | reflection/skew/degenerate tests |
| M1-M05 | Add weak/strong side score from profile. | exact report-to-score tests |
| M1-M06 | Add front top-five and duplicate-group ranking. | ranking/margin tests |
| M1-M07 | Add back/composite automatic acceptance. | threshold/margin tests |
| M1-M08 | Add no-match/retry/plausible-chooser classification. | fail-safe test matrix |
| M1-M09 | Add recipient-first then sender-fallback coordinator. | ordering tests |
| M1-M10 | Add process-memory `ScanGrantManager` with expiry/consume. | fake-clock tests |
| M1-M11 | Add Scan front/back capture session reusing capture components. | state tests |
| M1-M12 | Wire local candidate matching and retry state. | ViewModel tests |
| M1-M13 | Add scan-scoped ambiguity chooser with minimal hints only. | Compose/no-gallery tests |
| M1-M14 | Gate capsule route by grant ID and verified crypto result. | navigation bypass tests |
| M1-M15 | Add bounded fullscreen 3–5 photo/note presentation and cleanup. | Compose/state cleanup test |
| M1-M16 | Create preferred recipient fingerprint after verified self receipt. | origin/preferred persistence test |
| M1-M17 | Prove process restart requires rescan while ciphertext/key records survive. | instrumentation/manual evidence |

## 10. M2 queue — server capsule routing

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M2-S01 | Add `capsules` SQLAlchemy model/state constraints. | metadata tests |
| M2-S02 | Add `capsule_blobs` model/cardinality uniqueness. | constraint tests |
| M2-S03 | Add recipient envelope and delivery-state models. | FK/uniqueness tests |
| M2-S04 | Add idempotency-record model. | scope/expiry test |
| M2-S05 | Add Alembic capsule/upload/delivery migration. | upgrade/downgrade/upgrade |
| M2-S06 | Add capsule draft request validator. | protocol limit/cardinality tests |
| M2-S07 | Add idempotent draft-create service. | replay/conflict tests |
| M2-S08 | Expose draft-create endpoint with sender derived from auth. | authorization tests |
| M2-S09 | Add streamed blob temporary write/hash verifier. | wrong size/hash cleanup tests |
| M2-S10 | Add atomic blob promotion/state service. | identical/conflicting replay tests |
| M2-S11 | Expose blob PUT endpoint with limits. | endpoint streaming/auth tests |
| M2-S12 | Add deterministic publish-statement parser/declaration comparator. | malformed/mismatch tests |
| M2-S13 | Add public Ed25519 statement verification. | wrong key/signature tests |
| M2-S14 | Add finalize service transaction. | missing blob/stale key/rollback tests |
| M2-S15 | Expose idempotent finalize endpoint. | replay/conflict tests |
| M2-S16 | Add draft abort/expiry garbage-collection service. | ready protection/unreferenced cleanup test |
| M2-S17 | Add incoming cursor query restricted to recipient. | pagination/cross-user tests |
| M2-S18 | Expose incoming endpoint with redacted route-only DTO. | no-private-fields contract test |
| M2-S19 | Expose authorized blob GET endpoint. | sender-draft/recipient-ready/unrelated matrix |
| M2-S20 | Add material-synced transition with no sender query. | idempotency/privacy test |

## 11. M2 queue — Android upload, sync, and two-user flow

| ID | Single outcome | Minimum verification |
| --- | --- | --- |
| M2-A01 | Add draft-create API client/repository mapping. | MockWebServer contract test |
| M2-A02 | Add one-blob idempotent upload call with length/hash headers. | retry/header test |
| M2-A03 | Add finalize API call and stale-key mapping. | contract test |
| M2-A04 | Add per-capsule WorkManager upload chain. | worker state test |
| M2-A05 | Make upload worker skip already stored blobs after restart. | process/repository test |
| M2-A06 | Add stale-recipient re-resolve/re-envelope/re-sign transition. | no-photo-reencrypt test |
| M2-A07 | Add current-send publish progress/errors only. | Compose/no-history test |
| M2-A08 | Add incoming cursor API client/repository upsert. | page replay test |
| M2-A09 | Add authenticated incoming WorkManager chain. | unique-work/account-scope test |
| M2-A10 | Download/verify/open envelope and recognition ciphertext only. | wrong key/hash/context test |
| M2-A11 | Re-encrypt sender fingerprints into local index with no UI projection. | plaintext/index privacy test |
| M2-A12 | Add on-demand content/photo ciphertext downloader. | authorization/hash/cache test |
| M2-A13 | Wire first-receipt scan to pending sender candidates. | two-candidate coordinator test |
| M2-A14 | Persist delivered recipient pair only after verified automatic result. | order test |
| M2-A15 | Require explicit physical-card confirmation before persisting manual-result pair. | state test |
| M2-A16 | Prefer recipient pair on later scans and fall back only on no weak evidence. | ordering regression test |
| M2-A17 | Add offline cached-content open after successful scan. | network-disabled test |
| M2-A18 | Add clear connectivity-required state when content is absent. | UI/state test |
| M2-A19 | Add duplicate-front/different-back end-to-end fixture. | auto-or-chooser integration test |
| M2-A20 | Add unknown-postcard end-to-end fixture. | no-random-candidate test |
| M2-A21 | Run automated M2 verification and plaintext-canary inspection. | evidence record |
| M2-A22 | Run/document two-device physical scenario when devices are available. | signed manual checklist with APK/commit/device IDs |

## 12. Review and correction tasks

Review findings are not bundled. Each correction becomes a new task shaped as:

```text
FIX-<review>-<n>
Problem: one observed deviation with file/commit evidence.
Required behavior: one architecture/acceptance reference.
Allowed changes: minimal files.
Regression proof: one command/test.
Commit: fix(<area>): <single correction>
```

If a correction requires changing architecture rather than conforming to it, implementation stops. Codex writes/reviews an ADR first; Grok implements only after approval.
