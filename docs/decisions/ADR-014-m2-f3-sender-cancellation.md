# M2-F3 design: authenticated sender cancellation (24h revoke/tombstone)

Status: Proposed design packet. Server slice-1 defaults below are decided;
no implementation exists. Base: `7dfcb17`.

## 1. Bounded design (source-bound)

- **Server-authoritative revoke.** Cancellation is a sender-authenticated
  state transition on the existing `Capsule` row (`capsules/models.py:25`,
  states `DRAFT`/`READY`/`ABORTED`), adding a terminal revoked state
  (name TBD at implementation: `REVOKED`, tombstone row retained, never
  hard-deleted). Only the sender (`capsule.sender_user_id`, same ownership
  check as `abort_service.py:101` and `finalize_service.py:195`) may revoke.
- **Time rule: exactly 24 hours from `ready_at`, server UTC.**
  Eligible iff `now <= ready_at + 24h` (equality allowed),
  where `now` is `datetime.now(timezone.utc)` at the API boundary (same
  clock source as finalize/abort). Greater-than is expired. Fixed at 24h,
  not per-deployment configurable for MVP.
- **Idempotent repeat success.** Revoking an already-revoked capsule
  returns success with a replay marker (mirrors `abort` replay at
  `abort_service.py:107-110` and finalize idempotency), so retried sender
  actions converge instead of conflicting.
- **Atomic row-lock/CAS for writers; lock-free readers.** The revoke path
  takes the same `pg_advisory_xact_lock(capsule_lock_key(...))` +
  `populate_existing` re-read pattern as finalize
  (`finalize_service.py:187-194`) and abort (`abort_service.py:88-98`),
  then compare-and-set on `(state, ready_at)`: only `READY`-and-in-window
  transitions. Only finalize-vs-revoke writers serialize on the lock.
  Sync/incoming reads take no lock and observe either the committed
  pre-revoke or post-revoke state — never a partial one — by standard
  read-committed visibility.
- **Cursor stability across tombstone exclusion.** Incoming pages keyed by
  `(ready_at, capsule_id)` (`incoming_cursor.py:37`,
  `incoming_query_service.py:85-209`) must apply the revoked-row exclusion
  identically in row selection and cursor advancement, so an in-flight
  cursor never skips or duplicates later READY rows when a tombstone
  appears mid-pagination. Slice 1 requires an in-flight keyset cursor
  regression test proving exactly that.
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
  semantics. No new required request fields on existing endpoints.
- **Privacy.** Revocation carries no reason text, no new personal data;
  tombstones retain only IDs, state, and timestamps already stored.
  Recipient learns at most "withdrawn", never sender-side context.
- **API response convention** (via existing `_spec` + `_problem_response`
  machinery, `api/problems.py:53`, `api/capsules.py:362`):
  `POST /v1/capsules/{capsule_id}/revoke` returns 200 with an idempotent
  replay indicator; unknown/foreign capsule → existing
  `CAPSULE_NOT_FOUND`; non-READY state → existing `CAPSULE_STATE_INVALID`;
  past the 24h window → dedicated `WINDOW_EXPIRED` problem (new code in
  slice 1); unexpected failure → existing `INTERNAL_ERROR` with R8-style
  redacted internal logging.

## 2. Slice 1 scope: server state/migration/API only

- New terminal revoked state + tombstone retention on `Capsule` (retained
  indefinitely in MVP — no GC job in slice 1; retention policy is a later
  operational task, not a blocker); migration chained after R1 `0004`
  (down_revision) with downgrade dropping only the new state/columns.
- Revoke service mirroring `abort_service.py` structure (ownership check,
  advisory lock, re-read, CAS, replay marker, redacted `_error` codes)
  plus the `WINDOW_EXPIRED` code.
- One new sender-authenticated endpoint
  `POST /v1/capsules/{capsule_id}/revoke` + wiring into incoming-query
  filtering so revoked capsules stop being served, with the cursor
  stability rule above.
- Unit + endpoint tests: window edges (just-inside/at-exactly-24h vs
  just-outside by server clock), replay idempotence, finalize-vs-revoke
  race (advisory-lock serialization; sync needs no lock contention test,
  only committed-state visibility), in-flight keyset cursor regression
  (tombstone vanishes mid-pagination without skipping or duplicating
  later READY rows), non-owner rejection, tombstone invariants, migration
  upgrade/downgrade.

## 3. Explicitly later slices (listed, not designed here)

- **Android sender action**: surface, confirmation copy (must state that
  received/decrypted copies cannot be deleted), offline-disabled
  behavior, retry on replay-safe success.
- **Recipient local material handling**: purge of not-yet-decrypted cached
  ciphertext on next sync, tombstone display copy, retention of already
  accepted/deleted-nothing guarantees, outbox/sync worker changes.
- **Operational**: tombstone retention policy and any future purge job.

## 4. Test matrix (slice 1)

| # | Case | Expectation |
|---|------|-------------|
| 1 | Revoke READY capsule in window, owner | 200 success + replay indicator false, tombstone retained |
| 2 | Repeat revoke | 200 success with replay indicator true |
| 3 | Revoke at exactly `ready_at + 24h` | 200 success (equality allowed) |
| 4 | Revoke after window | Dedicated `WINDOW_EXPIRED`, state unchanged |
| 5 | Revoke DRAFT/ABORTED | `CAPSULE_STATE_INVALID` (abort path owns drafts) |
| 6 | Non-owner / unknown capsule | `CAPSULE_NOT_FOUND`, nothing mutated |
| 7 | Finalize-vs-revoke race | Exactly one winner; loser observes committed state |
| 8 | In-flight cursor across tombstone | No skipped/duplicated later READY rows |
| 9 | Revoked capsule in incoming query | Excluded from recipient pages |
| 10 | Migration upgrade/downgrade | Applies after R1 chain; clean rollback |
| 11 | Redaction | No statement/signature/ciphertext in problems or logs |

## 5. Former U1–U5: decided for server slice 1, no blockers remain

- U1 (window): decided — exactly 24h, equality allowed, server UTC, not
  configurable in MVP.
- U2 (response convention): decided — `POST .../revoke` with 200 +
  replay indicator; dedicated `WINDOW_EXPIRED` past the window.
- U3 (cursor stability): decided — exclusion applied identically in
  selection and cursor advancement, proven by the required in-flight
  keyset cursor regression test.
- U4 (retention): decided for MVP — retain tombstones, no GC in slice 1;
  retention policy is a later operational task.
- U5 (UX copy): Android slice remains later, with the hard requirement
  that its copy states received/decrypted copies cannot be deleted.
