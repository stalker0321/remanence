"""One-shot, best-effort maintenance for expired capsules and blob objects.

This module is intentionally separate from the API lifespan.  One invocation
marks at most 100 expired drafts and examines at most 100 committed ABORTED
objects.  The 100-second budget is cooperative: it is checked between the two
service calls and cannot interrupt a synchronous database or BlobStore call.

The object cleanup cursor is deliberately not persisted here.  ``has_more``
is reported as a deferred backlog, not as proof of eventual progress; a later
invocation starts from a null cursor and may revisit the first page.  Existing
cleanup services retain ownership of their transaction and per-object retry
semantics.
"""

from __future__ import annotations

import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Final, Literal

from sqlalchemy import Engine

from remanence.capsules.aborted_object_cleanup_service import (
    ABORTED_OBJECT_CLEANUP_BATCH_MAX,
    AbortedObjectCleanupResult,
    AbortedObjectCleanupService,
)
from remanence.capsules.expired_draft_service import (
    EXPIRED_DRAFT_BATCH_MAX,
    ExpiredDraftMarkingResult,
    ExpiredDraftMarkingService,
)
from remanence.db.session import build_engine, build_session_factory
from remanence.settings import Settings
from remanence.storage import BlobStore, LocalFileBlobStore


MAINTENANCE_BATCH_LIMIT: Final = 100
MAINTENANCE_TIME_BUDGET_SECONDS: Final = 100.0
EXIT_OK: Final = 0
EXIT_FAILURE: Final = 1
EXIT_DEFERRED: Final = 2

_StageStatus = Literal["ok", "partial", "backlog", "deferred", "failed"]
_STABLE_ERROR_CODES: Final = frozenset({"INTERNAL_ERROR", "VALIDATION_FAILED"})


@dataclass(frozen=True, slots=True)
class MaintenanceStageReport:
    stage: str
    status: _StageStatus
    examined_count: int | None = None
    changed_count: int | None = None
    deleted_or_missing_count: int | None = None
    failed_count: int | None = None
    skipped_count: int | None = None
    has_more: bool | None = None
    code: str | None = None
    reason: str | None = None


@dataclass(frozen=True, slots=True)
class MaintenanceReport:
    stages: tuple[MaintenanceStageReport, ...]
    exit_code: int


class _MaintenanceInternalError(Exception):
    """Internal ownership failure without a user-visible message."""


def _stable_code(error: BaseException) -> str:
    code = getattr(error, "code", None)
    if isinstance(code, str) and code in _STABLE_ERROR_CODES:
        return code
    return "INTERNAL_ERROR"


def _safe_rollback(session: object | None) -> None:
    if session is None:
        return
    try:
        session.rollback()  # type: ignore[attr-defined]
    except Exception:
        return


def _safe_close(session: object | None) -> bool:
    if session is None:
        return True
    try:
        session.close()  # type: ignore[attr-defined]
    except Exception:
        return False
    return True


def _mark_expired_drafts(
    session_factory: Callable[[], object], *, now: datetime
) -> ExpiredDraftMarkingResult:
    session: object | None = None
    primary: BaseException | None = None
    result: ExpiredDraftMarkingResult | None = None
    transaction_entered = False
    try:
        session = session_factory()
        with session.begin():  # type: ignore[attr-defined]
            transaction_entered = True
            result = ExpiredDraftMarkingService(session).mark_expired_drafts(
                now=now, limit=MAINTENANCE_BATCH_LIMIT
            )
    except Exception as error:
        primary = error
        if not transaction_entered:
            _safe_rollback(session)
    closed = _safe_close(session)
    if primary is not None:
        raise primary
    if not closed or result is None:
        raise _MaintenanceInternalError
    return result


def _expired_report(result: ExpiredDraftMarkingResult) -> MaintenanceStageReport:
    return MaintenanceStageReport(
        stage="expired_drafts",
        status="ok",
        examined_count=result.examined_count,
        changed_count=result.aborted_count,
    )


def _objects_report(result: AbortedObjectCleanupResult) -> MaintenanceStageReport:
    if result.has_more:
        status: _StageStatus = "backlog"
        reason = "page_limit"
    elif result.failed_count or result.skipped_count:
        status = "partial"
        reason = "per_object_outcome"
    else:
        status = "ok"
        reason = None
    return MaintenanceStageReport(
        stage="aborted_objects",
        status=status,
        examined_count=result.examined_count,
        deleted_or_missing_count=result.deleted_or_missing_count,
        failed_count=result.failed_count,
        skipped_count=result.skipped_count,
        has_more=result.has_more,
        reason=reason,
    )


