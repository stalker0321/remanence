"""Mark expired DRAFT capsules ABORTED. The caller owns commit/rollback."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState

_GENERIC_SERVICE_MESSAGE: Final = "expired draft marking failed"
EXPIRED_DRAFT_BATCH_MIN: Final = 1
EXPIRED_DRAFT_BATCH_MAX: Final = 100


class ExpiredDraftMarkingError(Exception):
    """Redacted, stable failure for expired-draft marking."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class ExpiredDraftMarkingResult:
    examined_count: int
    aborted_count: int

    def __repr__(self) -> str:
        return "ExpiredDraftMarkingResult(<redacted>)"


def _error(code: str) -> ExpiredDraftMarkingError:
    return ExpiredDraftMarkingError(code)


def _require_utc(value: object) -> datetime:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() != timedelta(0)
    ):
        raise _error("VALIDATION_FAILED")
    return value


def _require_limit(value: object) -> int:
    if type(value) is not int:
        raise _error("VALIDATION_FAILED")
    if not EXPIRED_DRAFT_BATCH_MIN <= value <= EXPIRED_DRAFT_BATCH_MAX:
        raise _error("VALIDATION_FAILED")
    return value


class ExpiredDraftMarkingService:
    """Abort expired drafts inside the caller's PostgreSQL transaction."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def mark_expired_drafts(
        self,
        *,
        now: datetime,
        limit: int = EXPIRED_DRAFT_BATCH_MAX,
    ) -> ExpiredDraftMarkingResult:
        now = _require_utc(now)
        limit = _require_limit(limit)
        try:
            return self._mark_expired_drafts(now=now, limit=limit)
        except ExpiredDraftMarkingError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None

    def _candidate_ids(self, *, now: datetime, limit: int) -> list[uuid.UUID]:
        with self._session.no_autoflush:
            return list(
                self._session.scalars(
                    select(Capsule.id)
                    .where(
                        Capsule.state == CapsuleState.DRAFT,
                        Capsule.draft_expires_at <= now,
                    )
                    .order_by(Capsule.draft_expires_at.asc(), Capsule.id.asc())
                    .limit(limit)
                )
            )

    def _mark_expired_drafts(
        self, *, now: datetime, limit: int
    ) -> ExpiredDraftMarkingResult:
        candidate_ids = self._candidate_ids(now=now, limit=limit)
        aborted_count = 0
        for capsule_id in candidate_ids:
            if self._abort_if_still_expired_draft(capsule_id, now=now):
                aborted_count += 1
        if type(aborted_count) is not int or aborted_count < 0:
            raise _error("INTERNAL_ERROR")
        examined_count = len(candidate_ids)
        if examined_count < aborted_count:
            raise _error("INTERNAL_ERROR")
        return ExpiredDraftMarkingResult(
            examined_count=examined_count,
            aborted_count=aborted_count,
        )

    def _abort_if_still_expired_draft(
        self, capsule_id: uuid.UUID, *, now: datetime
    ) -> bool:
        with self._session.no_autoflush:
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            if capsule is None or capsule.state is not CapsuleState.DRAFT:
                return False
            if capsule.draft_expires_at > now:
                return False
            capsule.state = CapsuleState.ABORTED
        self._session.flush()
        if (
            capsule.state is not CapsuleState.ABORTED
            or capsule.ready_at is not None
            or capsule.signed_statement is not None
            or capsule.signed_statement_sha256 is not None
            or capsule.publish_signature is not None
        ):
            raise _error("INTERNAL_ERROR")
        return True
