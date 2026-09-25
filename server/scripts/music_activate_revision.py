"""Bounded DEV/TEST operator CLI for index revision activation.

Dry-run is the default: argument/environment validation plus a printed
plan, with zero database connections, zero HTTP calls, and zero factory
invocations (proven by tests with a poisoned engine factory). Only an
explicit ``--execute`` builds the engine/adapter and calls
:func:`compose_and_activate_revision`.

Safety rules (all fail-closed, exit code 2):
- ``REMANENCE_MODE`` must be dev/test (read through ``Settings``) and
  ``REMANENCE_MUSIC_SEARCH_BACKEND`` must be explicitly enabled;
  DISABLED/PROD refuse before anything else.
- ``--stable-uid`` is required; ``--base-uid`` defaults to it and must
  equal it; ``--rev`` must be an int >= 0; timeouts must be finite
  non-negative numbers.
- Meilisearch target is fixed to ``ISOLATED_MEILISEARCH_URL``
  (no URL flag/env override exists); the optional API key comes only
  from ``REMANENCE_MUSIC_MEILI_KEY`` and is never printed or passed
  in argv. ``REMANENCE_DATABASE_URL`` likewise travels via Settings
  (env), never argv.
- ``--execute`` additionally requires the 0009 ledger tables and an
  already-existing stable index (bootstrap is manually prepared;
  this tool never migrates, never creates the stable index).

Run from ``server/`` with::

    REMANENCE_MODE=dev \\
    REMANENCE_DATABASE_URL=postgresql+psycopg://user:secret@127.0.0.1:55432/remanence \\
    REMANENCE_BLOB_ROOT=/var/lib/remanence/blobs \\
    REMANENCE_MUSIC_SEARCH_BACKEND=postgres_staging \\
        uv run python scripts/music_activate_revision.py \\
            --stable-uid remanence_tracks_v1 --rev 7

Add --execute to run. Without it, only validation + plan output run.
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from sqlalchemy import create_engine, inspect  # noqa: E402
from sqlalchemy.engine import Engine  # noqa: E402

from remanence.db.session import build_session_factory  # noqa: E402
from remanence.music.search.activation import (  # noqa: E402
    MusicActivationError,
    StableProbeSearch,
)
from remanence.music.search.activation_composition import (  # noqa: E402
    compose_and_activate_revision as compose_workflow,
)
from remanence.music.search.document_source import (  # noqa: E402
    StagingSearchDocumentSource,
)
from remanence.music.search.meilisearch import (  # noqa: E402
    ISOLATED_MEILISEARCH_URL,
    MeilisearchConfig,
    MeilisearchMusicSearch,
)
from remanence.music.search.revision_lock import RevisionActivationLock  # noqa: E402
from remanence.music.search.revision_store import RevisionStateStore  # noqa: E402
from remanence.music.search.revisions import build_revision_manager  # noqa: E402
from remanence.settings import AppMode, MusicSearchBackend, Settings  # noqa: E402

_MEILI_KEY_ENV = "REMANENCE_MUSIC_MEILI_KEY"


class PlanError(RuntimeError):
    """Refusal with a redacted, printable message (exit code 2)."""


@dataclass(frozen=True, slots=True)
class ActivationPlan:
    stable_uid: str
    base_uid: str
    rev: int
    task_timeout_s: float
    poll_interval_s: float
    mode: AppMode


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stable-uid", required=True)
    parser.add_argument("--base-uid", required=False, default=None)
    parser.add_argument("--rev", required=True, type=int)
    parser.add_argument("--task-timeout-s", type=float, default=300.0)
    parser.add_argument("--poll-interval-s", type=float, default=1.0)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="actually run the activation (default is dry-run)",
    )
    return parser.parse_args(argv)


def load_settings() -> Settings:
    try:
        return Settings()
    except Exception as exc:
        raise PlanError(f"invalid process settings: {type(exc).__name__}") from exc


def plan_from_args(args: argparse.Namespace, settings: Settings) -> ActivationPlan:
    """Pure validation of args + settings (no I/O of any kind)."""
    if settings.mode is AppMode.PROD:
        raise PlanError("refusing: PROD mode never runs revision activation here")
    if settings.mode is not AppMode.DEV and settings.mode is not AppMode.TEST:
        raise PlanError("refusing: mode must be dev or test")
    if settings.music_search_backend is not MusicSearchBackend.POSTGRES_STAGING:
        raise PlanError("refusing: music backend must be explicitly enabled")
    stable_uid = args.stable_uid
    _require_plan_uid(stable_uid, "--stable-uid")
    base_uid = args.base_uid if args.base_uid is not None else stable_uid
    _require_plan_uid(base_uid, "--base-uid")
    if base_uid != stable_uid:
        raise PlanError("refusing: --base-uid must equal --stable-uid")
    if type(args.rev) is not int or isinstance(args.rev, bool) or args.rev < 0:
        raise PlanError("refusing: --rev must be an int >= 0")
    _require_plan_timeout(args.task_timeout_s, "--task-timeout-s", allow_zero=False)
    _require_plan_timeout(args.poll_interval_s, "--poll-interval-s", allow_zero=True)
    return ActivationPlan(
        stable_uid=stable_uid,
        base_uid=base_uid,
        rev=args.rev,
        task_timeout_s=float(args.task_timeout_s),
        poll_interval_s=float(args.poll_interval_s),
        mode=settings.mode,
    )


def _require_plan_uid(value: object, label: str) -> None:
    if type(value) is not str or not value.strip():
        raise PlanError(f"refusing: {label} must be non-empty")
    if len(value) > 64 or value[0] in "-_" or any(
        char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        for char in value
    ):
        raise PlanError(f"refusing: {label} has an invalid index UID shape")


def _require_plan_timeout(value: object, label: str, *, allow_zero: bool) -> None:
    if (
        type(value) is bool
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < 0
        or (not allow_zero and value <= 0)
    ):
        bound = "non-negative" if allow_zero else "strictly positive"
        raise PlanError(f"refusing: {label} must be a finite {bound} number")


def _redacted_plan_line(plan: ActivationPlan) -> str:
    return (
        f"plan: mode={plan.mode.value} stable={plan.stable_uid} "
        f"base={plan.base_uid} rev={plan.rev} "
        f"timeout_s={plan.task_timeout_s} poll_s={plan.poll_interval_s} "
        f"meili={ISOLATED_MEILISEARCH_URL}"
    )


def _require_ledger_tables(engine: Engine) -> None:
    inspector = inspect(engine)
    for table in ("music_index_revision", "music_index_activation"):
        if not inspector.has_table(table, schema="music"):
            raise PlanError("ledger tables absent")


def _build_engine(settings: Settings) -> Engine:
    database_url = settings.database_url
    if database_url is None:
        raise PlanError("refusing: database_url is required")
    return create_engine(database_url.get_secret_value(), pool_pre_ping=True)


def _build_adapter(settings: Settings, stable_uid: str) -> MeilisearchMusicSearch:
    api_key = os.environ.get(_MEILI_KEY_ENV) or None
    return MeilisearchMusicSearch(
        MeilisearchConfig(
            base_url=ISOLATED_MEILISEARCH_URL,
            api_key=api_key,
            index_uid=stable_uid,
        )
    )


def execute(
    plan: ActivationPlan,
    settings: Settings,
    *,
    engine_factory=None,
    compose_fn=None,
    adapter_factory=None,
    manager=None,
    store=None,
    lock=None,
    search=None,
) -> int:
    """Run one activation (the only path that touches DB/HTTP)."""
    if engine_factory is None:
        engine_factory = _build_engine
    if compose_fn is None:
        compose_fn = compose_workflow
    if adapter_factory is None:
        adapter_factory = _build_adapter
    try:
        engine = engine_factory(settings)
    except PlanError:
        print("refusing: engine unavailable", flush=True)
        return 2
    except Exception as exc:
        print(f"engine unavailable: {type(exc).__name__}", flush=True)
        return 1
    try:
        try:
            _require_ledger_tables(engine)
        except PlanError:
            print(
                "refusing: music revision ledger tables absent "
                "(apply migration 0009 first; this tool never migrates)",
                flush=True,
            )
            return 2
        session_factory = build_session_factory(engine)
        adapter = adapter_factory(settings, plan.stable_uid)
        if not adapter.index_exists(plan.stable_uid):
            print(
                "refusing: stable index is absent "
                "(bootstrap it manually; this tool never creates it)",
                flush=True,
            )
            return 2
        source = StagingSearchDocumentSource(session_factory)
        if manager is None:
            manager = build_revision_manager(
                adapter, source, task_timeout_s=plan.task_timeout_s
            )
        if store is None:
            store = RevisionStateStore(session_factory)
        if lock is None:
            lock = RevisionActivationLock(engine)
        if search is None:
            search = StableProbeSearch(adapter, plan.stable_uid)
        try:
            _built, record = compose_fn(
                settings=settings,
                session_factory=session_factory,
                manager=manager,
                store=store,
                ops=adapter,
                lock=lock,
                search=search,
                stable_uid=plan.stable_uid,
                base_uid=plan.base_uid,
                rev=plan.rev,
                task_timeout_s=plan.task_timeout_s,
                poll_interval_s=plan.poll_interval_s,
            )
        except MusicActivationError:
            # Never echo exception text: injected/transport errors may
            # embed URLs or key material. Exit code carries the verdict.
            print("activation refused", flush=True)
            return 2
        except Exception as exc:
            print(f"activation aborted: {type(exc).__name__}", flush=True)
            return 1
        print(
            f"activated: rev={record.to_rev} state={record.state} "
            f"stable={plan.stable_uid}",
            flush=True,
        )
        return 0
    except (PlanError, MusicActivationError):
        # Never echo: even our own typed errors travel beside injected
        # values at this boundary. Exit 2 marks an expected refusal.
        print("activation refused", flush=True)
        return 2
    except Exception as exc:
        print(f"activation aborted: {type(exc).__name__}", flush=True)
        return 1
    finally:
        try:
            engine.dispose()
        except Exception:
            pass


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        settings = load_settings()
        plan = plan_from_args(args, settings)
    except PlanError as exc:
        print(str(exc), flush=True)
        return 2
    print(_redacted_plan_line(plan), flush=True)
    if not args.execute:
        print(
            "dry-run: no database connection opened, no HTTP sent, "
            "no writes performed. Re-run with --execute to run.",
            flush=True,
        )
        return 0
    print("executing activation", flush=True)
    try:
        return execute(plan, settings)
    except PlanError as exc:
        print(str(exc), flush=True)
        return 2


if __name__ == "__main__":
    sys.exit(main())
