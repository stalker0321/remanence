from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

import remanence.maintenance as maintenance
from remanence.capsules.aborted_object_cleanup_service import (
    AbortedObjectCleanupError,
    AbortedObjectCleanupResult,
)
from remanence.capsules.expired_draft_service import (
    ExpiredDraftMarkingError,
    ExpiredDraftMarkingResult,
)


_NOW = datetime(2030, 1, 1, tzinfo=timezone.utc)


class _Session:
    def __init__(self, events: list[str], name: str) -> None:
        self.events = events
        self.name = name

    class _Transaction:
        def __init__(self, owner: _Session) -> None:
            self.owner = owner

        def __enter__(self) -> _Session._Transaction:
            self.owner.events.append(f"{self.owner.name}:begin")
            return self

        def __exit__(self, exc_type: object, exc: object, tb: object) -> bool:
            self.owner.events.append(
                f"{self.owner.name}:{'rollback' if exc_type else 'commit'}"
            )
            return False

    def begin(self) -> _Transaction:
        return self._Transaction(self)

    def rollback(self) -> None:
        self.events.append(f"{self.name}:rollback")

    def close(self) -> None:
        self.events.append(f"{self.name}:close")


def _expired_result() -> ExpiredDraftMarkingResult:
    return ExpiredDraftMarkingResult(examined_count=2, aborted_count=1)


def _objects_result(
    *, has_more: bool = False, failed_count: int = 0, skipped_count: int = 0
) -> AbortedObjectCleanupResult:
    return AbortedObjectCleanupResult(
        examined_count=3,
        deleted_or_missing_count=3 - failed_count - skipped_count,
        failed_count=failed_count,
        skipped_count=skipped_count,
        has_more=has_more,
        next_cursor=None,
    )


def _patch_services(
    monkeypatch: pytest.MonkeyPatch,
    events: list[str],
    *,
    expired: Any = None,
    objects: Any = None,
) -> None:
    class FakeExpired:
        def __init__(self, session: object) -> None:
            events.append("expired:construct")
            self.session = session

        def mark_expired_drafts(self, **kwargs: object) -> ExpiredDraftMarkingResult:
            events.append(f"expired:mark:{kwargs['limit']}")
            if isinstance(expired, BaseException):
                raise expired
            return _expired_result() if expired is None else expired

    class FakeObjects:
        def __init__(self, session_factory: object, blob_store: object) -> None:
            events.append("objects:construct")

        def clean_aborted_objects(self, **kwargs: object) -> AbortedObjectCleanupResult:
            events.append(f"objects:clean:{kwargs['limit']}")
            if isinstance(objects, BaseException):
                raise objects
            return _objects_result() if objects is None else objects

    monkeypatch.setattr(maintenance, "ExpiredDraftMarkingService", FakeExpired)
    monkeypatch.setattr(maintenance, "AbortedObjectCleanupService", FakeObjects)


def test_expiry_commits_and_closes_before_object_cleanup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    sessions = iter((_Session(events, "expiry"), _Session(events, "objects")))
    _patch_services(monkeypatch, events)

    report = maintenance.run_maintenance(
        lambda: next(sessions), object(), now=_NOW, emit=lambda _: None
    )

    assert report.exit_code == maintenance.EXIT_OK
    assert events == [
        "expiry:begin",
        "expired:construct",
        "expired:mark:100",
        "expiry:commit",
        "expiry:close",
        "objects:construct",
        "objects:clean:100",
    ]


