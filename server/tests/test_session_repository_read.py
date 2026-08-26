"""Integration tests for auth session read/expiry lookups against a temporary PostgreSQL database."""

from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

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


def _create_active(repo: AuthSessionRepository, *, user_id, access_hash, refresh_hash, access_expires, refresh_expires) -> AuthSession:
    return repo.create(
        user_id=user_id,
        access_token_hash=access_hash,
        refresh_token_hash=refresh_hash,
        access_expires_at=access_expires,
        refresh_expires_at=refresh_expires,
    )


def test_access_lookup_finds_valid_session(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        created = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        session.commit()
        session.expunge_all()
        found = repo.find_by_access_token_hash(_ACCESS_HASH, datetime(2029, 1, 1, tzinfo=timezone.utc))
        assert found is not None
        assert found.id == created.id


def test_access_lookup_exact_expiry_boundary_not_found(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        session.commit()
        session.expunge_all()
        found = repo.find_by_access_token_hash(_ACCESS_HASH, _ACCESS_EXPIRES)
        assert found is None


def test_access_lookup_expired_revoked_rotated_not_found(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        expired = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            access_expires=datetime(2020, 1, 1, tzinfo=timezone.utc),
            refresh_expires=_REFRESH_EXPIRES,
        )
        revoked = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(128, 160)),
            refresh_hash=bytes(range(160, 192)),
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        revoked.revoked_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        rotated = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(192, 224)),
            refresh_hash=bytes(range(224, 256)),
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        rotated.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()
        now = datetime(2029, 6, 1, tzinfo=timezone.utc)
        assert repo.find_by_access_token_hash(expired.access_token_hash, now) is None
        assert repo.find_by_access_token_hash(revoked.access_token_hash, now) is None
        assert repo.find_by_access_token_hash(rotated.access_token_hash, now) is None


def test_access_lookup_wrong_hash_returns_none(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        session.commit()
        session.expunge_all()
        found = repo.find_by_access_token_hash(bytes(range(1, 33)), datetime(2029, 1, 1, tzinfo=timezone.utc))
        assert found is None


def test_raw_refresh_lookup_returns_expired_revoked_rotated(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        expired = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            access_expires=datetime(2020, 1, 1, tzinfo=timezone.utc),
            refresh_expires=datetime(2020, 2, 1, tzinfo=timezone.utc),
        )
        revoked = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(128, 160)),
            refresh_hash=bytes(range(160, 192)),
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        revoked.revoked_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        rotated = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(192, 224)),
            refresh_hash=bytes(range(224, 256)),
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        rotated.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()
        assert repo.find_by_refresh_token_hash(expired.refresh_token_hash) is not None
        assert repo.find_by_refresh_token_hash(revoked.refresh_token_hash) is not None
        assert repo.find_by_refresh_token_hash(rotated.refresh_token_hash) is not None


def test_is_refresh_usable_only_for_active_session(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        active = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=_REFRESH_HASH,
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        expired = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            access_expires=datetime(2020, 1, 1, tzinfo=timezone.utc),
            refresh_expires=datetime(2020, 2, 1, tzinfo=timezone.utc),
        )
        revoked = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(128, 160)),
            refresh_hash=bytes(range(160, 192)),
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        revoked.revoked_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        rotated = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(192, 224)),
            refresh_hash=bytes(range(224, 256)),
            access_expires=_ACCESS_EXPIRES,
            refresh_expires=_REFRESH_EXPIRES,
        )
        rotated.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()
        now = datetime(2029, 6, 1, tzinfo=timezone.utc)
        assert AuthSessionRepository.is_refresh_usable(active, now) is True
        assert AuthSessionRepository.is_refresh_usable(expired, now) is False
        assert AuthSessionRepository.is_refresh_usable(revoked, now) is False
        assert AuthSessionRepository.is_refresh_usable(rotated, now) is False
        assert AuthSessionRepository.is_refresh_usable(active, active.refresh_expires_at) is False