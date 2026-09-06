"""PostgreSQL capsule revocation transaction. The caller owns commit/rollback."""

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


_GENERIC_SERVICE_MESSAGE: Final = "capsule revoke failed"
_REVOCATION_WINDOW: Final = timedelta(hours=24)
_LOGGER = logging.getLogger(__name__)


def _log_internal(stage: str, capsule_id: uuid.UUID, exc: BaseException) -> None:
    _LOGGER.error(
        "capsule revoke internal failure stage=%s capsule_id=%s exc_type=%s",
        stage,
        capsule_id,
        type(exc).__name__,
    )


class CapsuleRevokeError(Exception):
    """Redacted, stable failure suitable for the HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class CapsuleRevokeResult:
    capsule_id: uuid.UUID
    state: CapsuleState
    is_replay: bool

    def __repr__(self) -> str:
        return "CapsuleRevokeResult(<redacted>)"


def _error(code: str) -> CapsuleRevokeError:
    return CapsuleRevokeError(code)


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


class CapsuleRevokeService:
    """Revoke one sender-owned READY capsule inside the caller's transaction."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def revoke(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        now: datetime,
    ) -> CapsuleRevokeResult:
        _require_uuid(authenticated_sender_user_id)
        _require_uuid(capsule_id)
        now = _require_utc(now)
        try:
            return self._revoke(
                authenticated_sender_user_id=authenticated_sender_user_id,
                capsule_id=capsule_id,
                now=now,
            )
        except CapsuleRevokeError:
            raise
        except Exception as exc:
            _log_internal("revoke", capsule_id, exc)
            raise _error("INTERNAL_ERROR") from None

    def _revoke(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        now: datetime,
    ) -> CapsuleRevokeResult:
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
            if capsule.state is CapsuleState.REVOKED:
                return self._accepted(capsule, is_replay=True)
            if capsule.state is not CapsuleState.READY:
                raise _error("CAPSULE_STATE_INVALID")
            ready_at = capsule.ready_at
            if (
                not isinstance(ready_at, datetime)
                or ready_at.tzinfo is None
                or ready_at.utcoffset() != timedelta(0)
            ):
                raise _error("INTERNAL_ERROR")
            if now > ready_at + _REVOCATION_WINDOW:
                raise _error("WINDOW_EXPIRED")

            capsule.state = CapsuleState.REVOKED
            try:
                self._session.flush()
            except Exception as exc:
                _log_internal("persist", capsule.id, exc)
                raise _error("INTERNAL_ERROR") from None
            return self._accepted(capsule, is_replay=False)

    @staticmethod
    def _accepted(capsule: Capsule, *, is_replay: bool) -> CapsuleRevokeResult:
        if capsule.state is not CapsuleState.REVOKED:
            raise _error("INTERNAL_ERROR")
        if not isinstance(capsule.id, uuid.UUID):
            raise _error("INTERNAL_ERROR")
        if not isinstance(capsule.ready_at, datetime) or capsule.ready_at.utcoffset() != timedelta(0):
            raise _error("INTERNAL_ERROR")
        if type(is_replay) is not bool:
            raise _error("INTERNAL_ERROR")
        return CapsuleRevokeResult(
            capsule_id=capsule.id,
            state=CapsuleState.REVOKED,
            is_replay=is_replay,
        )
