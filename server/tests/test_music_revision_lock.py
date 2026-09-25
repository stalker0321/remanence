"""Revision lock provider: fake-connection tests only (no live PG).

Exact SQL/key, busy/reacquire, double-release, exception/finally,
uncertain-unlock invalidation, no transaction held across the caller's
body, and the non-Postgres guard.
"""

from __future__ import annotations

import hashlib

import pytest

from remanence.music.search.revision_lock import (
    REVISION_ACTIVATION_LOCK_KEY,
    RevisionActivationLock,
    RevisionLockBusyError,
)


class FakeConnection:
    def __init__(self, script: list) -> None:
        self._script = list(script)
        self.statements: list[tuple[str, dict]] = []
        self.commits = 0
        self.closed = False
        self.invalidated = False

    def execute(self, statement, params=None):
        self.statements.append((str(statement), dict(params or {})))
        action = self._script.pop(0) if self._script else True
        if isinstance(action, Exception):
            raise action
        class _Scalar:
            def __init__(self, value: object) -> None:
                self._value = value

            def scalar(self) -> object:
                return self._value

        return _Scalar(action)

    def commit(self) -> None:
        self.commits += 1

    def invalidate(self) -> None:
        self.invalidated = True

    def close(self) -> None:
        self.closed = True


class FakeEngine:
    def __init__(self, dialect: str = "postgresql", script: list | None = None) -> None:
        self.dialect_name = dialect
        self._script = script if script is not None else []
        self.connections: list[FakeConnection] = []

    @property
    def dialect(self):
        parent = self

        class _Dialect:
            @property
            def name(self) -> str:
                return parent.dialect_name

        return _Dialect()

    def connect(self) -> FakeConnection:
        connection = FakeConnection(self._script)
        self.connections.append(connection)
        return connection


def _lock(engine: FakeEngine, **kwargs) -> RevisionActivationLock:
    return RevisionActivationLock(engine, **kwargs)  # type: ignore[arg-type]


def test_key_is_deterministic_namespaced_int64() -> None:
    expected = int.from_bytes(
        hashlib.sha256(b"music_index_activation").digest()[:8], "big", signed=True
    )
    assert REVISION_ACTIVATION_LOCK_KEY == expected
    assert -(2**63) <= REVISION_ACTIVATION_LOCK_KEY < 2**63


def test_acquire_emits_exact_sql_and_commits() -> None:
    engine = FakeEngine(script=[True])
    lock = _lock(engine)
    assert lock.acquire() is True
    assert lock.held is True
    (connection,) = engine.connections
    assert connection.statements == [
        ("SELECT pg_try_advisory_lock(:key)", {"key": REVISION_ACTIVATION_LOCK_KEY})
    ]
    assert connection.commits == 1
    assert connection.closed is False


def test_busy_returns_false_and_closes_connection() -> None:
    engine = FakeEngine(script=[False])
    lock = _lock(engine)
    assert lock.acquire() is False
    assert lock.held is False
    (connection,) = engine.connections
    assert connection.commits == 1
    assert connection.closed is True
    assert connection.invalidated is False


def test_reacquire_after_release_works() -> None:
    engine = FakeEngine(script=[True, True])
    lock = _lock(engine)
    assert lock.acquire() is True
    assert lock.release() is True
    assert lock.acquire() is True
    assert len(engine.connections) == 2


def test_double_acquire_is_programming_error() -> None:
    engine = FakeEngine(script=[True])
    lock = _lock(engine)
    assert lock.acquire() is True
    with pytest.raises(RevisionLockBusyError):
        lock.acquire()


def test_double_release_tolerated() -> None:
    engine = FakeEngine(script=[True])
    lock = _lock(engine)
    assert lock.acquire() is True
    assert lock.release() is True
    assert lock.release() is False


def test_release_confirms_unlock_and_closes() -> None:
    engine = FakeEngine(script=[True, True])
    lock = _lock(engine)
    lock.acquire()
    assert lock.release() is True
    (connection,) = engine.connections
    assert connection.statements == [
        ("SELECT pg_try_advisory_lock(:key)", {"key": REVISION_ACTIVATION_LOCK_KEY}),
        ("SELECT pg_advisory_unlock(:key)", {"key": REVISION_ACTIVATION_LOCK_KEY}),
    ]
    assert connection.commits == 2
    assert connection.closed is True
    assert connection.invalidated is False


def test_uncertain_unlock_invalidates_connection() -> None:
    engine = FakeEngine(script=[True, False])
    lock = _lock(engine)
    lock.acquire()
    assert lock.release() is False
    assert lock.held is False
    (connection,) = engine.connections
    assert connection.invalidated is True
    assert connection.closed is True


def test_unlock_transport_error_invalidates() -> None:
    engine = FakeEngine(script=[True, RuntimeError("boom")])
    lock = _lock(engine)
    lock.acquire()
    assert lock.release() is False
    (connection,) = engine.connections
    assert connection.invalidated is True
    assert connection.closed is True


def test_acquire_failure_closes_and_propagates() -> None:
    engine = FakeEngine(script=[RuntimeError("down")])
    lock = _lock(engine)
    with pytest.raises(RuntimeError):
        lock.acquire()
    assert lock.held is False
    (connection,) = engine.connections
    assert connection.invalidated is True
    assert connection.closed is True


def test_claimed_context_releases_on_body_exception() -> None:
    engine = FakeEngine(script=[True, True])
    lock = _lock(engine)
    with pytest.raises(ValueError):
        with lock.claimed():
            raise ValueError("body failed")
    assert lock.held is False
    assert engine.connections[0].closed is True


def test_claimed_busy_raises_without_body() -> None:
    engine = FakeEngine(script=[False])
    lock = _lock(engine)
    entered = False
    with pytest.raises(RevisionLockBusyError):
        with lock.claimed():
            entered = True
    assert entered is False


def test_non_postgres_engine_rejected() -> None:
    with pytest.raises(ValueError):
        _lock(FakeEngine(dialect="sqlite"))
    with pytest.raises(ValueError):
        _lock(FakeEngine(dialect=""))
    with pytest.raises(ValueError):
        RevisionActivationLock(object())  # type: ignore[arg-type]


def test_bad_key_rejected() -> None:
    engine = FakeEngine()
    with pytest.raises(ValueError):
        _lock(engine, key=2**63)
    with pytest.raises(ValueError):
        _lock(engine, key=-(2**63) - 1)
    with pytest.raises(ValueError):
        _lock(engine, key=True)  # type: ignore[arg-type]
    assert _lock(engine, key=7).key == 7
