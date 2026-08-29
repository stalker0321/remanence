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

## 10. M2 rebaseline gates and prerequisite queue

M2 is based on the completed M1 mechanisms, not on the pre-M1 design
assumptions. Server-only work may proceed while the M1 hardware smoke is
pending. Any Create/Scan integration commit must wait for that smoke result or
incorporate its fixes. Email invitations are a future M2.x concern described
in ADR-009; M2 implements existing-account recipients only.

Each row is one implementation-agent assignment and should normally fit one
short cycle. Dependencies are explicit so a worker never designs the next
layer implicitly.

| ID | Depends on | Single outcome | Minimum verification |
| --- | --- | --- | --- |
| M2-P01 | rebrand baseline | Inventory actual M1 outbox/incoming/crypto/state components and map old M2 tasks to reuse/generalize/delete. | checked inventory; no parallel-component proposal |
| M2-P02 | P01 | Add account-owner ID to incoming/outbox/blob/fingerprint/cursor domain and Room schema. | migration plus A/B DAO isolation tests |
| M2-P03 | P02 | Require account scope on every DAO/index/outbox query used by M2. | A logout/B login returns zero A rows/candidates |
| M2-P04 | P02 | Define account-scoped file roots and safe retention/purge policy. | cross-account path and cleanup tests |
| M2-P05 | P02 | Add account-scoped WorkManager naming/tag/cancellation contract. | logout/account-switch worker cancellation test |
| M2-P06 | P01 | Generalize the existing same-account publisher request/class for distinct sender/recipient without changing crypto framing. | self-send golden unchanged; distinct-ID AAD/statement/envelope test |
| M2-P07 | P06 | Remove the Create self-recipient guard and feed the confirmed immutable recipient snapshot into the generalized publisher. | wrong/stale/unconfirmed recipient transition tests |
| M2-P08 | P06 | Add sender-owned durable wrapped capsule-key retry material to the outbox. | wrong account/key/AAD fails; no plaintext storage canary |
| M2-P09 | P08 | Delete sender retry material only on published/abort/terminal cleanup. | process restart and lifecycle cleanup tests |
| M2-P10 | P01 | Extract one canonical statement/layout/ID verification core from `CapsuleAcceptanceGate`. | existing malformed/golden matrix remains green |
| M2-P11 | P10 | Add control/index acceptance for envelope plus one delivered recognition blob while treating other bindings as declarations. | missing photos accepted only for index; recognition mismatch rejects |
| M2-P12 | P10 | Add full presentation acceptance requiring every declared content/photo blob before plaintext. | missing/substituted blob and partial-plaintext rejection tests |
| M2-P13 | P11,P12 | Pin local/server material-state semantics and legal compare-and-set transitions. | exhaustive transition table test |
| M2-P14 | P01 | Record M1 physical CameraX/OpenCV smoke result and required corrections. | device/APK/commit evidence or explicit pending integration gate |

**Checkpoint P:** review P01–P13 together before server/Android integration.

## 11. M2 queue — server capsule routing

| ID | Depends on | Single outcome | Minimum verification |
| --- | --- | --- | --- |
| M2-S01 | P01 | Add `capsules` SQLAlchemy model with ownership, expiry, and state constraints. | PostgreSQL metadata/constraint tests |
| M2-S02 | S01 | Add `capsule_blobs` declaration model and uniqueness constraints. | PostgreSQL cardinality/ordinal tests |
| M2-S03 | S01 | Add the single M2 recipient-envelope model. | owner/key FK and one-envelope tests |
| M2-S04 | S01 | Add recipient delivery-state model; no open/scan fields. | privacy/uniqueness tests |
| M2-S05 | S01 | Add scoped idempotency-record model and expiry index. | scope/hash/expiry tests |
| M2-S06 | S01-S05 | Add one Alembic capsule/upload/delivery migration. | PostgreSQL upgrade/downgrade/upgrade |
| M2-S07 | S06 | Add bounded draft request/parser validation for existing-user target only. | limits/cardinality/unknown-target tests |
| M2-S08 | S07 | Add idempotent draft-create service with sender from auth. | replay/conflict/cross-user tests |
| M2-S09 | S08 | Expose draft-create endpoint and redacted errors. | authorization/API contract tests |
| M2-S10 | S06 | Add streaming temporary-object writer with actual byte cap and SHA-256. | oversized/truncated/hash-failure cleanup tests |
| M2-S11 | S10 | Add idempotent object promotion plus blob-state compare-and-set. | identical/conflicting replay and failure injection |
| M2-S12 | S11 | Expose authenticated blob PUT with required headers. | stream/auth/content-length tests |
| M2-S13 | P10,S06 | Parse bounded canonical publish statements and compare exact declarations. | malformed/depth/size/mismatch corpus |
| M2-S14 | S13 | Resolve authoritative sender/recipient bundles and verify Ed25519 signature. | wrong owner/key/signature; RETIRED sender/non-ACTIVE recipient matrix |
| M2-S15 | S14,S12 | Add finalize PostgreSQL transaction; promotion orphans remain GC-safe ciphertext. | missing blob/stale key/rollback tests |
| M2-S16 | S15 | Expose idempotent finalize endpoint. | replay/finalize-conflict tests |
| M2-S17 | S16 | Add draft abort and expiry/unreferenced-object GC service. | READY protection and orphan cleanup test |
| M2-S18 | S16 | Add recipient-only opaque cursor query. | stable pagination/page replay/cross-user tests |
| M2-S19 | S18 | Expose route-only incoming DTO with no private/display fields. | response allow-list contract test |
| M2-S20 | S16 | Expose recipient-only READY blob GET. | recipient/unrelated/draft authorization matrix |
| M2-S21 | S20 | Add idempotent `CIPHERTEXT_SYNCED` transition after full material cache only. | no sender query and no scan/open timestamp tests |

