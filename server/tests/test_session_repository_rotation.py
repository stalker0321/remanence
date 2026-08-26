"""Integration tests for session rotation repository primitives against a temporary PostgreSQL database."""

from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.dialects import postgresql

from remanence.auth.models import AuthSession
from remanence.auth.session_repository import AuthSessionRepository
from remanence.users.models import User

pytest_plugins = ("test_session_repository_create",)

_ACCESS_HASH = bytes(range(32))
_REFRESH_HASH = bytes(range(32, 64))
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


def test_locked_lookup_returns_active_and_rotated_rows(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        active = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
        )
        rotated = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
        )
        rotated.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()
        found_active = repo.find_by_refresh_token_hash_for_update(_REFRESH_HASH)
        found_rotated = repo.find_by_refresh_token_hash_for_update(rotated.refresh_token_hash)
        assert found_active is not None
        assert found_active.id == active.id
        assert found_rotated is not None
        assert found_rotated.id == rotated.id


def test_locked_lookup_wrong_hash_returns_none(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
        )
        session.commit()
        session.expunge_all()
        found = repo.find_by_refresh_token_hash_for_update(bytes(range(1, 33)))
        assert found is None


def test_revoke_lineage_revokes_root_and_child_only(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
        )
        child = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        other = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(128, 160)),
            refresh_hash=bytes(range(160, 192)),
        )
        session.commit()
        session.expunge_all()
        revoked_at = datetime(2029, 6, 1, tzinfo=timezone.utc)
        count = repo.revoke_lineage(root.lineage_id, revoked_at)
        assert count == 2
        session.expunge_all()
        persisted_root = session.get(AuthSession, root.id)
        persisted_child = session.get(AuthSession, child.id)
        persisted_other = session.get(AuthSession, other.id)
        assert persisted_root.revoked_at == revoked_at
        assert persisted_child.revoked_at == revoked_at
        assert persisted_other.revoked_at is None


def test_revoke_lineage_second_call_is_idempotent_zero(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
        )
        session.commit()
        session.expunge_all()
        revoked_at = datetime(2029, 6, 1, tzinfo=timezone.utc)
        assert repo.revoke_lineage(root.lineage_id, revoked_at) == 1
        assert repo.revoke_lineage(root.lineage_id, revoked_at) == 0


def test_external_rollback_undoes_revoke(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
        )
        session.commit()
        session.expunge_all()
        revoked_at = datetime(2029, 6, 1, tzinfo=timezone.utc)
        assert repo.revoke_lineage(root.lineage_id, revoked_at) == 1
        session.rollback()
        session.expunge_all()
        persisted = session.get(AuthSession, root.id)
        assert persisted.revoked_at is None


def test_locked_lookup_compiles_with_for_update() -> None:
    statement = select(AuthSession).where(AuthSession.refresh_token_hash == _REFRESH_HASH).with_for_update()
    compiled = str(statement.compile(dialect=postgresql.dialect()))
    assert "FOR UPDATE" in compiled