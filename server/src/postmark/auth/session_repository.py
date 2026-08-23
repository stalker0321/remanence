"""Auth session persistence creation."""

import uuid
from datetime import datetime

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