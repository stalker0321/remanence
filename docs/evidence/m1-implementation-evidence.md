# M1 implementation evidence (automated runs)

Generated at the close of the I-queue. Every item below was executed on this
workstation in a clean shell. Physical-device items are explicitly **PENDING**
and are not claimed.

## FIX-M1-ONDEVICE-01 (2026-08-25, baseline 1e20798)

Real on-device M1 integration bug in the Create self-send flow, reproduced
against the hosted backend during Test Build 2: entering own handle and
resolving reached the directory (`GET /v1/directory/handles/vodkolyan` →
200 OK — network, auth, and lookup all work), but nothing usable appeared
afterwards. Root cause: `RecipientConfirmContent` read
`confirmedRecipient`, which by design stays null until explicit
confirmation, while the resolved snapshot sat only in `CreateRecipientFlow`'s
private pending field - the screen required an already-confirmed snapshot to
OFFER confirmation. Eternal blank screen after every successful lookup.

Fix (one commit, code + tests): `a4cb987`

- `CreateRecipientFlow.pendingRecipient`: read-only observable of the
  resolved-but-NOT-yet-confirmed snapshot; the confirmation screen renders
  THAT immutable snapshot, never the unbound session store.
- Checkbox + Confirm remains the single action that MOVES the exact snapshot
  instance into the session store and advances to FRONT; the pending copy is
  dropped with the move. Explicit confirmation still required; no auto
  self-confirm.
- Cancel / restart / endSession / new epoch clear pending AND confirmed
  material; same-epoch rotation keeps an in-flight resolve untouched.
- RECIPIENT_CONFIRM without a pending snapshot now fails closed with an
  explicit error and a way back to lookup instead of a blank screen.
- Other handles may still resolve/confirm; the M1 publisher's own-account
  guard ("publishes only to your own account") is untouched.

### Verification commands and results for this batch

| Command | Result |
| --- | --- |
| Regression proof | The key test FAILED against the previous implementation (blank confirmation screen) and passes after the fix — red/green verified before committing |
| `cd android && JAVA_HOME=... POSTMARK_TEST_API_BASE_URL=http://127.0.0.1:8000/ ./gradlew clean testDebugUnitTest assembleDebug --console=plain` (JDK 17) | BUILD SUCCESSFUL — 649 unit tests (+5 new), 0 failures, 0 errors, 0 skipped (`ApiStackIntegrationTest` ran against the live local API) |
| Backend | Untouched by this commit (android-only diff). Hosted directory endpoint confirmed live: `GET /v1/directory/handles/vodkolyan` → 200 OK on-device during Test Build 2; local compose stack healthy during this run (`/healthz` = 200) |
| `git diff --check` | clean; worktree clean at the evidence commit |

New regression coverage (`CreateRecipientConfirmFlowTest`, production-shaped:
REAL CreateScreen over REAL CreateViewModel through real lookup):
Found(self) really shows handle/account cue/checkbox/button; no binding
before Confirm; same-instance binding after it; cancel returns to lookup
with pending+confirmed cleared; endSession/new-epoch drop everything while
same-epoch rotation keeps pending; impossible invariant renders explicit
error + recovery; another recipient confirms but publish stays gated fail-
closed with zero outbox rows.

### APK artifacts for this batch

| Artifact | Size | SHA-256 |
| --- | --- | --- |
| Default debug APK from the clean suite build (`API_BASE_URL = http://127.0.0.1:8000/`) | 155,133,699 bytes | `d8ea007cfed27222801b2e9afb9f67028ec336f76319a9078e33e6ef245ad6c9` |
| Hosted release candidate: clean `assembleDebug -Ppostmark.apiBaseUrl=https://remanence.hryshyn.dev/` | 155,133,699 bytes | `ded0aeca6765264c8a3e1ef1a4a996121a8b7165545211f2f56738f6733e5936` |

The hosted artifact was verified to contain `https://remanence.hryshyn.dev/`
in its dex bytecode and NOT contain the default loopback URL. Byte-for-byte
reproducibility across builds is NOT claimed (debug signing/ZIP metadata).

### Hardware status for this fix

Honest state: the lookup bug was REPRODUCED on physical hardware (Test
Build 2); the fix above is proven only by automated tests so far. The
hosted release-candidate APK has NOT been installed/retested on device yet —
the physical Create self-send retest (Resolve → visible confirmation controls
→ Confirm → FRONT → publish path) remains **PENDING** below.

## FIX-REVIEW3 correction batch (2026-08-25, baseline 0203c1c)

Independent Codex review of `0203c1c..ce78352`. One focused commit each,
tests with each, no amends:

