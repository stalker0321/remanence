# D4-B2b design: index revision activation and rollback (DRAFT, no implementation)

Status: design only. D4-A (document source), D4-B1 (target write ops),
and D4-B2a (build + count validation) are implemented code-only and
checkpointed. This ADR specifies activation/rollback; production
activation stays out of scope. STOP: no code, migration, or live call
lands from this document until independent review.

## 1. Corrected swap semantics (supersedes the stateless env-pointer sketch)

An earlier sketch treated activation as flipping an "active UID" pointer
in env plus an in-memory `ActivationRecord`. That is UNSAFE and is
hereby withdrawn: Meilisearch `POST /swap-indexes` exchanges the
**contents** of two indexes while both **UIDs remain**. There is no
pointer to flip — after `swap(stable, rev7)`, the stable UID serves
rev7's documents and `rev7` holds the previous content.

Consequences:

- There is exactly one **stable served UID** (`remanence_tracks_v1`).
  Traffic (the read path) is pinned to it forever; it is never deleted,
  never renamed, never rebuilt in place.
- A crash between the swap POST and the local state update leaves the
  operator unable to tell whether the swap happened. Blindly retrying
  the swap can **undo** a succeeded activation. Hence durable intent +
  fail-closed UNKNOWN (section 5), never automatic retry of an
  uncertain swap.

## 2. Persisted state (Postgres, new migration in B2b implementation)

```text
music_index_revision(
  rev            integer PRIMARY KEY,      -- monotonically increasing
  candidate_uid  text NOT NULL UNIQUE,     -- remanence_tracks_v1_revNNNN
  doc_count      integer NOT NULL,         -- validated source count
  settings_hash  text NOT NULL,            -- sha256 of applied settings JSON
  status         text NOT NULL,            -- READY | ACTIVE | SUPERSEDED | FAILED
  created_at     timestamptz NOT NULL DEFAULT now()
)

music_index_activation(
  id             bigserial PRIMARY KEY,
  stable_uid     text NOT NULL,            -- always the stable served UID
  partner_uid    text NOT NULL,            -- the other UID swapped with stable
  from_rev       integer NULL,             -- content previously served (NULL at bootstrap)
  to_rev         integer NOT NULL,         -- content to serve after swap
  post_attempted boolean NOT NULL DEFAULT FALSE, -- separate-txn sentinel, see section 5
  task_uid       bigint NULL,              -- Meilisearch swap task, once known
  state          text NOT NULL,            -- PENDING | SWAPPED | CONFIRMED | FAILED | UNKNOWN
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
)

STOP-1: after a swap, revision *content moves between UIDs*. The
rollback partner is therefore persisted (`partner_uid`) and so is the
content location it implies — never derived from `from_rev` at
rollback time. Rollback re-swaps `(stable_uid, partner_uid)` only
after a fingerprint check proves the partner still holds the
`from_rev` content; otherwise UNKNOWN + operator path. Roll-forward
is a fresh activation row through the same machinery (repeated
rollback/roll-forward in the matrix).

A `candidate_uid` (revision row or activation `partner_uid`) names a
UID *identity*, never a permanent content location: contents migrate
between UIDs on every swap, so location is established by fingerprint
at use time, never assumed from history.
```

The pending-intent row (`state = PENDING`, task unknown) is written
**before** the swap POST. The task UID is stored immediately after the
POST returns. `CONFIRMED` is written only after the task polls
`succeeded` **and** the post-swap fingerprint checks pass (section 4).

STOP-2: bare PENDING is ambiguous — it cannot tell "POST never sent"
from "POST sent, response lost". A `post_attempted` sentinel is
therefore committed in a **separate transaction immediately before**
the POST. Recovery rule: `PENDING + attempted=false` → safe retry
(nothing could have been sent); `attempted=true` + task unknown →
UNKNOWN/manual path, **never** a blind retry.

## 3. State machine

```text
revision:  BUILDING -> READY -> ACTIVE -> SUPERSEDED
                         \-> FAILED (terminal; row kept for audit)

activation: PENDING -> FAILED    (swap task terminally failed; traffic untouched)
            PENDING -> SWAPPED   (terminal task success persisted: content DID move)
            SWAPPED -> CONFIRMED (post-swap checks verified)
            SWAPPED -> UNKNOWN   (post-swap mismatch: operator path, never FAILED)
            PENDING -> UNKNOWN   (attempted + task lost; crash/ambiguity window)
```

