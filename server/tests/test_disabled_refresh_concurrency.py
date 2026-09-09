"""Concurrency coverage for account disablement and refresh rotation."""

import threading
from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import select

from remanence.auth.account_status import AccountStatusService
from remanence.auth.logout import LogoutService
from remanence.auth.models import AuthSession
from remanence.auth.session_repository import AuthSessionRepository
from remanence.auth.session_rotation import RefreshRotationStatus, SessionRotationService
from remanence.auth.tokens import REFRESH_TOKEN_PREFIX, hash_opaque_token
from remanence.users.models import User

pytest_plugins = ("test_session_repository_create",)

_ACCESS_EXPIRES = datetime(2030, 1, 1, tzinfo=timezone.utc)
_REFRESH_EXPIRES = datetime(2030, 2, 1, tzinfo=timezone.utc)
_REFRESH_NOW = datetime(2029, 6, 1, 12, 0, tzinfo=timezone.utc)
_DISABLE_NOW = datetime(2029, 6, 1, 12, 5, tzinfo=timezone.utc)


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


def _create_root(session, user_id, refresh_token: str) -> AuthSession:
    return AuthSessionRepository(session).create(
        user_id=user_id,
        access_token_hash=bytes(range(32)),
        refresh_token_hash=hash_opaque_token(refresh_token),
        access_expires_at=_ACCESS_EXPIRES,
        refresh_expires_at=_REFRESH_EXPIRES,
    )


class _BlockingUserLockRepo(AuthSessionRepository):
    def __init__(self, session, *, locked: threading.Event, release: threading.Event) -> None:
        super().__init__(session)
        self._locked = locked
        self._release = release

    def lock_user(self, user_id):
        user = super().lock_user(user_id)
        self._locked.set()
        assert self._release.wait(timeout=10), "user-lock release timed out"
        return user


class _ObservedUserLockRepo(AuthSessionRepository):
    def __init__(self, session, *, attempted: threading.Event) -> None:
        super().__init__(session)
        self._attempted = attempted

    def lock_user(self, user_id):
        self._attempted.set()
        return super().lock_user(user_id)


class _OrderedLogoutRepo(AuthSessionRepository):
    def __init__(
        self,
        session,
        *,
        user_locked: threading.Event,
        allow_lineage: threading.Event,
        lineage_locked: threading.Event,
        release: threading.Event,
    ) -> None:
        super().__init__(session)
        self._user_locked = user_locked
        self._allow_lineage = allow_lineage
        self._lineage_locked = lineage_locked
        self._release = release

    def lock_user(self, user_id):
        user = super().lock_user(user_id)
        self._user_locked.set()
        assert self._allow_lineage.wait(timeout=10), "lineage admission timed out"
        return user

    def lock_lineage(self, lineage_id):
        rows = super().lock_lineage(lineage_id)
        self._lineage_locked.set()
        assert self._release.wait(timeout=10), "lineage-lock release timed out"
        return rows


def _join(*threads: threading.Thread) -> None:
    for thread in threads:
        thread.join(timeout=10)
        assert not thread.is_alive(), "worker thread hung"


def test_disable_first_blocks_refresh_then_returns_invalid_without_child(session_factory) -> None:
    refresh_token = REFRESH_TOKEN_PREFIX + "disable-first"
    with session_factory() as session:
        user = _create_user(session)
        root = _create_root(session, user.id, refresh_token)
        session.commit()

    disable_locked = threading.Event()
    release_disable = threading.Event()
    refresh_attempted = threading.Event()
    disable_result: list[bool] = []
    refresh_result = []
    errors: list[Exception] = []

    def disable_worker() -> None:
        try:
            with session_factory() as session:
                repo = _BlockingUserLockRepo(
                    session, locked=disable_locked, release=release_disable
                )
                disable_result.append(
                    AccountStatusService(repo).disable(user.id, _DISABLE_NOW)
                )
                session.commit()
        except Exception as exc:  # pragma: no cover - surfaced by assertions
            errors.append(exc)

    def refresh_worker() -> None:
        try:
            with session_factory() as session:
                repo = _ObservedUserLockRepo(session, attempted=refresh_attempted)
                refresh_result.append(
                    SessionRotationService(repo).rotate(refresh_token, _REFRESH_NOW)
                )
                session.commit()
        except Exception as exc:  # pragma: no cover - surfaced by assertions
            errors.append(exc)

    disable_thread = threading.Thread(target=disable_worker)
    refresh_thread = threading.Thread(target=refresh_worker)
    disable_thread.start()
    try:
        assert disable_locked.wait(timeout=10), "disable did not acquire user lock"
        refresh_thread.start()
        assert refresh_attempted.wait(timeout=10), "refresh did not attempt user lock"
        release_disable.set()
        _join(disable_thread, refresh_thread)
    finally:
        release_disable.set()
        _join(disable_thread, refresh_thread)

    assert not errors, errors
    assert disable_result == [True]
    assert len(refresh_result) == 1
    assert refresh_result[0].status is RefreshRotationStatus.INVALID
    with session_factory() as session:
        rows = session.scalars(select(AuthSession)).all()
        assert len(rows) == 1
        assert rows[0].id == root.id
        assert rows[0].revoked_at == _DISABLE_NOW


