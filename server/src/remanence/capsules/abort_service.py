"""PostgreSQL abort transaction. The caller owns commit/rollback."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState

_GENERIC_SERVICE_MESSAGE: Final = "capsule abort failed"


class CapsuleAbortError(Exception):
    """Redacted, stable failure suitable for the later HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class CapsuleAbortResult:
    capsule_id: uuid.UUID
    state: CapsuleState
    is_replay: bool

    def __repr__(self) -> str:
        return "CapsuleAbortResult(<redacted>)"


def _error(code: str) -> CapsuleAbortError:
    return CapsuleAbortError(code)


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


class CapsuleAbortService:
    """Abort one sender-owned draft inside the caller's PostgreSQL transaction."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def abort(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        now: datetime,
    ) -> CapsuleAbortResult:
        _require_uuid(authenticated_sender_user_id)
        _require_uuid(capsule_id)
        _require_utc(now)
        try:
            return self._abort(
                authenticated_sender_user_id=authenticated_sender_user_id,
                capsule_id=capsule_id,
            )
        except CapsuleAbortError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None

    def _abort(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
    ) -> CapsuleAbortResult:
        with self._session.no_autoflush:
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            if capsule is None or capsule.sender_user_id != authenticated_sender_user_id:
                raise _error("CAPSULE_NOT_FOUND")
            if capsule.state is CapsuleState.ABORTED:
                return self._accepted(capsule, is_replay=True)
            if capsule.state is CapsuleState.READY:
                raise _error("CAPSULE_STATE_INVALID")
            if capsule.state is not CapsuleState.DRAFT:
                raise _error("INTERNAL_ERROR")
            capsule.state = CapsuleState.ABORTED
            self._session.flush()
            return self._accepted(capsule, is_replay=False)

    @staticmethod
    def _accepted(capsule: Capsule, *, is_replay: bool) -> CapsuleAbortResult:
        if capsule.state is not CapsuleState.ABORTED:
            raise _error("INTERNAL_ERROR")
        if (
            capsule.ready_at is not None
            or capsule.signed_statement is not None
            or capsule.signed_statement_sha256 is not None
            or capsule.publish_signature is not None
        ):
            raise _error("INTERNAL_ERROR")
        if type(is_replay) is not bool:
            raise _error("INTERNAL_ERROR")
        if not isinstance(capsule.id, uuid.UUID):
            raise _error("INTERNAL_ERROR")
        return CapsuleAbortResult(
            capsule_id=capsule.id,
            state=CapsuleState.ABORTED,
            is_replay=is_replay,
        )
