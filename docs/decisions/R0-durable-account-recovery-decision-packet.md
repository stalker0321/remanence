# R0: Durable account-recovery decision packet

Status: Proposed

Date: 2026-09-07

This packet records the user-agreed recovery boundary and the decisions needed
before implementation. It supplements, and does not replace, [ADR-010: Durable
account-key recovery and device enrollment](ADR-010-durable-account-key-recovery.md).
It is a decision packet, not a recovery implementation, schema, wire fixture,
or provider claim.

## Frozen invariants

- The durable Remanence identity is an `account_id`, independent of any email
  address. Email addresses and other login identifiers are authentication
  attributes, not the cryptographic account identity.
- An account may link multiple independently verified Google identities. One
  provider subject globally belongs to one account; there is no automatic
  account merge. Each linked verified Google identity may authenticate and
  receive for its account, and the last login identity cannot be unlinked.
  No linked identity is promoted to a primary email for recovery or
  decryption. The current MVP email/password login remains an authentication
  mechanism only.
- Purchases, quotas, device enrollment, and recovery state belong to
  `account_id`, never to an email address or provider subject.
- An account has at most two **ACTIVE** devices; historical registrations do
  not count. A third-device enrollment atomically replaces one active device.
  Normal addition requires approval from an active device. If all devices are
  lost, replacement is allowed only after successful Google-backed ARK recovery
  and fresh Google reauthentication.
- Device revocation stops future authenticated synchronization and enrollment.
  A revoked device must purge all Remanence-controlled account data on its next
  online contact and resume an interrupted purge before allowing account use.
  This cannot erase data already received or decrypted, screenshots, exports,
  or other copies outside the app's control.
- Authentication is not decryption. A password reset, federated assertion,
  Google login, ordinary passkey assertion, or account restore may establish
  account access but must not be described as an ARK unwrap capability.
- The ARK is random and distinct from the device key, recovery credential,
  signing identity, and capsule key. The server may retain only encrypted
  packages and opaque wrappers; it never receives plaintext ARK, private
  keysets, manual recovery secrets, or capsule keys.
- Recovery is not considered ready until a client-held capability has actually
  unwrapped and validated a recovery package, or the product explicitly shows
  the account as not recoverable.

These invariants are independent of the exact Google primitive selected. They
are not permission to substitute authentication for decryption.

## Current decision boundary

Google is the required baseline adapter family, but no Google-only capability
is established yet. Google account authentication is authentication only.
Restore Credentials is likewise auth/restore transport only. Block Store and/or
Google Password Manager/passkey PRF are candidate Google mechanisms; RV-01
must prove the exact primitive and provider/authenticator behavior needed for a
stable client-held ARK unwrap capability.

If no Google primitive satisfies the security and durability evidence gates,
the recovery release is blocked. Existing-device transfer and an optional
manual random recovery secret are future additions, not baseline fallbacks.
Apple and other providers are later adapter families.

The existing recovery architecture and its release gate remain normative:

- [ADR-010 recovery wrappers and authentication boundary](ADR-010-durable-account-key-recovery.md#recovery-wrappers)
- [ADR-010 release gate](ADR-010-durable-account-key-recovery.md#release-gate)
- [REC-01 through REC-10 implementation queue and probe requirement](../implementation-plan.md#15-pre-release-recovery-milestone)
- [Security device-loss and recovery boundary](../security.md#10-device-loss-and-recovery-path)

No new provider procedure is reproduced here. The existing REC-01
device/browser/provider probe protocol and its evidence rules must be used for
RV-01.

## Sequence and boundaries

The agreed order is:

```text
R0 contract freeze
  ├── S1 one-shot server maintenance (independent operational track)
  └── RV-01 physical/provider capability probes
          ↓
       RV-02 recovery protocol and policy freeze
          ↓
       M4-core implementation and destructive recovery evidence
```

S1 remains independent of recovery and is not a recovery dependency. S1
implementation and deployment status are intentionally not recorded in this
R0 packet.

## Minimum readiness and evidence matrix

| Boundary | Minimum evidence | State |
| --- | --- | --- |
| Account identity | Stable `account_id`; verified Google identities are account-linked; one provider subject maps to one account; no automatic merge or primary-email rule; last login identity cannot unlink | R0 frozen |
| Account ownership | Purchases, quotas, devices, and recovery state are keyed by `account_id` | R0 frozen |
| Authentication/decryption | Login succeeds without yielding ARK or private key material; recovery fails closed without a client-held capability | R0 frozen |
| Google baseline capability | Provider/authenticator evidence for a client-held ARK unwrap capability across fresh install, restore, account change, loss, and rollback cases | RV-01 evidence gate; no capability established; failure blocks recovery release |
| Device limit | Two ACTIVE devices; history excluded; third device atomically replaces one; active-device approval or Google-backed ARK recovery plus fresh Google reauth | R0 frozen; RV-02/M4 evidence |
| Revocation purge | Revoked device purges all Remanence-controlled account data at next online contact and resumes interrupted purge before use; external copies survive | R0 frozen; RV-02/M4 evidence |
| Recovery package | Client decrypts and validates the versioned package; server stores only opaque/encrypted material | RV-02/M4-core OPEN |
| Rotation and rollback | Generation, wrapper replacement, provider loss, replay, rollback, and tamper outcomes are fail-closed | RV-02 OPEN |
| Recovery readiness | No silent “recoverable” state; tested wrapper or explicit unrecoverable warning | M4-core OPEN |

## Evidence gates, not user-policy choices

No user-policy choice remains open in this packet. The following are
observable RV-01 gates whose result must be recorded, not assumptions or
alternate user decisions:

1. Which Block Store and/or Google Password Manager/passkey PRF path is
   actually available on the supported Google device/provider combinations?
2. Does the proven Google primitive produce a stable client-held capability
   across fresh install, restore, account change, complete device loss,
   rollback, and provider loss without exposing ARK or private key material?
3. Does the evidence satisfy the security and durability bar for release? If
   not, the baseline recovery release remains blocked; it does not silently
   fall back to login, Restore Credentials, Apple, device transfer, or a manual
   secret.

## Next executable doc/probe tasks

- R0: use this packet as the input checklist for RV-01/RV-02 review. The
  account, identity, device, purge, and authentication/decryption invariants
  are frozen; only the provider evidence gates remain unresolved.
- RV-01: execute the existing REC-01 provider-capability probe protocol for the
  Google baseline on fresh installations and supported provider/authenticator
  combinations. Record only capability, durability, loss, restore, account
  change, rollback, and no-secret evidence; do not define identity/device or
  purge contracts in RV-01.
- RV-02: define the identity-link, device-limit/replacement, revocation/purge,
  versioned RecoveryPackage, wrapper, generation, and fallback contracts from
  the frozen invariants and RV-01 evidence; include the server-has-no-unwrap-
  secret proof.
- M4-core: implement Android/server changes only after RV-02 approval, then run
  fresh-install, lost-device, provider-loss, rollback/tamper, two-device, and
  offline capsule-opening evidence as a separate gated packet.
