"""Successful refresh token rotation service."""

import enum
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from postmark.auth.session_repository import AuthSessionRepository
from postmark.auth.tokens import generate_access_token, generate_refresh_token, hash_opaque_token

ACCESS_TTL = timedelta(minutes=15)
REFRESH_TTL = timedelta(days=30)


class RefreshRotationStatus(enum.Enum):
    ROTATED = "ROTATED"
    REPLAYED = "REPLAYED"
    INVALID = "INVALID"


@dataclass(frozen=True)
class RefreshRotationResult:
    status: RefreshRotationStatus
    session_id: uuid.UUID | None
    access_token: str | None = field(repr=False, default=None)
    refresh_token: str | None = field(repr=False, default=None)
    access_expires_at: datetime | None = None
    refresh_expires_at: datetime | None = None


class SessionRotationService:
    def __init__(self, repo: AuthSessionRepository) -> None:
        self._repo = repo

    def rotate(self, refresh_token: str, now: datetime) -> RefreshRotationResult:
        token_hash = hash_opaque_token(refresh_token)
        lineage_id = self._repo.find_lineage_id_by_refresh_token_hash(token_hash)
        if lineage_id is None:
            return RefreshRotationResult(
                status=RefreshRotationStatus.INVALID,
                session_id=None,
            )
        self._repo.lock_lineage(lineage_id)
        old = self._repo.find_by_refresh_token_hash_for_update(token_hash)
        if old is None:
            return RefreshRotationResult(
                status=RefreshRotationStatus.INVALID,
                session_id=None,
            )
        if old.rotated_at is not None:
            self._repo.revoke_lineage(old.lineage_id, now)
            return RefreshRotationResult(
                status=RefreshRotationStatus.REPLAYED,
                session_id=None,
            )
        if not self._repo.is_refresh_usable(old, now):
            return RefreshRotationResult(
                status=RefreshRotationStatus.INVALID,
                session_id=None,
            )
        new_access_token = generate_access_token()
        new_refresh_token = generate_refresh_token()
        access_expires_at = now + ACCESS_TTL
        refresh_expires_at = now + REFRESH_TTL
        old.rotated_at = now
        old.last_used_at = now
        child = self._repo.create(
            user_id=old.user_id,
            lineage_id=old.lineage_id,
            parent_session_id=old.id,
            access_token_hash=hash_opaque_token(new_access_token),
            refresh_token_hash=hash_opaque_token(new_refresh_token),
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
        )
        return RefreshRotationResult(
            status=RefreshRotationStatus.ROTATED,
            session_id=child.id,
            access_token=new_access_token,
            refresh_token=new_refresh_token,
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
        )