def test_failure_rolls_back_closes_and_continues_with_redacted_code(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    sessions = iter((_Session(events, "expiry"), _Session(events, "objects")))
    _patch_services(
        monkeypatch,
        events,
        expired=ExpiredDraftMarkingError("INTERNAL_ERROR"),
    )
    output: list[str] = []

    report = maintenance.run_maintenance(
        lambda: next(sessions), object(), now=_NOW, emit=output.append
    )

    assert report.exit_code == maintenance.EXIT_FAILURE
    assert events == [
        "expiry:begin",
        "expired:construct",
        "expired:mark:100",
        "expiry:rollback",
        "expiry:close",
        "objects:construct",
        "objects:clean:100",
    ]
    assert output == [
        "maintenance stage=expired_drafts status=failed code=INTERNAL_ERROR",
        "maintenance stage=aborted_objects status=ok examined=3 "
        "deleted_or_missing=3 failed=0 skipped=0 has_more=false",
    ]
    assert "expired draft marking failed" not in " ".join(output)


def test_backlog_is_deferred_not_infrastructure_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_services(monkeypatch, [], objects=_objects_result(has_more=True))
    output: list[str] = []

    report = maintenance.run_maintenance(
        lambda: _Session([], "session"), object(), now=_NOW, emit=output.append
    )

    assert report.exit_code == maintenance.EXIT_DEFERRED
    assert report.stages[1].status == "backlog"
    assert "status=backlog" in output[1]
    assert "code=INTERNAL_ERROR" not in output[1]


def test_missing_objects_are_successful_service_outcomes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_services(monkeypatch, [], objects=_objects_result())
    report = maintenance.run_maintenance(
        lambda: _Session([], "session"), object(), now=_NOW, emit=lambda _: None
    )

    assert report.exit_code == maintenance.EXIT_OK
    assert report.stages[1].status == "ok"
    assert report.stages[1].deleted_or_missing_count == 3


def test_object_failure_is_partial_and_redacted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_services(
        monkeypatch,
        [],
        objects=_objects_result(failed_count=1),
    )
    output: list[str] = []

    report = maintenance.run_maintenance(
        lambda: _Session([], "session"), object(), now=_NOW, emit=output.append
    )

    assert report.exit_code == maintenance.EXIT_DEFERRED
    assert report.stages[1].status == "partial"
    assert "failed=1" in output[1]
    assert "object-key" not in output[1]


def test_time_budget_defers_without_running_second_service(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    _patch_services(monkeypatch, events)
    ticks = iter((0.0, 0.0, maintenance.MAINTENANCE_TIME_BUDGET_SECONDS + 1.0))

    report = maintenance.run_maintenance(
        lambda: _Session(events, "expiry"),
        object(),
        now=_NOW,
        clock=lambda: next(ticks),
        emit=lambda _: None,
    )

    assert report.exit_code == maintenance.EXIT_DEFERRED
    assert [stage.status for stage in report.stages] == ["ok", "deferred"]
    assert "objects:construct" not in events


def test_cleanup_service_failure_is_reported_without_raw_exception(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_services(
        monkeypatch,
        [],
        objects=AbortedObjectCleanupError("INTERNAL_ERROR"),
    )
    output: list[str] = []

    report = maintenance.run_maintenance(
        lambda: _Session([], "session"), object(), now=_NOW, emit=output.append
    )

    assert report.exit_code == maintenance.EXIT_FAILURE
    assert output[1] == (
        "maintenance stage=aborted_objects status=failed code=INTERNAL_ERROR"
    )
    assert "aborted object cleanup failed" not in output[1]


def test_main_disposes_owned_engine_after_success(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class Engine:
        def dispose(self) -> None:
            events.append("engine:dispose")

    settings = type("SettingsStub", (), {"blob_root": Path("/unused")})()
    monkeypatch.setattr(maintenance, "Settings", lambda: settings)
    monkeypatch.setattr(maintenance, "build_engine", lambda _: Engine())
    monkeypatch.setattr(maintenance, "build_session_factory", lambda _: "factory")
    monkeypatch.setattr(maintenance, "LocalFileBlobStore", lambda _: "store")
    monkeypatch.setattr(
        maintenance,
        "run_maintenance",
        lambda session_factory, blob_store: (
            events.append(f"run:{session_factory}:{blob_store}")
            or maintenance.MaintenanceReport((), maintenance.EXIT_OK)
        ),
    )

    assert maintenance.main() == maintenance.EXIT_OK
    assert events == ["run:factory:store", "engine:dispose"]


def test_main_disposes_engine_and_redacts_bootstrap_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class Engine:
        def dispose(self) -> None:
            events.append("engine:dispose")

    settings = type("SettingsStub", (), {"blob_root": Path("/unused")})()
    monkeypatch.setattr(maintenance, "Settings", lambda: settings)
    monkeypatch.setattr(maintenance, "build_engine", lambda _: Engine())
    monkeypatch.setattr(
        maintenance,
        "build_session_factory",
        lambda _: (_ for _ in ()).throw(RuntimeError("secret-dsn")),
    )
    output: list[str] = []
    monkeypatch.setattr(maintenance, "_default_emit", output.append)

    assert maintenance.main() == maintenance.EXIT_FAILURE
    assert events == ["engine:dispose"]
    assert output == [
        "maintenance stage=bootstrap status=failed code=INTERNAL_ERROR"
    ]
    assert "secret-dsn" not in " ".join(output)
