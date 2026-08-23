"""Integration tests for successful refresh rotation and ordinary invalid outcomes."""

from datetime import datetime, timedelta, timezone
from uuid import uuid4

from sqlalchemy import select

from postmark.auth.models import AuthSession
from postmark.auth.session_repository import AuthSessionRepository
from postmark.auth.session_rotation import (
    ACCESS_TTL,
    REFRESH_TTL,
    RefreshRotationResult,
    RefreshRotationStatus,
    SessionRotationService,
)
from postmark.auth.tokens import (
    ACCESS_TOKEN_PREFIX,
    REFRESH_TOKEN_PREFIX,
    generate_refresh_token,
    hash_opaque_token,
)
from postmark.users.models import User

pytest_plugins = ("test_session_repository_create",)

_ACCESS_HASH = bytes(range(32))
_REFRESH_HASH = bytes(range(32, 64))
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


def _create_active(repo: AuthSessionRepository, *, user_id, access_hash, refresh_hash, access_expires=_ACCESS_EXPIRES, refresh_expires=_REFRESH_EXPIRES, revoked_at=None, rotated_at=None) -> AuthSession:
    auth_session = repo.create(
        user_id=user_id,
        access_token_hash=access_hash,
        refresh_token_hash=refresh_hash,
        access_expires_at=access_expires,
        refresh_expires_at=refresh_expires,
    )
    if revoked_at is not None:
        auth_session.revoked_at = revoked_at
    if rotated_at is not None:
        auth_session.rotated_at = rotated_at
    return auth_session


def test_successful_rotation_marks_old_and_creates_exactly_one_child(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        old_refresh = REFRESH_TOKEN_PREFIX + "old-refresh-payload"
        old = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(old_refresh),
        )
        session.commit()
        session.expunge_all()

        service = SessionRotationService(repo)
        result = service.rotate(old_refresh, _NOW)

        assert result.status is RefreshRotationStatus.ROTATED
        assert result.session_id is not None
        assert result.access_token is not None
        assert result.refresh_token is not None
        assert result.access_token.startswith(ACCESS_TOKEN_PREFIX)
        assert result.refresh_token.startswith(REFRESH_TOKEN_PREFIX)
        assert result.access_expires_at == _NOW + ACCESS_TTL
        assert result.refresh_expires_at == _NOW + REFRESH_TTL

        session.expunge_all()
        persisted_old = session.get(AuthSession, old.id)
        assert persisted_old.rotated_at == _NOW
        assert persisted_old.last_used_at == _NOW

        children = session.scalars(
            select(AuthSession).where(AuthSession.parent_session_id == old.id)
        ).all()
        assert len(children) == 1
        child = children[0]
        assert child.id == result.session_id
        assert child.user_id == user.id
        assert child.lineage_id == old.lineage_id
        assert child.parent_session_id == old.id
        assert child.access_token_hash == hash_opaque_token(result.access_token)
        assert child.refresh_token_hash == hash_opaque_token(result.refresh_token)
        assert child.access_expires_at == _NOW + ACCESS_TTL
        assert child.refresh_expires_at == _NOW + REFRESH_TTL


def test_db_contains_hashes_not_plaintext(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        old_refresh = REFRESH_TOKEN_PREFIX + "old-refresh-payload"
        old = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(old_refresh),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(old_refresh, _NOW)
        session.expunge_all()
        child = session.get(AuthSession, result.session_id)
        assert result.access_token not in (child.access_token_hash, child.refresh_token_hash)
        assert result.refresh_token not in (child.access_token_hash, child.refresh_token_hash)
        assert old_refresh not in (child.access_token_hash, child.refresh_token_hash)


def test_repr_omits_tokens(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        old_refresh = REFRESH_TOKEN_PREFIX + "old-refresh-payload"
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(old_refresh),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(old_refresh, _NOW)
        rendered = repr(result)
        assert result.access_token not in rendered
        assert result.refresh_token not in rendered
        assert old_refresh not in rendered


def test_unknown_refresh_yields_invalid_without_mutation(session_factory) -> None:
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
        before = session.scalars(select(AuthSession)).all()

        result = SessionRotationService(repo).rotate(REFRESH_TOKEN_PREFIX + "unknown", _NOW)
        assert result == RefreshRotationResult(RefreshRotationStatus.INVALID, None)
        assert result.status is RefreshRotationStatus.INVALID
        assert result.session_id is None
        assert result.access_token is None
        assert result.refresh_token is None
        assert result.access_expires_at is None
        assert result.refresh_expires_at is None
        session.expunge_all()
        after = session.scalars(select(AuthSession)).all()
        assert len(after) == len(before)
        assert all(row.rotated_at is None and row.last_used_at is None for row in after)


def test_expired_refresh_yields_invalid_no_child(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        expired_refresh = REFRESH_TOKEN_PREFIX + "expired"
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(expired_refresh),
            access_expires=datetime(2020, 1, 1, tzinfo=timezone.utc),
            refresh_expires=datetime(2020, 2, 1, tzinfo=timezone.utc),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(expired_refresh, _NOW)
        assert result == RefreshRotationResult(RefreshRotationStatus.INVALID, None)
        session.expunge_all()
        assert session.scalars(select(AuthSession)).all() is not None
        assert len(session.scalars(select(AuthSession)).all()) == 1


def test_revoked_refresh_yields_invalid_no_child(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        revoked_refresh = REFRESH_TOKEN_PREFIX + "revoked"
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(revoked_refresh),
            revoked_at=datetime(2029, 1, 1, tzinfo=timezone.utc),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(revoked_refresh, _NOW)
        assert result == RefreshRotationResult(RefreshRotationStatus.INVALID, None)
        session.expunge_all()
        assert len(session.scalars(select(AuthSession)).all()) == 1


def test_rotated_refresh_yields_invalid_no_child_or_mutation(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        rotated_refresh = REFRESH_TOKEN_PREFIX + "rotated"
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(rotated_refresh),
            rotated_at=datetime(2029, 1, 1, tzinfo=timezone.utc),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(rotated_refresh, _NOW)
        assert result == RefreshRotationResult(RefreshRotationStatus.INVALID, None)
        session.expunge_all()
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 1
        assert rows[0].rotated_at == datetime(2029, 1, 1, tzinfo=timezone.utc)


def test_external_rollback_undoes_old_mutation_and_child(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        old_refresh = REFRESH_TOKEN_PREFIX + "old-refresh-payload"
        old = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(old_refresh),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(old_refresh, _NOW)
        session.rollback()
        session.expunge_all()
        persisted_old = session.get(AuthSession, old.id)
        assert persisted_old.rotated_at is None
        assert persisted_old.last_used_at is None
        assert session.get(AuthSession, result.session_id) is None