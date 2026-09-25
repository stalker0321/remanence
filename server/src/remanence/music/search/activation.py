"""Forward index activation (D4-B2b3b, code-only, no rollback).

Moves validated revision content onto the stable served UID through the
durable intent protocol from INDEX_REVISIONS.md: DEV/TEST-only guard,
READY candidate, no open flight, session lock, PENDING intent, committed
post_attempted sentinel, swap POST, single task-UID write, conservative
task poll (timeout/transport/unknown -> UNKNOWN, no retry), full
settings-hash + document-count verification against read-back, and only
a verified terminal success proceeds to SWAPPED plus the atomic
``confirm_activation``. Post-swap ambiguity is UNKNOWN with a loud
signal, never a silent accept and never an automatic second swap.

No rollback, reconciliation, migration, or live calls live here.
"""

from __future__ import annotations

import math
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Any, Protocol

from sqlalchemy.orm import Session

from remanence.music.domain import normalize_text
from remanence.music.search.document import VARIANT_TOKENS
from remanence.music.search.meilisearch import TaskTimeoutError, canonical_settings_hash
from remanence.music.search.revision_lock import (
    RevisionActivationLock,
    RevisionLockBusyError,
)
from remanence.music.search.revision_store import RevisionStateStore, RevisionStoreConflictError
from remanence.music.staging.models import StagedTrack
from remanence.settings import AppMode

DEVTEST_MODES = (AppMode.DEV, AppMode.TEST)


class MusicActivationError(RuntimeError):
    """Forward activation refused or failed (fail-closed, inspectable)."""


@dataclass(frozen=True, slots=True)
class QueryProbe:
    """One deterministic post-swap content probe.

    After the swap, searching ``query`` on the stable UID must return
    ``expected_top_id`` first with at least ``min_total`` hits; anything
    else (including a probe transport error) fails the fingerprint.
    """
    query: str
    expected_top_id: str
    min_total: int = 1


class ProbeSearchPort(Protocol):
    """Narrow read port the probes need (search adapter implements this)."""

    def search_with_total(
        self, query: str, limit: int, offset: int = 0
    ) -> tuple[list, int]: ...


@dataclass(frozen=True, slots=True)
class StableProbeSearch:
    """Search port explicitly bound to one index UID.

    The binding is checked against the stable UID on every probe run:
    a port built for another index is refused instead of silently
    probing the wrong content.
    """

    port: ProbeSearchPort
    index_uid: str


def _is_cyrillic(text: str) -> bool:
    return any("\u0400" <= char <= "\u04ff" for char in text)


# Hard cap on the staged rows a probe derivation may consider. Above
# this the GROUP BY scan is too expensive for a per-activation read and
# the 0010 prefix-index slice takes over; the derivation refuses
# fail-closed instead of silently falling back to sample-only counts.
MAX_PROBE_SOURCE_ROWS = 1_000_000

# Bounded candidate window for probe picking (deterministic ID order).
MAX_PROBE_CANDIDATES = 1000


def _select_probes(
    candidates: list[tuple[object, str, str | None]],
    global_counts: dict[str, int],
) -> list[QueryProbe]:
    """Pick exact/Cyrillic/variant probes from globally-unique titles.

    Pure selection over one candidate window plus whole-table counts:
    a title qualifies only when its global normalized count is exactly
    1, so duplicates beyond the candidate window are seen and skipped.
    The variant query names its kind (``"<title> <variant-token>"``),
    matching the variant-aware ranking path.
    """
    probes: list[QueryProbe] = []
    seen: set[str] = set()

    def _add(query: str, track_id: object) -> None:
        if query.strip() and query not in seen:
            seen.add(query)
            probes.append(
                QueryProbe(query=query, expected_top_id=str(track_id), min_total=1)
            )

    for track_id, title, _variant in candidates:
        if global_counts.get(normalize_text(title), 0) == 1:
            _add(title, track_id)
            break
    for track_id, title, _variant in candidates:
        if _is_cyrillic(title) and global_counts.get(normalize_text(title), 0) == 1:
            _add(title, track_id)
            break
    for track_id, title, variant in candidates:
        tokens = [tok for tok in VARIANT_TOKENS if tok in normalize_text(variant or "")]
        if tokens and global_counts.get(normalize_text(title), 0) == 1:
            _add(f"{title} {tokens[0]}", track_id)
            break
    return probes


