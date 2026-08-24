# M1 implementation evidence (automated runs)

Generated at the close of the I-queue. Every item below was executed on this
workstation in a clean shell. Physical-device items are explicitly **PENDING**
and are not claimed.

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
  production Create/Scan capture surfaces.
- ORB extraction latency and match-loop timing on-device (OpenCV instrumentation).
- Full two-device physical scenario: mail card, second device scans and opens.
- AndroidKeystore-backed KEK wrapping round trip on hardware TEE/StrongBox.
- M1 completion claim: the automated surface proves login/register, Create,
  Scan, and real photo presentation are wired end to end; the milestone is
  physically complete only when the actual APK demonstrates them on a device.
