# ADR-006: Canonical crypto context encoding

Status: Accepted for protocol v1

## Context

Capsule artifact AEAD associated data and HPKE envelope context info must be one exact byte string on every implementation. Logical field lists already exist in `security.md`, but an unspecified concatenation of strings or varints would be ambiguous as the schema evolves. Publish signature input (`"postmark/publish/v1" || deterministic PublishStatement bytes`) is already documented and is outside this decision.

## Decision

- Artifact AAD bytes are UTF-8/ASCII `"postmark/artifact/v1"` + one `0x00` delimiter + deterministic protobuf bytes of `ArtifactAadContext`.
- HPKE context info bytes are UTF-8/ASCII `"postmark/envelope/v1"` + one `0x00` delimiter + deterministic protobuf bytes of `RecipientEnvelopeContext`.
- The domain prefix is not a protobuf field. The `0x00` delimiter is mandatory.
- Context protobuf messages must be fully populated: protocol version exactly 1; typed IDs exactly 16 bytes; `artifact_kind` cannot be `ARTIFACT_KIND_UNSPECIFIED`; ordinal is `-1` for non-photo artifacts and `0..4` for `PHOTO`.
- Unknown version/kind or malformed IDs fail closed before AEAD or HPKE is invoked.
- No JSON, string UUID, locale, or varint hand-concatenation outside protobuf.
- A wire-affecting change requires a new protocol version, ADR, and vectors.

## Alternatives

- Delimiter-free or manual field concatenation: rejected because of ambiguity and evolution risk.
- JSON context objects: rejected; noncanonical for signed/encrypted bindings.
- One generic context message for both domains: rejected; permits wrong fields and domain confusion.

## Consequences

Android and server reconstruct identical AAD/info bytes from typed IDs and layout. Artifact AEAD and envelope HPKE cannot be mixed by omitting a prefix or sharing one message type. Publish signatures stay on the existing `"postmark/publish/v1" || statement` input.