def test_refresh_first_is_revoked_by_disable_and_reenable_does_not_resurrect(
    session_factory,
) -> None:
    refresh_token = REFRESH_TOKEN_PREFIX + "refresh-first"
    with session_factory() as session:
        user = _create_user(session)
        root = _create_root(session, user.id, refresh_token)
        session.commit()

    refresh_locked = threading.Event()
    release_refresh = threading.Event()
    disable_attempted = threading.Event()
    refresh_result = []
    disable_result: list[bool] = []
    errors: list[Exception] = []

    def refresh_worker() -> None:
        try:
            with session_factory() as session:
                repo = _BlockingUserLockRepo(
                    session, locked=refresh_locked, release=release_refresh
                )
                refresh_result.append(
                    SessionRotationService(repo).rotate(refresh_token, _REFRESH_NOW)
                )
                session.commit()
        except Exception as exc:  # pragma: no cover - surfaced by assertions
            errors.append(exc)

    def disable_worker() -> None:
        try:
            with session_factory() as session:
                repo = _ObservedUserLockRepo(session, attempted=disable_attempted)
                disable_result.append(
                    AccountStatusService(repo).disable(user.id, _DISABLE_NOW)
                )
                session.commit()
        except Exception as exc:  # pragma: no cover - surfaced by assertions
            errors.append(exc)

    refresh_thread = threading.Thread(target=refresh_worker)
    disable_thread = threading.Thread(target=disable_worker)
    refresh_thread.start()
    try:
        assert refresh_locked.wait(timeout=10), "refresh did not acquire user lock"
        disable_thread.start()
        assert disable_attempted.wait(timeout=10), "disable did not attempt user lock"
        release_refresh.set()
        _join(refresh_thread, disable_thread)
    finally:
        release_refresh.set()
        _join(refresh_thread, disable_thread)

    assert not errors, errors
    assert disable_result == [True]
    assert len(refresh_result) == 1
    assert refresh_result[0].status is RefreshRotationStatus.ROTATED
    child_token = refresh_result[0].refresh_token
    assert child_token is not None

    with session_factory() as session:
        rows = session.scalars(select(AuthSession).order_by(AuthSession.id)).all()
        assert len(rows) == 2
        root_row = next(row for row in rows if row.id == root.id)
        child_id = refresh_result[0].session_id
        assert child_id is not None
        child_row = next(row for row in rows if row.id == child_id)
        assert child_row.user_id == user.id
        assert child_row.parent_session_id == root.id
        assert child_row.refresh_token_hash == hash_opaque_token(child_token)
        assert child_row.lineage_id == root.lineage_id
        assert root_row.parent_session_id is None
        assert all(row.revoked_at == _DISABLE_NOW for row in rows)

    with session_factory() as session:
        with session.begin():
            assert AccountStatusService(AuthSessionRepository(session)).enable(user.id)
        enabled_user = session.get(User, user.id)
        assert enabled_user is not None
        assert enabled_user.disabled_at is None
        rows_after_enable = session.scalars(
            select(AuthSession).where(AuthSession.user_id == user.id)
        ).all()
        assert len(rows_after_enable) == 2
        assert {row.id for row in rows_after_enable} == {root.id, child_id}
        assert all(row.revoked_at == _DISABLE_NOW for row in rows_after_enable)
        result = SessionRotationService(AuthSessionRepository(session)).rotate(
            child_token, _DISABLE_NOW
        )
        assert result.status is RefreshRotationStatus.INVALID
        session.rollback()


