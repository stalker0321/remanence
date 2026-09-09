"""Idempotent lineage-safe logout service."""

from datetime import datetime

from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.tokens import hash_opaque_token


class LogoutService:
    def __init__(self, repo: AuthSessionRepository) -> None:
        self._repo = repo

    def logout(self, access_token: str, now: datetime) -> None:
        token_hash = hash_opaque_token(access_token)
        # Resolve ownership without locking. This also permits logout of a
        # disabled account; the user row is locked before any session rows.
        candidate = self._repo.find_session_by_access_token_hash(token_hash)
        if candidate is None:
            return
        if self._repo.lock_user(candidate.user_id) is None:
            return
        current = self._repo.find_session_by_access_token_hash(token_hash)
        if current is None:
            return
        self._repo.lock_lineage(current.lineage_id)
        auth_session = self._repo.find_by_access_token_hash_for_update(token_hash)
        if auth_session is None:
            return
        self._repo.revoke_lineage(auth_session.lineage_id, now)