def derive_query_probes(session_factory: Callable[[], Session]) -> list[QueryProbe]:
    """Derive deterministic probes from staging (read-only, bounded).

    Counts ALL staged rows first (refuses empty staging and anything
    above ``MAX_PROBE_SOURCE_ROWS`` — no sample-only fallback), then
    reads at most ``MAX_PROBE_CANDIDATES`` title rows in keyset order
    and resolves their global normalized-title counts with ONE
    whole-table GROUP BY restricted to the candidate titles (duplicates
    beyond the window are seen without returning a million groups).
    Yields exact/Cyrillic/variant probes only for globally unique
    titles; no qualifying probe fails closed.
    """
    if not callable(session_factory):
        raise MusicActivationError("probe derivation requires a session factory")
    with session_factory() as session:
        from sqlalchemy import func as sa_func
        from sqlalchemy import select as sa_select

        total = int(
            session.scalar(sa_select(sa_func.count()).select_from(StagedTrack)) or 0
        )
        if total <= 0:
            raise MusicActivationError("staging yields no probe rows")
        if total > MAX_PROBE_SOURCE_ROWS:
            raise MusicActivationError("staging exceeds the probe source cap")
        candidates = session.execute(
            sa_select(StagedTrack.id, StagedTrack.title, StagedTrack.variant)
            .order_by(StagedTrack.id)
            .limit(MAX_PROBE_CANDIDATES)
        ).all()
        wanted = {normalize_text(title) for _track_id, title, _variant in candidates}
        counts = dict(
            session.execute(
                sa_select(StagedTrack.normalized_title, sa_func.count())
                .where(StagedTrack.normalized_title.in_(wanted))
                .group_by(StagedTrack.normalized_title)
            ).all()
        )
    probes = _select_probes(
        [(track_id, title, variant) for track_id, title, variant in candidates],
        counts,
    )
    if not probes:
        raise MusicActivationError("staging yields no probe rows")
    return probes


class RevisionIndexOps(Protocol):
    """Narrow transport activation needs (adapter implements this)."""

    def index_exists(self, index_uid: str) -> bool: ...
    def swap_indexes(self, uid_a: str, uid_b: str) -> int: ...
    def task_status(self, task_uid: int) -> str: ...
    def get_settings_for(self, index_uid: str) -> dict: ...
    def index_document_count(self, index_uid: str) -> int: ...


def _require_mode(mode: object) -> AppMode:
    # AppMode-typed (never a string flag): composition must pass the
    # process Settings mode, so a PROD caller cannot opt in by passing
    # a "dev" literal later.
    if mode is not AppMode.DEV and mode is not AppMode.TEST:
        raise MusicActivationError("activation is DEV/TEST only")
    return mode


def _require_rev(value: object, label: str) -> int:
    if type(value) is not int or isinstance(value, bool) or value < 0:
        raise MusicActivationError(f"{label} must be an int >= 0")
    assert type(value) is int
    return value


def _require_interval(value: object, label: str) -> float:
    if (
        type(value) is bool
        or not isinstance(value, (int, float))
        or not value >= 0
        or not math.isfinite(value)
    ):
        raise MusicActivationError(f"{label} must be a finite non-negative number")
    assert isinstance(value, (int, float))
    return float(value)


def _poll_terminal(
    ops: RevisionIndexOps, task_uid: int, timeout_s: float, interval_s: float
) -> str:
    """Poll to a terminal task status or raise typed timeout."""
    deadline = time.monotonic() + timeout_s
    while True:
        status = ops.task_status(task_uid)
        if status in ("succeeded", "failed", "canceled"):
            return status
        if time.monotonic() >= deadline:
            raise TaskTimeoutError("swap task timed out")
        if interval_s > 0:
            time.sleep(interval_s)


def _to_unknown_best_effort(
    store: RevisionStateStore, stable_uid: str, activation_id: int
) -> None:
    """Best-effort durable UNKNOWN for one activation; swallows errors."""
    try:
        current = store.read_open_activation(stable_uid)
    except Exception:
        return
    if current is None or current.id != activation_id:
        return
    try:
        if current.state == "PENDING":
            store.transition(activation_id, "PENDING", "UNKNOWN")
        elif current.state == "SWAPPED":
            store.transition(activation_id, "SWAPPED", "UNKNOWN")
    except Exception:
        pass


