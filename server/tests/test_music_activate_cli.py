"""Activate CLI tests: refusal paths, dry-run safety, execute seams.

No live database or Meili: dry-run proves zero engine/adapter/compose
calls with poisoned seams; execute paths run against SQLite engines
(real, empty or with music tables) and fake adapters/compose. Secrets
never appear in argv or output.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

_CLI_PATH = Path(__file__).resolve().parents[1] / "scripts" / "music_activate_revision.py"


def _cli():
    spec = importlib.util.spec_from_file_location("music_activate_revision", _CLI_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["music_activate_revision"] = module
    spec.loader.exec_module(module)
    return module


def _env(monkeypatch, mode="dev", backend="postgres_staging"):
    monkeypatch.setenv("REMANENCE_MODE", mode)
    monkeypatch.setenv(
        "REMANENCE_DATABASE_URL", "postgresql+psycopg://u:p@127.0.0.1:1/db"
    )
    monkeypatch.setenv("REMANENCE_BLOB_ROOT", "/tmp/opencode-music-blobs")
    monkeypatch.setenv("REMANENCE_MUSIC_SEARCH_BACKEND", backend)


def _poisoned(module):
    calls: list[str] = []

    def _poison(*args, **kwargs):
        calls.append("called")
        raise AssertionError("must not be called")

    monkeypatch_holder = {"calls": calls, "poison": _poison}
    return monkeypatch_holder


def _argv(**overrides) -> list[str]:
    argv = ["--stable-uid", "remanence_tracks_v1", "--rev", "7"]
    if overrides.pop("execute", False):
        argv.append("--execute")
    for key, value in overrides.items():
        argv.extend([f"--{key.replace('_', '-')}", str(value)])
    return argv


def test_dry_run_validates_and_calls_nothing(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    poisoned = _poisoned(module)
    monkeypatch.setattr(module, "_build_engine", poisoned["poison"])
    monkeypatch.setattr(module, "_build_adapter", poisoned["poison"])
    monkeypatch.setattr(module, "compose_workflow", poisoned["poison"])
    assert module.main(_argv()) == 0
    output = capsys.readouterr().out
    assert "plan:" in output and "dry-run" in output
    assert poisoned["calls"] == []


def test_refusals_exit_2_without_calls(monkeypatch, capsys) -> None:
    module = _cli()
    cases = [
        ({"mode": "prod"}, {}),
        ({"backend": "disabled"}, {}),
        ({}, {"rev": "-1"}),
        ({}, {"stable_uid": ""}),
        ({}, {"base_uid": "other"}),
        ({}, {"task_timeout_s": "inf"}),
        ({}, {"poll_interval_s": "nan"}),
    ]
    for env_override, argv_override in cases:
        _env(monkeypatch, **env_override)
        poisoned = _poisoned(module)
        monkeypatch.setattr(module, "_build_engine", poisoned["poison"])
        monkeypatch.setattr(module, "_build_adapter", poisoned["poison"])
        monkeypatch.setattr(module, "compose_workflow", poisoned["poison"])
        assert module.main(_argv(**argv_override)) == 2, (env_override, argv_override)
        assert poisoned["calls"] == []
        capsys.readouterr()


def _sqlite_music_engine():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as connection:
        connection.execute(text("ATTACH DATABASE ':memory:' AS music"))
    from remanence.music.staging.models import MusicBase

    MusicBase.metadata.create_all(engine)
    return engine


def _stub_pieces(module, monkeypatch):
    class _FakeLock:
        def acquire(self) -> bool:
            return True

        def release(self) -> bool:
            return True

    monkeypatch.setattr(
        module, "StagingSearchDocumentSource", lambda session_factory: object()
    )
    monkeypatch.setattr(
        module, "build_revision_manager", lambda adapter, source, **kwargs: object()
    )
    monkeypatch.setattr(
        module, "RevisionStateStore", lambda session_factory: object()
    )
    monkeypatch.setattr(module, "RevisionActivationLock", lambda engine: _FakeLock())
    monkeypatch.setattr(
        module, "StableProbeSearch", lambda adapter, uid: object()
    )


def test_execute_missing_ledger_tables(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    composed: list[str] = []

    def _fake_compose(**kwargs):
        composed.append("called")
        raise AssertionError("must not be called")

    monkeypatch.setattr(module, "compose_workflow", _fake_compose)
    engine = create_engine("sqlite://")
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    try:
        assert module.main(_argv(execute=True)) == 2
        assert composed == []
        assert "migration 0009" in capsys.readouterr().out
    finally:
        engine.dispose()


def test_execute_stable_missing(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)

    class _NoStable:
        def index_exists(self, index_uid: str) -> bool:
            return False

    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: _NoStable())
    composed: list[str] = []

    def _fake_compose(**kwargs):
        composed.append("called")
        raise AssertionError("must not be called")

    monkeypatch.setattr(module, "compose_workflow", _fake_compose)
    try:
        assert module.main(_argv(execute=True)) == 2
        assert composed == []
    finally:
        engine.dispose()


def test_execute_happy_and_key_never_printed(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    monkeypatch.setenv("REMANENCE_MUSIC_MEILI_KEY", "secret-value-xyz")
    seen: dict = {}

    class _Record:
        to_rev = 7
        state = "CONFIRMED"

    def _fake_compose(**kwargs):
        seen.update(kwargs)
        assert kwargs["stable_uid"] == "remanence_tracks_v1"
        assert kwargs["rev"] == 7
        assert kwargs["settings"].mode.value == "dev"
        return ({"rev": 7}, _Record())

    monkeypatch.setattr(module, "compose_workflow", _fake_compose)
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)

    class _Adapter:
        def index_exists(self, index_uid: str) -> bool:
            return True

    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: _Adapter())
    _stub_pieces(module, monkeypatch)
    try:
        assert module.main(_argv(execute=True)) == 0
        output = capsys.readouterr().out
        assert "activated: rev=7 state=CONFIRMED" in output
        assert "secret-value-xyz" not in output
        assert "u:p@" not in output
    finally:
        engine.dispose()


def test_execute_compose_failure_codes(monkeypatch, capsys) -> None:
    from remanence.music.search.activation import MusicActivationError

    module = _cli()
    _env(monkeypatch)
    for error, expected in ((MusicActivationError("nope"), 2), (RuntimeError("boom"), 1)):
        engine = _sqlite_music_engine()
        monkeypatch.setattr(module, "_build_engine", lambda settings: engine)

        class _Adapter:
            def index_exists(self, index_uid: str) -> bool:
                return True

        monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: _Adapter())

        def _fake_compose(**kwargs):
            raise error

        monkeypatch.setattr(module, "compose_workflow", _fake_compose)
        _stub_pieces(module, monkeypatch)
        try:
            assert module.main(_argv(execute=True)) == expected
            assert "boom" not in capsys.readouterr().out
        finally:
            engine.dispose()


def test_plan_rejects_uid_shape_and_zero_timeout(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    poisoned = _poisoned(module)
    monkeypatch.setattr(module, "_build_engine", poisoned["poison"])
    for argv_override in (
        {"stable_uid": "a/b"},
        {"stable_uid": "x" * 65},
        {"task_timeout_s": "0"},
    ):
        assert module.main(_argv(**argv_override)) == 2
        assert poisoned["calls"] == []
        capsys.readouterr()
    assert module.main(_argv(poll_interval_s="0")) == 0
    assert module.main(
        ["--stable-uid", "remanence_tracks_v1", "--rev", "7", "--stable-uid=-lead"]
    ) == 2


def test_manager_receives_requested_timeout(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    seen: dict = {}

    def _recording_manager(adapter, source, task_timeout_s=60.0):
        seen["timeout"] = task_timeout_s
        return object()

    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)

    class _Adapter:
        def index_exists(self, index_uid: str) -> bool:
            return True

    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: _Adapter())
    _stub_pieces(module, monkeypatch)
    monkeypatch.setattr(module, "build_revision_manager", _recording_manager)

    def _fake_compose(**kwargs):
        return ({"rev": 7}, type("R", (), {"to_rev": 7, "state": "CONFIRMED"})())

    monkeypatch.setattr(module, "compose_workflow", _fake_compose)
    try:
        assert module.main(_argv(execute=True, task_timeout_s="45")) == 0
        assert seen["timeout"] == 45.0
    finally:
        engine.dispose()


def test_secret_bearing_failures_stay_silent(monkeypatch, capsys) -> None:
    module = _cli()
    _env(monkeypatch)
    secret = "SECRET-DB-pw-postgresql://u:pw@h/db"

    def _secret_error(where: str):
        return RuntimeError(f"{where} leaked {secret}")

    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    _stub_pieces(module, monkeypatch)

    class _Adapter:
        def index_exists(self, index_uid: str) -> bool:
            return True

    monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: _Adapter())

    def _fake_compose(**kwargs):
        return ({"rev": 7}, type("R", (), {"to_rev": 7, "state": "CONFIRMED"})())

    monkeypatch.setattr(module, "compose_workflow", _fake_compose)

    def _drain():
        captured = capsys.readouterr()
        assert secret not in captured.out
        assert secret not in captured.err

    try:
        # Engine factory failure.
        monkeypatch.setattr(
            module, "_build_engine", lambda settings: (_ for _ in ()).throw(_secret_error("engine"))
        )
        assert module.main(_argv(execute=True)) == 1
        _drain()
        # Adapter construction failure.
        monkeypatch.setattr(
            module,
            "_build_adapter",
            lambda settings, uid: (_ for _ in ()).throw(_secret_error("adapter")),
        )
        monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
        assert module.main(_argv(execute=True)) == 1
        _drain()
        # Dispose failure still reports success quietly. Fresh engine:
        # earlier cases disposed the shared in-memory DB (ATTACH lost).
        monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: _Adapter())
        engine = _sqlite_music_engine()
        monkeypatch.setattr(module, "_build_engine", lambda settings: engine)

        def _bad_dispose() -> None:
            raise _secret_error("dispose")

        monkeypatch.setattr(engine, "dispose", _bad_dispose, raising=False)
        assert module.main(_argv(execute=True)) == 0
        _drain()
    finally:
        capsys.readouterr()


def test_secret_typed_refusals_stay_silent(monkeypatch, capsys) -> None:
    from remanence.music.search.activation import MusicActivationError

    module = _cli()
    _env(monkeypatch)
    secret = "SECRET-KEY-postgresql://u:pw@h/db"
    engine = _sqlite_music_engine()
    monkeypatch.setattr(module, "_build_engine", lambda settings: engine)
    _stub_pieces(module, monkeypatch)

    class _Adapter:
        def index_exists(self, index_uid: str) -> bool:
            return True

    def _drain():
        captured = capsys.readouterr()
        assert secret not in captured.out
        assert secret not in captured.err

    try:
        # Compose-level typed refusal carrying a secret.
        monkeypatch.setattr(module, "_build_adapter", lambda settings, uid: _Adapter())

        def _refusing_compose(**kwargs):
            raise MusicActivationError(f"refused {secret}")

        monkeypatch.setattr(module, "compose_workflow", _refusing_compose)
        assert module.main(_argv(execute=True)) == 2
        _drain()
        # Adapter probe raising a secret-bearing PlanError.
        class _ProbingAdapter:
            def index_exists(self, index_uid: str) -> bool:
                raise module.PlanError(f"probe failed {secret}")

        monkeypatch.setattr(
            module, "_build_adapter", lambda settings, uid: _ProbingAdapter()
        )
        assert module.main(_argv(execute=True)) == 2
        _drain()
        # Engine factory raising a secret-bearing PlanError.
        monkeypatch.setattr(
            module,
            "_build_engine",
            lambda settings: (_ for _ in ()).throw(module.PlanError(f"db {secret}")),
        )
        assert module.main(_argv(execute=True)) == 2
        _drain()
    finally:
        capsys.readouterr()

