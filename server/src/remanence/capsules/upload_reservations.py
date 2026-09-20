"""Bounded, per-account admission control for in-flight ciphertext uploads.

The upload endpoint reserves an account's declared artifact size before it
opens a staging file and releases the hold once promotion against disk has
finished (success, failure, or client disconnect). This keeps one account from
turning unbounded concurrent requests into unbounded staging disk usage.

A reservation is keyed by the client's idempotency token for the logical
upload. Re-reserving the same key while the first holder is still active shares
one budgeted hold instead of charging the account twice, and the shared hold is
released only after the last holder lets go.
"""

from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field
from typing import Hashable

from remanence.capsules.limits import (
    MAX_OUTSTANDING_UPLOAD_BYTES_PER_ACCOUNT,
    MAX_OUTSTANDING_UPLOADS_PER_ACCOUNT,
)


class UploadReservationError(Exception):
    """The account already holds its full outstanding-upload budget."""

    code = "RATE_LIMITED"

    def __init__(self) -> None:
        super().__init__("upload admission rejected")

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass
class _ReservationState:
    size: int
    holders: int = 1


@dataclass
class _AccountBudget:
    bytes_reserved: int = 0
    uploads: dict[Hashable, _ReservationState] = field(default_factory=dict)


class UploadReservation:
    """An explicitly owned hold on one account's outstanding-upload budget."""

    __slots__ = ("_manager", "_owner", "_token", "_size", "_released")

    def __init__(
        self,
        manager: "UploadReservationManager",
        owner_user_id: uuid.UUID,
        token: Hashable,
        size: int,
    ) -> None:
        self._manager = manager
        self._owner = owner_user_id
        self._token = token
        self._size = size
        self._released = False

    @property
    def size(self) -> int:
        return self._size

    @property
    def owner_user_id(self) -> uuid.UUID:
        return self._owner

    def __repr__(self) -> str:
        return "UploadReservation(<opaque>)"

    def release(self) -> None:
        """Release the hold. Safe to call more than once."""

        if self._released:
            return
        self._released = True
        self._manager._release(self._owner, self._token)


class UploadReservationManager:
    """Process-local bound on outstanding staged uploads per account."""

    def __init__(
        self,
        *,
        max_bytes_per_account: int,
        max_uploads_per_account: int,
    ) -> None:
        if type(max_bytes_per_account) is not int or max_bytes_per_account <= 0:
            raise ValueError("max bytes per account must be a positive integer")
        if type(max_uploads_per_account) is not int or max_uploads_per_account <= 0:
            raise ValueError("max uploads per account must be a positive integer")
        self._max_bytes = max_bytes_per_account
        self._max_uploads = max_uploads_per_account
        self._lock = threading.Lock()
        self._accounts: dict[uuid.UUID, _AccountBudget] = {}

    def reserve(
        self,
        *,
        owner_user_id: uuid.UUID,
        token: Hashable,
        size: int,
    ) -> UploadReservation:
        if not isinstance(owner_user_id, uuid.UUID):
            raise ValueError("owner must be a UUID")
        if type(size) is not int or size <= 0:
            raise ValueError("size must be a positive integer")

        with self._lock:
            budget = self._accounts.get(owner_user_id)
            if budget is not None:
                existing = budget.uploads.get(token)
                if existing is not None:
                    existing.holders += 1
                    return UploadReservation(self, owner_user_id, token, existing.size)
                if (
                    budget.bytes_reserved + size > self._max_bytes
                    or len(budget.uploads) + 1 > self._max_uploads
                ):
                    raise UploadReservationError()
            if budget is None:
                budget = _AccountBudget()
                self._accounts[owner_user_id] = budget
            budget.uploads[token] = _ReservationState(size=size)
            budget.bytes_reserved += size
            return UploadReservation(self, owner_user_id, token, size)

    def _release(self, owner_user_id: uuid.UUID, token: Hashable) -> None:
        with self._lock:
            budget = self._accounts.get(owner_user_id)
            if budget is None:
                return
            state = budget.uploads.get(token)
            if state is None:
                return
            state.holders -= 1
            if state.holders > 0:
                return
            del budget.uploads[token]
            budget.bytes_reserved -= state.size
            if not budget.uploads:
                del self._accounts[owner_user_id]

    def outstanding_bytes(self, owner_user_id: uuid.UUID) -> int:
        with self._lock:
            budget = self._accounts.get(owner_user_id)
            return 0 if budget is None else budget.bytes_reserved

    def outstanding_uploads(self, owner_user_id: uuid.UUID) -> int:
        with self._lock:
            budget = self._accounts.get(owner_user_id)
            return 0 if budget is None else len(budget.uploads)


def build_upload_reservation_manager() -> UploadReservationManager:
    """Construct the manager with the server's configured admission bounds."""

    return UploadReservationManager(
        max_bytes_per_account=MAX_OUTSTANDING_UPLOAD_BYTES_PER_ACCOUNT,
        max_uploads_per_account=MAX_OUTSTANDING_UPLOADS_PER_ACCOUNT,
    )