Rollback is one step because swap is symmetric — but only against the
**persisted** `partner_uid` (STOP-1), never a re-derived one: after
`swap(stable, rev7)`, `rev7` holds the previous content, so rolling
back is `swap(stable, rev7)` again **after** a fingerprint check proves
the partner still holds the `from_rev` content. Rollback requires a
`CONFIRMED` activation with a non-null `from_rev`; it writes its own
activation row (from = current content rev, to = previous rev, partner
= recorded partner) and follows the same PENDING/SWAPPED/CONFIRMED path.

## 4. Pre/post checks (content fingerprint, no trust in task alone)

Before POST: candidate `index_exists`, candidate count == revision
`doc_count`, candidate settings hash == revision `settings_hash`,
`to_rev` revision row is `READY`, no other activation in
`PENDING`/`SWAPPED`/`UNKNOWN` (serialized, section 6), caller holds
the advisory lock.

After `succeeded` poll: persist SWAPPED immediately (terminal task
success means content DID move — this write is what separates known
outcomes from UNKNOWN), then run the checks: stable count ==
`doc_count`, stable settings hash matches, plus K deterministic query
probes derived from staging (exact title -> top-hit own id, Cyrillic,
variant) against the stable UID via the read adapter. Checks pass ->
CONFIRMED. Any mismatch -> UNKNOWN with a loud rollback signal and
explicit reconciliation (content moved, so FAILED would be a lie).

STOP-4a: the current `wait_for_task` conflates poll-timeout with task
failure in one generic error — but timeout means "swap may have
applied" while `failed` means "certainly did not". The B2b
implementation must split them: a typed `TaskTimeoutError` (unknown
outcome -> UNKNOWN path, section 5) versus terminal `failed` (known
untouched -> FAILED), backed by a separate `task_status(task_uid)`
read op (`GET /tasks/{uid}`) so initial poll, resume-after-crash, and
reconciliation all share one status reader.

STOP-4b: the settings-hash check above is unverifiable with the
current adapter (write-only settings, no read). The B2b implementation
must add a `get_settings_for(index_uid)` read op (bounded body, same
4xx/5xx classification as the other reads) — or, failing that, the
hash check is dropped from the fingerprint (count + probes only). No
unverifiable claims ship. Hash canonicalization when present: sha256
over `json.dumps(settings, sort_keys=True, separators=(",", ":"))`
encoded UTF-8. Any mismatch -> `UNKNOWN` with a loud rollback signal
and explicit reconciliation (traffic already swapped — declared loudly
for operator rollback, never silently accepted).

## 5. Crash analysis and UNKNOWN

| Crash point | Durable trace | Recovery |
|---|---|---|
| Before pending row commit | none | safe fresh attempt |
| After PENDING, `attempted=false` | intent only | safe retry (no POST could have been sent) |
| `attempted=true`, task unknown | sentinel set | **UNKNOWN**: no auto-retry; operator reconciles by fingerprint, then marks CONFIRMED or FAILED explicitly |
| Task UID stored, crash during poll | PENDING + task | resume polling to terminal: task failed -> FAILED; succeeded + verified -> CONFIRMED; succeeded + mismatch or timeout/unknown -> UNKNOWN/operator |
| CONFIRMED write lost after checks | task succeeded + checks passed, unrecorded | reconcile by fingerprint, then record CONFIRMED (idempotent) |

Automatic retry is allowed **only** while no swap POST can have been
sent (first two rows). Everything else is explicit operator
reconciliation with a dedicated read-only inspect command.

## 6. Serialization (lock done right)

STOP-3: `pg_advisory_xact_lock` must NOT span network calls — it would
hold a database transaction open across HTTP waits (connection
starvation, lock held through crashes opaquely). Instead:

- Acquire a **session-level** lock with `pg_try_advisory_lock()` on a
  fixed 64-bit key: immediate busy error, never queued, never
  interleaved. The session (and a dedicated short-lived connection)
  exists only to hold the lock.