def activate_revision(
    lock: RevisionActivationLock,
    store: RevisionStateStore,
    ops: RevisionIndexOps,
    stable_uid: str,
    to_rev: int,
    *,
    mode: AppMode,
    search: StableProbeSearch | None = None,
    query_probes: Sequence[QueryProbe],
    task_timeout_s: float = 60.0,
    poll_interval_s: float = 0.1,
) -> Any:
    """Activate validated revision content onto the stable UID.

    Returns the CONFIRMED activation snapshot. Every refusal and every
    failure raises :class:`MusicActivationError` (transport timeouts
    surface as the adapter's ``TaskTimeoutError``, a subclass) with the
    ledger left in an explicitly recoverable state. Non-empty
    ``query_probes`` are mandatory (staging-derived): an activation with
    nothing to verify against is refused before any intent row. The
    probe search must arrive pre-bound (``StableProbeSearch`` carrying
    the port's own index UID); binding is re-checked against stable.
    """
    _require_mode(mode)
    to_rev = _require_rev(to_rev, "to_rev")
    if type(stable_uid) is not str or not stable_uid.strip():
        raise MusicActivationError("stable_uid must be a non-empty string")
    if not isinstance(store, RevisionStateStore):
        raise TypeError("activation requires a RevisionStateStore")
    timeout = _require_interval(task_timeout_s, "task_timeout_s")
    interval = _require_interval(poll_interval_s, "poll_interval_s")
    probes = tuple(query_probes)
    if not probes:
        raise MusicActivationError("query probes are mandatory")
    for probe in probes:
        if not isinstance(probe, QueryProbe):
            raise MusicActivationError("query_probes must hold QueryProbe entries")
        if not probe.query.strip() or not probe.expected_top_id.strip():
            raise MusicActivationError("query probe text must be non-blank")
        if (
            type(probe.min_total) is not int
            or isinstance(probe.min_total, bool)
            or probe.min_total < 1
        ):
            raise MusicActivationError("query probe min_total must be an int >= 1")
    if search is not None and not isinstance(search, StableProbeSearch):
        raise MusicActivationError("probe search must be a bound StableProbeSearch")
    if probes and search is None:
        raise MusicActivationError("query probes require a search port")

    if search is not None and search.index_uid != stable_uid:
        raise MusicActivationError("probe search is bound to another index")
    if search is not None:
        # A mislabeled wrapper around a foreign port must fail here (zero
        # HTTP), not after the swap: verify the underlying adapter config.
        bound = getattr(getattr(search.port, "config", None), "index_uid", None)
        if bound != stable_uid:
            raise MusicActivationError("probe port serves another index")

    candidate = store.get_revision(to_rev)
    if candidate is None or candidate.status not in ("READY", "ACTIVE"):
        raise MusicActivationError(f"revision {to_rev} is not READY or ACTIVE")
    try:
        acquired = lock.acquire()
    except RevisionLockBusyError as exc:
        raise MusicActivationError("activation lock is busy") from exc
    if not acquired:
        raise MusicActivationError("activation lock is busy")
    try:
        return _activate_locked(
            store,
            ops,
            stable_uid,
            candidate.candidate_uid,
            to_rev,
            timeout,
            interval,
            search,
            probes,
        )
    except RevisionStoreConflictError as exc:
        raise MusicActivationError(f"activation ledger conflict: {exc}") from exc
    finally:
        lock.release()


def _stable_fingerprint(
    ops: RevisionIndexOps, stable_uid: str
) -> tuple[int, str]:
    """Read-back (count, settings-hash) of what stable serves now."""
    return (
        ops.index_document_count(stable_uid),
        canonical_settings_hash(ops.get_settings_for(stable_uid)),
    )


def _read_fingerprint(
    ops: RevisionIndexOps, index_uid: str, label: str
) -> tuple[int, str]:
    """Read-back fingerprint, wrapped as a typed refusal on transport error."""
    try:
        return _stable_fingerprint(ops, index_uid)
    except Exception as exc:
        raise MusicActivationError(f"{label} fingerprint unreadable") from exc


