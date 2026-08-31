# Milestones

Status: **APPROVED.**

Milestones are vertical gates, not calendars. Grok receives only one atomic implementation task at a time; no task may mean “implement a milestone/module/feature.” A milestone advances only when its acceptance criteria pass and the supervisor records the reviewed commit.

## Architecture Gate — Approved engineering package

Goal: remove design decisions from the implementation agent.

Deliverables:

- normative product scope and non-goals;
- system/module/storage/sync architecture;
- threat model and exact cryptographic construction;
- canonical protocol/API/database/state contracts;
- versioned local recognition/scoring pipeline;
- milestone and pass/fail criteria;
- atomic implementation queue and supervisor state format.

No application source, build dependency, migration, or API implementation starts before this gate.

## M0 — Reproducible foundation

Goal: prove the Android and backend toolchains work headlessly and can communicate.

Scope:

- Linux SDK/JDK/Python/container setup documentation;
- Gradle wrapper and five Android modules;
- installable minimal Compose APK;
- FastAPI package, settings, health endpoint, locked dependencies;
- PostgreSQL connection and Alembic baseline;
- local filesystem `BlobStore` interface/adapter skeleton;
- Docker Compose backend stack;
- Android configurable API base URL and typed health call;
- unit-test infrastructure and CI-equivalent local commands.

Not in M0: accounts, keys, CameraX, OpenCV behavior, capsule endpoints, UI polish.

## M1 — Single-account real local vertical slice

Goal: one account exercises real account, crypto, capture, fingerprint, persistence, process restart, and scan-gated presentation without fake mechanisms.

Scope:

- real registration/login/opaque sessions and unique handle;
- account HPKE/signing identity generated client-side and Keystore-wrapped;
- initial server schema for users/sessions/key bundles;
- Android Room schema and secure session/key storage;
- self-recipient handle resolution through the real directory;
- explicit confirmation bound to immutable user/key IDs;
- CameraX front/back still capture and manual crop fallback;
- capture normalization, ORB fingerprint extraction, encrypted local fingerprint storage;
- Android Photo Picker for exactly 3–5 normalized photos and optional note;
- real capsule keyset, AEAD artifacts, publish statement/signature, self envelope;
- encrypted local outbox and blob-granular retry machinery;
- local scan matching, crypto verification, ten-minute memory-only scan grant;
- close/process restart followed by required rescan and fullscreen presentation.

M1 may route ciphertext through the real local backend, but it is intentionally the same user on both ends. Multi-user assumptions (UUID routing/key IDs/envelopes) must already be real.

## M2 — Two-user physical transfer

Goal: demonstrate the product north star end to end with two real accounts/installations and one physical postcard.

Entry conditions and sequencing:

- M2 extends the reviewed M1 publisher/outbox/crypto/scan components; it does
  not introduce parallel implementations.
- Server schema, storage, and API work may run while M1 hardware evidence is
  pending.
- Create/Scan integration is not accepted until the M1 physical CameraX/OpenCV
  smoke result is recorded and any resulting fixes are incorporated.
- Local incoming/outgoing/index state and background work are account-scoped
  before a second account is allowed through the flow.
- Sender-owned wrapped capsule-key retry material exists before
  `RECIPIENT_KEY_STALE` recovery is implemented.
- Control/index acceptance and full presentation acceptance are distinct
  fail-closed stages sharing one canonical statement verifier.

Scope:

- sender resolves/explicitly confirms another user;
- capsule draft/blob/finalize API and storage authorization;
- incoming cursor sync and encrypted recognition-index bootstrap;
- recipient first-receipt local matching against sender fingerprints;
- ambiguity chooser for plausible candidates only;
- recipient envelope open, signature/AEAD verification, content download/cache;
- fullscreen capsule and recipient delivered fingerprint creation;
- later scan prefers recipient fingerprint and falls back to sender fingerprint;
- default ciphertext-only prefetch for every assigned capsule, with offline first and later open after a fresh scan;
- no gallery/inbox/history/deep-link path.

M2 addresses only an existing registered recipient with a non-null immutable
user ID and active key bundle. Handles remain required in this milestone.
Email-addressed pre-registration invitations are reserved for M2.x and must
not appear as tables, endpoint stubs, or incomplete UI in M2.

M2 is the first complete product proof. It is not a claim of public-release recognition/security hardening.