**Checkpoint S:** review authoritative-key finalize, storage failure injection,
and authorization matrix before Android upload work consumes the API.

## 12. M2 queue — Android upload, sync, and two-user flow

| ID | Depends on | Single outcome | Minimum verification |
| --- | --- | --- | --- |
| M2-A01 | S09,P02 | Add draft-create client/repository mapping with existing-user target. | MockWebServer request/error contract |
| M2-A02 | S12 | Add one-blob idempotent upload call with length/hash headers. | retry/header/body test |
| M2-A03 | S16 | Add finalize client and structured stale-key mapping. | MockWebServer contract test |
| M2-A04 | P05,A01-A03 | Add one account/capsule-scoped upload worker and legal state CAS. | worker account/state test |
| M2-A05 | A04 | Resume after restart by reconciling server blob states and skipping STORED blobs. | process/repository replay test |
| M2-A06 | P08,A03 | On stale recipient key, re-resolve and recover K from sender retry material. | restart + stale-key recovery test |
| M2-A07 | A06 | Re-envelope and re-sign without changing artifact ciphertext. | byte-identical artifact/hash regression test |
| M2-A08 | A04,P14 | Render current-send progress and recoverable/terminal errors only. | Compose state/no-history test |
| M2-A09 | S19,P02 | Add account-scoped incoming cursor page upsert. | atomic page replay/cursor policy test |
| M2-A10 | A09,P05 | Complete: bounded authenticated account-scoped incoming page worker, authenticated `KEEP` scheduling, and foreground/resume/restart/logout lifecycle boundaries. | page-loop, high-watermark, unique-work, foreground, logout/account-switch tests |
| M2-A11a | A09,S20 | Add authenticated recipient ciphertext blob GET with exact transport headers, bounded streaming, and fresh temporary-file cleanup. | MockWebServer header/stream/hash/cleanup matrix |
| M2-A11 | A11a,A09,S20 | Download and perform control/index acceptance on envelope + recognition blob. | wrong bundle/signature/hash/context matrix |
| M2-A11b | A11a,A09,M2-P11 | Accept one owner/capsule-scoped incoming control/index record through the existing P11 gate, including envelope HPKE open and one transport-verified recognition ciphertext. | real Room/crypto owner, context, sender-trust, hash, recognition, cancellation, and redaction matrix |
| M2-A11c1 | A11b | Add the reusable owner/capsule/blob-scoped, crash-safe atomic adoption primitive for one verified recognition ciphertext temp file. | bounded re-read/hash, no-follow roots, atomic no-overwrite adoption, retry/concurrency/redaction matrix |
| M2-A11c2 | A11c1 | Adopt the A11b verified control/index result and recognition ciphertext into account-scoped Room state; verify the A11c1 file as a preflight, then use one owner-scoped Room transaction for blob CACHED plus capsule INDEX_CACHED (filesystem and Room are not one atomic domain). | durable file adoption followed by owner/CAS blob CACHED and capsule INDEX_CACHED tests |
| M2-A11d1 | A11a,A11b,A11c1,A11c2,A12b | Compose one already-discovered capsule: revalidate the authenticated owner and owner-scoped READY/DISCOVERED declaration, download recognition ciphertext to a deterministic owner TEMP file, require A11b Verified, require A12 durable encrypted fingerprint/hint persistence, then adopt through A11c1 and commit through A11c2; no scheduling, page-loop, content prefetch, or plaintext persistence here. | exact crypto → A12 persistence → adoption → Room order, account-switch, cleanup, retry, cancellation, idempotent replay, redaction, and real-file/Room stitch tests |
| M2-A12a | A11b | Define the canonical local sender index bundle plaintext/codec and stage one owner/capsule-bound sealed ciphertext file with crash-safe no-replace/idempotent replay semantics; no Room activation or A11d1 wiring. | deterministic codec, limits, AAD, randomized-sealer replay, no-follow storage, durability, concurrency, cleanup, and plaintext-canary tests |
| M2-A12b | A12a,A11d1 | Implement the mandatory A12 persistence port over A12a, returning durable only after the account-bound encrypted bundle is staged; only then may A11d1 adopt and advance A11c2 state. | exact A11b → A12a → A11c1 → A11c2 order, failure/process-death/account isolation, and no plaintext/index activation before durable staging |
| M2-A13 | P12,S20 | Prefetch/cache every assigned capsule's remaining content/photo ciphertext by default, before scan, and verify transport bindings without decrypting content. Ciphertext download may precede envelope availability. | authorization/hash/restart/no-pre-scan-plaintext tests |
| M2-A14 | A13,S21 | Mark `CIPHERTEXT_SYNCED` only after all required blobs are durable and hash-checked. | index-only never acknowledges test |
| M2-A15 | A12,P14 | Feed only account-scoped verified sender candidates into the existing Scan flow. | two-candidate coordinator/no-list test |
| M2-A16 | A15 | Persist recipient pair after verified automatic result. | generation/order test |
| M2-A17 | A15 | Require explicit physical-card confirmation after a plausible manual chooser result. | state/confirmation test |
| M2-A18 | A16,A17 | Prefer recipient pair later and retain sender fallback only under documented weak-evidence rule. | ordering regression test |
| M2-A19 | A13,A15 | After current scan, run full presentation acceptance and only then issue the presentation grant. | bypass/partial-material/content-AEAD tests |
| M2-A20 | A19 | Open on the first or any later successful scan entirely from prefetched ciphertext; network fetch is fallback only when prefetch is incomplete. | first-scan and repeat-scan network-disabled/process-restart tests |
| M2-A21 | A19 | Show connectivity-required state when matched content is absent, with zero partial plaintext. | Compose/state test |
| M2-A22 | A15 | Add duplicate-front/different-back end-to-end fixture. | automatic-or-plausible-chooser test |
| M2-A23 | A15 | Add unknown-postcard fixture. | no-random-candidate test |
| M2-A24 | all automated | Run PostgreSQL, BlobStore, Android, crypto, replay, account-switch, and plaintext-canary verification. | evidence record |
| M2-A25 | A24,P14 | Run two-device existing-account physical transfer and later offline scan. | signed APK/commit/device checklist |

