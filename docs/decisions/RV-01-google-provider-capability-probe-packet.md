# RV-01: Google provider-capability probe packet

Status: Proposed / evidence pending

Date: 2026-09-07

This is a bounded RV-01 probe packet. It records official-source expectations
and the exact physical probe protocol required before any recovery adapter is
selected. It does not define identity, device, or purge protocol; those belong
to RV-02. It is not an implementation, schema, wire fixture, or provider claim.

Normative input:

- [R0 durable account-recovery decision packet](R0-durable-account-recovery-decision-packet.md)
- [ADR-010 recovery wrappers and authentication boundary](ADR-010-durable-account-key-recovery.md#recovery-wrappers)
- [ADR-010 release gate](ADR-010-durable-account-key-recovery.md#release-gate)
- [REC-01 through REC-10 implementation queue and probe requirement](../implementation-plan.md#15-pre-release-recovery-milestone)
- [Security device-loss and recovery boundary](../security.md#10-device-loss-and-recovery-path)

## Baseline rule

Google is the required baseline adapter family. The exact Google primitive is
evidence-gated: no Google-only capability is established yet.

If no Google primitive passes the gates below, the M4 recovery release blocks.
Existing-device transfer and an optional manual random recovery secret are
future optional additions, not baseline fallbacks. Apple and other providers
are later adapter families.

## Official primary sources (accessed 2026-09-07)

Only the following official primary sources are used. No secondary sources,
blogs, or forum claims are used.

- Restore Credentials overview — <https://developer.android.com/identity/sign-in/restore-credentials> — accessed 2026-09-07.
- Block Store overview — <https://developer.android.com/identity/block-store> — accessed 2026-09-07.
- Block Store restore-flow testing — <https://developer.android.com/identity/block-store/testing-restore-flows> — accessed 2026-09-07.
- Credential Manager (passkeys / credential-provider surface) — <https://developer.android.com/identity/credential-manager> — accessed 2026-09-07.
- Passkeys / Google Password Manager — <https://developers.google.com/identity/passkeys> — accessed 2026-09-07.
- WebAuthn Level 3, PRF extension — <https://www.w3.org/TR/webauthn-3/#prf-extension> — accessed 2026-09-07.
- Android Keystore — <https://developer.android.com/privacy-and-security/keystore> — accessed 2026-09-07.
- Auto Backup overview — <https://developer.android.com/guide/topics/data/autobackup> — accessed 2026-09-07.

## Mechanism separation (official-source expectation, not probe evidence)

### Restore Credentials — auth-only

Restore Credentials restores or signs in an account and transports restore
data. The official Android flow describes a restore key that can be restored
locally or from cloud/device transfer and then used to sign in. Per R0 and
ADR-010 it is authentication/restore transport only. It must not be described
as an ARK unwrap capability unless a separately proven client-held secret
survives the same restore path in RV-01 probes.

### Block Store — conditional

Block Store is a candidate Google Play services mechanism for storing a small
app value and returning it during a restore flow. Its official Android
documentation distinguishes cloud backup, which requires available end-to-end
encryption and a device screen lock, from device-to-device restore. A cloud
candidate must record both the configured screen-lock state and the result of
`isEndToEndEncryptionAvailable()`; a false result cannot support a cloud
baseline claim. Whether a stable unwrap capability survives fresh install,
restore, account change, complete device loss, rollback, and provider loss is
unknown without physical probes. RV-01 must prove it.

### Passkey PRF through an actual provider/authenticator — conditional

A passkey assertion alone is authentication only. The official Google passkey
documentation describes public-key authentication and provider storage/sync;
it does not establish that Google Password Manager or passkeys expose the
WebAuthn PRF extension to this app. PRF is a candidate adapter only where the
actual provider/authenticator reports support and returns a stable client-held
output. A passkey being present, or being stored in Google Password Manager,
must never be treated as PRF support. Availability is per-device,
per-provider, and per-authenticator. RV-01 must prove the exact path.

### Android Keystore — non-exportable, no recovery

Android Keystore keys are non-exportable and device-bound. Loss of the device
or deletion of the installation destroys access to the Keystore-held wrapper.
Keystore alone provides no cross-install or lost-device recovery capability.

### Auto Backup — unsuitable for secret recovery

Auto Backup excludes or must exclude wrapped identity keysets, tokens,
decrypted cache, and fingerprint keys. Ciphertext without its Keystore KEK is
not a recovery capability and can create misleading recovery expectations.
Auto Backup must not be used as a secret-recovery primitive.

## GO / CONDITIONAL / NO-GO matrix (gate, not result)

| Mechanism | Pre-probe gate | RV-01 question |
| --- | --- | --- |
| Google account authentication | NO-GO for unwrap | Must succeed as auth and must not yield ARK material |
| Restore Credentials | NO-GO for unwrap | Must be shown auth-only unless a separate client-held secret independently passes all durability cases |
| Block Store cloud | CONDITIONAL | Passes only with a recorded screen lock, `isEndToEndEncryptionAvailable() == true`, and stable client-held unwrap across all required cloud/loss cases |
| Block Store D2D-only | NO-GO for baseline | May be recorded as a limited result, but cannot satisfy complete device loss without a source device and therefore cannot pass the Google baseline gate |
| WebAuthn PRF through an actual provider/authenticator | CONDITIONAL | Passes only if that provider/authenticator explicitly supports PRF and returns stable client-held output across all cases; GPM/passkey presence is not evidence |
| Android Keystore alone | NO-GO for recovery | Expected to fail cross-install/loss cases by design; recorded as negative control |
| Auto Backup of secrets | NO-GO for recovery | Expected unsuitable; recorded as negative control |
| Exact Google primitive selected | NO-GO until proven | No GO is recorded until at least one CONDITIONAL row passes every pass/fail criterion |

No GO is claimed in this packet. All capability cells are evidence pending.

## Throwaway probe app design (no Remanence keys)

RV-01 probes must use a throwaway probe app, not the Remanence app:

- Generates an independent random 32-byte test secret per probe run.
- Never touches the Remanence ARK, account HPKE/signing keysets, capsule keys, auth tokens, or production storage.
- Stores only test wrappers and public probe metadata.
- Logs only redacted evidence (see below).
- Can be fully uninstalled, reinstalled, and wiped between cases without affecting Remanence state.
- Reports capability-detect results (available / unavailable) before attempting unwrap.

No Remanence protocol, identity, device, or purge behavior is defined or
changed by this probe app.

## Physical device / account / API matrix (evidence pending)

Each probe case must record the exact tuple below. No tuple has been executed
yet.

| Dimension | Values to record |
| --- | --- |
| Physical device model | Exact model, OEM, RAM/storage as shown on device |
| Android version / API level | Exact version and API level from Settings / `adb shell getprop` |
| Google Play services version | Exact version from Settings / Apps |
| Credential provider / authenticator | Google Password Manager version, provider package, authenticator attestation path where visible |
| Test Google account role | Fresh test-only account identifier class (restore-source vs restore-target); no personal accounts |
| Screen-lock state | For Block Store, qualifying PIN/pattern/password present; biometric-only is non-qualifying; record `not applicable` for non-Block candidates |
| Block Store E2EE state | `isEndToEndEncryptionAvailable()` result; record `not applicable` for non-Block candidates |
| Backup/restore path | Cloud or cable/wireless D2D, with the user-selected restore flow recorded |
| Capability placement | Synced/cloud-backed, D2D-transferred, device-bound, or unknown, with the candidate and evidence source named |
| Backup eligibility | Configured/eligible, configured/ineligible, unsupported, or unknown, plus the observed provider result |
| Network state | Online / offline at each step |
| App state | Fresh install / restored / updated / rolled back |

Minimum matrix: at least two distinct physical Google devices and at least two
distinct test Google accounts, covering fresh install, restore-target install,
account-change, and complete-loss (source wiped) cases. Emulator-only results
do not pass. The applicable P5 cloud tuple and P6 below are mandatory, not
optional coverage; P5-D2D is required only when D2D support is claimed.

Mandatory cross-device/account tuples:

- **P5-BS-CLOUD / second device:** for a Block Store cloud candidate, source
  device D1 signed into test account A and fresh target D2 signed into the same
  account A use Block Store's cloud path: the test value is stored with cloud
  backup enabled, `Backup Now` is completed, and D2 restores the app from the
  cloud. Record the screen-lock, E2EE, and backup-eligibility fields for both
  devices where applicable.
- **P5-PRF-CLOUD / second device:** for a PRF candidate, source D1/A creates
  the throwaway provider credential through the actual tested
  provider/authenticator. Fresh D2/A must obtain that provider-synced
  credential, report PRF support, and return a new PRF output for the same
  probe salt/context; compare it in memory with D1's output. This is a
  provider-sync tuple, not a Block Store `Backup Now` result.
- **P5-D2D / second device:** only when the candidate claims D2D support, source
  D1/A and fresh target D2/A use the documented cable or wireless D2D restore
  flow. If a candidate cannot claim cloud/provider-sync support, it must be
  labelled `D2D-only`; that result is not a Google baseline recovery pass
  because it cannot recover after complete loss of the source device.
- **P6-A-to-B / account isolation:** create the A wrapper on D1/A, then use a
  fresh D2 signed into a different test account B with A absent. Retrieval
  must return no A value and must not unwrap A's secret. If B has its own
  probe value, the result must be B's value or an explicit absence/failure,
  never A's value. This tuple is mandatory even if P5 succeeds.

## Procedures P1..Pn (probe steps only)

- P1 — Capability detect: on a fresh install, record for Block Store and for
  each candidate provider/authenticator whether the API reports available,
  unavailable, or indeterminate. For Block Store, record the screen-lock
  state and require a qualifying PIN, pattern, or password before calling
  `isEndToEndEncryptionAvailable()` and enabling cloud backup; biometric-only
  state is non-qualifying. For PRF, record an explicit
  provider/authenticator capability result; a passkey or Google Password
  Manager record is not such a result. Also record whether the candidate
  capability is synced or device-bound and its backup eligibility. Stop
  unavailable paths; do not assume fallback.
- P2 — Wrap: wrap the throwaway test secret with the candidate primitive and record wrapper creation success without exporting raw key material.
- P3 — Local unwrap: immediately unwrap on the same installation and verify byte-equality with the test secret held only in memory.
- P4 — Fresh-install unwrap: uninstall and reinstall the probe app on the same device/account, then attempt unwrap. Record success/failure.
- P5 — Second-device restore. The applicable candidate path is mandatory:
  - **P5-BS-CLOUD:** for Block Store, set a qualifying PIN, pattern, or
    password on D1/A and require `isEndToEndEncryptionAvailable() == true`.
    Install the same signed throwaway probe release from its test Play
    distribution, store the test value with cloud backup enabled, and trigger
    the documented `Backup Now` flow. Factory-reset fresh D2, sign it into A,
    install/select the probe app in the cloud restore flow, then invoke the
    Block Store retrieval and pass the exact returned opaque bytes to the
    candidate unwrap. Compare only in memory. A sideload-only result is not a
    cloud-restore result. Record Restore Credentials sign-in separately; it is
    not proof that the Block Store wrapper survived.
  - **P5-PRF-CLOUD:** for a PRF candidate, create the throwaway passkey or
    credential on D1/A through the actual tested provider/authenticator. After
    the provider's documented sync/availability condition is met, factory-reset
    fresh D2, sign it into A, and obtain the provider-synced credential. The
    provider/authenticator must report PRF support and return a new PRF output
    on D2 for the same probe salt/context. Compare D1/D2 outputs in memory and
    use the D2 output to unwrap the test package. A passkey assertion or
    ordinary provider credential without a returned PRF output is not a pass.
  - **P5-D2D:** only when the candidate claims D2D support, factory-reset fresh
    D2, connect D1 to D2 through the documented cable or wireless D2D flow,
    select the probe app, and invoke the candidate retrieval/unwrap. Record
    the exact path and compare in memory. If this is the only successful path,
    label the candidate `D2D-only`; it cannot pass complete-loss baseline
    recovery.
- P6 — **Mandatory account isolation and provider-loss:** execute the P6-A-to-B
  tuple above and record no-cross-account retrieval/unwrap. Then, in a
  separate run, remove/replace the Google account or disable the candidate
  provider and attempt unwrap. Both account isolation and provider-loss must
  fail closed; do not treat an authentication success as an unwrap success.
- P7 — **Executable local authenticated-package tamper procedure:** after P2,
  invoke the candidate's retrieval operation and capture the exact opaque
  wrapper/package bytes and metadata returned by that operation. Pass those
  exact returned bytes to the same unwrap entry point used by P3/P5/P8; do not
  substitute synthetic ciphertext or an unrelated app-local blob. In a
  test-only harness, persist that exact retrieved object in the probe app's
  private area, for example `files/rv01/retrieved-package.bin`. Force-stop the
  app, invoke `tamperRetrievedPackage` to flip one byte at a fixed offset,
  relaunch, and pass the tampered exact object to unwrap. Pass is an explicit
  rejection/non-success, zero plaintext returned, and no authentication-only
  downgrade; fail is any successful unwrap, partial plaintext, or uncaught
  ambiguity. This proves local authenticated-package tamper handling only; it
  does not prove provider-side rollback or provider history integrity. A
  provider rollback operation is an additional optional procedure only when a
  candidate explicitly claims rollback behavior, with exact provider/app
  versions and the same observable fail-closed result recorded. If the exact
  retrieved object cannot be captured and passed to unwrap, P7 is not passed.
- P8 — Complete-loss, using the applicable cloud/provider-sync path and no
  source device:
  - **P8-BS-CLOUD:** after completing the Block Store `Backup Now` flow, wipe
    the source D1/installation and leave no source device available. On fresh
    D2 signed into A, restore through the Block Store cloud flow, retrieve the
    exact opaque bytes, and attempt unwrap using only the candidate primitive.
  - **P8-PRF-CLOUD:** after the tested provider has synchronized the credential,
    wipe D1 and leave no source device available. On fresh D2/A, obtain the
    provider-synced credential and require a newly returned stable PRF output
    for the same probe salt/context before attempting unwrap. A D2D-only result
    is inapplicable here and fails the baseline complete-loss gate.

P1..P8 define probe steps only. They do not define RecoveryPackage,
generation, device enrollment, revocation, or purge contracts.

## Pass / fail criteria

A candidate Google primitive passes only if all hold:

1. P3 succeeds and P4 succeeds. Exactly one applicable cloud/complete-loss
   path must succeed: P5-BS-CLOUD plus P8-BS-CLOUD for a Block Store
   candidate, or P5-PRF-CLOUD plus P8-PRF-CLOUD for a PRF/provider-sync
   candidate. If the candidate claims both families, each claimed path must
   be tested and pass. P5-D2D is required only when D2D support is claimed;
   D2D-only is never sufficient for the baseline.
2. Mandatory P6-A-to-B and the universal local-authenticated-package P7 fail
   closed: no cross-account unwrap, no exact-tampered-object unwrap, no
   partial plaintext, and no downgrade to authentication-only success.
3. Authentication-only paths (Google login, ordinary passkey assertion, Restore Credentials) succeed as login/restore and simultaneously fail as unwrap when the candidate secret is absent.
4. For a Block Store cloud candidate, the source and target tuple records a
   qualifying PIN, pattern, or password screen lock and
   `isEndToEndEncryptionAvailable() == true`; a biometric-only lock, false
   result, or unrecorded result fails the cloud claim.
5. For a PRF candidate, the tested provider/authenticator explicitly reports
   PRF support and returns a stable output; a passkey or Google Password
   Manager record alone never satisfies this criterion.
6. The evidence records whether the capability is synced/provider-backed,
   D2D-transferred, device-bound, or unknown, and records backup eligibility;
   P8 success alone must not be treated as proof of takeover resistance.
7. The server/probe-backend, if any, observes only opaque bytes; a plaintext canary never appears outside the probe app memory.
8. Results reproduce across the minimum device/account matrix above, including
   the mandatory P5 cloud and P6 tuples.

Any other outcome is a fail for that tuple. If every tuple fails for every
candidate, the M4 recovery release blocks with no silent fallback.

## Redacted evidence rules

Each probe run must attach:

- Exact device/account/API tuple as classes, not personal identifiers.
- P1..P8 step outcomes (pass/fail) with timestamps, including the applicable
  P5-BS-CLOUD or P5-PRF-CLOUD and mandatory P6-A-to-B tuple identifiers.
  Include P5-D2D only when D2D support is claimed; otherwise record it as
  `not claimed`, not as a missing required result.
- Wrapper version and public metadata only.
- Byte-equality result (match / mismatch) without publishing secret bytes.
- Screen-lock state and, for Block Store, the exact
  `isEndToEndEncryptionAvailable()` result and cloud-backup setting.
- Restore path (Block Store cloud, PRF/provider-sync cloud, or D2D),
  provider/authenticator versions, capability placement (synced versus
  device-bound), backup eligibility, and the observable P7
  rejection/result category.
- Fail-closed observations for tamper/rollback/provider-loss.

Forbidden in evidence: email addresses, Google subject identifiers, raw test
secrets, private keysets, auth tokens, full backups, screenshots containing
identifiers, or any Remanence production key material.

## Unknowable without device (must be probed, not assumed)

- Whether Block Store retains the test value across the exact cloud and D2D
  restore, account-change, and wipe paths on the tested OEM/API/GMS tuple.
- Whether the tested provider/authenticator exposes a stable PRF output at
  all; neither a passkey nor Google Password Manager presence answers this.
- Whether Restore Credentials restores the probe wrapper bytes, the provider credential, neither, or both on the tested path.
- OEM-specific Auto Backup inclusion/exclusion behavior for the probe package.
- GMS/provider version differences that change capability-detect results.
- Provider rollback behavior remains unknown unless the executable rollback
  alternative in P7 is actually run; local tamper does not prove provider
  rollback semantics.

These items remain unknown until P1..P8 are executed on physical devices. This
packet makes no claim about them.

## Out of scope for RV-01 (belongs to RV-02)

RV-01 must not define: account identity linking, `account_id` contracts,
device-limit/replacement rules, revocation/purge contracts, versioned
RecoveryPackage format, wrapper generation/rotation policy, fallback policy,
or server-has-no-unwrap-secret proofs. Those belong to RV-02 after RV-01
evidence is recorded.
