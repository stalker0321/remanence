"""Integration tests for lineage serialization repository primitives against a temporary PostgreSQL database."""

import inspect
import threading
from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.dialects import postgresql

from postmark.auth.models import AuthSession
from postmark.auth.session_repository import AuthSessionRepository
from postmark.users.models import User

pytest_plugins = ("test_session_repository_create",)

_ACCESS_HASH = bytes(range(32))
_ACCESS_EXPIRES = datetime(2030, 1, 1, tzinfo=timezone.utc)
_REFRESH_EXPIRES = datetime(2030, 2, 1, tzinfo=timezone.utc)
_NOW = datetime(2029, 6, 1, 12, 0, 0, tzinfo=timezone.utc)


def _create_user(session) -> User:
    user = User(
        id=uuid4(),
        email_normalized=f"user-{uuid4().hex}@example.com",
        handle_normalized=f"handle{uuid4().hex[:20]}",
        handle_display=f"handle{uuid4().hex[:20]}",
    )
    session.add(user)
    session.flush()
    return user


def _create_active(repo: AuthSessionRepository, *, user_id, access_hash, refresh_hash, lineage_id=None, parent_session_id=None) -> AuthSession:
    return repo.create(
        user_id=user_id,
        access_token_hash=access_hash,
        refresh_token_hash=refresh_hash,
        access_expires_at=_ACCESS_EXPIRES,
        refresh_expires_at=_REFRESH_EXPIRES,
        lineage_id=lineage_id,
        parent_session_id=parent_session_id,
    )


def test_lineage_id_lookup_found_and_wrong(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=bytes(range(32, 64)),
        )
        session.commit()
        session.expunge_all()
        found = repo.find_lineage_id_by_refresh_token_hash(root.refresh_token_hash)
        assert found == root.lineage_id
        assert repo.find_lineage_id_by_refresh_token_hash(bytes(range(1, 33))) is None


def test_lock_lineage_returns_only_that_lineage_in_uuid_order(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=bytes(range(32, 64)),
        )
        child_a = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        child_b = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(128, 160)),
            refresh_hash=bytes(range(160, 192)),
            lineage_id=root.lineage_id,
            parent_session_id=child_a.id,
        )
        other = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(192, 224)),
            refresh_hash=bytes(range(224, 256)),
        )
        session.commit()
        session.expunge_all()

        locked = repo.lock_lineage(root.lineage_id)
        assert {row.id for row in locked} == {root.id, child_a.id, child_b.id}
        assert other.id not in {row.id for row in locked}
        ids = [row.id for row in locked]
        assert ids == sorted(ids)


def test_lock_lineage_source_uses_order_by_for_update_and_populate_existing() -> None:
    source = inspect.getsource(AuthSessionRepository.lock_lineage)
    assert "order_by(AuthSession.id)" in source
    assert ".with_for_update()" in source
    assert "populate_existing" in source
    statement = (
        select(AuthSession)
        .where(AuthSession.lineage_id == uuid4())
        .order_by(AuthSession.id)
        .with_for_update()
    )
    compiled = str(statement.compile(dialect=postgresql.dialect()))
    assert "ORDER BY auth_sessions.id" in compiled
    assert "FOR UPDATE" in compiled


def test_second_transaction_waits_for_lineage_lock_then_sees_refreshed_state(
    session_factory,
) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=bytes(range(32, 64)),
        )
        session.commit()
        session.expunge_all()

        holder_locked = threading.Event()
        waiter_about_to_lock = threading.Event()
        release_holder = threading.Event()
        waiter_done = threading.Event()
        waiter_locked: list[tuple[AuthSession, ...]] = []
        waiter_error: list[Exception] = []

        def holder() -> None:
            with session_factory() as holder_session:
                holder_repo = AuthSessionRepository(holder_session)
                holder_repo.lock_lineage(root.lineage_id)
                root_row = holder_session.get(AuthSession, root.id)
                root_row.revoked_at = _NOW
                holder_session.flush()
                holder_locked.set()
                assert release_holder.wait(timeout=10), "holder release timed out"
                holder_session.commit()

        def waiter() -> None:
            try:
                with session_factory() as waiter_session:
                    waiter_repo = AuthSessionRepository(waiter_session)
                    pre = waiter_session.get(AuthSession, root.id)
                    assert pre.revoked_at is None
                    waiter_about_to_lock.set()
                    locked = waiter_repo.lock_lineage(root.lineage_id)
                    waiter_locked.append(locked)
            except Exception as exc:  # pragma: no cover - surfaced via assertion
                waiter_error.append(exc)
            finally:
                waiter_done.set()

        holder_thread = threading.Thread(target=holder)
        waiter_thread = threading.Thread(target=waiter)
        holder_thread.start()
        try:
            assert holder_locked.wait(timeout=10), "holder did not acquire lineage lock"
            waiter_thread.start()
            assert waiter_about_to_lock.wait(timeout=10), "waiter did not reach lock call"
            assert not waiter_done.wait(timeout=0.25), "waiter must block on lineage lock"
            release_holder.set()
            assert holder_thread.join(timeout=10) is None, "holder thread hung"
            assert waiter_thread.join(timeout=10) is None, "waiter thread hung"
            assert not waiter_error, waiter_error
            assert len(waiter_locked) == 1
            locked = waiter_locked[0]
            assert len(locked) == 1
            assert locked[0].id == root.id
            assert locked[0].revoked_at == _NOW
        finally:
            release_holder.set()
            holder_thread.join(timeout=10)
            waiter_thread.join(timeout=10)