"""Revision activation workflow composition (D4-B2b3c-3a, code-only).

Wires the existing slices in dependency order through injected pieces:

1. gates: mode comes ONLY from ``Settings.mode`` (DEV/TEST, default
   deny) and the music search backend must be explicitly enabled —
   both refused before any I/O;
2. derive staging probes first (cheap read; over-cap/empty fails fast
   with zero activation-side mutation);
3. B2a ``build_revision`` (manager, injected);
4. ``persist_validated_revision`` (store + ops, injected);
5. ``activate_revision`` with the stable-bound search (injected
   pre-bound; binding re-verified inside).

No real adapter is constructed here, no CLI/``--execute``, no live
calls. Every refusal and failure raises :class:`MusicActivationError`
(or ``TypeError`` for programming errors) before any partial effect
beyond the step that failed.
"""

from __future__ import annotations

import math
from typing import Any

from remanence.music.search.activation import (
    MusicActivationError,
    StableProbeSearch,
    activate_revision,
    derive_query_probes,
)
from remanence.music.search.revision_lock import RevisionActivationLock
from remanence.music.search.revision_store import (
    RevisionStateStore,
    RevisionStoreConflictError,
)
from remanence.music.search.revisions import (
    MusicIndexRevisionManager,
    persist_validated_revision,
)
from remanence.settings import AppMode, MusicSearchBackend, Settings


def _require_uid(value: object, label: str) -> str:
    if type(value) is not str or not value.strip():
        raise MusicActivationError(f"{label} must be a non-empty string")
    return value


def compose_and_activate_revision(
    *,
    settings: Settings,
    session_factory,
    manager: MusicIndexRevisionManager,
    store: RevisionStateStore,
    ops,
    lock: RevisionActivationLock,
    search: StableProbeSearch,
    stable_uid: str,
    base_uid: str,
    rev: int,
    task_timeout_s: float = 60.0,
    poll_interval_s: float = 0.1,
) -> tuple[Any, Any]:
    """Run build -> persist -> probe -> activate; return (revision, activation).

    An already-ACTIVE same rev short-circuits to the verified no-op
    (returns ``(None, prior_confirmation)`` with zero rebuild); any
    other recorded status fails closed.
    """
    if not isinstance(settings, Settings):
        raise TypeError("composition requires Settings")
    if not callable(getattr(manager, "build_revision", None)):
        raise TypeError("composition requires a revision manager")
    if not isinstance(store, RevisionStateStore):
        raise TypeError("composition requires a RevisionStateStore")
    if not isinstance(search, StableProbeSearch):
        raise TypeError("composition requires a stable-bound search")
    if settings.mode is AppMode.PROD:
        raise MusicActivationError("revision workflow is DEV/TEST only")
    if settings.mode is not AppMode.DEV and settings.mode is not AppMode.TEST:
        raise MusicActivationError("revision workflow is DEV/TEST only")
    if settings.music_search_backend is MusicSearchBackend.DISABLED:
        raise MusicActivationError("revision workflow requires an enabled music backend")
    stable_uid = _require_uid(stable_uid, "stable_uid")
    base_uid = _require_uid(base_uid, "base_uid")
    if base_uid != stable_uid:
        raise MusicActivationError("revision base UID must equal the stable UID")
    if search.index_uid != stable_uid:
        raise MusicActivationError("probe search is bound to another index")
    bound = getattr(getattr(search.port, "config", None), "index_uid", None)
    if bound != stable_uid:
        raise MusicActivationError("probe port serves another index")
    if type(rev) is not int or isinstance(rev, bool) or rev < 0:
        raise MusicActivationError("rev must be an int >= 0")
    for label, value in (
        ("task_timeout_s", task_timeout_s),
        ("poll_interval_s", poll_interval_s),
    ):
        if (
            type(value) is bool
            or not isinstance(value, (int, float))
            or not value >= 0
            or not math.isfinite(value)
        ):
            raise MusicActivationError(f"{label} must be a finite non-negative number")
    try:
        existing = store.get_revision(rev)
        active = store.find_active_revision()
    except RevisionStoreConflictError as exc:
        raise MusicActivationError(f"activation ledger conflict: {exc}") from exc
    if existing is not None:
        # Re-run for an already-ACTIVE same rev returns the prior
        # confirmation without rebuilding or re-persisting; any other
        # recorded status fails closed (never rebuild over it here).
        if (
            existing.status == "ACTIVE"
            and active is not None
            and active.rev == rev
        ):
            probes = derive_query_probes(session_factory)
            return None, activate_revision(
                lock,
                store,
                ops,
                stable_uid,
                to_rev=rev,
                mode=settings.mode,
                search=search,
                query_probes=probes,
                task_timeout_s=float(task_timeout_s),
                poll_interval_s=float(poll_interval_s),
            )
        raise MusicActivationError(
            f"revision {rev} already recorded as {existing.status}"
        )
    probes = derive_query_probes(session_factory)
    built = manager.build_revision(base_uid, rev)
    persist_validated_revision(store, ops, built, base_uid)
    return built, activate_revision(
        lock,
        store,
        ops,
        stable_uid,
        to_rev=rev,
        mode=settings.mode,
        search=search,
        query_probes=probes,
        task_timeout_s=float(task_timeout_s),
        poll_interval_s=float(poll_interval_s),
    )
