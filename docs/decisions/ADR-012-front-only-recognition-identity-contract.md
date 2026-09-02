# ADR-012: FRONT_ONLY recognition identity and v1 transport compatibility

Status: Accepted architecture checkpoint; implementation pending

Date: 2026-09-02

## Context

The original M1/M2 recognition contract required a prepared postcard back and
treated a physical card as if it identified one capsule. That is too strict
for the revised product. The postcard front is the design identity; a design
may have no capsule, one capsule, or several capsules owned by the recipient.
The back is not mandatory and may become a later disambiguator.

Test8/Test9 already-produced two-sided capsules must remain readable. A client
upgrade must not rewrite their manifests, synthesize a missing back, or
reinterpret an old profile as a new identity mode. The transport and server
must not learn a visual identity or a uniqueness relation merely to support
the new client contract.

## Decision

### Compatibility boundary

- Existing two-sided capsules use the strict inner recognition manifest v1:
  FRONT and BACK are both required, and the existing `mvp-orb-v1` fingerprint
  profile remains applicable.
- New `FRONT_ONLY` capsules use an explicitly versioned encrypted inner
  recognition manifest v2 with an explicit identityMode (`identity_mode` on
  the wire). FRONT is
  required. In strict `FRONT_ONLY`, BACK is absent; the wire field may be
  optional only for a separately named future mode that explicitly permits it.
- Identity mode is never inferred from BACK presence/absence, profile ID, or a
  missing field. Unsupported inner versions/modes fail closed, so an old
  client safely rejects v2 rather than treating it as v1.
- The encrypted recognition artifact remains exactly one artifact per capsule.
  Its outer server transport, REST/protocol version, publish statement,
  artifact AAD, and blob cardinality remain v1 initially. The server treats
  the inner recognition bytes as opaque and performs no visual indexing or
  design uniqueness check.

### Recognition identity

- `mvp-orb-v1` remains the fingerprint algorithm/profile unless a later ADR
  changes the algorithm. Profile/version and identity mode are separate
  fields and compatibility decisions.
- On the recipient, the local, account-owner-scoped index maps one recognized
  FRONT design to zero or more local capsule candidates. Zero means
  `NO_MATCH`; one candidate may proceed to full verification and opening; more
  than one is ambiguity and must never auto-open. A future recipient picker
  presents the plausible candidates explicitly.
- Every selected candidate still passes complete E2EE verification: immutable
  bindings, envelope, signature, ciphertext hashes, AAD, and artifact AEAD
  before any presentation grant or plaintext.
- There is no global/server visual index, cross-owner candidate search, or
  server-visible uniqueness leakage.

### Local compatibility and storage

- `SenderIndexBundle` gets an explicit version. The reader is dual-mode: it
  reads the existing two-sided v1 representation and the v2 representation
  carrying explicit identity mode, with fail-closed unknown-version handling.
- Prove that the current Room v7 schema and existing owner-scoped rows can
  represent the design-to-many relation before considering a migration. This
  checkpoint prescribes no Room schema change.
- Incoming acceptance, local persistence, and scan matching retain legacy v1
  regressions. A v1 row is not upgraded by manufacturing BACK or changing its
  identity mode.

### Bounded rollout

Implementation follows the queue in `docs/implementation-plan.md`:

1. add a typed FRONT_ONLY seam and explicit mode/version types;
2. add dual readers for the inner manifest and `SenderIndexBundle`, proving
   Room v7 suffices;
3. wire outgoing and incoming encrypted artifact handling while outer v1
   declarations remain unchanged;
4. add FRONT_ONLY Create;
5. add FRONT_ONLY Scan with zero/one/many fail-safe classification;
6. add the recipient ambiguity picker.

The legacy two-sided Create/Scan and upgrade regressions remain required at
each applicable gate.

## Deferred decisions

- Conservative sender+recipient duplicate prevention is a separate future
  milestone. It must not block legitimate multiple capsules for one design or
  expose a visual equality signal to the server.
- A short, optional 24-hour cancellation window is a separate future
  milestone. Revoke requires a durable server tombstone that prevents
  resurrection; it cannot erase recipient copies already downloaded or
  decrypted.
- M3 benchmarks design-to-many behavior. A physical BACK is an optional
  future disambiguator, not a prerequisite for FRONT_ONLY acceptance.

## Consequences

New clients can read legacy two-sided v1 and can introduce v2 without a server
transport migration. Old clients reject v2 safely. The local index and UI must
handle a design with zero, one, or many capsule candidates, and the absence of
a BACK no longer carries implicit semantics. No source implementation or Room
migration is claimed by this architecture checkpoint.
