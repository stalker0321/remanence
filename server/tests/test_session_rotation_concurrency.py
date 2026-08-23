"""Integration tests for lineage-serialized refresh rotation against a temporary PostgreSQL database."""

import threading
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
_REPLAY_NOW = datetime(2029, 6, 1, 12, 0, 0, tzinfo=timezone.utc)
_CHILD_NOW = datetime(2029, 6, 1, 12, 5, 0, tzinfo=timezone.utc)


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


class _LockSignalingRepo(AuthSessionRepository):
    def __init__(self, session, *, lock_acquired, release) -> None:
        super().__init__(session)
        self._lock_acquired = lock_acquired
        self._release = release

    def lock_lineage(self, lineage_id):
        result = super().lock_lineage(lineage_id)
        self._lock_acquired.set()
        assert self._release.wait(timeout=10), "replay lock release timed out"
        return result


def test_replay_blocks_concurrent_child_rotation_and_revokes_lineage(
    session_factory,
) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root_refresh = REFRESH_TOKEN_PREFIX + "root-refresh"
        child_refresh = REFRESH_TOKEN_PREFIX + "child-refresh"
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
            refresh_hash=hash_opaque_token(child_refresh),
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        root.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()

        replay_has_lock = threading.Event()
        release_replay = threading.Event()
        child_about_to_rotate = threading.Event()
        child_done = threading.Event()
        replay_result: list[RefreshRotationResult] = []
        child_result: list[RefreshRotationResult] = []
        replay_error: list[Exception] = []
        child_error: list[Exception] = []

        def replay_thread() -> None:
            try:
                with session_factory() as replay_session:
                    replay_repo = _LockSignalingRepo(
                        replay_session,
                        lock_acquired=replay_has_lock,
                        release=release_replay,
                    )
                    replay_result.append(
                        SessionRotationService(replay_repo).rotate(root_refresh, _REPLAY_NOW)
                    )
                    replay_session.commit()
            except Exception as exc:  # pragma: no cover - surfaced via assertion
                replay_error.append(exc)

        def child_thread_fn() -> None:
            try:
                with session_factory() as child_session:
                    child_repo = AuthSessionRepository(child_session)
                    child_about_to_rotate.set()
                    child_result.append(
                        SessionRotationService(child_repo).rotate(child_refresh, _CHILD_NOW)
                    )
                    child_session.commit()
            except Exception as exc:  # pragma: no cover - surfaced via assertion
                child_error.append(exc)
            finally:
                child_done.set()

        replay = threading.Thread(target=replay_thread)
        child_thread = threading.Thread(target=child_thread_fn)
        replay.start()
        try:
            assert replay_has_lock.wait(timeout=10), "replay did not acquire lineage lock"
            child_thread.start()
            assert child_about_to_rotate.wait(timeout=10), "child did not start rotation"
            assert not child_done.wait(timeout=0.25), "child rotation must block on lineage lock"
            release_replay.set()
            replay.join(timeout=10)
            child_thread.join(timeout=10)
            assert not replay.is_alive(), "replay thread did not terminate"
            assert not child_thread.is_alive(), "child thread did not terminate"
            assert not replay_error, replay_error
            assert not child_error, child_error
            assert len(replay_result) == 1
            assert len(child_result) == 1
            assert replay_result[0].status is RefreshRotationStatus.REPLAYED
            assert child_result[0].status is RefreshRotationStatus.INVALID
            assert child_result[0].session_id is None

            session.expunge_all()
            persisted_root = session.get(AuthSession, root.id)
            persisted_child = session.get(AuthSession, child.id)
            assert persisted_root.revoked_at == _REPLAY_NOW
            assert persisted_child.revoked_at == _REPLAY_NOW
            assert persisted_root.rotated_at == datetime(2029, 1, 1, tzinfo=timezone.utc)
            grandchildren = session.scalars(
                select(AuthSession).where(AuthSession.parent_session_id == child.id)
            ).all()
            assert grandchildren == []
        finally:
            release_replay.set()
            replay.join(timeout=10)
            child_thread.join(timeout=10)


def test_child_rotation_then_root_replay_revokes_new_grandchild(session_factory) -> None:
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root_refresh = REFRESH_TOKEN_PREFIX + "root-refresh"
        child_refresh = REFRESH_TOKEN_PREFIX + "child-refresh"
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
            refresh_hash=hash_opaque_token(child_refresh),
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        root.rotated_at = datetime(2029, 1, 1, tzinfo=timezone.utc)
        session.commit()
        session.expunge_all()

        service = SessionRotationService(repo)
        child_rotation = service.rotate(child_refresh, _CHILD_NOW)
        assert child_rotation.status is RefreshRotationStatus.ROTATED
        session.commit()
        session.expunge_all()

        replay = service.rotate(root_refresh, _REPLAY_NOW)
        assert replay.status is RefreshRotationStatus.REPLAYED
        session.commit()
        session.expunge_all()

        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 3
        for row in rows:
            assert row.revoked_at == _REPLAY_NOW
        persisted_root = session.get(AuthSession, root.id)
        assert persisted_root.rotated_at == datetime(2029, 1, 1, tzinfo=timezone.utc)