def _activate_locked(
    store: RevisionStateStore,
    ops: RevisionIndexOps,
    stable_uid: str,
    candidate_uid: str,
    to_rev: int,
    timeout_s: float,
    interval_s: float,
    search: StableProbeSearch | None,
    query_probes: tuple[QueryProbe, ...],
) -> Any:
    # Second open-flight check INSIDE the lock (closes the check-then-act
    # race; the pre-lock check stays as a cheap early refusal).
    if store.read_open_activation(stable_uid) is not None:
        raise MusicActivationError(f"stable {stable_uid} already has an open flight")
    if not ops.index_exists(stable_uid):
        raise MusicActivationError(f"stable {stable_uid} does not exist")
    from_rev = store.find_active_revision()
    from_number: int | None = from_rev.rev if from_rev is not None else None
    candidate_row = store.get_revision(to_rev)
    assert candidate_row is not None
    stable_fp = _read_fingerprint(ops, stable_uid, "stable")
    target_fp = (candidate_row.doc_count, candidate_row.settings_hash)
    if candidate_row.status == "ACTIVE":
        # Idempotent path FIRST (before any candidate-index checks): after
        # a real swap the partner UID holds old content, so candidate
        # verification would false-fail here. Safe no-op ONLY when stable
        # provably serves this content, the probes pass, and a prior
        # confirmation exists: return it with ZERO new intent rows and
        # ZERO swap POSTs. Anything else fails closed (a count+hash
        # collision across different content must never mark anything).
        if stable_fp != target_fp:
            raise MusicActivationError(
                f"stable {stable_uid} diverged from ACTIVE revision {to_rev}"
            )
        assert search is not None
        try:
            _run_probes(search, stable_uid, query_probes)
        except MusicActivationError:
            raise
        except Exception as exc:
            raise MusicActivationError("idempotent probe read failed") from exc
        prior = store.latest_confirmation(stable_uid, to_rev)
        if prior is None:
            raise MusicActivationError(
                f"revision {to_rev} served but never confirmed"
            )
        return prior
    if candidate_row.status != "READY":
        raise MusicActivationError(
            f"revision {to_rev} is {candidate_row.status}, not swappable"
        )
    if not ops.index_exists(candidate_uid):
        raise MusicActivationError(f"candidate {candidate_uid} does not exist")
    if _read_fingerprint(ops, candidate_uid, "candidate") != target_fp:
        raise MusicActivationError(f"candidate {candidate_uid} fails pre-POST check")
    if stable_fp == target_fp:
        # Stable already serves READY-revision content without ledger
        # confirmation: refuse (would need a synthetic CONFIRMED row).
        raise MusicActivationError(
            f"stable serves revision {to_rev} content but it is not ACTIVE"
        )
    if from_number is None:
        # Bootstrap only onto an empty stable index. Emptiness is the
        # document count alone: a live backend always returns its own
        # default settings, so hashing for emptiness would never match.
        if stable_fp[0] != 0:
            raise MusicActivationError(
                f"stable {stable_uid} is not empty for bootstrap"
            )
    else:
        # An ACTIVE revision exists: the stable index must provably serve
        # exactly that content before any intent row is written.
        expected = store.get_revision(from_number)
        assert expected is not None
        if stable_fp != (expected.doc_count, expected.settings_hash):
            raise MusicActivationError(
                f"stable {stable_uid} does not serve revision {from_number}"
            )
    intent = store.open_activation(stable_uid, candidate_uid, from_number, to_rev)
    activation_id = intent.id
    store.mark_attempted(activation_id)
    try:
        task_uid = ops.swap_indexes(stable_uid, candidate_uid)
    except Exception as exc:
        _to_unknown_best_effort(store, stable_uid, activation_id)
        raise MusicActivationError("swap post failed") from exc
    try:
        store.store_task_uid(activation_id, task_uid)
    except Exception as exc:
        _to_unknown_best_effort(store, stable_uid, activation_id)
        raise MusicActivationError("task uid persistence failed") from exc
    try:
        outcome = _poll_terminal(ops, task_uid, timeout_s, interval_s)
    except Exception as exc:
        _to_unknown_best_effort(store, stable_uid, activation_id)
        if isinstance(exc, TaskTimeoutError):
            raise MusicActivationError("swap task timed out") from exc
        raise MusicActivationError("swap task status unknown") from exc
    if outcome != "succeeded":
        store.transition(activation_id, "PENDING", "FAILED")
        raise MusicActivationError(f"swap task {outcome}")
    store.transition(activation_id, "PENDING", "SWAPPED")
    revision = store.get_revision(to_rev)
    assert revision is not None
    try:
        live_count, live_hash = _stable_fingerprint(ops, stable_uid)
    except Exception as exc:
        _to_unknown_best_effort(store, stable_uid, activation_id)
        raise MusicActivationError("post-swap read-back failed") from exc
    if (live_count, live_hash) != (revision.doc_count, revision.settings_hash):
        _to_unknown_best_effort(store, stable_uid, activation_id)
        raise MusicActivationError("post-swap fingerprint mismatch")
    assert search is not None
    try:
        _run_probes(search, stable_uid, query_probes)
    except Exception as exc:
        _to_unknown_best_effort(store, stable_uid, activation_id)
        raise MusicActivationError("post-swap probe mismatch") from exc
    return store.confirm_activation(activation_id)


def _run_probes(
    search: StableProbeSearch, stable_uid: str, probes: tuple[QueryProbe, ...]
) -> None:
    """Deterministic content probes; any deviation fails loudly.

    The port is explicitly bound to one index UID: probing through a
    port built for another index is refused instead of silently
    verifying the wrong content. Both the wrapper label and the
    underlying adapter config are checked — a mislabeled wrapper
    around a candidate-bound port fails closed.
    """
    if search.index_uid != stable_uid:
        raise MusicActivationError("probe search is bound to another index")
    bound = getattr(getattr(search.port, "config", None), "index_uid", None)
    if bound != stable_uid:
        raise MusicActivationError("probe port serves another index")
    for probe in probes:
        hits, total = search.port.search_with_total(probe.query, 5, 0)
        if (
            type(total) is not int
            or total < probe.min_total
            or not hits
            or str(hits[0].id) != probe.expected_top_id
        ):
            raise MusicActivationError(
                f"probe {probe.query!r} failed: total={total}"
            )