| Fix | Commit |
| --- | --- |
| 01 in-flight page loads finishing after close are rejected and zeroed | `358260b` |
| 02 grant revalidated AFTER every suspended decrypt too (TOCTOU closed) | `b7185ba` |
| 03 presentation state owns and drops the plaintext note reference | `6c5797d` |
| 04 deterministic lifecycle/plaintext regression suite | `ce78352` |

FIX-REVIEW3-01: page loads carry a session generation bumped by open/close
and are serialized behind one mutex. A load still in flight when close(),
expiry, or revocation ran can neither return its plaintext nor re-enter
`loadedPages`; rejected bytes are zeroed first. Concurrent same-page loads
share exactly one decrypt path.

FIX-REVIEW3-02: `GrantGuardedCapsuleContentSource` previously validated the
grant only BEFORE each operation - plaintext finishing into a dead grant was
returned successfully. The single validator now runs both before and after
every photo/note/count operation through THE authoritative manager; a photo
refused post-decrypt has its bytes zeroed before the failure propagates; an
immutable String note is refused without delivery.

FIX-REVIEW3-03: the pre-decrypted note is handed over at
`open(expectedCount, note)` into the closable presentation state; `close()`
nulls it immediately (Kotlin cannot scrub String bytes; dropping the last
controlled strong reference is the strongest available guarantee). No
plaintext note is logged anywhere.

FIX-REVIEW3-04: `PresentationPlaintextLifecycleTest` drives the REAL
composition (presentation → GrantGuardedCapsuleContentSource → decrypt)
through suspension gates on the test scheduler - no sleeps, no wall clock.

### Deterministic race-test verification (all PASS inside the same clean run)

- Expiry/close during a suspended load: `CapsulePresentationStateLoadRaceTest`
  (`hungLoaderCompletingAfterCloseReturnsNothingCachesNothingAndZeroesBytes`,
  `closeDuringFlightAlsoRejectsLoadsThatArriveAfterwards`,
  `concurrentLoadsForTheSamePageShareOneDecryptAndSurviveOnlyWhileOpen`) -
  nothing returned, nothing cached or re-entered after clear, bytes zeroed.
- Post-decrypt grant rejection: `GrantGuardedPostDecryptValidationTest`
  (`photoDecryptedIntoADeadGrantIsZeroedAndRefused`, `liveGrantStillServesThePhoto`,
  `noteDecryptedIntoADeadGrantIsRefusedWithoutDelivery`,
  `countOperationIsAlsoValidatedOnBothSides`).
- Note strong-reference release: `PresentationPlaintextLifecycleTest`
  (`noteOwnershipMovesIntoTheStateAndDiesWithIt`,
  `grantDeathDuringFlightRefusesPlaintextAndCloseDropsTheNote`) plus the
  internal ownership probe assertions in `GrantGateCapsuleFlowTest`
  (`holdsDecryptedNoteForTests` held while open, gone after close).
- Full lifecycle composition: `PresentationPlaintextLifecycleTest`
  (`closeWinningTheRaceLeavesNoPlaintextAnywhere`,
  `postDecryptValidationIsProvableWithoutPresentationClosure`).

### Verification commands and results for this batch

