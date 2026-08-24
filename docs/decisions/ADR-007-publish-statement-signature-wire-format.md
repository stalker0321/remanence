# ADR-007: Publish statement signature wire format

Status: Accepted for protocol v1

## Context

The publish statement is signed with the sender Ed25519 keyset (docs/security.md section 6.4) and transported by REST as opaque signature bytes (docs/protocol.md section 3). Tink `PublicKeySign` emits its own output-prefix framing, and implementations could plausibly strip it to a bare 64-byte `r||s`, re-wrap it, or accept multiple encodings. Without one frozen wire form, the backend and recipients cannot validate signature length or route signatures to historical key bundles unambiguously.

## Decision

- The signing keyset is generated from the Tink `ED25519` template with output-prefix variant `TINK`.
- REST signature bytes are exactly the raw Tink output and are exactly 69 bytes: `0x01 || key_id(4B big-endian) || r||s(64B)`. `0x01` is the TINK prefix-type byte; the four bytes are the primary key ID in network byte order.
- No prefix stripping, no `RAW` signing, no fallback decode path. Signers fail closed if their primitive emits anything but this exact framing; verifiers apply a structural length/prefix/key-ID guard before invoking Tink verification, which remains the cryptographic authority.
- Signature input remains `"postmark/publish/v1" || deterministic_statement_bytes`.
- A fixed non-secret Ed25519 keyset plus exact Android/backend golden vector are checked in at `protocol/fixtures/publish-signature-v1.json`; both platforms must reproduce/accept those bytes for protocol v1.

## Alternatives

- Stripping the 5-byte prefix to transport bare 64-byte Ed25519: rejected — it loses in-band key identification needed for historical bundle lookup and creates a second decode path.
- `RAW` output prefix: rejected because the approved Tink template model is `TINK` (consistent with ADR-005 artifact framing) and v1 must not vary.
- Accepting both prefixed and stripped forms with auto-detection: rejected — heuristic alternate decoding is forbidden by docs/security.md.

## Consequences

Every producer and verifier can assert an exact 69-byte signature before any cryptographic work; malformed or foreign-framed signatures fail closed with no primitive invocation. Key bundles can be located by the embedded key ID without parsing protobuf on the hot path. Any change requires a new protocol version, ADR, and fresh cross-platform vectors.