def test_logout_and_disable_serialize_user_then_multi_row_lineage(session_factory) -> None:
    access_token = "access-multi-row-logout"
    refresh_token = REFRESH_TOKEN_PREFIX + "multi-row-logout"
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = repo.create(
            user_id=user.id,
            access_token_hash=hash_opaque_token(access_token),
            refresh_token_hash=hash_opaque_token(refresh_token),
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
        )
        child = repo.create(
            user_id=user.id,
            access_token_hash=hash_opaque_token(access_token + "-child"),
            refresh_token_hash=hash_opaque_token(refresh_token + "-child"),
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        session.commit()

    user_locked = threading.Event()
    allow_lineage = threading.Event()
    lineage_locked = threading.Event()
    release_logout = threading.Event()
    disable_attempted = threading.Event()
    logout_result: list[bool] = []
    disable_result: list[bool] = []
    errors: list[Exception] = []

    def logout_worker() -> None:
        try:
            with session_factory() as session:
                LogoutService(
                    _OrderedLogoutRepo(
                        session,
                        user_locked=user_locked,
                        allow_lineage=allow_lineage,
                        lineage_locked=lineage_locked,
                        release=release_logout,
                    )
                ).logout(access_token, _DISABLE_NOW)
                session.commit()
                logout_result.append(True)
        except Exception as exc:  # pragma: no cover - surfaced by assertions
            errors.append(exc)

    def disable_worker() -> None:
        try:
            with session_factory() as session:
                disable_result.append(
                    AccountStatusService(
                        _ObservedUserLockRepo(session, attempted=disable_attempted)
                    ).disable(user.id, _DISABLE_NOW)
                )
                session.commit()
        except Exception as exc:  # pragma: no cover - surfaced by assertions
            errors.append(exc)

    logout_thread = threading.Thread(target=logout_worker)
    disable_thread = threading.Thread(target=disable_worker)
    logout_thread.start()
    try:
        assert user_locked.wait(timeout=10), "logout did not lock the user first"
        disable_thread.start()
        assert disable_attempted.wait(timeout=10), "disable did not attempt user lock"
        allow_lineage.set()
        assert lineage_locked.wait(timeout=10), "logout did not lock the full lineage"
        release_logout.set()
        _join(logout_thread, disable_thread)
    finally:
        release_logout.set()
        _join(logout_thread, disable_thread)

    assert not errors, errors
    assert logout_result == [True]
    assert disable_result == [True]
    with session_factory() as session:
        locked_user = session.get(User, user.id)
        assert locked_user is not None
        assert locked_user.disabled_at == _DISABLE_NOW
        rows = session.scalars(
            select(AuthSession)
            .where(AuthSession.user_id == user.id)
            .order_by(AuthSession.id)
        ).all()
        assert [row.id for row in rows] == sorted((root.id, child.id))
        assert all(row.revoked_at == _DISABLE_NOW for row in rows)


def test_disabled_logout_still_revokes_legacy_live_lineage(session_factory) -> None:
    access_token = "access-disabled-logout"
    refresh_token = REFRESH_TOKEN_PREFIX + "disabled-logout"
    with session_factory() as session:
        user = _create_user(session)
        repo = AuthSessionRepository(session)
        root = repo.create(
            user_id=user.id,
            access_token_hash=hash_opaque_token(access_token),
            refresh_token_hash=hash_opaque_token(refresh_token),
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
        )
        child = repo.create(
            user_id=user.id,
            access_token_hash=hash_opaque_token(access_token + "-child"),
            refresh_token_hash=hash_opaque_token(refresh_token + "-child"),
            access_expires_at=_ACCESS_EXPIRES,
            refresh_expires_at=_REFRESH_EXPIRES,
            lineage_id=root.lineage_id,
            parent_session_id=root.id,
        )
        user.disabled_at = _DISABLE_NOW
        session.commit()

    with session_factory() as session:
        LogoutService(AuthSessionRepository(session)).logout(access_token, _DISABLE_NOW)
        session.commit()

    with session_factory() as session:
        rows = session.scalars(
            select(AuthSession).where(AuthSession.user_id == user.id)
        ).all()
        assert {row.id for row in rows} == {root.id, child.id}
        assert all(row.revoked_at == _DISABLE_NOW for row in rows)