| Command | Result |
| --- | --- |
| `cd android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ANDROID_SDK_ROOT=/usr/lib/android-sdk POSTMARK_TEST_API_BASE_URL=http://127.0.0.1:8000/ ./gradlew clean testDebugUnitTest assembleDebug --console=plain` | BUILD SUCCESSFUL in 5m — 644 unit tests, 0 failures, 0 errors, 0 skipped (with the local API up, `ApiStackIntegrationTest` ran instead of skipping) |
| `cd server && uv lock --check && uv sync --locked && POSTMARK_TEST_DATABASE_URL=postgresql+psycopg://postmark:postmark-dev-only@127.0.0.1:55432/postmark uv run --locked pytest -q -W error` | 324 passed (full PostgreSQL-backed suites included, 0 skipped), compose stack healthy (`migrate` exited 0, `/healthz` = `{"status":"ok"}`) |
| `git diff --check` | clean; worktree clean at the FIX-REVIEW3-05 commit |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`, 155,133,699 bytes, SHA-256 `19da697692a35cb5108e9904da6e4b514ee4483f1bf60363d57c8cf411fb2a74` |

No code fixes were needed in this batch's verification: all four REVIEW3
commits compiled and passed the full suites unchanged.

The APK hash records exactly this verification run's artifact. Byte-for-byte
reproducibility across clean builds is NOT claimed: debug signing and ZIP
metadata may vary between runs.

### Plaintext canary status

Automated canaries pass inside the same clean run:
`CapsuleOutboxStagerTest`
(`plaintextCanaryAcrossEveryProducedByteFindsNothing` plus the rollback-
without-traces case) scans every produced byte — artifact files,
statement/signature files, SQLite db/WAL — for note/photo/manifest markers,
and the create-close-reopen-scan-open narrative (`CreateRescanOpenFlowTest`)
repeats the scan over sealed baselines, fingerprint files, and outbox
ciphertext with the REAL acceptance gate deciding every grant. No plaintext
marker appears in any persisted byte.

### Honest-client status after this batch

Unchanged from the FIX-REVIEW2 record below, hardened further: fullscreen
photo presentation now also survives its own races - an in-flight page load
that finishes after close/expiry returns nothing and zeroing-scrubs its
bytes, every suspended decrypt is revalidated against THE authoritative
grant on both sides, and the plaintext note reference dies with the
presentation state. CameraX/OpenCV/Keystore behavior on physical hardware
and the two-device scenario remain the open items listed at the bottom.

## FIX-REVIEW2 correction batch (2026-08-25, baseline 3e742f2)

Independent Codex review of `d815251..3e742f2`. One focused commit each,
tests with each, no amends:

| Fix | Commit |
| --- | --- |
| 01 strict routing parser: malformed identity material fails closed | `a72b9a2` |
| 02 leaving Scan always invalidates the authoritative grant manager | `f7c46bb` |
| 03 real ten-minute expiry during capsule presentation + per-load grant revalidation | `31145d1` |
| 04 sender verification only through the trusted key-directory boundary | `38c648d` |

FIX-REVIEW2-01: one shared `CapsuleRoutingPolicy` parses persisted routing
material strictly for BOTH Scan verification and content decryption.
`recipient_user_id`/`recipient_key_bundle_id` are mandatory (malformed never
falls back to the authenticated account); non-null malformed sender IDs or
bundles fail closed; a carried signing export must decode to valid non-secret
Ed25519 material or the row is corrupt - an error NEVER falls back to the own
signing key. Only genuinely NULL v2-migrated columns resolve through the
documented self-send fallback. `CapsuleRoutingCorruptionTest` (16 cases)
proves every malformed case yields no grant, no decrypted hint, and no
plaintext baseline, while genuine legacy NULL self-send keeps verifying.

FIX-REVIEW2-02: `returnToHome()` now clears THE authoritative
`ScanGrantManager` itself when leaving Scan, so a grant issued in the race
before the navigation effect completed cannot outlive the flow. Close,
logout, flow reset/re-entry, and process-death guarantees unchanged;
rotation never touches a live presentation.

FIX-REVIEW2-03: presentation expiry is REAL. One lifecycle-bound timer
(`CapsuleExpiryWatch`, no polling) wakes exactly at the grant deadline from
the same injected clock, THE authoritative manager re-decides, the route
ejects to Home, and a revocation event closes the presentation state,
releasing every decrypted reference. The production route reaches decryption
ONLY through `GrantGuardedCapsuleContentSource`, which revalidates the same
grant through THE manager before every photo/note/count operation - an
expired/consumed/wrong grant decrypts nothing even before any wake-up.
Rotation preserves grant and timer; process death loses both.
`ScanGrantManager.expiresAtMillis` exposes the exact deadline.

FIX-REVIEW2-04: sender verification trusts ONLY `TrustedSenderKeyStore`.
Production resolves other senders exclusively through the authenticated
`GET /v1/directory/key-bundles/{id}` material (owner must match, not REVOKED,
well-formed non-secret Ed25519; uncached so later revocation cannot be
outrun). The own public export is returned solely for an exact match of the
authenticated account and its active bundle - provable self-send without the
network. Row-carried exports stay strictly parsed transport/cache candidates
and never decide trust: `ScanViewModel` now REQUIRES the boundary, and
CrossIdentity proofs show a forged replacement row export is inert while
wrong-owner/revoked/missing/malformed directory entries all fail closed. The
documented malicious-live-directory limitation remains unchanged; no M2
delivery machinery was implemented.

### Verification commands and results for this batch

| Command | Result |
| --- | --- |
| `cd android && ./gradlew clean testDebugUnitTest assembleDebug --console=plain` (JDK 17) | BUILD SUCCESSFUL — 633 unit tests, 0 failures, 0 errors, 1 pre-existing environment skip (`ApiStackIntegrationTest`, skips cleanly without its external dependency) |
| `cd server && POSTMARK_TEST_DATABASE_URL=postgresql+psycopg://postmark:postmark-dev-only@127.0.0.1:55432/postmark uv run --locked pytest -q -W error` | 324 passed (full PostgreSQL-backed suites included, 0 skipped) |
| `git diff --check` | clean; worktree clean at the FIX-REVIEW2-05 commit |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`, 155,133,699 bytes, SHA-256 `caf7bb831bd4656f1da3c0b7b36fad0ed8ab83030b97b3cab1ad01940948599b` |

The APK hash records exactly this verification run's artifact. Byte-for-byte
reproducibility across clean builds is NOT claimed: debug signing and ZIP
metadata may vary between runs.

### Plaintext canary status

Automated canaries pass inside the same clean run:
`CapsuleOutboxStagerTest`
(`plaintextCanaryAcrossEveryProducedByteFindsNothing` plus the rollback-
without-traces case) scans every produced byte — artifact files, statement/
signature files, SQLite db/WAL — for note/photo/manifest markers, and the
create-close-reopen-scan-open narrative (`CreateRescanOpenFlowTest`) repeats
the scan over sealed baselines, fingerprint files, and outbox ciphertext with
the REAL acceptance gate deciding every grant. No plaintext marker appears in
any persisted byte. Persisted routing identity material remains public-only
(user/bundle IDs plus the sender PUBLIC keyset export), parsed strictly.

### Honest-client status after this batch

The debug APK exposes login/register surfaces, reachable Create (directory
resolve → confirm → front/prepared-back capture → picker/note → sealing into
the durable outbox), reachable Scan (front/back capture → local hierarchy →
chooser on decrypted hints → verified grant through the one authoritative
grant manager), and fullscreen photo presentation that exists ONLY behind a
live memory-only grant plus directory-bound verified crypto, with real
ten-minute expiry enforced during presentation. Tampered or malformed local
capsule material fails closed everywhere it can be met; leaving Scan always
invalidates grants; camera permission states distinguish first request,
retryable denial, and permanent denial with no re-request loop.
CameraX/OpenCV/Keystore behavior on physical hardware and the two-device
scenario remain the open items below.

## FIX-REVIEW correction batch (2026-08-25, baseline 2111289)

Review corrections after baseline `2111289`. One focused commit each,
tests with each, no amends:

| Fix | Commit |
| --- | --- |
| 01 honest FRONT-first Scan state before matching | `d815251` |
| 02 fresh Create/Scan session on every flow re-entry | `8eddde3` |
| 03 one authoritative memory-only ScanGrantManager | `f724919` |
| 04 sender/recipient identities separated end to end (Room v2→v3) | `579a5db` |
| 05 camera permission: PermanentlyDenied reachable via real OS ask-again signal | `408b04a` |

FIX-REVIEW-04 detail: persisted/authenticated capsule material now carries
separate `sender_user_id`, `recipient_user_id`, `sender_key_bundle_id`,
`recipient_key_bundle_id`, and the sender signing public keyset
(`outbox_capsule` v2→v3 migration, exported schema checked in, no destructive
reset; legacy NULL rows fall back to the authenticated account so M1 self-send
stays natural). `CrossIdentityCapsuleFlowTest` proves a capsule sealed for a
different recipient opens only with that recipient's private key and verifies
only through row-carried sender material — never own-key-as-sender or
recipient-bundle-as-sender conflation. No M2 delivery machinery was added.

### Verification commands and results for this batch

| Command | Result |
| --- | --- |
| `cd android && ./gradlew clean testDebugUnitTest assembleDebug --console=plain` (JDK 17) | BUILD SUCCESSFUL — 607 unit tests, 0 failures, 1 pre-existing environment skip (`ApiStackIntegrationTest`, skips cleanly without its external dependency) |
| `cd server && POSTMARK_TEST_DATABASE_URL=postgresql+psycopg://postmark:postmark-dev-only@127.0.0.1:55432/postmark uv run --locked pytest -q -W error` | 324 passed (full PostgreSQL-backed suites included, 0 skipped) |
| `git diff --check` | clean; worktree clean at the FIX-REVIEW-06 commit |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`, 155,117,315 bytes, SHA-256 `d33cb55daa5ec61fe3a4999b74e9923172bb97054c2a13028ca425e5888dea87` |

### Plaintext canary status

Automated canaries pass inside the same clean run:
`CapsuleOutboxStagerTest` (`plaintextCanaryAcrossEveryProducedByteFindsNothing`,
rollback-without-traces case) scans every produced byte — artifact files,
statement/signature files, SQLite db/WAL — for note/photo/manifest markers,
and the FIX-M1-007-14 narrative (`CreateRescanOpenFlowTest`) repeats the scan
over the sealed-baseline database, sealed fingerprint files, and outbox
ciphertext across close/reopen with the REAL acceptance gate deciding every
grant. No plaintext marker appears in any persisted byte. Room rows now also
carry only public routing identity material (user/bundle IDs plus the sender
PUBLIC signing keyset export); no private keyset bytes enter Room.

### Honest-client status after this batch

The debug APK exposes login/register surfaces, reachable Create (directory
resolve → confirm → front/prepared-back capture → picker/note → sealing into
the durable outbox), reachable Scan (front/back capture → local hierarchy →
chooser on decrypted hints → verified grant through the one authoritative
grant manager), and fullscreen photo presentation that exists only behind a
live memory-only grant plus a verified crypto result. Sender and recipient
identities are distinct throughout publish/persist/verify/present; camera
permission states distinguish first request, retryable denial, and permanent
denial with no re-request loop. CameraX/OpenCV/Keystore behavior on physical
hardware remains the open item below.

## FIX-M1-007 correction batch (2026-08-24, baseline 42a45eb)

Fifteen ordered corrections from the M1-007 review. One focused commit each,
tests with each, no amends:

| Fix | Commit |
| --- | --- |
| 01 acceptance gate fail-closed + 69-byte guard before indexing | `3852e58` |
| 02 statement+signature persisted across restart (Room v1→v2) | `209ca93` |
| 03 replay-safe outbox staging (unique temps, owned cleanup) | `dd9c60f` |
| 04 sender fallback on recipient weak-evidence absence; empty index no-match; no front-as-back | `b4b2240` |
| 05 sealed rotating refresh only; memory-only access token; refresh before Active | `b734c73` |
| 06 bearer interceptor + serialized one-retry authenticator on a bare refresh client | `c9989ad` |
| 07 real local_account persistence + ordered logout teardown | `5477554` |
| 08 lifecycle ViewModels/scopes + collectAsStateWithLifecycle | `6f76f99` |
| 09 real CryptoReady derivation + working Create/Scan callbacks | `2ac10b7` |
| 10 reachable Create/Scan destinations + one-shot transient cleanup | `2103941` |
| 11 production Create connected through the single ciphertext publisher | `1c36196` |
| 12 production Scan connected with real crypto verification before every grant | `cf67529` |
| 13 real on-demand decoded photo pages bound to the grant lifecycle | `9e78716` |
| 14 honest E2E: real AEAD sealing and real acceptance gate (no XOR / verifier=true) | `39b5ddf`, `7d25ab3` |

### Verification commands and results for this batch

| Command | Result |
| --- | --- |
| `cd android && ./gradlew clean testDebugUnitTest assembleDebug --console=plain` | BUILD SUCCESSFUL — 584 unit tests, 0 failures |
| `cd server && POSTMARK_TEST_DATABASE_URL=postgresql+psycopg://postmark:postmark-dev-only@127.0.0.1:55432/postmark uv run --locked pytest -q -W error` | 324 passed (PostgreSQL-backed suites included, 0 skipped) |
| `git diff --check` | clean; worktree clean at `7d25ab3` |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`, 155,100,931 bytes, SHA-256 `b13da93df3929dce7a0993ce1cdd6ff16bee67b8e83dbb1b3ce324c853386d0a` |

### Plaintext canary status

Automated canaries pass inside the suite: the ciphertext-only outbox stager
scans every produced byte (artifact files, statement/signature files, SQLite
db/WAL) for note/photo/manifest markers (`CapsuleOutboxStagerTest`), and the
FIX-M1-007-14 narrative repeats the scan over the sealed-baseline database,
sealed fingerprint files, and outbox ciphertext after a close/reopen cycle —
now with REAL AES-GCM sealing under a software KEK boundary and the REAL
`CapsuleAcceptanceGate` deciding every grant (tamper case proves no grant).
No plaintext marker appears in any persisted byte.

### Honest-client status after this batch

The debug APK now exposes working login/register surfaces, reachable Create
(directory resolve → confirm → front/prepared-back capture → picker/note →
sealing into the durable outbox), reachable Scan (front/back capture → local
hierarchy → chooser on decrypted hints → verified grant), and fullscreen
photo presentation that exists only behind a live memory-only grant plus a
verified crypto result. CameraX/OpenCV behavior on physical hardware remains
the open item below.

## I-queue batch (original record)

## Verification commands and results

| Command | Result |
| --- | --- |
| `cd android && ./gradlew --no-daemon test` | BUILD SUCCESSFUL — 1137 unit tests, 0 failures |
| `cd android && ./gradlew --no-daemon assembleDebug` | BUILD SUCCESSFUL — `android/app/build/outputs/apk/debug/app-debug.apk` (~155 MB, OpenCV bundled) |
| `cd server && uv run python -m pytest -q` | 230 passed, 94 skipped (DB/docker-dependent suites skip cleanly) |

## Commit sequence for this batch

Fixes and approved protocol decision:

- `f8e1269` fix(android): bound staged photo reads to the plaintext budget (FIX-006-01)
- `a48fa45` feat(protocol): freeze 69-byte TINK-prefixed Ed25519 publish signature (FIX-006-02)

C queue:

- `0aa5f58` C12 — HPKE recipient envelope, canonical context, cross-platform vector
- `a674222` C13 — CapsuleAcceptanceGate verify-before-decrypt
- `729beb7` C14 — atomic ciphertext-only outbox + plaintext canary

M queue (matching):

- `620ab31` M01 Hamming KNN ratio + mutual matcher
- `582ed65` M02 homography/RANSAC inlier report
- `c3771e3` M03 spatial hull/grid coverage
- `dd63c18` M04 homography plausibility gates
- `aaa80da` M05 weak/strong side score (+ `36ed468` binary-exact margin test fix)
- `9635549` M06 front top-five ranking / duplicate group
- `f345dbe` M07 composite automatic acceptance
- `bb14cab` M08 outcome classification matrix

M queue (scan flow):

- `0f89b5a` M09 recipient-first/sender-fallback coordinator
- `fb42dbf` M10 memory-only ScanGrantManager
- `630ef24` M11 scan front/back capture session
- `e7752f9` M12 matching/retry ViewModel
- `e39561f` M13 minimal-hints ambiguity chooser
- `e8ab5a2` M14 grant-gated capsule route
- `03b0c9c` M15 bounded fullscreen presentation + cleanup
- `621ba10` M16 preferred recipient baseline after verified receipt
- `6c1aa49` M17 process-restart resilience proof

I queue (integration):

- `6497e25` I01 explicit application container
- `d88c5b0` I02 cold-start session bootstrap / recovery-required
- `9c147b0` I03 register/login/logout guarded root navigation
- `00352a9` I04 recipient resolution → immutable confirmation wiring
- `a895c2b` I05 camera still → encrypted front fingerprint flow
- `a77f0e7` I06 checklist-gated ordered back capture
- `d0cd6db` I07 picker/note lazy staging with guaranteed cleanup
- `8fa5339` I08 same-account capsule publish end to end
- `2c0fd79` I09 full local hierarchy into one-time grants
- `037cbef` I10 grant lifecycle ↔ capsule presentation binding
- `b79db2c` I11 create-close-reopen-scan-open narrative + canary

## Automated evidence highlights

- Cross-platform golden vectors checked in and consumed by tests:
  `protocol/fixtures/publish-signature-v1.json` (Android reproduces tink-python
  signature bytes exactly; backend verifies), `protocol/fixtures/recipient-envelope-v1.json`
  (Android opens a tink-python-sealed HPKE ciphertext).
- Plaintext canaries: staged-photo assembler, capsule publisher/outbox stager,
  and the create-reopen-scan-open narrative all scan every produced byte
  (files, SQLite db, WAL) for note/photo/fingerprint markers and find none.
- Restart resilience: scan grants/navigation are memory-only and die with the
  process while outbox rows, ciphertext blobs, sealed baselines, and wrapped
  keysets survive (JVM-level proof).
- Backend policy respected: backend tests parse/verify only public statement/
  signature material; decrypting key material never crosses the server boundary.

## Physical-device evidence — PENDING

The following require real hardware/emulator runs and remain **PENDING**:

- CameraX preview/capture on physical ARM64 devices (permission flow, focus,
  exposure behavior under real lighting) — including the now-connected
  production Create/Scan capture surfaces and the FIX-REVIEW-05 first-request/
  retryable/permanent permission states, whose real system-dialog behavior is
  only unit-proven at the classifier level until then.
- On-device presentation lifecycle from FIX-REVIEW2-02/03: real Back/Recents
  navigation out of Scan invalidating grants, and the exact ten-minute expiry
  ejecting an open capsule on hardware (unit-proven with virtual clocks only).
- Directory-backed sender verification from FIX-REVIEW2-04 against a live
  authenticated backend (owner/status/malformed refusals over real HTTP).
- ORB extraction latency and match-loop timing on-device (OpenCV instrumentation).
- Full two-device physical scenario: mail card, second device scans and opens.
- FIX-M1-ONDEVICE-01 physical retest: install the hosted release-candidate
  APK (`ded0aeca…`) and repeat the real Create self-send flow on hardware —
  own-handle Resolve now showing handle/account cue/checkbox/button,
  explicit Confirm advancing to FRONT, and the publish path end to end.
- AndroidKeystore-backed KEK wrapping round trip on hardware TEE/StrongBox.
- M1 completion claim: the automated surface proves login/register, Create,
  Scan, and real photo presentation are wired end to end; the milestone is
  physically complete only when the actual APK demonstrates them on a device.

## FIX-STATE hardening batch (state-transition review corrections)

Scope: one coordinated hardening batch after the on-device state review —
authoritative capture contract, create transition table, scan parity,
content/publish recovery, auth/capsule terminal UX, and a production-shaped
transition suite. No crypto/protocol changes; no rebrand; no M2 scope.

Commits (in order):

- `d741823` fix(capture): single authoritative capture-attempt contract
  (FIX-STATE-01/03). Replaces the three unsynchronized states (shell phase /
  VM step / rejection set) with one `CaptureAttemptController` per side:
  monotonic attempt ids, guaranteed terminal (Accepted/Rejected/Failed) for
  every begun attempt even when the OpenCV processor or persistence throws,
  structurally inert stale callbacks, clean cancellation without publication.
  CPU pipeline on injected Default dispatcher, sealed persistence on IO;
  suspending photo normalization port. Camera adapter seam with explicit
  use-case release and inert late callbacks.
- `92a3751` fix(create): the create transition table (FIX-STATE-02). Checklist
  Continue now REALLY advances BACK_CHECKLIST → BACK (production previously
  looped BACK_CHECKLIST→BACK_CHECKLIST so the prepared back was unreachable);
  out-of-order events fail closed with a visible recovery message instead of
  crashing via `check()`; publish guards route through the same table.
- `8f7f329` fix(scan): capture parity + dead-wiring removal (FIX-STATE-05).
  Same contract on both sides; reset wipes pair, cancels in-flight work, and
  invalidates queued stills; GuidedRecapture/ConfirmSingle removed from the
  production state surface together with the unused ScanMatchingViewModel;
  orphaned legacy capture components (SingleStillCaptureShell,
  StillCaptureScreen, PreparedBackScreen, CropConfirm/CropConfirmationShell)
  deleted so no second state machine survives.
- `6f40478` fix(create): observable content input + recoverable publishing
  (FIX-STATE-06). Note text/limit error and the photo count / 3..5 gate are
  Compose-observable through ONE picker sink; PUBLISHING shows a real spinner;
  EVERY publish failure — identity resolution included, which previously left
  the spinner forever — terminates visibly at CONTENT preserving recipient/
  photos/note/captures while plaintext staging is cleared; the durable outbox
  refuses replayed capsules so retries cannot duplicate artifacts.
- `50a45b8` fix(auth,capsule): registration renders THE submit state
  (Submitting disables with progress, Failed visible, Completed blocks a second
  submit), scrollable keyboard-reachable auth surface, and the capsule route
  extracted behind Loading | Ready | Failed: never spins forever, photoCount
  outside 3..5 fails closed WITHOUT coercion, Retry+Close always actionable,
  grant-guarded decrypts preserved, revocation closes instantly, page decode
  failures get Try again.
- `f5d1226` test(state): production-shaped suite (FIX-STATE-08). Real-surface
  happy path A (lookup → confirm → FRONT → checklist Continue → BACK → 3 photos
  via production sink → note → publish → PUBLISHED with ENCRYPTED outbox row),
  stale-delivery E (exit during processing changes nothing — which exposed and
  fixed a real gap where the controller was inert but the VM step still
  advanced from the stale coroutine; fixed by a monotonic delivery generation),
  31-row golden transition table I, small-viewport D (rejection panel replaces
  camera at 320x480 with working Retake; scroll reaches below-the-fold errors),
  scan parity F, registration lifecycle G, capsule route/page recovery H.

Validation evidence (JDK 17, host `vuzol-main`):

- `./gradlew clean testDebugUnitTest assembleDebug` — BUILD SUCCESSFUL,
  app module **279 tests / 0 failures** (+28 core JVM tests green in the same
  gate run of `test`).
- Full-suite hang root-caused and eliminated: the first stale-delivery draft
  blocked the Unconfined test thread on an unreleased latch; rewritten to a
  paused `StandardTestDispatcher` queue (no latches/threads/sleeps), then each
  new class proven individually under external timeouts before the full run.
- `git diff --check` clean (one trailing-newline warning in ScanScreen fixed).
- Plaintext canaries: production sources scanned for note/photo/fingerprint
  markers (`dear mama`, `plaintext-FRONT`, payload markers) — none present;
  staging cleanup remains covered by PhotoStagingPipelineTest and the publish
  failure/recovery tests.
- Backend unchanged this batch; hosted health verified live:
  `GET https://remanence.hryshyn.dev/healthz` → HTTP 200 `{"status":"ok"}`.
