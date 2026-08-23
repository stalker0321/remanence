# ADR-002: Encrypted recognition material and no raw scan upload

Status: Accepted for protocol v1

## Context

The postcard back can contain address, handwriting, signature, and private text. Recipient recognition must bootstrap before capsule presentation, but the backend must not become a CV or plaintext descriptor service.

## Decision

- Raw front/back scans never upload in MVP, plaintext or ciphertext.
- Sender uploads ORB fingerprints/keypoints only inside the capsule-encrypted recognition manifest.
- Recipient downloads routed envelopes/manifests and performs all matching locally.
- Recipient-after-delivery fingerprints are stored locally encrypted and become preferred.
- Derived descriptors are treated as sensitive, not anonymous.

## Alternatives

- Plaintext descriptors on server: rejected; leaks visual structure and expands trust boundary.
- Encrypted raw scans: not required by the current matching protocol and increases highly sensitive retention.
- Server-side CV: rejected; contradicts privacy/local-first product architecture.
- Pixel similarity: rejected; fails on perspective, lighting, postal marks, wear, and partial occlusion.

## Consequences

Incoming encrypted index material must synchronize before first receipt. The server sees routing and ciphertext size but not postcard visual data. Algorithm/profile migration cannot rebuild a sender fingerprint from server raw pixels, so old profile compatibility or an explicit rescan is required.
