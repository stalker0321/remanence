"""Durable recipient first-open claim transaction."""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState


_GENERIC_SERVICE_MESSAGE: Final = "capsule first-open claim failed"
_LOGGER = logging.getLogger(__name__)


def _log_internal(stage: str, capsule_id: uuid.UUID, exc: BaseException) -> None:
    _LOGGER.error(
        "capsule first-open internal failure stage=%s capsule_id=%s exc_type=%s",
        stage,
        capsule_id,
        type(exc).__name__,
    )


class CapsuleFirstOpenError(Exception):
    """Redacted, stable failure suitable for the HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class CapsuleFirstOpenResult:
    capsule_id: uuid.UUID
    first_opened_at: datetime
    is_replay: bool

    def __repr__(self) -> str:
        return "CapsuleFirstOpenResult(<redacted>)"


def _error(code: str) -> CapsuleFirstOpenError:
    return CapsuleFirstOpenError(code)


def _require_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")
    return value


def _require_utc(value: object) -> datetime:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() != timedelta(0)
    ):
        raise _error("VALIDATION_FAILED")
    return value


class CapsuleFirstOpenService:
    """Claim one recipient first-open inside the capsule transaction."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def claim(
        self,
        *,
        authenticated_recipient_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        now: datetime,
    ) -> CapsuleFirstOpenResult:
        recipient_id = _require_uuid(authenticated_recipient_user_id)
        capsule_id = _require_uuid(capsule_id)
        now = _require_utc(now)
        try:
            return self._claim(
                recipient_id=recipient_id,
                capsule_id=capsule_id,
                now=now,
            )
        except CapsuleFirstOpenError:
            raise
        except Exception as exc:
            _log_internal("claim", capsule_id, exc)
            raise _error("INTERNAL_ERROR") from None

    def _claim(
        self,
        *,
        recipient_id: uuid.UUID,
        capsule_id: uuid.UUID,
        now: datetime,
    ) -> CapsuleFirstOpenResult:
        with self._session.no_autoflush:
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            if capsule is None or capsule.recipient_user_id != recipient_id:
                raise _error("CAPSULE_NOT_FOUND")
            if capsule.state is not CapsuleState.READY:
                raise _error("CAPSULE_STATE_INVALID")
            if capsule.first_opened_at is not None:
                return self._accepted(capsule.id, capsule.first_opened_at, is_replay=True)

            capsule.first_opened_at = now
            self._session.flush()
            if capsule.first_opened_at != now:
                raise _error("INTERNAL_ERROR")
            return self._accepted(capsule.id, now, is_replay=False)

    @staticmethod
    def _accepted(
        capsule_id: uuid.UUID,
        first_opened_at: datetime,
        *,
        is_replay: bool,
    ) -> CapsuleFirstOpenResult:
        if not isinstance(capsule_id, uuid.UUID):
            raise _error("INTERNAL_ERROR")
        if (
            not isinstance(first_opened_at, datetime)
            or first_opened_at.tzinfo is None
            or first_opened_at.utcoffset() != timedelta(0)
        ):
            raise _error("INTERNAL_ERROR")
        return CapsuleFirstOpenResult(capsule_id, first_opened_at, is_replay)
