"""Integration tests for refresh replay detection and lineage revocation."""

from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import select

from postmark.auth.models import AuthSession
from postmark.auth.session_repository import AuthSessionRepository
from postmark.auth.session_rotation import (
    RefreshRotationResult,
    RefreshRotationStatus,
    SessionRotationService,
)
from postmark.auth.tokens import REFRESH_TOKEN_PREFIX, hash_opaque_token
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


def _create_active(repo: AuthSessionRepository, *, user_id, access_hash, refresh_hash, lineage_id=None, parent_session_id=None, access_expires=_ACCESS_EXPIRES, refresh_expires=_REFRESH_EXPIRES, revoked_at=None, rotated_at=None) -> AuthSession:
    auth_session = repo.create(
        user_id=user_id,
        access_token_hash=access_hash,
        refresh_token_hash=refresh_hash,
        access_expires_at=access_expires,
        refresh_expires_at=refresh_expires,
        lineage_id=lineage_id,
        parent_session_id=parent_session_id,
    )
    if revoked_at is not None:
        auth_session.revoked_at = revoked_at
    if rotated_at is not None:
        auth_session.rotated_at = rotated_at
    return auth_session


def test_replay_of_rotated_root_revokes_entire_lineage(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root_refresh = REFRESH_TOKEN_PREFIX + "root-refresh"
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(root_refresh),
        )
        child = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        root.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(root_refresh, _NOW)
        assert result.status is RefreshRotationStatus.REPLAYED
        assert result.session_id is None
        assert result.access_token is None
        assert result.refresh_token is None
        assert result.access_expires_at is None
        assert result.refresh_expires_at is None

        session.commit()
        session.expunge_all()
        persisted_root = session.get(AuthSession, root.id)
        persisted_child = session.get(AuthSession, child.id)
        assert persisted_root.revoked_at == _NOW
        assert persisted_child.revoked_at == _NOW
        assert persisted_root.rotated_at == datetime(2029, 1, 1, tzinfo=timezone.utc)
        assert persisted_child.rotated_at is None


def test_replay_does_not_touch_other_lineage_or_grow_children(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root_refresh = REFRESH_TOKEN_PREFIX + "root-refresh"
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(root_refresh),
        )
        child = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        root.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        other = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(128, 160)),
            refresh_hash=bytes(range(160, 192)),
        )
        session.commit()
        session.expunge_all()
        children_before = len(session.scalars(select(AuthSession)).all())

        SessionRotationService(repo).rotate(root_refresh, _NOW)
        session.commit()
        session.expunge_all()
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == children_before
        persisted_other = session.get(AuthSession, other.id)
        assert persisted_other.revoked_at is None


def test_replay_result_repr_and_fields_omit_input_token(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root_refresh = REFRESH_TOKEN_PREFIX + "root-refresh"
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(root_refresh),
        )
        root.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(root_refresh, _NOW)
        assert root_refresh not in repr(result)
        assert root_refresh not in (result.access_token, result.refresh_token)


def test_second_replay_keeps_original_revoked_at(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root_refresh = REFRESH_TOKEN_PREFIX + "root-refresh"
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(root_refresh),
        )
        root.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()

        service = SessionRotationService(repo)
        first = service.rotate(root_refresh, _NOW)
        session.commit()
        session.expunge_all()
        second = service.rotate(root_refresh, _NOW)
        session.commit()
        session.expunge_all()
        assert first.status is RefreshRotationStatus.REPLAYED
        assert second.status is RefreshRotationStatus.REPLAYED
        persisted = session.get(AuthSession, root.id)
        assert persisted.revoked_at == _NOW


def test_rollback_after_replay_undoes_revocations(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root_refresh = REFRESH_TOKEN_PREFIX + "root-refresh"
        root = _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(root_refresh),
        )
        child = _create_active(
            repo,
            user_id=user.id,
            access_hash=bytes(range(64, 96)),
            refresh_hash=bytes(range(96, 128)),
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        root.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()

        SessionRotationService(repo).rotate(root_refresh, _NOW)
        session.rollback()
        session.expunge_all()
        persisted_root = session.get(AuthSession, root.id)
        persisted_child = session.get(AuthSession, child.id)
        assert persisted_root.revoked_at is None
        assert persisted_child.revoked_at is None


def test_rotated_and_expired_classified_replayed(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        refresh = REFRESH_TOKEN_PREFIX + "rotated-expired"
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(refresh),
            access_expires=datetime(2020, 1, 1, tzinfo=timezone.utc),
            refresh_expires=datetime(2020, 2, 1, tzinfo=timezone.utc),
            rotated_at=datetime(2029, 1, 1, tzinfo=timezone.utc),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(refresh, _NOW)
        assert result.status is RefreshRotationStatus.REPLAYED
        assert result.session_id is None


def test_rotated_and_already_revoked_classified_replayed(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        refresh = REFRESH_TOKEN_PREFIX + "rotated-revoked"
        _create_active(
            repo,
            user_id=user.id,
            access_hash=_ACCESS_HASH,
            refresh_hash=hash_opaque_token(refresh),
            revoked_at=datetime(2029, 1, 1, tzinfo=timezone.utc),
            rotated_at=datetime(2029, 1, 1, tzinfo=timezone.utc),
        )
        session.commit()
        session.expunge_all()

        result = SessionRotationService(repo).rotate(refresh, _NOW)
        assert result.status is RefreshRotationStatus.REPLAYED
        assert result.session_id is None