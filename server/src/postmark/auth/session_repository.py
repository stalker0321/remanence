"""Auth session persistence creation, read/expiry lookups, and rotation primitives."""

import uuid
from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from postmark.auth.models import AuthSession


class AuthSessionRepository:
    def __init__(self, session: Session) -> None:
        self._session = session

    def create(
        self,
        *,
        user_id: uuid.UUID,
        access_token_hash: bytes,
        refresh_token_hash: bytes,
        access_expires_at: datetime,
        refresh_expires_at: datetime,
        lineage_id: uuid.UUID | None = None,
        parent_session_id: uuid.UUID | None = None,
    ) -> AuthSession:
        session_id = uuid.uuid4()
        if lineage_id is None:
            lineage_id = session_id
        auth_session = AuthSession(
            id=session_id,
            user_id=user_id,
            lineage_id=lineage_id,
            parent_session_id=parent_session_id,
            access_token_hash=access_token_hash,
            refresh_token_hash=refresh_token_hash,
            access_expires_at=access_expires_at,
            refresh_expires_at=refresh_expires_at,
        )
        self._session.add(auth_session)
        self._session.flush()
        return auth_session

    def find_by_access_token_hash(self, token_hash: bytes, now: datetime) -> AuthSession | None:
        statement = select(AuthSession).where(
            AuthSession.access_token_hash == token_hash,
            AuthSession.revoked_at.is_(None),
            AuthSession.rotated_at.is_(None),
            AuthSession.access_expires_at > now,
        )
        return self._session.scalar(statement)

    def find_by_refresh_token_hash(self, token_hash: bytes) -> AuthSession | None:
        statement = select(AuthSession).where(AuthSession.refresh_token_hash == token_hash)
        return self._session.scalar(statement)

    def find_by_refresh_token_hash_for_update(self, token_hash: bytes) -> AuthSession | None:
        statement = (
            select(AuthSession)
            .where(AuthSession.refresh_token_hash == token_hash)
            .with_for_update()
        )
        return self._session.scalar(statement)

    def revoke_lineage(self, lineage_id: uuid.UUID, revoked_at: datetime) -> int:
        statement = (
            update(AuthSession)
            .where(
                AuthSession.lineage_id == lineage_id,
                AuthSession.revoked_at.is_(None),
            )
            .values(revoked_at=revoked_at)
        )
        result = self._session.execute(statement)
        return result.rowcount

    @staticmethod
    def is_refresh_usable(auth_session: AuthSession, now: datetime) -> bool:
        return (
            auth_session.refresh_expires_at > now
            and auth_session.revoked_at is None
            and auth_session.rotated_at is None
        )