# ADR-015: F3 recipient tombstone feed

Status: Proposed design packet. No implementation. Base: `68a9a56`.
Companion to ADR-014 (sender revocation); blocks the recipient purge/UI
slice. Recipient purge behavior and UI copy are explicitly out of scope
here and live in the recipient-slice packet.

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
  codec instance so cursor namespaces never mix).
- **Assignment at revoke, same transaction**: the sequence is assigned
  inside the existing revoke advisory-lock transaction alongside the
  `READY`→`REVOKED` state CAS (`revoke_service.py` pattern, same
  `capsule_lock_key`), so ordering and transition are atomic with no new
  lock primitive.
- **Lock-free committed visibility**: readers take no locks and observe
  committed pre/post-revoke states via read-committed visibility, exactly
  like incoming pages.
- **Privacy fields only**: capsule IDs + revocation timestamps for rows
  already addressed to the authenticated recipient; no reasons, no
  sender context, no payload metadata. A withdrawn capsule the client
  never cached reveals nothing beyond "addressed to you, withdrawn" —
  the same information as page absence.
- **Retention**: tombstone feed entries live exactly as long as tombstone
  rows, which ADR-014 retains indefinitely in MVP with no GC job; no
  separate feed retention config in slice 1.
- **Old-client compatibility**: pages, item DTOs, and cursors are
  untouched; old clients simply never call the feed.
- **Idempotent sync**: the client persists a `tombstones` high-watermark
  beside the existing `sync_cursor` row; marker-set + purge are naturally
  idempotent and replays converge.
- **Why not inline in pages**: tombstones have no meaningful
  `publication_sequence` position (revoke postdates publication); inline
  items would disturb limit+1 pagination and the specified cursor
  guarantees, and would change item semantics old clients parse.

## 3. Exact server files (slice scope)

- `capsules/models.py` (+ migration `0006`, chained after R1 `0004`):
  `tombstone_sequence` on the recipient tombstone row, NOT NULL,
  assigned at revoke.
- `capsules/revoke_service.py`: sequence assignment in-transaction
  during the state CAS.
- New `tombstone_query_service.py` (preferred over extending
  `incoming_query_service.py`, for blast radius): page query, cursor
  codec, fail-closed validation mirroring existing conventions.
- `api/capsules.py`: one `GET` route with the recipient principal;
  redacted problems only (`CAPSULE_NOT_FOUND`-uniform unknown,
  existing validation codes, `INTERNAL_ERROR` with redacted logging).
- `api/problems.py`: no new codes expected.

## 4. Minimal tests

Cursor codec round-trip/canonical/limit validation; sequence ordering +
capsule-ID tiebreak; unknown/forged cursor fail-closed; other-recipient
isolation; revoke→feed visibility; replay idempotence; migration
upgrade/downgrade including sequence backfill default; redaction
(IDs + timestamps only); anchor-tolerance for cursors issued
pre-revocation.

## 5. Sequencing

Must land before the recipient Android purge/UI slice, which cannot
trigger without an observable tombstone signal.