- Hosted debug APK built ONLY after full green:
  `./gradlew clean :app:assembleDebug -Ppostmark.apiBaseUrl=https://remanence.hryshyn.dev/`
  — size **155,133,699 bytes**, SHA-256
  **3d2af9d17148da35702b3e18c2373d0ed2112bc6a93d734d93796016743de402**.
  Verified inside the dex: exactly one occurrence of
  `https://remanence.hryshyn.dev/`; the only `127.0.0.1` strings are the
  `ApiBaseUrl` loopback GUARD constant that rejects loopback bases — no
  loopback API base is compiled in.

Physical-device status: honest **PENDING** — nothing in this batch is claimed
as hardware PASS; the on-device items above remain outstanding.

## FIX-STATE review follow-up batch 2 (staging ownership + scan root layout)

Scope: the two remaining findings from the independent review of the FIX-STATE
batch — one cross-session staging race left inside FIX-STATE-11's accepted
state guards, and the missing production-root viewport test for Scan. No
crypto/protocol changes; no rebrand; no M2 scope. Accepted FIX-STATE-09/10/12
untouched.

Commits (in order):

- `c2a8805` fix(create): session-owned plaintext staging directories
  (FIX-STATE-13). Every publication now stages normalized plaintext ONLY
  inside `create-staging/<capsule UUID>/` derived from its immutable
  `PublishInputs`, and cleanup touches exactly that directory: NonCancellable
  on cancellation/supersession, `finally` on success/failure. Ownership of a
  directory belongs to its LIVE publication (tracked by an in-flight ledger,
  not the detached job handle): `beginSession`/`endSession` remove a session's
  own directory only when no publication still owns it, so an old coroutine
  woken after non-cooperative normalization can never delete a newer session's
  files (previously `clearStagedPhotos()` deleted ALL children of the shared
  `create-staging`). `beginSession` sweeps process-death leftovers strictly by
  capsule-UUID directory names, skipping the current session and in-flight
  owners; arbitrary files/unknown entries are never touched (follows
  docs/architecture.md "discard incomplete plaintext state" recovery line —
  no bigger mechanism invented). Bounded sequential staging, plaintext
  cleanup guarantees, ciphertext-only outbox, single-publish and generation
  guards unchanged.
