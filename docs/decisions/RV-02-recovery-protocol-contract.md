# RV-02: Recovery protocol contract

Status: Proposed / RV-01 evidence pending

Date: 2026-09-07

This packet freezes the recovery protocol boundary needed before M4-core
implementation. It defines abstract messages, state transitions, and safety
invariants only. It is not an implementation, database schema, endpoint
design, provider selection, or claim that a recovery capability exists.

Normative inputs:

- [R0 durable account-recovery decision packet](R0-durable-account-recovery-decision-packet.md)
- [ADR-010 durable account-key recovery and device enrollment](ADR-010-durable-account-key-recovery.md)
- [RV-01 Google provider-capability probe packet](RV-01-google-provider-capability-probe-packet.md)
- [Security device-loss and recovery boundary](../security.md#10-device-loss-and-recovery-path)
- [REC-01 through REC-10 recovery milestone](../implementation-plan.md#15-pre-release-recovery-milestone)

## 1. Scope and symbolic provider boundary

The contract uses these symbolic values only:

```text
RECOVERY_ADAPTER        = TBD(RV-01)
UNWRAP_CAPABILITY       = TBD(RV-01)
CAPABILITY_PROOF        = TBD(RV-01)
PROVIDER_CONTEXT        = TBD(RV-01)
```

RV-01 must replace those symbols with a physically demonstrated capability
before implementation can select an adapter. No provider-specific primitive,
capability name, output string, or availability claim is normative here.

Google authentication identifies or authenticates a provider subject; it does
not unwrap an ARK. Login, Restore Credentials, an ordinary passkey assertion,
or any federated assertion is authentication/restore transport only and must
never be accepted as `UNWRAP_CAPABILITY`. The same rule applies if one of
those flows succeeds immediately before recovery.

If no `UNWRAP_CAPABILITY = TBD(RV-01)` passes the RV-01 durability, binding,
tamper, rollback, account-isolation, and no-secret evidence gates, the M4
recovery release remains blocked. There is no silent fallback to login,
Restore Credentials, an ordinary passkey assertion, provider presence, device
transfer, or a manual secret.

## 2. Account and provider-identity invariants

- `account_id` is the permanent Remanence account identity. It is independent
  of email addresses, handles, Google account names, and other login labels.
- An account may have multiple independently verified Google provider
  subjects. Each verified provider subject is globally unique and belongs to
  at most one Remanence account.
- There is no automatic account merge. A successful authentication by one
  provider subject cannot join, alias, or transfer another account.
- No provider subject or email is promoted to a primary recovery email. Email
  and other login identifiers remain authentication attributes only.
- The last login identity for an account cannot be unlinked. Unlinking any
  other identity requires the normal account-authentication and policy checks;
  it cannot delete the account's cryptographic identity or recovery state.
- Purchases, quotas, device enrollment, recovery wrappers, recovery sessions,
  and recovery readiness are owned by `account_id`, never by an email address
  or provider subject.

An authenticated provider subject may select an account only after the
server-side account binding has been established. Authentication alone does
not authorize an ARK unwrap or a cross-account operation.

## 3. Device limits and enrollment paths

Each device has an immutable device identity and an account binding. Only
devices in `ACTIVE` state count toward the limit. Historical, replaced,
revoked, or otherwise inactive records do not consume a slot.

The account has at most two `ACTIVE` devices.

### Normal addition

When an active device is available, a new device must be approved by an
active device through an authenticated transfer ceremony. The approving
device unwraps the ARK locally, and the new device receives only the
authenticated transfer result needed to create its own device-local wrapper.
The server never receives plaintext ARK or an unwrap capability.

### Third-device replacement

Adding a device when two devices are active is one atomic replacement
operation. It selects one eligible active device, records its replacement or
revocation, and activates the new device in one single-winner decision. A
concurrent replacement cannot leave three active devices or two competing
replacement winners.

The operation is idempotent for the same logical request. A replay returns
the already committed outcome or a safe conflict; it cannot allocate another
active slot or revive the replaced device.

### All-devices-lost recovery

When no active device can approve a new device, enrollment requires both:

1. fresh Google reauthentication for a verified provider subject already
   bound to the account; and
2. a successful `UNWRAP_CAPABILITY = TBD(RV-01)` operation physically proven
   by RV-01 to survive the required loss/restore cases.

The authenticated Google subject selects the account but does not supply the
unwrap. If either condition is absent, stale, ambiguous, or unverifiable,
the operation fails closed and no new active device is enrolled.

## 4. Abstract recovery objects and binding

The following are protocol concepts, not prescribed storage schemas or wire
formats.

### RecoveryPackage

The client creates a versioned `RecoveryPackage` containing only the private
account key material required by ADR-010, together with the authenticated
key-history metadata required to validate it. It contains no capsule media,
decrypted cache, login token, provider credential, or unrelated account data.

Every package is bound by authenticated metadata to:

- permanent `account_id`;
- package format/profile version;
- package generation and key-history generation;
- intended recovery purpose/context;
- the target device identity where target binding is required; and
- the protocol/application compatibility context.

The package is encrypted and integrity-protected before leaving the client.
The server may retain only opaque encrypted package material and public
metadata. It never receives plaintext ARK, private keysets, capsule keys,
manual recovery secrets, or a capability that can unwrap them.

### RecoveryWrapper

Each wrapper is versioned and has a unique wrapper identity, account binding,
generation, adapter marker `RECOVERY_ADAPTER = TBD(RV-01)`, provider context
`PROVIDER_CONTEXT = TBD(RV-01)`, target-device binding where applicable,
creation/revocation metadata, and authenticated purpose/context. The provider
context is not a substitute for `account_id` and is not a primary email.

The wrapper authenticates all binding fields as associated data. A wrapper
from another account, device, provider context, package generation, protocol
purpose, or recovery session must fail validation before any ARK plaintext is
released.

Generation is monotonic for an account's recovery material. A replacement
wrapper may supersede an earlier generation, but an older generation cannot
be installed after a newer generation has been accepted. Revoked wrappers
are never fallback candidates.

### Recovery request context

Every recovery attempt has a fresh, unpredictable session nonce and a
single-use logical request identity. The authenticated context includes the
account, target device, intended purpose, package/wrapper generation, and
`PROVIDER_CONTEXT = TBD(RV-01)`. The client and server must reject context
substitution, nonce reuse, account/device mismatch, and stale generation.

These bindings provide the protocol properties below without requiring the
server to possess an ARK unwrap capability:

- **anti-swap:** package and wrapper metadata must match the same account,
  target, generation, purpose, and provider context;
- **anti-replay:** a completed or expired session nonce and logical request
  cannot authorize a second installation or device allocation;
- **anti-rollback:** an accepted generation cannot be replaced by an older
  generation, including after local restore or provider rollback;
- **anti-tamper:** authenticated package, wrapper, and binding metadata must
  verify before decryption or installation; altered or ambiguous material
  fails closed; and
- **no downgrade:** authentication success without a valid unwrap result is
  never reported as recovery success.

## 5. Recovery-session state machine

The state machine is per target device and per logical recovery request. A
session is not an account readiness claim until it reaches `COMMITTED`.

```text
NEW
  -> AUTHENTICATED
  -> CAPABILITY_PROVEN
  -> WRAPPER_SELECTED
  -> PACKAGE_FETCHED
  -> UNWRAPPED
  -> VALIDATED
  -> INSTALLED
  -> COMMITTED

Any state
  -> FAILED_TERMINAL
Any retryable transport/provider interruption
  -> RETRYABLE
RETRYABLE
  -> the last durable state, or FAILED_TERMINAL
```

Required transition rules:

- `NEW -> AUTHENTICATED` requires fresh authentication bound to the target
  account. It does not prove an unwrap capability.
- `AUTHENTICATED -> CAPABILITY_PROVEN` requires an actual successful
  `CAPABILITY_PROOF = TBD(RV-01)` for this account, target, provider context,
  and session. A capability-detect result without an unwrap result is not
  enough.
- `CAPABILITY_PROVEN -> WRAPPER_SELECTED` requires a current, non-revoked
  wrapper whose binding matches the session. Multiple candidates do not
  permit guessing; ambiguity fails closed.
- `WRAPPER_SELECTED -> PACKAGE_FETCHED` accepts only the package selected
  for the same account and generation. Missing, revoked, or stale material
  cannot fall back to an older wrapper silently.
- `PACKAGE_FETCHED -> UNWRAPPED` requires local authenticated unwrap and
  integrity verification. No ARK bytes are exposed to the server or logs.
- `UNWRAPPED -> VALIDATED` requires package-version, account, generation,
  key-history, and protocol-context validation before key installation.
- `VALIDATED -> INSTALLED` writes the new device-local wrapper and required
  durable recovery state atomically from the client's perspective. Partial
  installation is not usable.
- `INSTALLED -> COMMITTED` requires the server-side single-winner enrollment
  or replacement result and a durable local readiness marker. The marker is
  not set on a timeout, unknown result, or failed server decision.
- Any account mismatch, target mismatch, invalid proof, wrapper/package
  authentication failure, generation rollback, replay, tamper indication,
  ambiguous candidate, or policy violation transitions to
  `FAILED_TERMINAL`.
- Network loss, provider unavailability, process death before a durable
  boundary, or a server response whose commit status is unknown transitions
  to `RETRYABLE` only when replay is idempotent and no safety decision is
  ambiguous. Retry must revalidate all bindings.
- A retry never resumes from an unverified in-memory ARK or assumes that a
  prior network write committed. It reopens the last durable state and
  converges to one committed winner or a terminal failure.
- `COMMITTED` is terminal for that logical request. Replays return its safe
  result and cannot create another device, wrapper, or active slot.

There is no transition from authentication directly to `UNWRAPPED`,
`INSTALLED`, or `COMMITTED`.

## 6. Concurrency, idempotency, and single-winner rules

- Account-device enrollment, third-device replacement, wrapper supersession,
  and recovery-session commit are serialized by one account-scoped logical
  decision. The exact database mechanism is an implementation choice outside
  this packet.
- A logical request identity is stable across retries and unique to the
  account, target device, operation, and intended generation. It cannot be
  reused for a different account or target.
- Concurrent requests for the same logical operation have exactly one
  committed winner. Losers observe the winner's durable outcome or a safe
  retryable/terminal result; they cannot overwrite it.
- Concurrent requests for different accounts never share recovery state,
  active-device slots, wrapper generations, or idempotency outcomes.
- A process death at any pre-commit boundary leaves no claim of readiness.
  Reopening the application either resumes a clearly durable retryable state
  or requires a fresh recovery session.
- A process death after commit is convergent: the client refreshes the
  durable account/device outcome and does not create a second winner.

## 7. Revocation and purge-before-use

Revocation stops future authenticated synchronization and enrollment for the
revoked local device. Before the device may use the account again, it must
complete a local purge of all Remanence-controlled account data that the
device is allowed to remove, including locally controlled wrappers, cached
key material, ciphertext, envelopes, indexes, and recovery-session residue.

The purge contract is:

- The next authenticated online contact learns the revocation and starts or
  resumes the purge before ordinary account use.
- Purge progress is durable and resumable. A crash, cancellation, or process
  death restarts from a safe durable boundary and never marks purge complete
  while protected residue remains.
- Each deletion is idempotent and owner/account scoped. Missing already-
  deleted material is a successful converged outcome; ambiguous ownership or
  unsafe deletion fails closed and preserves residue for a later safe retry.
- A revoked device cannot use offline cached account state to bypass the
  purge-before-use rule. Offline presentation of already-open material may
  follow the separate recipient semantics, but it cannot authorize new
  account use after revocation.
- The product must state honestly that screenshots, exports, already-decrypted
  copies, backups outside Remanence control, and other external copies cannot
  be remotely erased.

The server does not claim that revocation destroys data already obtained by a
device. Revocation controls future protocol access and the device's own
Remanence-controlled purge boundary.

## 8. Safe audit, telemetry, and privacy

Recovery telemetry may record only bounded, redacted facts needed to operate
and audit the protocol:

- event class, coarse result class, state transition, retryability, and safe
  policy reason;
- opaque non-user correlation identifiers;
- account/device roles or stable internal references only where access
  control and retention policy permit them;
- wrapper/package generation numbers and protocol/profile versions; and
- whether a purge or recovery session reached a durable boundary.

It must never record email addresses, provider subjects, provider tokens,
capability outputs, ARK or private key material, package plaintext,
ciphertext, decrypted content, full request/response payloads, paths,
database details, or raw exception messages/tracebacks. Logs and metrics must
not permit reconstruction of a provider credential or correlation of linked
identities by default.

Unknown, malformed, or sensitive provider errors map to bounded internal
reason classes. Error text shown to users is generic and does not echo server,
provider, key, package, or account details. Audit access is restricted,
retained for the minimum operational period, and reviewed as part of the M4
security gate.

## 9. Threat and error taxonomy

The implementation must classify outcomes into these protocol-level classes;
provider-specific details remain `TBD(RV-01)`:

| Class | Required outcome |
| --- | --- |
| `AUTHENTICATION_ONLY` | Authentication may continue, but recovery does not advance. |
| `CAPABILITY_UNAVAILABLE` | Fail closed; expose only a generic unavailable/recovery-not-ready result. |
| `CAPABILITY_REJECTED` | Fail closed; do not retry as a different capability without explicit policy. |
| `ACCOUNT_BINDING_MISMATCH` | Terminal failure; no cross-account lookup, unwrap, or enrollment. |
| `DEVICE_BINDING_MISMATCH` | Terminal failure; no package installation or slot mutation. |
| `WRAPPER_NOT_FOUND_OR_REVOKED` | Terminal for that wrapper; no unsafe older-wrapper fallback. |
| `PACKAGE_INTEGRITY_OR_FORMAT` | Terminal failure; discard uncommitted plaintext and preserve only safe diagnostics. |
| `GENERATION_ROLLBACK_OR_REPLAY` | Terminal failure and audit event; do not advance readiness. |
| `PROVIDER_OR_TRANSPORT_UNAVAILABLE` | Retryable only when the durable state and idempotency decision are unambiguous. |
| `CONCURRENCY_LOSS` | Return the committed winner or retry its durable outcome; never create a second winner. |
| `PURGE_INCOMPLETE_OR_AMBIGUOUS` | Block account use and preserve residue for safe retry. |
| `EXTERNAL_COPY` | Informational limitation only; never claim remote erasure. |
| `INTERNAL_FAILURE` | Generic failure, no sensitive detail, and no readiness or enrollment commit. |

An error-chain or provider message must not select a more permissive class
than the verified protocol evidence allows. In particular, successful login,
credential restoration, or ordinary passkey authentication cannot downgrade
`CAPABILITY_UNAVAILABLE` into recovery success.

## 10. Explicit non-goals

This packet does not:

- select or name a Google recovery primitive; all such details remain
  `TBD(RV-01)`;
- treat Google login, Restore Credentials, an ordinary passkey assertion, or
  account authentication as ARK unwrap;
- define SQL tables, Room entities, wire schemas, HTTP routes, SDK calls,
  provider capability strings, or platform adapter code;
- change the existing capsule encryption, sender revoke behavior, recipient
  tombstone behavior, or session protocol;
- provide server-side ARK escrow, plaintext key storage, a master recovery
  key, or a server unwrap service;
- automatically merge accounts, designate a primary recovery email, or use
  an email/provider subject as cryptographic identity;
- exceed two active devices or count historical devices toward that limit;
- remotely erase screenshots, exports, decrypted copies, or external backups;
- define a manual secret, Apple adapter, device-transfer fallback, or other
  provider as an M4 substitute without a separate reviewed decision;
- make an account silently recoverable, or unblock M4 before RV-01 passes;
- define UI wording beyond the requirement for honest generic outcomes; or
- authorize implementation, deployment, migration, recovery testing with
  production accounts, or release.

## 11. Acceptance checklist

RV-02 is ready for implementation review only when all of the following are
true:

- [ ] `account_id` permanence, provider-subject uniqueness, no merge, no
      primary email, and last-login-identity protection are accepted.
- [ ] Purchases, quotas, devices, wrappers, sessions, and readiness are
      account-owned.
- [ ] The two-active-device limit, history exclusion, normal approval path,
      and atomic single-winner third-device replacement are accepted.
- [ ] The all-devices-lost path requires fresh Google reauthentication plus a
      physically proven `UNWRAP_CAPABILITY = TBD(RV-01)`.
- [ ] The recovery-session state machine and fail-closed/retryable boundary
      are accepted, including process-death convergence.
- [ ] RecoveryPackage and RecoveryWrapper versioning, binding, generation,
      anti-swap, anti-replay, anti-rollback, and anti-tamper properties are
      accepted.
- [ ] The server-has-no-plaintext-ARK and no-server-unwrap-capability proof
      is accepted.
- [ ] Account-scoped idempotency, concurrency, and exactly-one replacement
      winner are accepted.
- [ ] Revoked-device purge-before-use, resumable purge, offline refusal, and
      external-copy limitation are accepted.
- [ ] Telemetry and error handling are demonstrably redacted and bounded.
- [ ] No implementation begins while the M4 recovery release gate is blocked.

## 12. Unresolved evidence dependencies

The following dependencies remain open and are owned by RV-01 or a later
security review:

1. RV-01 must identify whether any tested Google path can instantiate
   `UNWRAP_CAPABILITY = TBD(RV-01)` and pass every required physical loss,
   restore, account-isolation, provider-loss, rollback, and tamper case.
2. RV-01 must provide the exact adapter binding context and safe capability
   proof semantics without exposing provider secrets or ARK material.
3. The recovery gate must confirm that the selected capability is client-held
   and that server-observed material remains opaque.
4. M4 must define the concrete authenticated package/wrapper encoding,
   durable state storage, device enrollment transaction, and purge journal
   only after this contract and RV-01 evidence are approved.
5. A separate security review must validate generation rotation, provider
   loss, account unlinking, rollback, process death, concurrent replacement,
   and purge interruption on the selected platforms.

Until these dependencies are closed, this packet remains Proposed, no
provider is selected, and the recovery release remains blocked.
