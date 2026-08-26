# M1 Rebrand Evidence: Postmark → Remanence

Date: 2026-08-26
Worktree: `/home/vodkolyan/projects/Remanence-rebrand` (branch `rebrand/remanence`, base `b6553a6`)
Decision record: [`docs/decisions/ADR-008-remanence-rebrand.md`](../decisions/ADR-008-remanence-rebrand.md)

## Result

Full product/source rebrand to **Remanence** completed in small verified commits.
Architecture, security model, M1 scope, stack, REST API paths, and the public
backend URL `https://remanence.hryshyn.dev/` are unchanged. No push, merge,
release, or deploy was performed.

## Commits (base b6553a6 → HEAD)

| Commit | Content |
| --- | --- |
| `b9972dc` | ADR-008: naming inventory + compatibility boundary |
| `fa421cc` | Display metadata: app label `Remanence`, `Theme.Remanence`, README title, Gradle root name, OpenAPI title `Remanence API` |
| `037e7ad` | Android namespace: `dev.hryshyn.remanence`, Kotlin packages `dev.hryshyn.remanence(.core.*)`, proto sources/java packages, Room schema folder move |
| `00a8d37` | Backend package `remanence`, env prefix `REMANENCE_`, Docker/compose/script deployment names, problem-type URIs |
| `58ecf81` | Persisted device-local names: DB file `remanence.db`, Keystore aliases `remanence.{session,identity,fingerprint}.v1`; contract test updated |
| `7073607` | Audit classification; documented-but-unimplemented header renamed to `X-Remanence-Ciphertext-SHA256` |
| `3731b6e` | `docs/development.md` commands under Remanence |

## Naming inventory (case-insensitive rg)

| Pattern | Before b6553a6 | After HEAD |
| --- | --- | --- |
| `postmark*` | 2135 matches / 361 files | 93 matches / 22 files — all classified intentional (below) |
| phrase `Postcard Memory Capsules` | 2 | 0 outside ADR-008/evidence history |
| `postcard*` (domain vocabulary) | 133 / 52 | 136 / 52 — physical-object term, intentionally kept |

## Intentional legacy identifiers (complete list)

Frozen crypto/wire domain-separation strings (byte-for-byte; pinned by golden
tests/fixtures): `postmark/artifact/v1`, `postmark/envelope/v1`,
`postmark/publish/v1`, `postmark/kek/wrap/v1`, `postmark/session/v1`,
`postmark/local-fp/v1`, fixture marker `postmark-envelope-plaintext-v1`.
Locations: `CryptoContextEncoder`, `PublishStatementSigner`,
`KeysetKekWrapper`, `SessionTokenStore`, `EncryptedFingerprintStore`, their
test pins (`CryptoContextEncoderTest`, `KeysetKekWrapperTest`,
`AccountIdentityGeneratorTest`, `CapsuleKeysetGeneratorTest`,
`TinkPrimitivesTest`, `server/tests/test_publish_signature_verification.py`),
fixtures (`protocol/fixtures/publish-signature-v1.json`,
`recipient-envelope-v1.json`), contract docs (`docs/security.md`,
`docs/protocol.md`) and ADR-006/007.

Historical records unchanged: `docs/evidence/m1-implementation-evidence.md`,
ADR-001..007, `.agent/*`.

Domain vocabulary kept: `PostcardFingerprint` proto message,
`PostcardContourDetector`, lowercase "postcard" prose — the physical scanned
object per `docs/product.md`, not branding.

## Verification

Environment: JDK 17 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`,
`ANDROID_HOME=ANDROID_SDK_ROOT=/usr/lib/android-sdk`.

- Android: `./gradlew clean testDebugUnitTest assembleDebug --console=plain`
  → BUILD SUCCESSFUL; **707 tests, 0 failures/errors**, 1 skipped
  (`ApiStackIntegrationTest`, runs only when `REMANENCE_TEST_API_BASE_URL`
  is exported — same gating as before the rebrand).
- Backend: `uv run --locked pytest -q -W error` → **230 passed, 94 skipped**
  (skips are Docker/`REMANENCE_TEST_DATABASE_URL`-gated cases; identical
  gating pre-rebrand). Alembic migration history untouched
  (`0001_m0_baseline`, `0002_m1_accounts` revision ids intact).
- `git diff --check` → clean.

## APK artifacts

| Build | Command | Size (bytes) | SHA-256 |
| --- | --- | --- | --- |
| Debug (loopback default) | `./gradlew :app:assembleDebug` | 155214239 | `60b71ebdb33f8f7ba9903ede8b8efd746d81981ec5ef93313664f0c7afa19b5f` |
| Hosted canary | `./gradlew :app:assembleDebug -Premanence.apiBaseUrl=https://remanence.hryshyn.dev/` | 155214231 | `568684f1ebd26744f3ef25f1ef7ab581e8103e6cfdcf0acb16ec3197c84de184` |

Manifest checks (aapt2): package `dev.hryshyn.remanence`, versionName
`0.1.0-m0`, label `Remanence` (all locales), launchable
`dev.hryshyn.remanence.MainActivity`.

Production URL canary (canary APK dex scan): **exactly one** occurrence of
`https://remanence.hryshyn.dev/` across all 21 classes*.dex; **zero**
occurrences of loopback bases (`http://127.0.0.1:8000/`, `http://localhost`,
`http://10.0.2.2`). Default debug build contains the loopback base and zero
production URLs. `ApiBaseUrl` continues to reject non-loopback cleartext HTTP.

## Expected pre-production consequences

- `dev.hryshyn.remanence` installs as a separate sandbox next to any old
  `app.postmark.memory` test build; old test data (incl. its Keystore keys)
  is abandoned with that package and removed by uninstalling it.
- Next server deployment under the renamed compose stack starts with fresh
  server state (postgres volume/db/user, blob root renamed); consistent with
  the client-side fresh sandbox.
- The documented blob-upload header is now `X-Remanence-Ciphertext-SHA256`;
  neither server route nor client uploader exists yet, so nothing breaks.

## PENDING (hardware)

Physical-device validation remains outstanding, as before the rebrand:
APK installation on hardware, CameraX capture, OpenCV recognition on real
postcards, physical M1/M2 flows, hosted-device health check via adb reverse.
No emulator/device was attached to this session; `adb` steps were not run.
