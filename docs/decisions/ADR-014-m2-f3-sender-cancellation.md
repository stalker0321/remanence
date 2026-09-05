# M2-F3 design: authenticated sender cancellation (~24h revoke/tombstone)

Status: Proposed design packet. No implementation, no thresholds finalized
beyond the ~24h product shape, no product decisions taken. Base: `7dfcb17`.

## 1. Bounded design (source-bound)

- **Server-authoritative revoke.** Cancellation is a sender-authenticated
  state transition on the existing `Capsule` row (`capsules/models.py:25`,
  states `DRAFT`/`READY`/`ABORTED`), adding a terminal revoked state
  (name TBD at implementation: `REVOKED`, tombstone row retained, never
  hard-deleted). Only the sender (`capsule.sender_user_id`, same ownership
  check as `abort_service.py:101` and `finalize_service.py:195`) may revoke.
- **24h window from `ready_at` by server clock.** Eligible iff
  `now < ready_at + ~24h`, where `now` is `datetime.now(timezone.utc)` at
  the API boundary (same clock source as finalize/abort). Outside the
  window the capsule is immutable. Exact duration (24h vs 24h+skew) is U1.
- **Idempotent repeat success.** Revoking an already-revoked capsule
  returns success with a replay marker (mirrors `abort` replay at
  `abort_service.py:107-110` and finalize idempotency), so retried sender
  actions converge instead of conflicting.
- **Atomic row-lock/CAS vs finalize and sync.** The revoke path takes the
  same `pg_advisory_xact_lock(capsule_lock_key(...))` + `populate_existing`
  re-read pattern as finalize (`finalize_service.py:187-194`) and abort
  (`abort_service.py:88-98`), then compare-and-set on `(state, ready_at)`:
  only `READY`-and-in-window transitions; concurrent finalize-vs-revoke
  and sync-vs-revoke serialize on the lock, and losers observe the
  winner's committed state on re-read. No new lock primitive.
- **R1 migration dependency.** The revoke migration chains after R1's
  `0004` publication-order revision (this tree heads at `0003`; R1 adds
  `publication_sequence` and related ordering state the tombstone and
  sync filtering build on). Implementing M2-F3 slice 1 requires the R1
  chain present first.
- **Already received/decrypted copies cannot be deleted.** Revocation
  stops server distribution and marks the tombstone; ciphertext already
  synced, let alone decrypted, on a recipient device is outside server
  reach by construction (E2EE). This limitation is load-bearing for all
  UX copy and must survive review unchanged.
- **Offline semantics.** Revoke requires connectivity (server-authoritative
  by definition). An offline sender queues nothing locally in slice 1;
  recipient devices learn revocation on next sync (see slice 3).
- **Old-client compatibility.** Clients predating M2-F3 never send revoke;
  they observe revoked capsules as absent-or-stale per existing sync
  semantics. No new required request fields on existing endpoints; the
  revoke action is a new endpoint (name TBD, following the
  `POST /v1/capsules/{id}/finalize` pattern at `api/capsules.py:797`).
- **Privacy.** Revocation carries no reason text, no new personal data;
  tombstones retain only IDs, state, and timestamps already stored.
  Recipient learns at most "withdrawn", never sender-side context.
- **API problem shapes** (all via existing `_spec` + `_problem_response`
  machinery, `api/problems.py:53`, `api/capsules.py:362`): success 200
  with replay flag; unknown/foreign capsule → existing
  `CAPSULE_NOT_FOUND`; non-READY or expired window → existing
  `CAPSULE_STATE_INVALID`; unexpected failure → existing
  `INTERNAL_ERROR` with R8-style redacted internal logging. No new
  problem codes are proposed in slice 1 (U2 decides whether a dedicated
  code is needed).

## 2. Slice 1 scope: server state/migration/API only

- New terminal revoked state + tombstone retention on `Capsule`; migration
  chained after R1 `0004` (down_revision) with downgrade dropping only the
  new state/columns.
- Revoke service mirroring `abort_service.py` structure (ownership check,
  advisory lock, re-read, CAS, replay marker, redacted `_error` codes).
- One new sender-authenticated endpoint + wiring into incoming-query
  filtering so revoked capsules stop being served (cursor pagination by
  `ready_at`/`capsule_id` in `incoming_query_service.py:85-209` must
  exclude revoked rows; cursor stability across the exclusion is U3).
- Unit + endpoint tests: window edges (just-inside/just-outside 24h by
  server clock), replay idempotence, finalize-vs-revoke and sync-vs-revoke
  races (advisory-lock serialization), non-owner rejection, missing
  tombstone invariants, migration upgrade/downgrade.

## 3. Explicitly later slices (listed, not designed here)

- **Android sender action**: surface, confirmation copy (must state the
  cannot-delete-received limit), offline-disabled behavior, retry on
  replay-safe success.
- **Recipient local material handling**: purge of not-yet-decrypted cached
  ciphertext on next sync, tombstone display copy, retention of already
  accepted/deleted-nothing guarantees, outbox/sync worker changes.

## 4. Test matrix (slice 1)

| # | Case | Expectation |
|---|------|-------------|
| 1 | Revoke READY capsule in window, owner | 200 success, tombstone retained |
| 2 | Repeat revoke | 200 success with replay marker |
| 3 | Revoke after window | `CAPSULE_STATE_INVALID`, state unchanged |
| 4 | Revoke DRAFT/ABORTED | `CAPSULE_STATE_INVALID` (abort path owns drafts) |
| 5 | Non-owner / unknown capsule | `CAPSULE_NOT_FOUND`, nothing mutated |
| 6 | Finalize-vs-revoke race | Exactly one winner; loser observes committed state |
| 7 | Revoked capsule in incoming query | Excluded from recipient pages |
| 8 | Migration upgrade/downgrade | Applies after R1 chain; clean rollback |
| 9 | Redaction | No statement/signature/ciphertext in problems or logs |

## 5. Material product decisions still unresolved

- U1: exact window (24h sharp vs 24h + skew) and whether the window is
  configurable per deployment.
- U2: whether revocation needs a dedicated problem code or reuses
  `CAPSULE_STATE_INVALID`; endpoint path/name.
- U3: incoming cursor stability guarantees across tombstone exclusion.
- U4: tombstone retention horizon (forever vs GC window) and any purge job.
- U5: sender UX copy for the cannot-delete-received limit (blocks Android
  slice, not slice 1).
