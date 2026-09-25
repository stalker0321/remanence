"""Bounded DEV/TEST operator CLI: bootstrap an empty stable index.

Dry-run is the default: argument/environment validation plus a printed
plan, with zero database connections, zero HTTP calls, and zero factory
invocations (proven by tests with poisoned seams). Only an explicit
``--execute`` prepares the empty stable index a first activation
requires; activation itself stays in ``music_activate_revision.py``.

Safety rules (all fail-closed, exit code 2 for expected refusals):
- ``REMANENCE_MODE`` must be dev/test (read through ``Settings``) and
  ``REMANENCE_MUSIC_SEARCH_BACKEND`` must be explicitly enabled;
  DISABLED/PROD refuse before anything else.
- ``--stable-uid`` shape-checked in pure plan validation; task timeout
  must be finite strictly positive.
- Meilisearch target is fixed to ``ISOLATED_MEILISEARCH_URL``
  (no URL flag/env override exists); the optional API key comes only
  from ``REMANENCE_MUSIC_MEILI_KEY`` and is never printed or passed
  in argv. ``REMANENCE_DATABASE_URL`` likewise travels via Settings
  (env), never argv.
- ``--execute`` additionally requires the 0009 ledger tables, no ACTIVE
  revision and no open activation for the stable UID, then: existing
  nonempty index is refused; existing empty index is an idempotent
  success with NO create call; an absent index is created with
  primaryKey ``id``, its task awaited to terminal success, and
  existence plus document count 0 re-verified. No settings/document
  writes, no delete/overwrite/migration, no activation.
- Run bootstrap with no concurrent activation in flight: a raced second
  bootstrap fails closed on the ledger gate (nonblocking advisory).

Run from ``server/`` with::

    REMANENCE_MODE=dev \\
    REMANENCE_DATABASE_URL=postgresql+psycopg://user:secret@127.0.0.1:55432/remanence \\
    REMANENCE_BLOB_ROOT=/var/lib/remanence/blobs \\
    REMANENCE_MUSIC_SEARCH_BACKEND=postgres_staging \\
        uv run python scripts/music_bootstrap_stable.py \\
            --stable-uid remanence_tracks_v1

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
from remanence.music.search.meilisearch import (  # noqa: E402
    ISOLATED_MEILISEARCH_URL,
    MeilisearchConfig,
    MeilisearchMusicSearch,
)
from remanence.music.search.revision_store import (  # noqa: E402
    RevisionStateStore,
    RevisionStoreConflictError,
)
from remanence.settings import AppMode, MusicSearchBackend, Settings  # noqa: E402

_MEILI_KEY_ENV = "REMANENCE_MUSIC_MEILI_KEY"


class PlanError(RuntimeError):
    """Refusal with a fixed message (exit code 2, never exception text)."""


@dataclass(frozen=True, slots=True)
class BootstrapPlan:
    stable_uid: str
    task_timeout_s: float
    mode: AppMode


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stable-uid", required=True)
    parser.add_argument("--task-timeout-s", type=float, default=300.0)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="actually prepare the index (default is dry-run)",
    )
    return parser.parse_args(argv)


def load_settings() -> Settings:
    try:
        return Settings()
    except Exception:
        raise PlanError("invalid process settings") from None


def _require_uid(value: object, label: str) -> None:
    if type(value) is not str or not value.strip():
        raise PlanError(f"refusing: {label} must be non-empty")
    if len(value) > 64 or value[0] in "-_" or any(
        char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        for char in value
    ):
        raise PlanError(f"refusing: {label} has an invalid index UID shape")


def _require_timeout(value: object, label: str, *, allow_zero: bool) -> float:
    if (
        type(value) is bool
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < 0
        or (not allow_zero and value <= 0)
    ):
        bound = "non-negative" if allow_zero else "strictly positive"
        raise PlanError(f"refusing: {label} must be a finite {bound} number")
    assert isinstance(value, (int, float))
    return float(value)


def plan_from_args(args: argparse.Namespace, settings: Settings) -> BootstrapPlan:
    """Pure validation of args + settings (no I/O of any kind)."""
    if settings.mode is AppMode.PROD:
        raise PlanError("refusing: PROD mode never bootstraps here")
    if settings.mode is not AppMode.DEV and settings.mode is not AppMode.TEST:
        raise PlanError("refusing: mode must be dev or test")
    if settings.music_search_backend is not MusicSearchBackend.POSTGRES_STAGING:
        raise PlanError("refusing: music backend must be explicitly enabled")
    _require_uid(args.stable_uid, "--stable-uid")
    task_timeout_s = _require_timeout(args.task_timeout_s, "--task-timeout-s", allow_zero=False)
    return BootstrapPlan(
        stable_uid=args.stable_uid,
        task_timeout_s=task_timeout_s,
        mode=settings.mode,
    )


def _redacted_plan_line(plan: BootstrapPlan) -> str:
    return (
        f"plan: mode={plan.mode.value} stable={plan.stable_uid} "
        f"timeout_s={plan.task_timeout_s} "
        f"meili={ISOLATED_MEILISEARCH_URL}"
    )


def _require_ledger_tables(engine: Engine) -> None:
    try:
        inspector = inspect(engine)
        present = all(
            inspector.has_table(table, schema="music")
            for table in ("music_index_revision", "music_index_activation")
        )
    except Exception as exc:
        raise PlanError(
            "refusing: music revision ledger tables unverifiable "
            "(apply migration 0009 first; this tool never migrates)"
        ) from exc
    if not present:
        raise PlanError(
            "refusing: music revision ledger tables absent "
            "(apply migration 0009 first; this tool never migrates)"
        )


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
    plan: BootstrapPlan,
    settings: Settings,
    *,
    engine_factory=None,
    adapter_factory=None,
) -> int:
    """Prepare the empty stable index (the only path touching DB/HTTP)."""
    if engine_factory is None:
        engine_factory = _build_engine
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
        session_factory = build_session_factory(engine)
        try:
            _require_ledger_tables(engine)
        except PlanError:
            print(
                "refusing: music revision ledger tables absent "
                "(apply migration 0009 first; this tool never migrates)",
                flush=True,
            )
            return 2
        store = RevisionStateStore(session_factory)
        try:
            if store.find_active_revision() is not None:
                print("refusing: an ACTIVE revision already exists", flush=True)
                return 2
            if store.read_open_activation(plan.stable_uid) is not None:
                print("refusing: an open activation flight exists", flush=True)
                return 2
        except RevisionStoreConflictError:
            print("refusing: ledger state is unsettled", flush=True)
            return 2
        adapter = adapter_factory(settings, plan.stable_uid)
        if adapter.index_exists(plan.stable_uid):
            if adapter.index_document_count(plan.stable_uid) != 0:
                print(
                    "refusing: stable index already serves content "
                    "(bootstrap needs an empty index; this tool never deletes)",
                    flush=True,
                )
                return 2
            print(
                f"bootstrap: stable index {plan.stable_uid} already present "
                "and empty",
                flush=True,
            )
            return 0
        task_uid = adapter.create_index_for(plan.stable_uid, "id")
        adapter.wait_for_task(task_uid, timeout_s=plan.task_timeout_s)
        if not adapter.index_exists(plan.stable_uid):
            print("bootstrap aborted: stable index missing after create", flush=True)
            return 1
        if adapter.index_document_count(plan.stable_uid) != 0:
            print("bootstrap aborted: stable index is not empty", flush=True)
            return 1
        print(
            f"bootstrapped: stable index {plan.stable_uid} present and empty",
            flush=True,
        )
        return 0
    except (PlanError, RevisionStoreConflictError):
        print("bootstrap refused", flush=True)
        return 2
    except Exception as exc:
        print(f"bootstrap aborted: {type(exc).__name__}", flush=True)
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
    print("preparing stable index", flush=True)
    try:
        return execute(plan, settings)
    except PlanError:
        print("bootstrap refused", flush=True)
        return 2


if __name__ == "__main__":
    sys.exit(main())
