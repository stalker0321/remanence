"""Session-level advisory lock for index activation (D4-B2b3a, code-only).

One dedicated PostgreSQL connection holds ``pg_try_advisory_lock()``
for the whole critical section: immediate busy signal, never a
blocking ``pg_advisory_lock``. The acquisition transaction is committed
before the caller's network work begins, so no transaction is ever
held across the swap HTTP round trips; the lock itself is session
scope and survives the commit.

Uncertain unlock (``False`` result or transport error) invalidates and
discards the connection so a pooled connection can never leak a held
lock back into circulation. Non-Postgres engines are refused outright
(SQLite has no advisory locks — tests inject fakes instead).

No activation, swap, rollback, or store logic lives here: this module
only answers "may I proceed exclusively right now".
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from sqlalchemy import text
from sqlalchemy.engine import Engine


def _lock_key_for(namespace: bytes) -> int:
    digest = hashlib.sha256(namespace).digest()
    return int.from_bytes(digest[:8], "big", signed=True)


#: Fixed scoped lock key: first 8 bytes of sha256("music_index_activation")
#: as a signed int64. A test recomputes this independently.
REVISION_ACTIVATION_LOCK_KEY = _lock_key_for(b"music_index_activation")

_INT64_MIN = -(2**63)
_INT64_MAX = 2**63 - 1


class RevisionLockBusyError(RuntimeError):
    """Another activation flight holds the lock (immediate busy)."""


class RevisionActivationLock:
    """Exclusive session-level lock on its own dedicated connection."""

    def __init__(self, engine: Engine, key: int = REVISION_ACTIVATION_LOCK_KEY) -> None:
        dialect = getattr(getattr(engine, "dialect", None), "name", None)
        if dialect != "postgresql":
            raise ValueError("revision lock requires a PostgreSQL engine")
        if type(key) is not int or isinstance(key, bool):
            raise ValueError("lock key must be an int")
        if not _INT64_MIN <= key <= _INT64_MAX:
            raise ValueError("lock key must fit in signed int64")
        self._engine = engine
        self._key = key
        self._connection: Any | None = None

    @property
    def key(self) -> int:
        return self._key

    @property
    def held(self) -> bool:
        return self._connection is not None

    def acquire(self) -> bool:
        """Try to take the lock; True held, False busy (no waiting).

        On success the acquisition transaction is committed at once and
        the dedicated connection is retained until :meth:`release`.
        """
        if self._connection is not None:
            raise RevisionLockBusyError("lock already held by this provider")
        connection = self._engine.connect()
        try:
            held = connection.execute(
                text("SELECT pg_try_advisory_lock(:key)"), {"key": self._key}
            ).scalar()
            connection.commit()
        except Exception:
            connection.invalidate()
            connection.close()
            raise
        if not held:
            connection.close()
            return False
        self._connection = connection
        return True

    def release(self) -> bool:
        """Release the lock and discard the dedicated connection.

        Returns True only on a confirmed unlock. Any uncertainty
        (``False`` result, transport error) invalidates the connection
        so a possibly-still-locked handle never returns to the pool.
        Releasing an unheld lock is a tolerated no-op returning False.
        """
        connection, self._connection = self._connection, None
        if connection is None:
            return False
        try:
            released = connection.execute(
                text("SELECT pg_advisory_unlock(:key)"), {"key": self._key}
            ).scalar()
            connection.commit()
        except Exception:
            connection.invalidate()
            connection.close()
            return False
        if not released:
            connection.invalidate()
            connection.close()
            return False
        connection.close()
        return True

    @contextmanager
    def claimed(self) -> Iterator[RevisionActivationLock]:
        """Hold the lock for a block; busy raises, release is guaranteed."""
        if not self.acquire():
            raise RevisionLockBusyError("revision activation lock is busy")
        try:
            yield self
        finally:
            self.release()
