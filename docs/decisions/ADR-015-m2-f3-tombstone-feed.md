# ADR-015: F3 recipient tombstone feed

Status: Proposed design packet (revision 2: review corrections applied).
No implementation. Base: `68a9a56`. Companion to ADR-014 (sender
revocation); blocks the recipient purge/UI slice. Recipient purge
behavior and UI copy are explicitly out of scope here and live in the
recipient-slice packet.

## 1. Problem (source-bound)

`IncomingCapsuleQueryService._list_incoming`
(`server/src/remanence/capsules/incoming_query_service.py`) filters
`Capsule.state == CapsuleState.READY`, so a revoked capsule silently
vanishes from later pages. `_resolve_cursor_sequence` tolerates
`READY|REVOKED` only to anchor already-issued cursors. A recipient that
cached the capsule before revocation therefore cannot distinguish
"revoked" from "not yet synced": **absence is not a signal**, and
absence detection would false-purge on flaky pages. An explicit feed is
required before any recipient purge can trigger.

## 2. Specified feed

- New recipient-scoped endpoint, e.g.
  `GET /v1/incoming/tombstones?since=<opaque>&limit=`, returning
  tombstone items ordered by a new per-recipient `tombstone_sequence`,
  each item `{capsule_id, revoked_at}` and nothing else, with
  `next_cursor`/`has_more` following the existing `limit+1` +
  opaque-cursor-codec pattern (`incoming_cursor.py` conventions; separate
  codec version/namespace so cursor namespaces never mix). Page order is
  the row-value tuple `(tombstone_sequence, capsule_id)`; the capsule-ID
  tiebreak makes the order total even if two rows ever shared a sequence.
- **Assignment at revoke, same transaction, recipient-serialized.** The
  sequence is assigned inside the existing revoke transaction alongside
  the `READY`→`REVOKED` state CAS via an explicit per-recipient
  transactional counter row: read the recipient's counter, insert it at
  zero on first use, increment it in-transaction, and assign the new
  value — all under the recipient advisory lock below. Rollback returns
  the counter untouched with the transaction. `MAX()+1` reads are
  forbidden (unlocked races duplicate even under scrutiny); PostgreSQL
  `nextval` sequences are forbidden (rollback burns values and breaks
  gap-free watermarks). Concurrency tests must prove N concurrent
  same-recipient revokes yield exactly `{base+1 .. base+N}` with no
  duplicates and no gaps. The revoke path takes a per-recipient advisory
  lock (`recipient_tombstone_lock_key(recipient_user_id)`, same
  `_lock_key` pattern as `locking.py`) so concurrent same-recipient
  revokes serialize. **Lock order is fixed
  globally: recipient lock first, then the existing capsule lock.**
  Finalize takes only the capsule lock and never the recipient lock, so
  no path can acquire the two in opposite order and no AB-BA deadlock is
  possible. `UNIQUE(recipient_user_id, tombstone_sequence)` backs the
  invariant at the schema level.
- **`revoked_at`: server UTC, assigned once on first revoke, never
  changed by replay.** Replay re-serves the stored timestamp; the
  idempotence test asserts byte-level stability of both fields.
- **Nullability shape (CHECK-enforced):** `tombstone_sequence` and
  `revoked_at` are NOT NULL exactly when `Capsule.state = 'REVOKED'`
  and NULL otherwise. Non-revoked rows therefore cannot carry stale
  sequence/timestamp values.
- **Lock-free committed visibility**: readers take no locks and observe
  committed pre/post-revoke states via read-committed visibility, exactly
  like incoming pages.
- **Privacy fields only**: capsule IDs + revocation timestamps for rows
  already addressed to the authenticated recipient; no reasons, no
  sender context, no payload metadata.
- **Retention**: tombstone feed entries live exactly as long as tombstone
  rows, which ADR-014 retains indefinitely in MVP with no GC job; no
  separate feed retention config in slice 1.
- **Old-client compatibility**: pages, item DTOs, and cursors are
  untouched; old clients simply never call the feed.
- **Idempotent sync**: the client persists a `tombstones` high-watermark
  beside the existing `sync_cursor` row; marker-set + purge are naturally
  idempotent and replays converge. Gap-free per-recipient sequences make
  the watermark exact (no duplicate-tolerance logic needed client-side).
- **Why not inline in pages**: tombstones have no meaningful
  `publication_sequence` position (revoke postdates publication); inline
  items would disturb limit+1 pagination and the specified cursor
  guarantees, and would change item semantics old clients parse.

## 3. Exact server files (slice scope)

- `capsules/models.py` (+ migration `0006`, `down_revision` chained
  from `0005_m2_f3_capsule_revocation`, not `0004`):
  nullable `tombstone_sequence BIGINT` + `revoked_at TIMESTAMPTZ` on
  the recipient tombstone row; `UNIQUE(recipient_user_id,
  tombstone_sequence)`; CHECK tying both columns to `REVOKED` state.
- `capsules/locking.py`: `recipient_tombstone_lock_key/1` following the
  existing `_lock_key` pattern.
- `capsules/revoke_service.py`: recipient lock (first), capsule lock,
  re-read, CAS, sequence assignment, `revoked_at = now`, replay path
  leaves both fields untouched.
- New `tombstone_query_service.py` (preferred over extending
  `incoming_query_service.py`, for blast radius): page query ordered by
  `(sequence, capsule_id)`, cursor codec, fail-closed validation
  mirroring existing conventions.
- `api/capsules.py`: one `GET` route with the recipient principal;
  redacted problems only (`CAPSULE_NOT_FOUND`-uniform unknown,
  existing validation codes, `INTERNAL_ERROR` with redacted logging).
- `api/problems.py`: no new codes expected.

## 4. Migration 0006: pre-existing REVOKED rows

No deployment invariant is provable from inside the migration (the
revoke path exists on a sibling branch), so `0006` backfills
deterministically instead of assuming emptiness: for every row already
in `REVOKED` state, assign `tombstone_sequence` per recipient ordered by
`(ready_at, capsule_id)` and set `revoked_at` to the migration execution
timestamp, honestly labeled in code as a **backfill marker, not the true
revocation instant** (unknowable post hoc). The migration test asserts
the backfill order, the marker semantics, and the new constraints on a
seeded tombstone. Non-blocking clarification: downgrade drops the
derived tombstone metadata (sequence, timestamps, constraints) with the
columns, and a later re-upgrade deterministically re-stamps fresh
backfill markers; no marker value is ever preserved across a downgrade
cycle, which is acceptable because markers are explicitly not true
revocation instants.

## 5. Minimal tests

Cursor codec round-trip/canonical/version-mismatch/limit validation;
concurrent same-recipient revokes yield gap-free unique sequences;
replay leaves sequence + `revoked_at` byte-identical; unknown/forged
cursor fail-closed; other-recipient isolation; revoke→feed visibility;
migration upgrade/downgrade including seeded-tombstone backfill order;
redaction (IDs + timestamps only); anchor-tolerance for cursors issued
pre-revocation.

## 6. Sequencing

Must land before the recipient Android purge/UI slice, which cannot
trigger without an observable tombstone signal.
