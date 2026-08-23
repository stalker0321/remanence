# ADR-005: Capsule artifact AEAD wire format

Status: Accepted for protocol v1

## Context

Every capsule artifact (recognition manifest, content manifest, each photo) is encrypted with a fresh capsule Tink AES-256-GCM keyset. Validators and clients need one canonical ciphertext length relative to plaintext. Android Keystore KEK wrapping and the HPKE recipient envelope are different constructions and are not governed by this artifact-overhead decision.

## Decision

- The capsule keyset primary AES-256-GCM key uses Tink output-prefix variant `TINK`, not `RAW`, `CRUNCHY`, or `LEGACY`.
- Artifact ciphertext wire bytes are `5-byte Tink prefix || 12-byte IV || plaintext-length ciphertext || 16-byte tag`. Exact overhead is 33 bytes.
- The 5-byte prefix is a routing/key-ID hint, not an authentication substitute. Integrity is the AEAD tag and AAD.
- Normalized photo plaintext max is 8,388,608 bytes; encrypted photo ciphertext max is exactly 8,388,641 bytes.
- The same 33-byte rule applies when validating other capsule artifact ciphertext versus its plaintext. Published absolute caps for recognition (1 MiB) and content (64 KiB) remain ciphertext caps.
- No custom crypto, no `RAW` fallback, and no heuristic alternate decoding. A wire-affecting change requires a new protocol version, ADR, and vectors.

## Alternatives

- `RAW` prefix: rejected because the approved Tink keyset/template/default routing model is `TINK` and exact v1 must not vary.
- Unspecified or conservative slack on ciphertext size: rejected because validators need a canonical bound.
- Custom AES-GCM framing: rejected custom crypto.

## Consequences

Photo and other artifact encrypt/decrypt paths share one length identity: ciphertext = plaintext + 33. Servers can reject oversized photo ciphertext at 8,388,641 bytes without knowing plaintext. Prefix bytes never stand in for AEAD verification. Envelope and Keystore wrapping stay out of this bound.
