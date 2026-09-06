"""Recipient-only, lock-free tombstone feed query."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import select, tuple_
from sqlalchemy.orm import Session

from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.tombstone_cursor import (
    TombstoneCursor,
    decode_tombstone_cursor,
    encode_tombstone_cursor,
)


_GENERIC_SERVICE_MESSAGE: Final = "tombstone query failed"
_TOMBSTONE_PAGE_MIN: Final = 1
_TOMBSTONE_SEQUENCE_MAX: Final = (1 << 63) - 1


class TombstoneQueryError(Exception):
    """Redacted, stable failure for the later HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class TombstoneSnapshot:
    capsule_id: uuid.UUID
    tombstone_sequence: int
    revoked_at: datetime

    def __repr__(self) -> str:
        return "TombstoneSnapshot(<redacted>)"


@dataclass(frozen=True, slots=True)
class TombstonePage:
    items: tuple[TombstoneSnapshot, ...]
    has_more: bool
    next_cursor: str | None

    def __repr__(self) -> str:
        return "TombstonePage(<redacted>)"


def _error(code: str) -> TombstoneQueryError:
    return TombstoneQueryError(code)


def _require_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")
    return value


def _require_limit(value: object) -> int:
    if type(value) is not int or not _TOMBSTONE_PAGE_MIN <= value <= LIMITS_V1.incoming_page_max:
        raise _error("VALIDATION_FAILED")
    return value


def _require_cursor(value: object) -> TombstoneCursor | None:
    if value is None:
        return None
    mapped: TombstoneQueryError | None = None
    decoded: TombstoneCursor | None = None
    try:
        decoded = decode_tombstone_cursor(value)
    except Exception:
        mapped = _error("VALIDATION_FAILED")
    if mapped is not None:
        raise mapped
    if decoded is None:
        raise _error("VALIDATION_FAILED")
    return decoded


class TombstoneQueryService:
    """Page committed REVOKED capsule markers for the authenticated recipient."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def list_tombstones(
        self,
        *,
        authenticated_recipient_user_id: uuid.UUID,
        cursor: str | None = None,
        limit: int = LIMITS_V1.incoming_page_default,
    ) -> TombstonePage:
        recipient_id = _require_uuid(authenticated_recipient_user_id)
        after = _require_cursor(cursor)
        limit = _require_limit(limit)
        mapped: TombstoneQueryError | None = None
        try:
            return self._list_tombstones(
                recipient_id=recipient_id,
                after=after,
                limit=limit,
            )
        except TombstoneQueryError:
            raise
        except Exception:
            mapped = _error("INTERNAL_ERROR")
        if mapped is not None:
            raise mapped

    def _list_tombstones(
        self,
        *,
        recipient_id: uuid.UUID,
        after: TombstoneCursor | None,
        limit: int,
    ) -> TombstonePage:
        if after is not None:
            anchor = self._session.scalar(
                select(Capsule.tombstone_sequence).where(
                    Capsule.id == after.capsule_id,
                    Capsule.recipient_user_id == recipient_id,
                    Capsule.state == CapsuleState.REVOKED,
                )
            )
            if anchor != after.tombstone_sequence:
                raise _error("VALIDATION_FAILED")

        stmt = select(Capsule.id, Capsule.tombstone_sequence, Capsule.revoked_at).where(
            Capsule.recipient_user_id == recipient_id,
            Capsule.state == CapsuleState.REVOKED,
        )
        if after is not None:
            stmt = stmt.where(
                tuple_(Capsule.tombstone_sequence, Capsule.id)
                > tuple_(after.tombstone_sequence, after.capsule_id)
            )
        rows = list(
            self._session.execute(
                stmt.order_by(Capsule.tombstone_sequence.asc(), Capsule.id.asc())
                .limit(limit + 1)
            ).all()
        )
        has_more = len(rows) > limit
        items = tuple(self._snapshot(row) for row in rows[:limit])
        if items:
            last = items[-1]
            next_cursor = encode_tombstone_cursor(
                tombstone_sequence=last.tombstone_sequence,
                capsule_id=last.capsule_id,
            )
        elif after is not None:
            next_cursor = encode_tombstone_cursor(
                tombstone_sequence=after.tombstone_sequence,
                capsule_id=after.capsule_id,
            )
        else:
            next_cursor = None
        if has_more and not items:
            raise _error("INTERNAL_ERROR")
        return TombstonePage(items=items, has_more=has_more, next_cursor=next_cursor)

    @staticmethod
    def _snapshot(row: object) -> TombstoneSnapshot:
        capsule_id, sequence, revoked_at = row
        if (
            not isinstance(capsule_id, uuid.UUID)
            or type(sequence) is not int
            or not 0 < sequence <= _TOMBSTONE_SEQUENCE_MAX
            or not isinstance(revoked_at, datetime)
            or revoked_at.tzinfo is None
            or revoked_at.utcoffset() != timedelta(0)
        ):
            raise _error("INTERNAL_ERROR")
        return TombstoneSnapshot(
            capsule_id=capsule_id,
            tombstone_sequence=sequence,
            revoked_at=revoked_at,
        )
