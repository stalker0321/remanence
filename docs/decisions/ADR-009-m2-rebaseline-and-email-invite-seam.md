# ADR-009: M2 rebaseline and email-invite extension seam

Status: ACCEPTED for the M2 rebaseline; email target identity choice deferred

Date: 2026-08-26

## Context

The original M2 queue predated the completed M1 vertical slice and its state,
crypto, storage, and lifecycle hardening. M1 now contains real account keys,
artifact encryption, a signed publish statement, a recipient envelope, a
ciphertext outbox, incoming/index tables, local matching, and a memory-only
scan grant. M2 must generalize those mechanisms for two accounts rather than
build parallel replacements.

A future product requirement also allows a sender to address a person by
verified email before that person has an account. The postcard carries only
branding/discovery information, never a claim secret. The server must never
receive the capsule key. This requirement is valuable, but it is not part of
the M2 critical path.

## Decision

### M2 boundary

M2 proves transfer between two already-existing users with immutable,
non-null user IDs and active key bundles. Handles remain required for M2
registration and recipient confirmation. Email invitations, federated login,
optional handles, push, and sender key-delivery jobs are deferred to M2.x.

Server routing work may proceed while the M1 physical-device test is pending.
Create/Scan integration cannot pass its merge gate until the M1 CameraX and
OpenCV smoke flow passes on hardware or its findings have been incorporated.

### Generalize the M1 path

M2 extends the existing publisher, outbox, incoming persistence, acceptance
gate, matching, and presentation route. There must not be a second M2-only
publisher or scan state machine. The self-recipient guard is removed only
after distinct-user crypto and state regression tests pass.

### Durable sender re-wrap material

`RECIPIENT_KEY_STALE` can occur after process death. A recipient envelope is
not sender-readable in a two-user flow, so it cannot recover the capsule key
for re-enveloping. Before upload work begins, Android therefore persists a
sender-owned wrapped copy of the capsule keyset:

- bound to local account ID, capsule ID, sender key-bundle ID, protocol, and
  purpose through versioned associated data;
- encrypted under the sender's existing Keystore/identity boundary;
- stored only in app-private files with an opaque Room reference;
- never uploaded and never stored as plaintext;
- retained through process-death retries and removed after successful
  finalize, abort, or terminal cleanup.

This is retry material, not a gallery/recovery feature. A future email invite
may need a longer-lived server-routed sender envelope; that requires a
separate threat model and protocol decision.

### Account-scoped local state

M1's single-account assumptions are insufficient when a device logs out as A
and logs in as B. Incoming capsules, outbox rows, fingerprints, blob cache,
sync cursors, files, and WorkManager chains are scoped to the local account
ID. DAOs and candidate-index queries require that scope; no fallback query may
return another account's records. Work names include the account ID, workers
revalidate it before every durable transition, and logout cancels that
account's work and scan grants. Retention versus purge of opaque ciphertext is
an explicit policy, but retained rows remain inaccessible to another account.

### Two-stage recipient verification

M1's `CapsuleAcceptanceGate` assumes every declared blob is already present.
M2 needs recognition material before downloading all photos. Verification is
therefore factored around one canonical statement/ID verifier:

1. **Control/index acceptance** verifies canonical statement bytes and full
   artifact layout, authoritative sender bundle/signature, routed and
   authenticated IDs, recipient envelope context/plaintext, and the downloaded
   recognition blob's declared size/hash and AEAD. Undownloaded content/photo
   bindings remain declarations, never claims of delivered verification.
2. **Presentation acceptance**, invoked only after a current physical scan
   identifies the capsule, requires every content/photo ciphertext, verifies
   every statement binding size/hash, decrypts and validates the content
   manifest, and only then publishes a presentation grant and permits
   note/photo plaintext on demand.

Locally encrypted sender fingerprints may enter the candidate index after the
first gate. Background caching performs transport hash verification only for
content/photos; it does not decrypt the content manifest. A presentation grant
requires a current physical scan plus the second gate. Missing content
produces a connectivity-required state and no partial plaintext.

### Delivery semantics

`INDEX_CACHED` is local-only. Server state `CIPHERTEXT_SYNCED` means all
required ciphertext is durably cached and hash-checked, not merely the
recognition index. The server never records scan, recognition, decryption,
opening, or recipient-fingerprint events.

### Upload and finalize trust boundaries

WorkManager receives only capsule ID and expected local account ID. It reloads
opaque outbox material, rechecks the account, and uses compare-and-set,
idempotent transitions. Object promotion cannot be atomic with PostgreSQL;
rollback may leave an unreferenced ciphertext object, which bounded garbage
collection removes.

Finalize resolves key bundles authoritatively by ID. The recipient bundle must
be ACTIVE and owned by the recipient. A sender signing bundle must belong to
the sender and be non-REVOKED; a valid RETIRED sender bundle is accepted so a
rotation during upload does not destroy an otherwise authenticated draft.
Neither server nor recipient trusts public keys embedded beside capsule data.

### Email-invite seam and protocol constraint

Future domain code may model:

```text
RecipientTarget = ExistingUser(user_id, key_bundle_id)
                | PendingEmail(normalized_email)
```

M2 implements only `ExistingUser`. It creates no pending-email tables,
endpoints, or non-functional UI.

Protocol v1 binds `recipient_user_id` into every artifact AAD and the signed
publish statement. Consequently a pre-registration capsule cannot be made
deliverable by “adding only a recipient envelope later” unless one of these
choices is made explicitly:

1. reserve a pending UUID transactionally as the recipient's exact future
   immutable `user_id`, then promote/bind that principal at registration; or
2. define a new protocol version with a stable `recipient_target_id` and new
   acceptance bindings.

Re-encrypting all artifacts after registration is rejected for the desired
flow. The choice between reserved future user ID and a new protocol version is
DEFERRED and is not approved by this ADR.

Any future automatic sender re-wrap must verify a locally authenticated
`capsule_id -> pending target/email commitment`; it must never wrap to an
arbitrary server-provided key. Provider identity is eventually keyed by
`(provider, provider_subject)` with verified email as routing metadata. Email
is not a secret, push is only an accelerator, and correctness relies on a
persistent polled queue plus the documented eventual sender-online trade-off.

## Consequences

- M2 remains small enough to prove the core two-device product.
- Four M1-to-M2 prerequisites become explicit: generalization, sender re-wrap
  retry material, account scoping, and staged verification.
- Recognition can synchronize without prefetching all photos while plaintext
  presentation remains fail-closed.
- Email invite remains architecturally visible without fake mechanisms or an
  accidental promise that protocol v1 already supports it.