- `2250df7` test(scan): production-root viewport coverage (FIX-STATE-14).
  RootScreen hosting the REAL ScanScreen/CaptureAttemptSurface at 320x480dp:
  "Back to Home" visible and actionable, flow body starts below the header
  with the remaining height, `capture_preview` keeps its deterministic
  positive area with the shutter beneath, and after a real rejection through
  the camera seam the terminal Retake panel scrolls fully into view inside
  the newly named `scan_screen_scroll` container while the header stays on
  screen (mirrors the accepted Create production-root test).

Regression proof (FIX-STATE-13):

- New `CreateSessionOwnedStagingTest.stalePublicationCleanupSparesTheNewSessionArtifactsAndRow`
  parks an old publication INSIDE a non-cooperative normalization (plain
  latch invisible to coroutine cancellation) with its first staged file
  already written, starts epoch 2, REALLY publishes it mid-park (two real
  staged photos parked on a gate), wakes the stale job so its cancellation
  cleanup runs against the live new-session artifacts, then completes the new
  publication: stale cleanup removed only its own directory, the new staged
  bytes survived byte-for-byte, and exactly one ENCRYPTED outbox row (plus
  ≥5 blobs) exists for the NEW capsule id while the old row stays absent.
  RED on pre-fix code (shared delete-all refused/corrupted the second
  publication), GREEN after.
