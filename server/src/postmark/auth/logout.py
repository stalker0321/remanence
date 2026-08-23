"""Idempotent lineage-safe logout service."""

from datetime import datetime

from postmark.auth.session_repository import AuthSessionRepository
from postmark.auth.tokens import hash_opaque_token


class LogoutService:
    def __init__(self, repo: AuthSessionRepository) -> None:
        self._repo = repo

    def logout(self, access_token: str, now: datetime) -> None:
        token_hash = hash_opaque_token(access_token)
        lineage_id = self._repo.find_lineage_id_by_access_token_hash(token_hash)
        if lineage_id is None:
            return
        self._repo.lock_lineage(lineage_id)
        auth_session = self._repo.find_by_access_token_hash_for_update(token_hash)
        if auth_session is None:
            return
        self._repo.revoke_lineage(lineage_id, now)