M2-A09 adds the authenticated incoming cursor-page transport and the
owner-scoped Room page commit boundary. The client validates the complete
ciphertext-only response, preserves the server's explicit `has_more` loop
signal and opaque cursor, and
commits routed capsule metadata, one recipient envelope, and declared blob
metadata (initially `DOWNLOADING`) atomically. Exact page replays preserve
existing local cache state and reject immutable identity, binding, or path
mismatches; account/session changes and any failed preflight or database write
leave the page and cursor unchanged. Signature, HPKE, and control-payload
verification remain later A11 work. Rows migrated from v5 have both incoming
statement-digest and publish-signature fields empty; a complete later page may
fill both exactly once only when every other immutable capsule binding matches
under the same owner. This completion does not advance local material state;
A11 must independently verify the completed material before any state advance
or plaintext handling.

M2-A10b wires the existing authenticated page worker through one
account-scoped `enqueueUniqueWork` chain with `KEEP`, `CONNECTED`, and bounded
exponential backoff. Authenticated root resolution resumes outbox uploads
first, then enqueues incoming sync after the current owner is revalidated;
M2-A10c adds the single Compose `ON_RESUME` bridge to rerun that same
authenticated resolution. Cold start, session establishment, foreground,
process reconstruction, and logout/account-switch ordering are covered by
the root, WorkManager, and account-cancellation evidence; no periodic or
parallel scheduler is introduced.

### M2-A06 bounded recovery notes

