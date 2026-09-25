"""Bootstrap CLI tests: refusal paths, dry-run safety, execute seams.

No live database or Meili: dry-run proves zero engine/adapter calls
with poisoned seams; execute paths run against SQLite engines (real,
empty or with music tables) and scripted fake adapters. Secrets never
appear in argv or output.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

_CLI_PATH = Path(__file__).resolve().parents[1] / "scripts" / "music_bootstrap_stable.py"


def _cli():
    spec = importlib.util.spec_from_file_location("music_bootstrap_stable", _CLI_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["music_bootstrap_stable"] = module
    spec.loader.exec_module(module)
    return module


def _env(monkeypatch, mode="dev", backend="postgres_staging"):
    monkeypatch.setenv("REMANENCE_MODE", mode)
    monkeypatch.setenv(
        "REMANENCE_DATABASE_URL", "postgresql+psycopg://u:p@127.0.0.1:1/db"
    )
    monkeypatch.setenv("REMANENCE_BLOB_ROOT", "/tmp/opencode-music-blobs")
    monkeypatch.setenv("REMANENCE_MUSIC_SEARCH_BACKEND", backend)


def _argv(**overrides) -> list[str]:
    argv = ["--stable-uid", "remanence_tracks_v1"]
    if overrides.pop("execute", False):
        argv.append("--execute")
    for key, value in overrides.items():
        argv.extend([f"--{key.replace('_', '-')}", str(value)])
    return argv


def _sqlite_music_engine():
    from remanence.music.staging.models import MusicBase

    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as connection:
        connection.execute(text("ATTACH DATABASE ':memory:' AS music"))
    MusicBase.metadata.create_all(engine)
    return engine


class FakeAdapter:
    def __init__(
        self,
        exists: bool = True,
        count: int = 0,
        task_statuses: list[str] | None = None,
    ) -> None:
        self.calls: list[tuple] = []
        self._exists = exists
        self._count = count
        self._statuses = list(task_statuses or ["succeeded"])

    def index_exists(self, index_uid: str) -> bool:
        self.calls.append(("exists", index_uid))
        return self._exists

    def create_index_for(self, index_uid: str, primary_key: str = "id") -> int:
        self.calls.append(("create", index_uid, primary_key))
        assert primary_key == "id"
        self._exists = True
        return 55

    def wait_for_task(self, task_uid: int, *, timeout_s: float = 60.0) -> None:
        from remanence.music.search.meilisearch import TaskTimeoutError

        self.calls.append(("wait", task_uid, timeout_s))
        status = self._statuses.pop(0) if len(self._statuses) > 1 else self._statuses[0]
        if status == "timeout":
            raise TaskTimeoutError("timed out")
        if status != "succeeded":
            from remanence.music.ports import MusicSearchError

            raise MusicSearchError(f"task {status}")

    def index_document_count(self, index_uid: str) -> int:
        self.calls.append(("count", index_uid))
        return self._count


def test_dry_run_validates_and_calls_nothing(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    calls: list[str] = []

    def _poison(*args, **kwargs):
        calls.append("called")
        raise AssertionError("must not be called")

    monkeypatch.setattr(module, "_build_engine", _poison)
    monkeypatch.setattr(module, "_build_adapter", _poison)
    assert module.main(_argv()) == 0
    output = capsys.readouterr().out
    assert "plan:" in output and "dry-run" in output
    assert calls == []


def test_refusals_exit_2_without_calls(monkeypatch, capsys) -> None:
    module = _cli()
    cases = [
        ({"mode": "prod"}, {}),
        ({"backend": "disabled"}, {}),
        ({}, {"stable_uid": "a/b"}),
        ({}, {"stable_uid": "x" * 65}),
        ({}, {"task_timeout_s": "0"}),
        ({}, {"task_timeout_s": "inf"}),
    ]
    for env_override, argv_override in cases:
        _env(monkeypatch, **env_override)
        calls: list[str] = []

        def _poison(*args, **kwargs):
            calls.append("called")
            raise AssertionError("must not be called")

        monkeypatch.setattr(module, "_build_engine", _poison)
        monkeypatch.setattr(module, "_build_adapter", _poison)
        assert module.main(_argv(**argv_override)) == 2, (env_override, argv_override)
        assert calls == []
        capsys.readouterr()


def test_execute_missing_ledger_tables(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    engine = create_engine("sqlite://")
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    try:
        assert module.main(_argv(execute=True)) == 2
        assert "migration 0009" in capsys.readouterr().out
    finally:
        engine.dispose()


def test_execute_refuses_active_and_open_flight(monkeypatch, capsys) -> None:
    from remanence.music.search.revision_store import RevisionStateStore
    from remanence.music.staging.models import MusicBase
    from sqlalchemy.orm import Session as OrmSession

    module = _cli()
    _env(monkeypatch)
    # Open flight: refused before any adapter call.
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    adapter = FakeAdapter()
    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: adapter)
    try:
        store = RevisionStateStore(lambda: OrmSession(engine))
        store.create_revision(
            rev=6,
            candidate_uid="remanence_tracks_v1_rev0006",
            doc_count=2,
            settings_hash="ab" * 32,
        )
        store.open_activation(
            stable_uid="remanence_tracks_v1",
            partner_uid="remanence_tracks_v1_rev0006",
            from_rev=None,
            to_rev=6,
        )
        assert module.main(_argv(execute=True)) == 2
        assert adapter.calls == []
    finally:
        engine.dispose()
    # ACTIVE revision present: refused before any adapter call.
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    adapter = FakeAdapter()
    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: adapter)
    try:
        store = RevisionStateStore(lambda: OrmSession(engine))
        store.create_revision(
            rev=6,
            candidate_uid="remanence_tracks_v1_rev0006",
            doc_count=2,
            settings_hash="ab" * 32,
        )
        boot = store.open_activation(
            stable_uid="remanence_tracks_v1",
            partner_uid="remanence_tracks_v1_rev0006",
            from_rev=None,
            to_rev=6,
        )
        store.transition(boot.id, "PENDING", "SWAPPED")
        store.confirm_activation(boot.id)
        assert module.main(_argv(execute=True)) == 2
        assert adapter.calls == []
    finally:
        engine.dispose()


def test_execute_existing_paths_and_absent_create(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    # Nonempty existing index: refused, no create call.
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    adapter = FakeAdapter(exists=True, count=41)
    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: adapter)
    try:
        assert module.main(_argv(execute=True)) == 2
        assert "create" not in [call[0] for call in adapter.calls]
    finally:
        engine.dispose()
    # Empty existing index: idempotent success, still no create.
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    adapter2 = FakeAdapter(exists=True, count=0)
    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: adapter2)
    try:
        assert module.main(_argv(execute=True)) == 0
        assert [call[0] for call in adapter2.calls] == ["exists", "count"]
        assert "already present and empty" in capsys.readouterr().out
    finally:
        engine.dispose()
    # Absent index: create + wait + re-verify.
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    adapter3 = FakeAdapter(exists=False, count=0)
    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: adapter3)
    try:
        assert module.main(_argv(execute=True)) == 0
        kinds = [call[0] for call in adapter3.calls]
        assert kinds[0] == "exists"
        assert ("create", "remanence_tracks_v1", "id") in adapter3.calls
        assert ("wait", 55, 300.0) in adapter3.calls
        assert "bootstrapped" in capsys.readouterr().out
    finally:
        engine.dispose()


def test_execute_task_failure_and_timeout(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    for statuses, timeout, expected in (
        (["failed"], "300.0", 1),
        (["enqueued"], "0.05", 1),
    ):
        engine = _sqlite_music_engine()
        monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
        adapter = FakeAdapter(exists=False, count=0, task_statuses=statuses)
        monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: adapter)
        try:
            assert (
                module.main(_argv(execute=True, task_timeout_s=timeout))
                == expected
            )
        finally:
            engine.dispose()


def test_secret_bearing_failures_stay_silent(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    secret = "SECRET-KEY-postgresql://u:pw@h/db"
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)

    def _drain():
        captured = capsys.readouterr()
        assert secret not in captured.out
        assert secret not in captured.err

    def _secret_error(where: str):
        return RuntimeError(f"{where} leaked {secret}")

    try:
        monkeypatch.setattr(
            module, "_build_engine", lambda settings: (_ for _ in ()).throw(_secret_error("engine"))
        )
        assert module.main(_argv(execute=True)) == 1
        _drain()
        monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
        monkeypatch.setattr(
            module,
            "_build_adapter",
            lambda settings, uid: (_ for _ in ()).throw(_secret_error("adapter")),
        )
        assert module.main(_argv(execute=True)) == 1
        _drain()

        def _bad_dispose() -> None:
            raise _secret_error("dispose")

        engine = _sqlite_music_engine()
        monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
        monkeypatch.setattr(
            module, "_build_adapter", lambda settings, uid: FakeAdapter(exists=False, count=0)
        )
        monkeypatch.setattr(engine, "dispose", _bad_dispose, raising=False)
        assert module.main(_argv(execute=True)) == 0
        _drain()
    finally:
        capsys.readouterr()
