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

        first_ready = threading.Event()
        second_started = threading.Event()
        second_finished: list[tuple[AuthSession, ...]] = []
        second_error: list[BaseException] = []

        def first_holder() -> None:
            with session_factory() as holder_session:
                holder_repo = AuthSessionRepository(holder_session)
                holder_repo.lock_lineage(root.lineage_id)
                first_ready.set()
                second_started.wait(timeout=10)
                holder_session.commit()

        def second_waiter() -> None:
            try:
                with session_factory() as waiter_session:
                    waiter_repo = AuthSessionRepository(waiter_session)
                    second_started.set()
                    locked = waiter_repo.lock_lineage(root.lineage_id)
                    second_finished.append(locked)
            except BaseException as exc:  # pragma: no cover - surfaced via assertion
                second_error.append(exc)

        holder = threading.Thread(target=first_holder)
        waiter = threading.Thread(target=second_waiter)
        holder.start()
        try:
            assert first_ready.wait(timeout=10), "first transaction did not acquire lock"
            waiter.start()
            assert second_started.wait(timeout=10), "second transaction did not start"
            assert not second_finished and not second_error, "second transaction must block on lock"
            holder.join(timeout=10)
            waiter.join(timeout=10)
            assert not holder.is_alive(), "holder thread hung"
            assert not waiter.is_alive(), "waiter thread hung"
            assert not second_error, second_error
            assert len(second_finished) == 1
            locked = second_finished[0]
            assert len(locked) == 1
            assert locked[0].id == root.id
        finally:
            first_ready.set()
            second_started.set()
            holder.join(timeout=10)
            waiter.join(timeout=10)