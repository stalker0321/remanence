"""Account disablement boundaries for authenticated session state."""

import uuid
from datetime import datetime

from remanence.auth.session_repository import AuthSessionRepository


class AccountStatusService:
    """Disable or re-enable an account without reviving old credentials.

    The caller owns the transaction.  Both transitions lock the user row
    first; disablement then revokes all session rows while that lock is held.
    Refresh rotation uses the same first lock, so either operation observes a
    single linear order and a post-disable refresh cannot create a child.
    """

    def __init__(self, repo: AuthSessionRepository) -> None:
        self._repo = repo

    def disable(self, user_id: uuid.UUID, now: datetime) -> bool:
        user = self._repo.lock_user(user_id)
        if user is None:
            return False
        if user.disabled_at is None:
            user.disabled_at = now
        revoked_at = user.disabled_at
        self._repo.revoke_all_for_user(user_id, revoked_at)
        return True

    def enable(self, user_id: uuid.UUID) -> bool:
        user = self._repo.lock_user(user_id)
        if user is None:
            return False
        if user.disabled_at is not None:
            # Also repair any session rows created by an older/out-of-band
            # disable path before the flag is cleared.  Re-enable never
            # makes a pre-disable credential live again.
            self._repo.revoke_all_for_user(user_id, user.disabled_at)
        user.disabled_at = None
        return True
