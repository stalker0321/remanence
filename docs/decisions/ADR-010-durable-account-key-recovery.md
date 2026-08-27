# ADR-010: Durable account-key recovery and device enrollment

Status: ACCEPTED as a pre-release architecture; platform adapters require a security spike

Date: 2026-08-27

## Context

Remanence memories are expected to remain usable for years. Authentication can
prove which server account a person controls, but it cannot reconstruct private
E2EE keysets lost with an installation. Android Keystore, iOS Keychain, a
passkey signature, and a federated-login assertion are therefore not an account
recovery protocol by themselves.

The M0-M2 implementation keeps exportable HPKE and signing keysets wrapped by a
non-exportable device KEK. This is safe for the initial device but insufficient
for public release: uninstalling the app or losing the only device can otherwise
make every old capsule permanently unreadable.

## Decision

### Account recovery root

Each account has a random 256-bit **Account Recovery Key (ARK)**. The ARK is a
platform-neutral wrapping root, not a device key, authentication credential,
capsule key, or signing identity:

```text
DeviceKey != ARK != RecoveryCredential != capsule key
```

Capsules continue to use random per-capsule AEAD keys and the existing account
HPKE/signing key bundles. The ARK does not directly encrypt capsule media and is
not used as a deterministic seed for those keys.

The ARK encrypts a versioned **RecoveryPackage** containing only private account
key material still required to restore access:

- the current active HPKE and signing private keysets;
- retired HPKE private keysets while old recipient envelopes still target them;
- immutable bundle IDs and authenticated key-history/transition records needed
  to validate the package generation.

Retired signing private keys are excluded by default. Historical capsule
signatures require the corresponding public keys, which belong in the durable
public directory/key-transition history, not private recovery escrow. A retired
signing private key may be retained only if a future protocol explicitly
requires producing a new signature under that retired key; such a protocol
requires its own ADR and retention/expiry rule. Key rotation must update and
test the recovery package before an old decrypting HPKE key is removed.

On a device, the ARK is wrapped by a device-local hardware-backed key where
available. Loss of that device wrapper does not destroy the server-held
encrypted recovery package or independently configured recovery wrappers.

### Recovery wrappers

The backend may store multiple opaque wrappers for the same ARK:

```text
RecoveryWrapper {
    wrapper_id
    user_id
    generation
    type
    encrypted_ark
    public_metadata
    created_at
    revoked_at?
}
```

The key that encrypts `encrypted_ark` must be available only through the
selected recovery mechanism. The backend stores encrypted packages, encrypted
ARK wrappers, public credential/device metadata, salts, and versions; it never
receives plaintext ARK, private account keysets, manual recovery secrets, or
capsule keys.

Supported adapter classes may include a credential exposing a verified
client-side PRF/KDF output, an existing-device transfer using authenticated
ephemeral key agreement, an optional random manual recovery secret, or a future
provider mechanism satisfying the same boundary.

A normal passkey assertion or federated-login token is authentication only. It
must not be described as decrypting a recovery wrapper. WebAuthn `prf` is a
possible adapter, not a universal assumption: clients capability-detect it and
offer another method when unavailable. Android Restore Credentials may restore
or sign in an account, but E2EE recovery still requires a separately proven
client-held unwrap capability.

### Devices and transfer

Devices are registered independently from account identity. Each registration
has an immutable device ID, platform, public enrollment key, creation/last-seen
timestamps, and optional revocation time. An existing device may approve a new
device, unwrap the ARK locally, and transfer it through an authenticated
ephemeral channel. The new device creates its own local device wrapper and
should add a recovery adapter usable in its ecosystem.

Device revocation stops future authenticated synchronization and enrollment.
It cannot erase ciphertext or keys already obtained by a stolen device, and the
UI must not imply remote destruction.

### Recovery readiness

Registration is not silently considered recoverable. The client records a
verified recovery-readiness state only after at least one wrapper has been
created and test-unwrapped or otherwise proved usable. If no durable method is
available, the user receives a clear device-loss warning and an alternate or
explicit-deferral path according to release policy.

The ordinary UI says that memory recovery is protected or needs setup; it does
not expose ARK terminology. A manual random recovery secret is an optional
advanced fallback, never mandatory onboarding.

### Restoration

After authentication on a replacement installation, a recovery adapter obtains
its client-held unwrap capability, recovers the ARK locally, decrypts and
validates the RecoveryPackage locally, and installs account keysets under a new
device-local wrapper. Encrypted capsule/fingerprint synchronization can then
rebuild the offline working copy. Physical scanning remains the presentation
gate; recovery does not create a gallery.

If every device and every independent recovery secret/provider credential is
lost, content remains unreadable. The server must not gain escrow capability to
avoid this honest E2EE failure mode.

## Release gate

This architecture is not implemented in M2. A dedicated pre-release milestone
must ship and be security-reviewed before public release. Before selecting the
default adapter, verify current behavior for Android backup/Restore Credentials,
credential providers, passkey/WebAuthn PRF support, Apple/iCloud behavior,
browser/PWA limitations, cross-platform migration, provider loss, rollback,
wrapper replacement, and device revocation.

## Consequences

- M2 remains a two-device product proof and must state that its device-local
  identity is not yet recoverable.
- Existing still-required exportable Tink private keysets become payload inside
  a versioned recovery package rather than being replaced by an ARK-derived
  identity; retired signing private keys are not retained without an explicit
  protocol need.
- Server compromise alone does not reveal the ARK or capsule plaintext.
- Multiple platforms can protect the same recovery root without creating
  different Remanence accounts or re-encrypting every capsule.
- Platform APIs remain adapters, so one provider can be replaced without a
  recovery-protocol redesign.