def _deferred_report(stage: str) -> MaintenanceStageReport:
    return MaintenanceStageReport(
        stage=stage,
        status="deferred",
        reason="time_budget",
    )


def _failed_report(stage: str, error: BaseException) -> MaintenanceStageReport:
    return MaintenanceStageReport(
        stage=stage,
        status="failed",
        code=_stable_code(error),
    )


def _emit_stage(report: MaintenanceStageReport, emit: Callable[[str], None]) -> None:
    fields = [f"maintenance stage={report.stage}", f"status={report.status}"]
    if report.examined_count is not None:
        fields.append(f"examined={report.examined_count}")
    if report.changed_count is not None:
        fields.append(f"changed={report.changed_count}")
    if report.deleted_or_missing_count is not None:
        fields.append(f"deleted_or_missing={report.deleted_or_missing_count}")
    if report.failed_count is not None:
        fields.append(f"failed={report.failed_count}")
    if report.skipped_count is not None:
        fields.append(f"skipped={report.skipped_count}")
    if report.has_more is not None:
        fields.append(f"has_more={str(report.has_more).lower()}")
    if report.code is not None:
        fields.append(f"code={report.code}")
    if report.reason is not None:
        fields.append(f"reason={report.reason}")
    emit(" ".join(fields))


def _default_emit(line: str) -> None:
    print(line, file=sys.stderr)


def run_maintenance(
    session_factory: Callable[[], object],
    blob_store: BlobStore,
    *,
    now: datetime | None = None,
    clock: Callable[[], float] = time.monotonic,
    emit: Callable[[str], None] = _default_emit,
) -> MaintenanceReport:
    """Run one bounded maintenance attempt without owning a process loop."""

    if EXPIRED_DRAFT_BATCH_MAX != MAINTENANCE_BATCH_LIMIT:
        raise RuntimeError("expired cleanup batch contract changed")
    if ABORTED_OBJECT_CLEANUP_BATCH_MAX != MAINTENANCE_BATCH_LIMIT:
        raise RuntimeError("object cleanup batch contract changed")
    captured_now = datetime.now(timezone.utc) if now is None else now
    deadline = clock() + MAINTENANCE_TIME_BUDGET_SECONDS
    stages: list[MaintenanceStageReport] = []

    if clock() >= deadline:
        stages.append(_deferred_report("expired_drafts"))
    else:
        try:
            stages.append(
                _expired_report(_mark_expired_drafts(session_factory, now=captured_now))
            )
        except Exception as error:
            stages.append(_failed_report("expired_drafts", error))

    if clock() >= deadline:
        stages.append(_deferred_report("aborted_objects"))
    else:
        try:
            result = AbortedObjectCleanupService(
                session_factory, blob_store
            ).clean_aborted_objects(limit=MAINTENANCE_BATCH_LIMIT)
            stages.append(_objects_report(result))
        except Exception as error:
            stages.append(_failed_report("aborted_objects", error))

    for report in stages:
        _emit_stage(report, emit)
    if any(report.status == "failed" for report in stages):
        exit_code = EXIT_FAILURE
    elif any(report.status in {"partial", "backlog", "deferred"} for report in stages):
        exit_code = EXIT_DEFERRED
    else:
        exit_code = EXIT_OK
    return MaintenanceReport(stages=tuple(stages), exit_code=exit_code)


def main() -> int:
    """Build owned production resources, run once, and dispose the engine."""

    engine: Engine | None = None
    exit_code = EXIT_FAILURE
    try:
        settings = Settings()
        engine = build_engine(settings)
        session_factory = build_session_factory(engine)
        if settings.blob_root is None:
            raise RuntimeError("blob root is required")
        blob_store = LocalFileBlobStore(settings.blob_root)
        exit_code = run_maintenance(session_factory, blob_store).exit_code
    except Exception as error:
        _emit_stage(_failed_report("bootstrap", error), _default_emit)
    finally:
        if engine is not None:
            try:
                engine.dispose()
            except Exception as error:
                _emit_stage(_failed_report("shutdown", error), _default_emit)
                exit_code = EXIT_FAILURE
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