- All state writes (intent row, `post_attempted` sentinel, task UID,
  terminal states) use **short, separately committed transactions**.
- Release the session lock in a `finally`; after a crash, recovery
  consults the pending row/sentinel (section 5), never lock state —
  a stale lock holder that died holds nothing.
- The "no open PENDING/SWAPPED/UNKNOWN row" gate from the previous
  draft is kept as a second check inside the locked section, but the
  lock itself is what closes the check-then-act race.

## 7. Bootstrap precondition

The stable UID must exist. If absent, the operator creates it empty via
`create_index_for` (the single allowed create-on-stable), then the
first activation proceeds from a validated candidate with
`from_rev = NULL`. Activation from a non-existent stable UID fails
closed without writes.

## 8. Permissions and retention

- Meilisearch keys: build/validate may use a write-scoped key without
  delete; activation/rollback and any delete require the operator key
  carrying the swap capability (the `indexes.swap` action scope where
  the Meilisearch version exposes it, otherwise full index write
  scope). The serialization lock uses one fixed scoped 64-bit key
  derived from the string `music_index_activation`. Code refuses
  mutation outside DEV/TEST (existing composition guard);
  production activation procedure is explicitly out of scope here.
- Retention: the stable UID plus the current recorded `partner_uid`
  are pinned; at most one older candidate may exist. Prune is an
  explicit operator op naming the UID; activate/rollback never delete.
  Prune automation is an awaited follow-up task, not part of B2b.

## 9. Invariants (must hold after every op, including crashes)

1. Traffic is served only by the stable UID.
2. The stable UID and the last-good candidate UID are never deleted.
3. No activation row stays in PENDING/SWAPPED without either terminal
   progress or UNKNOWN + operator action.
4. Counts, settings hash, and probes agree between the revision row and
   the served content after CONFIRMED.
5. At most one activate/rollback flight at a time (advisory lock).
6. A failed build, a refused activation, or a failed swap task never
   mutates served content. **Correction (STOP-4c):** a *post-swap*
   validation failure DID mutate served content (the swap applied, the
   checks rejected it) — so it is classified as operator-action/UNKNOWN
   with a loud rollback signal and explicit reconciliation, never as a
   simple FAILED.

## 10. Fake-test matrix (B2b implementation)

| Case | Fake behavior | Expectation |
|---|---|---|
| Happy activate | exists/count ok, swap task succeeds, post checks pass | CONFIRMED, active content = candidate |
| Idempotent re-activate | candidate content already served (fingerprint equal) | no-op success, zero swap POSTs |
| Swap task failed | task `failed` | FAILED, traffic untouched, no retry |
| Swap timeout | poll exceeds deadline | UNKNOWN (swap may have applied) → fingerprint reconciliation, no auto-retry |
| Crash before POST (simulated: raise pre-POST) | — | PENDING row only, retry succeeds |
| Crash with task UID lost | drop UID | UNKNOWN, no auto-retry call |
| Crash during poll, task UID kept | resume status reads to terminal | CONFIRMED on success+checks, FAILED on task failure, UNKNOWN on timeout |
| Lost CONFIRMED write after passing checks | checks passed, record absent | reconcile by fingerprint, then record CONFIRMED (idempotent) |
| Concurrent activate | lock held | busy error, zero HTTP |
| Rollback happy | CONFIRMED prior, partner fingerprint verified | symmetric swap on recorded `(stable, partner)`, new CONFIRMED row |
| Rollback without record | no prior CONFIRMED | fail, zero HTTP |
| Rollback partner moved | partner fingerprint mismatch | UNKNOWN, operator path (never derive partner from `from_rev`) |
| Repeated rollback/roll-forward | alternating CONFIRMED rows | each leg re-verifies partner content; content-location chain stays consistent |
| Bootstrap missing stable | exists=False | FAILED before any write |
| Count/probe mismatch post-swap | altered stats | UNKNOWN + loud rollback signal + explicit reconcile (content DID change) |
| DEV-only mutation guard | PROD mode | refusal before any HTTP |

## 11. Explicitly out of scope

Production activation procedure and checklist, Meilisearch cluster
topology/keys provisioning, the Alembic migration itself (DDL sketched
in section 2, authored in implementation), query-relevance tuning, and
any read-path or composition change.
