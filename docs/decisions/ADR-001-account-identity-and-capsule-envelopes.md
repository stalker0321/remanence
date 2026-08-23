# ADR-001: Account identity and capsule envelopes

Status: Accepted for protocol v1

## Context

The server must route capsules without learning content keys. Android Keystore does not provide a uniformly portable hardware X25519 identity that also leaves a future recovery path. Recipient confidentiality and sender authenticity are separate properties.

## Decision

- Each account key bundle contains independent Tink HPKE X25519 and Ed25519 keysets.
- Public keysets and immutable key-bundle IDs are stored by the directory.
- Exportable private keysets are encrypted locally by a non-exportable Android Keystore AES-GCM KEK.
- Each capsule has a fresh Tink AES256_GCM keyset wrapped in one HPKE recipient envelope.
- An Ed25519 publish signature binds the complete declared ciphertext set.

## Alternatives

- Derive a key from postcard pixels: rejected; unstable, observable, and low-entropy.
- Store all private identity material only as non-exportable hardware keys: rejected for v1 because algorithm/device portability and future recovery become accidental dead ends.
- Encryption without sender signature: rejected because a meaningful addressed memory should have origin integrity relative to the directory key.
- One asymmetric key for signing and encryption: rejected; violates key-purpose separation.

## Consequences

Database/object compromise does not reveal content. A compromised unlocked/rooted client can invoke/unlock keys. Recovery remains possible but is not implemented through M2. A malicious first-contact directory still requires future key transparency/out-of-band verification.
