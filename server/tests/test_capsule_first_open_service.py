"""PostgreSQL tests for the durable recipient first-open claim."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from tink import tink_config
from sqlalchemy import func, select, text
from sqlalchemy.exc import OperationalError

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.first_open_service import (
    CapsuleFirstOpenError,
    CapsuleFirstOpenResult,
    CapsuleFirstOpenService,
)
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.locking import capsule_lock_key, recipient_tombstone_lock_key
from remanence.capsules.revoke_service import CapsuleRevokeError, CapsuleRevokeService

from test_capsule_revoke_service import _NOW, _make_ready


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _claim(session, world, *, now=_NOW) -> CapsuleFirstOpenResult:
    return CapsuleFirstOpenService(session).claim(
        authenticated_recipient_user_id=world["recipient"].id,
        capsule_id=world["capsule"].id,
        now=now,
    )


def _assert_claim_error(call, code: str) -> None:
    with pytest.raises(CapsuleFirstOpenError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "capsule first-open claim failed"
    assert repr(caught.value) == f"CapsuleFirstOpenError(code={code!r})"
    assert caught.value.__cause__ is None


def test_first_open_claim_is_durable_and_replay_safe(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        first = _claim(session, world)
        session.commit()
        replay = _claim(session, world, now=_NOW.replace(microsecond=1))
        session.commit()

        assert first.is_replay is False
        assert replay.is_replay is True
        assert replay.first_opened_at == first.first_opened_at
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule is not None
        assert capsule.state is CapsuleState.READY
        assert capsule.first_opened_at == first.first_opened_at


def test_first_open_outer_rollback_leaves_no_durable_claim(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        _claim(session, world)
        session.rollback()
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule is not None
        assert capsule.first_opened_at is None


def test_first_open_flush_failure_is_redacted_and_rolls_back(
    session_factory,
    tmp_path,
    monkeypatch,
):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)

        def fail_flush(_session, *_args, **_kwargs):
            raise RuntimeError("private capsule material")

        monkeypatch.setattr(type(session), "flush", fail_flush)
        with pytest.raises(CapsuleFirstOpenError) as caught:
            _claim(session, world)
        assert caught.value.code == "INTERNAL_ERROR"
        assert str(caught.value) == "capsule first-open claim failed"
        assert "private capsule material" not in repr(caught.value)
        session.rollback()

        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule is not None
        assert capsule.first_opened_at is None


def test_first_open_requires_the_recipient_and_ready_state(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        _assert_claim_error(
            lambda: CapsuleFirstOpenService(session).claim(
                authenticated_recipient_user_id=world["sender"].id,
                capsule_id=world["capsule"].id,
                now=_NOW,
            ),
            "CAPSULE_NOT_FOUND",
        )
        session.rollback()

        CapsuleRevokeService(session).revoke(
            authenticated_sender_user_id=world["sender"].id,
            capsule_id=world["capsule"].id,
            now=_NOW,
        )
        session.commit()
        _assert_claim_error(lambda: _claim(session, world), "CAPSULE_STATE_INVALID")
        session.rollback()


def test_first_open_wins_then_cancel_is_rejected_without_tombstone(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        result = _claim(session, world)
        session.commit()

        with pytest.raises(CapsuleRevokeError) as caught:
            CapsuleRevokeService(session).revoke(
                authenticated_sender_user_id=world["sender"].id,
                capsule_id=world["capsule"].id,
                now=_NOW,
            )
        assert caught.value.code == "CAPSULE_STATE_INVALID"
        session.rollback()
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule is not None
        assert capsule.state is CapsuleState.READY
        assert capsule.first_opened_at == result.first_opened_at
        assert capsule.tombstone_sequence is None


def test_repeated_cancel_remains_idempotent_when_first_open_never_commits(
    session_factory,
    tmp_path,
):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        first = CapsuleRevokeService(session).revoke(
            authenticated_sender_user_id=world["sender"].id,
            capsule_id=world["capsule"].id,
            now=_NOW,
        )
        session.commit()
        replay = CapsuleRevokeService(session).revoke(
            authenticated_sender_user_id=world["sender"].id,
            capsule_id=world["capsule"].id,
            now=_NOW,
        )
        session.commit()

        assert first.is_replay is False
        assert replay.is_replay is True
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule is not None
        assert capsule.state is CapsuleState.REVOKED
        assert capsule.first_opened_at is None


def test_cancel_wins_then_first_open_is_rejected(session_factory, tmp_path):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        CapsuleRevokeService(session).revoke(
            authenticated_sender_user_id=world["sender"].id,
            capsule_id=world["capsule"].id,
            now=_NOW,
        )
        session.commit()
        _assert_claim_error(lambda: _claim(session, world), "CAPSULE_STATE_INVALID")
        session.rollback()
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule is not None
        assert capsule.state is CapsuleState.REVOKED
        assert capsule.first_opened_at is None


def _assert_capsule_lock_is_blocked(session, capsule_id) -> None:
    try:
        with session.begin_nested():
            session.execute(text("SET LOCAL lock_timeout = '1s'"))
            session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
    except OperationalError:
        return
    pytest.fail("expected the capsule advisory lock to be held by the other transaction")


def test_cancel_first_forced_order_commits_revoke_then_claim_rejects(
    session_factory,
    tmp_path,
):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        session.commit()
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        recipient_id = world["recipient"].id

    cancel_lock_held = Event()
    open_blocked = Event()
    cancel_committed = Event()

    def cancel_worker():
        with session_factory() as session:
            with session.begin():
                session.execute(
                    select(
                        func.pg_advisory_xact_lock(
                            recipient_tombstone_lock_key(recipient_id)
                        )
                    )
                )
                session.execute(
                    select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
                )
                cancel_lock_held.set()
                assert open_blocked.wait(timeout=10)
                result = CapsuleRevokeService(session).revoke(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    now=_NOW,
                )
                assert result.is_replay is False
            cancel_committed.set()
            return ("cancel", "ok", False)

    def open_worker():
        with session_factory() as session:
            assert cancel_lock_held.wait(timeout=10)
            with session.begin():
                _assert_capsule_lock_is_blocked(session, capsule_id)
                open_blocked.set()
                assert cancel_committed.wait(timeout=10)
                with pytest.raises(CapsuleFirstOpenError) as caught:
                    CapsuleFirstOpenService(session).claim(
                        authenticated_recipient_user_id=recipient_id,
                        capsule_id=capsule_id,
                        now=_NOW,
                    )
                assert caught.value.code == "CAPSULE_STATE_INVALID"
            return ("open", "error", "CAPSULE_STATE_INVALID")

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(lambda worker: worker(), (cancel_worker, open_worker)))

    assert sorted(outcomes) == [
        ("cancel", "ok", False),
        ("open", "error", "CAPSULE_STATE_INVALID"),
    ]
    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        assert capsule is not None
        assert capsule.state is CapsuleState.REVOKED
        assert capsule.first_opened_at is None


def test_claim_first_forced_order_commits_claim_then_revoke_rejects(
    session_factory,
    tmp_path,
):
    with session_factory() as session:
        world = _make_ready(session, tmp_path)
        session.commit()
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        recipient_id = world["recipient"].id

    open_flushed = Event()
    cancel_blocked = Event()
    open_committed = Event()

    def open_worker():
        with session_factory() as session:
            with session.begin():
                result = CapsuleFirstOpenService(session).claim(
                    authenticated_recipient_user_id=recipient_id,
                    capsule_id=capsule_id,
                    now=_NOW,
                )
                assert result.is_replay is False
                open_flushed.set()
                assert cancel_blocked.wait(timeout=10)
            open_committed.set()
            return ("open", "ok", False)

    def cancel_worker():
        with session_factory() as session:
            with session.begin():
                assert open_flushed.wait(timeout=10)
                session.execute(
                    select(
                        func.pg_advisory_xact_lock(
                            recipient_tombstone_lock_key(recipient_id)
                        )
                    )
                )
                _assert_capsule_lock_is_blocked(session, capsule_id)
                cancel_blocked.set()
                assert open_committed.wait(timeout=10)
                with pytest.raises(CapsuleRevokeError) as caught:
                    CapsuleRevokeService(session).revoke(
                        authenticated_sender_user_id=sender_id,
                        capsule_id=capsule_id,
                        now=_NOW,
                    )
                assert caught.value.code == "CAPSULE_STATE_INVALID"
            return ("cancel", "error", "CAPSULE_STATE_INVALID")

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(lambda worker: worker(), (open_worker, cancel_worker)))

    assert sorted(outcomes) == [
        ("cancel", "error", "CAPSULE_STATE_INVALID"),
        ("open", "ok", False),
    ]
    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        assert capsule is not None
        assert capsule.state is CapsuleState.READY
        assert capsule.first_opened_at is not None