## M2.x — Email-addressed invitation (deferred)

Goal: let the physical postcard introduce Remanence to a recipient who did not
have an account when the capsule was created, without a postcard secret,
server key escrow, or sender approval after verified registration.

This milestone starts only after M2. ADR-009 records the extension seam and a
critical unresolved protocol choice: protocol v1 binds `recipient_user_id` in
artifact AAD, so the system must either reserve the pending UUID as the exact
future user ID or introduce a new protocol version with a stable recipient
target ID. “Add an envelope later” alone is not sufficient.

Future scope includes verified provider identity, email privacy/expiry/abuse
controls, optional-handle onboarding, a locally authenticated pending-target
commitment, a persistent sender key-delivery queue, polling-based eventual
delivery, and push only as an accelerator.

## M3 — Recognition hardening

Goal: tune the fail-safe vision system against reproducible data rather than intuition.

Scope:

- consented/synthetic physical-postcard dataset;
- duplicate printed fronts with distinct backs;
- real or controlled postal modifications;
- low light, perspective, rotation, crop, shadow, glare, blur, occlusion, dirt, wear;
- locked instance/design-separated evaluation split;
- threshold/profile tuning and performance measurements;
- optional ORB-versus-SIFT experiment only if ORB misses the agreed gate;
- captured match reports and regression fixtures without private user data.

## M4 — Durable E2EE identity and account recovery

Goal: make reinstall, device replacement, and cross-platform migration normally
recoverable without giving the backend an E2EE escrow secret.

Scope:

- ADR-010 Account Recovery Key and versioned RecoveryPackage protocol;
- device-local ARK wrapping distinct from long-term account keysets;
- encrypted server-held recovery packages and multiple RecoveryWrappers;
- registered-device enrollment, existing-device transfer, and revocation;
- Android default recovery adapter selected only after a compatibility/security spike;
- iOS and browser/PWA adapter design proving the wire protocol is platform-neutral;
- capability negotiation and fallback when passkey/WebAuthn PRF is unavailable;
- optional random manual recovery secret;
- recovery-readiness UX and explicit unrecoverable-state warning;
- reinstall, lost-device, same-platform, cross-platform, rotation, rollback,
  provider-loss, and total-secret-loss tests;
- recovery followed by encrypted prefetch/index restoration and offline postcard opening.

This milestone is a public-release gate. M2 may remain an explicitly
unrecoverable engineering/product proof, but a public release may not silently
depend on one installation's Keystore/Keychain material.

## M5 — Security and failure hardening

Goal: prove confidentiality/integrity/state recovery under adversarial and interrupted conditions.

Scope:

- golden crypto/protocol vectors and malformed-input corpus;
- token rotation/replay, rate limits, authorization matrix;
- corrupt/substituted/truncated artifacts and wrong recipient/key/AAD;
- upload interruption, finalize replay, stale key, storage/database restart;
- Room and Alembic migrations from prior versions;
- log/storage plaintext scans;
- recovery-package/wrapper tampering, rollback, generation, and authorization checks;
- key rotation/revocation behavior.

## M6 — Focused UX polish

Goal: make the proven mechanisms understandable without increasing engagement pressure.

Scope:

- concise onboarding and device-loss warning;
- recipient confirmation clarity;
- front/prepared-back capture guidance and quality errors;
- orientation-aware postcard framing so portrait capture can use the available
  screen area instead of forcing a distant landscape guide;
- upload/resume status for the current creation only;
- scan progress, retry, ambiguity choice, useful crypto/network errors;
- capsule presentation and accessibility;
- removal of any accidental counts, lists, deep links, or social language.

## M7 — Music investigation (not core MVP)

Goal: separately evaluate legal/provider/playback constraints.

No provider SDK, downloader, track search, or playback code enters the core product before an ADR and explicit product decision. `TrackAttachment` remains absent/null through M0–M6.

## Release interpretation

- **Testable engineering sample:** M0 passes and APK/backend run.
- **Mechanism sample:** M1 passes on a physical Android device.
- **Product proof:** M2 passes with two physical installations.
- **Candidate for limited external testing:** M2 + relevant M3/M5 security/recognition gates pass with an explicit unrecoverable-build warning.
- **Public recovery readiness:** M4 and its security checks in M5 pass.
- **Public release:** requires a separate privacy/legal/operations decision; not implied by these milestones.
