# ADR-012: FRONT-only recognition identity

Status: Accepted architecture checkpoint; implementation pending

Date: 2026-09-02

## Context

The postcard FRONT identifies a visual design. A design can map locally to zero,
one, or many decryptable capsules available to the authenticated recipient.
The BACK is not part of production recognition, enrollment, matching, or
presentation authorization.

The Test8/Test9 recognition state is disposable development state. A clean app
database and clean server recognition state are accepted deployment
prerequisites for this breaking reset. Historical evidence under
`docs/evidence/` and `.agent/reviews/` remains untouched and is historical
record only; its old BACK wording is not normative.

## Decision

### Single production contract

- Create captures and persists exactly one required FRONT fingerprint.
- Scan captures exactly one FRONT fingerprint.
- The local owner-scoped relation is `design -> 0..N capsules`.
- Zero candidates produce `NO_MATCH`.
- One candidate may proceed to complete E2EE verification and presentation.
- Multiple candidates are an explicit ambiguity result. The picker is deferred
  to M2-F1; until then none may auto-open.
- BACK is absent from the production recognition model, encrypted manifest,
  Create/Scan state, local index, acceptance path, and grant preconditions.
- A front match is never cryptographic authorization. Envelope, statement,
  artifact binding, AEAD, and recipient-account checks remain mandatory before
  any presentation grant or plaintext.

### Manifest format

The encrypted recognition artifact uses one explicit front-only manifest
format/version. The version exists only to reject unsupported or malformed
payloads fail-closed; it is not an identity-mode selector. The manifest
contains a required FRONT fingerprint, capsule binding, and minimal encrypted
chooser hint. It contains no BACK field.

The outer REST/protocol, signed statement, envelope, artifact AAD, and
recognition-blob cardinality remain unchanged unless a separate protocol ADR
requires otherwise. The backend treats the encrypted recognition bytes as
opaque and does not build a visual index or uniqueness relation.

`mvp-orb-v1` remains the fingerprint extraction/profile identity. Its profile
ID is independent of manifest parsing and must not be overloaded to represent
the front-only product contract.

### Local storage and ownership

The local index is account-owner scoped and stores a required FRONT fingerprint
per capsule. The same design may have many capsule IDs. Recipient FRONT
baselines may be preferred over sender-derived FRONT references, while all
candidate sources remain scoped to the authenticated owner.

Room/data migration is not part of this reset. Test8/Test9 local and server
recognition state may be discarded and recreated from the new schema. No
production path is required to read or repair old two-sided rows or files.

### Security and lifecycle

The scan grant remains short-lived, in-memory, capsule-scoped, and issued only
after a current FRONT scan, an unambiguous accepted result, and complete crypto
verification. Logout, process death, expiry, and leaving presentation revoke
the grant. The grant contains no visual bytes or BACK material.

## Bounded implementation slices

1. Replace the recognition domain and local index contract with required FRONT
   and design-to-many candidate identity; keep the explicit manifest format
   version and reject unsupported versions.
2. Implement the front-only recognition manifest, crypto acceptance, and
   outgoing artifact construction without changing statement/envelope
   security bindings.
3. Implement front-only incoming acceptance, Room/index persistence, and
   owner-scoped offline candidate loading on the clean schema.
4. Implement Create as recipient confirmation → FRONT capture → content/photo
   selection → encryption → outgoing staging/upload.
5. Implement Scan as FRONT capture → local 0/1/N classification → crypto
   verification → grant or no-match/ambiguity result. Defer picker UI.

Each slice is independently tested before the next slice. No legacy path,
dual reader, optional BACK production mode, or migration is a completion
criterion.

## Deferred milestones

- M2-F1: recipient-facing picker for multiple FRONT candidates.
- M2-F2: conservative sender+recipient duplicate prevention using FRONT
  similarity; no global uniqueness rule.
- M2-F3: optional short cancellation window with durable revoke semantics.

## Consequences

The reset intentionally breaks recognition compatibility with Test8/Test9
two-sided local/server state. A clean app/DB reset is required for deployment
of the new implementation; no data deletion is performed by this ADR. Captured
BACK images in the research dataset remain ancillary calibration material and
are excluded from production contracts and benchmarks.