- `beginSessionSweepsOnlyAbandonedUuidDirectories`: planted UUID-shaped
  leftovers are swept at beginSession; foreign dirs/files survive; the
  replaced idle session's own directory is removed by rotation.
- Accepted-suite fixtures updated to the owned-dir contract only where they
  encoded the shared directory: lifetime/transition tests now keep the
  ciphertext outbox OUTSIDE the staging root (as production wiring already
  does) and plant mid-flight plaintext inside the owning session subdirectory.

Validation evidence (JDK 17, host `vuzol-main`):

- Targeted first: create/session/scan packages green (110 tests), including
  the red/green race proof above; then
  `./gradlew clean testDebugUnitTest assembleDebug --console=plain` —
  BUILD SUCCESSFUL, **679 tests / 0 failures / 0 errors** counted from the
  JUnit XML across app + core modules.
- `git diff --check` clean.
- Hosted health verified live during this batch:
  `GET https://remanence.hryshyn.dev/healthz` → HTTP 200 `{"status":"ok"}`.
- Hosted debug APK built ONLY after full green:
  `./gradlew :app:assembleDebug -Ppostmark.apiBaseUrl=https://remanence.hryshyn.dev/`
  — size **155,197,144 bytes**, SHA-256
  **e2435e78fb52c60b63ad321390152e3b64a7a616e0216e99fcff702e2f3860c3**
  (`android/app/build/outputs/apk/debug/app-debug.apk`). Verified inside the
  dex: exactly ONE occurrence of `https://remanence.hryshyn.dev/`; no
  loopback API base (`http://127.0.0.1:8000/` absent); the only `127.0.0.1`
  strings are the bare `ApiBaseUrl` loopback-GUARD host constants — no
  loopback base is compiled in.

Physical-device status: honest **PENDING** — nothing in this batch is claimed
as hardware PASS; the on-device items above remain outstanding.