The Android stale-recipient recovery writes replacement envelope, statement,
and signature files beneath the owner outbox root before its owner-guarded
Room CAS, then best-effort deletes only the three superseded recipient-facing
files after the new paths are committed. It never deletes blob files or sender
retry material. A process death before the CAS, or after the CAS and before
that best-effort deletion, can leave ciphertext-only orphan files: current
A05 discovery returns capsule IDs and has no orphan-path sweep. A bounded
follow-up must add an owner-scoped orphan cleanup/reconciliation mechanism
before claiming crash-proof superseded-file cleanup; this A06 task does not
add a schema or discovery redesign.

M2 has no sender key-rotation flow (the protocol reserves rotation endpoints
for M4). Accordingly, the current Android loader requires the live active
sender bundle to match the persisted capsule sender bundle and parks stale
recovery when the historical signing key is unavailable. A future rotation
recovery task must unwrap retry material with the historical sender bundle ID
in its AAD, then re-sign with the current active sender key and update the
statement sender bundle ID, subject to the server contract; it must not retain
retired signing private keys solely for this recovery.

Stale recovery also preserves its origin phase: draft-origin parking re-enters
ENCRYPTED, while finalize-origin parking remains FINALIZING. The server binds
the draft idempotency key to the original request SHA, so a finalize-origin
replay must not call createDraft with a changed recipient bundle; it must
replay finalize against the existing server draft. Replacement files are
marked adopted before best-effort superseded-file cleanup, and ambiguous DAO
completion preserves possible winners; the bounded orphan sweep follow-up
must handle leftovers from those crash/uncertain-completion windows.

The production stale markers are `RECIPIENT_KEY_STALE_DRAFT` and
`RECIPIENT_KEY_STALE_FINALIZE`. A first wire stale response returns a
retryable worker outcome so A06 gets its recovery invocation; a later recovery
failure is parked as `RecipientKeyStale` and can be rediscovered by the
owner-scoped activation resumer. The generic `RECIPIENT_KEY_STALE` marker was
only present in unreleased intermediate commits, is explicitly fail-closed,
and is not a released database contract.

**Checkpoints A:** review after upload A01–A08, incoming index A09–A12,
presentation A13–A21, and final two-device evidence. Do not review every
single implementation commit.

## 13. Future M2.x — email invitation (architecture only)

ADR-009 records the `RecipientTarget.ExistingUser | PendingEmail` seam. No M2
task implements `PendingEmail`. Before M2.x work, an ADR must choose reserved
future `user_id` versus a new protocol version, specify sender-job target
commitment verification, and threat-model longer-lived sender envelopes,
provider identity, email privacy, expiry, abuse, and eventual delivery.

## 14. Review and correction tasks

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

## 15. Pre-release recovery milestone

ADR-010 is the normative starting point. This queue begins after the M2
two-user product proof and must pass before public release. Platform API names
are deliberately absent from protocol tasks until the compatibility spike
selects adapters from observed capabilities.

| ID | Depends on | Single outcome | Minimum verification |
| --- | --- | --- | --- |
| REC-01 | M2 | Threat/compatibility spike for Android restore, credential providers, passkey/WebAuthn PRF, Apple/iCloud, browser/PWA, and migration. | primary-source matrix plus device/browser probes; no assumed secret-export capability |
| REC-02 | REC-01 | Define versioned RecoveryPackage, ARK wrapper, generation/rollback, device, and recovery-method contracts. | ADR/protocol vectors and backend-has-no-unwrap-secret proof |
| REC-03 | REC-02 | Generate ARK and protect it with a per-installation device key independently from account HPKE/signing keysets. | reinstall/lost-device fixtures; DeviceKey != ARK invariant |
| REC-04 | REC-02 | Persist encrypted recovery packages and multiple opaque wrappers on the server. | authz, tamper, rollback, deletion, and plaintext-canary tests |
| REC-05 | REC-03,REC-04 | Implement the selected Android recovery adapter with capability negotiation and fallback. | physical reinstall and replacement-device recovery |
| REC-06 | REC-03,REC-04 | Implement authenticated existing-device enrollment/transfer and device revocation. | A-to-B, Android-to-other-platform harness, replay/MITM/revocation tests |
| REC-07 | REC-04 | Add optional random manual recovery secret wrapper. | wrong/checksum/bruteforce-boundary tests; never mandatory onboarding |
| REC-08 | REC-05,REC-06 | Restore account key history, encrypted prefetch, and recognition index on a fresh installation. | old physical postcards open offline after recovery |
| REC-09 | REC-05..REC-08 | Add recovery-readiness UX and explicit unrecoverable-state policy. | no silent unprotected account; accessibility/error tests |
| REC-10 | all REC | Independent security review and destructive recovery drill. | lost device/provider/all-secrets matrices with expected fail-safe outcomes |
