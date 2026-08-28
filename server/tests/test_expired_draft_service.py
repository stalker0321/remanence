"""PostgreSQL tests for expired-draft marking."""

from __future__ import annotations

import inspect
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Barrier
from typing import Any
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.abort_service import CapsuleAbortError, CapsuleAbortService
from remanence.capsules.blob_models import CapsuleBlob
from remanence.capsules.expired_draft_service import (
    EXPIRED_DRAFT_BATCH_MAX,
    EXPIRED_DRAFT_BATCH_MIN,
    ExpiredDraftMarkingError,
    ExpiredDraftMarkingResult,
    ExpiredDraftMarkingService,
)
from remanence.capsules.finalize_service import CapsuleFinalizeError, CapsuleFinalizeService
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.models import Capsule, CapsuleState

from test_capsule_abort_service import (
    _NOW,
    _add_draft,
    _add_ready,
    _blob_snapshot,
    _capsule_shape,
    _seed_user,
)


def _assert_error(call, code: str) -> ExpiredDraftMarkingError:
    with pytest.raises(ExpiredDraftMarkingError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "expired draft marking failed"
    assert repr(caught.value) == f"ExpiredDraftMarkingError(code={code!r})"
    return caught.value


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("expired draft marking must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


def _mark(session, *, now: datetime = _NOW, limit: int = EXPIRED_DRAFT_BATCH_MAX):
    return ExpiredDraftMarkingService(session).mark_expired_drafts(now=now, limit=limit)


def test_invalid_now_and_limit_fail_closed_without_database() -> None:
    service = ExpiredDraftMarkingService(None)
    _assert_error(
        lambda: service.mark_expired_drafts(now=datetime(2030, 1, 1, 12, 0, 0), limit=1),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.mark_expired_drafts(
            now=datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone(timedelta(hours=2))),
            limit=1,
        ),
        "VALIDATION_FAILED",
    )
    for limit in (0, 101, -1, True, False, 1.0, "1"):
        _assert_error(
            lambda limit=limit: service.mark_expired_drafts(now=_NOW, limit=limit),
            "VALIDATION_FAILED",
        )
    assert EXPIRED_DRAFT_BATCH_MIN == 1
    assert EXPIRED_DRAFT_BATCH_MAX == 100
    parameters = inspect.signature(ExpiredDraftMarkingService.__init__).parameters
    assert set(parameters) == {"self", "session"}
    import remanence.capsules.expired_draft_service as expired_module

    source = inspect.getsource(expired_module)
    assert "BlobStore" not in source
    assert "blob_store" not in source
    assert "with_for_update" not in source
    assert "CapsuleAbortService" not in source


def test_expired_draft_is_marked_and_other_states_are_untouched(session_factory, monkeypatch):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        expired = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=8),
            draft_expires_at=_NOW - timedelta(days=1),
        )
        active = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        ready = _add_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        ready.created_at = _NOW - timedelta(days=8)
        ready.draft_expires_at = _NOW - timedelta(days=1)
        already_aborted = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=8),
            draft_expires_at=_NOW - timedelta(days=1),
        )
        already_aborted.state = CapsuleState.ABORTED
        session.flush()
        expired_blobs = _blob_snapshot(session, expired.id)
        expired_shape = _capsule_shape(expired)
        ready_shape = _capsule_shape(ready)
        aborted_shape = _capsule_shape(already_aborted)
        idempotency_before = session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord))
        _forbid_commit_rollback(session, monkeypatch)
        result = _mark(session, now=_NOW, limit=10)
        monkeypatch.undo()
        assert isinstance(result, ExpiredDraftMarkingResult)
        assert result.examined_count == 1
        assert result.aborted_count == 1
        assert repr(result) == "ExpiredDraftMarkingResult(<redacted>)"
        assert str(expired.id) not in repr(result)
        assert expired.state is CapsuleState.ABORTED
        after = _capsule_shape(expired)
        assert after[6] is CapsuleState.ABORTED
        assert after[:6] == expired_shape[:6]
        assert after[7:] == expired_shape[7:]
        assert _blob_snapshot(session, expired.id) == expired_blobs
        assert active.state is CapsuleState.DRAFT
        assert _capsule_shape(ready) == ready_shape
        assert _capsule_shape(already_aborted) == aborted_shape
        assert session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord)) == idempotency_before
        session.commit()


def test_batch_order_and_limit_are_deterministic(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        later = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=5),
            draft_expires_at=_NOW - timedelta(hours=1),
        )
        earlier = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=6),
            draft_expires_at=_NOW - timedelta(hours=2),
        )
        leftover = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=4),
            draft_expires_at=_NOW - timedelta(minutes=1),
        )
        session.commit()
        result = _mark(session, now=_NOW, limit=2)
        session.commit()
        assert result.examined_count == 2
        assert result.aborted_count == 2
        assert session.get(Capsule, earlier.id).state is CapsuleState.ABORTED
        assert session.get(Capsule, later.id).state is CapsuleState.ABORTED
        assert session.get(Capsule, leftover.id).state is CapsuleState.DRAFT
        second = _mark(session, now=_NOW, limit=2)
        session.commit()
        assert second.examined_count == 1
        assert second.aborted_count == 1
        assert session.get(Capsule, leftover.id).state is CapsuleState.ABORTED


def test_stale_candidate_is_skipped_after_authoritative_reread(session_factory, monkeypatch):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        extended = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=8),
            draft_expires_at=_NOW - timedelta(days=1),
        )
        became_ready = _add_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        missing_id = uuid4()
        session.commit()
        stale_ids = [extended.id, became_ready.id, missing_id]
        monkeypatch.setattr(
            ExpiredDraftMarkingService,
            "_candidate_ids",
            lambda self, *, now, limit: stale_ids,
        )
        extended.draft_expires_at = _NOW + timedelta(days=1)
        session.flush()
        result = _mark(session, now=_NOW, limit=10)
        session.commit()
        assert result.examined_count == 3
        assert result.aborted_count == 0
        assert session.get(Capsule, extended.id).state is CapsuleState.DRAFT
        assert session.get(Capsule, became_ready.id).state is CapsuleState.READY
        assert session.get(Capsule, missing_id) is None


def test_caller_rollback_restores_draft(session_factory, monkeypatch):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        expired = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=8),
            draft_expires_at=_NOW - timedelta(days=1),
        )
        session.commit()
        blobs = _blob_snapshot(session, expired.id)
        _forbid_commit_rollback(session, monkeypatch)
        result = _mark(session, now=_NOW, limit=1)
        assert result.aborted_count == 1
        assert expired.state is CapsuleState.ABORTED
        monkeypatch.undo()
        session.rollback()
        session.expire_all()
        restored = session.get(Capsule, expired.id)
        assert restored.state is CapsuleState.DRAFT
        assert _blob_snapshot(session, expired.id) == blobs


def test_concurrent_gc_workers_are_idempotent(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        expired = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=8),
            draft_expires_at=_NOW - timedelta(days=1),
        )
        capsule_id = expired.id
        session.commit()
    barrier = Barrier(2)

    def worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = _mark(session, now=_NOW, limit=10)
                session.commit()
                return ("ok", result.examined_count, result.aborted_count)
            except ExpiredDraftMarkingError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = [future.result(timeout=20) for future in (
            executor.submit(worker),
            executor.submit(worker),
        )]
    assert all(outcome[0] == "ok" for outcome in outcomes)
    assert sum(outcome[2] for outcome in outcomes) == 1
    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        assert capsule.state is CapsuleState.ABORTED
        assert capsule.ready_at is None
        assert session.scalar(
            select(func.count()).select_from(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule_id)
        ) == 5


def test_gc_versus_explicit_abort_stays_aborted(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        expired = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=8),
            draft_expires_at=_NOW - timedelta(days=1),
        )
        capsule_id = expired.id
        sender_id = sender.id
        session.commit()
    barrier = Barrier(2)

    def gc_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            result = _mark(session, now=_NOW, limit=10)
            session.commit()
            return result.aborted_count

    def abort_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = CapsuleAbortService(session).abort(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    now=_NOW,
                )
                session.commit()
                return ("aborted", result.is_replay)
            except CapsuleAbortError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        gc_future = executor.submit(gc_worker)
        abort_future = executor.submit(abort_worker)
        gc_aborted = gc_future.result(timeout=20)
        abort_outcome = abort_future.result(timeout=20)

    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        assert capsule.state is CapsuleState.ABORTED
        assert capsule.ready_at is None
        assert abort_outcome[0] == "aborted"
        assert gc_aborted in (0, 1)


def test_gc_versus_finalize_has_one_legal_terminal_state(session_factory, tmp_path):
    from tink import tink_config

    from test_capsule_finalize_service import _ready_world

    tink_config.register()
    gc_now = _NOW + timedelta(days=8)

    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        session.commit()
    barrier = Barrier(2)

    def gc_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            result = _mark(session, now=gc_now, limit=10)
            session.commit()
            return result.aborted_count

    def finalize_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = CapsuleFinalizeService(session, world["store"]).finalize(
                    authenticated_sender_user_id=sender_id,
                    capsule_id=capsule_id,
                    statement=world["statement"],
                    signature=world["signature"],
                    sender_key_bundle_id=world["sender_bundle"].id,
                    envelope=world["envelope"],
                    now=_NOW,
                )
                session.commit()
                return ("ready", result.is_replay)
            except CapsuleFinalizeError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        gc_future = executor.submit(gc_worker)
        finalize_future = executor.submit(finalize_worker)
        gc_aborted = gc_future.result(timeout=20)
        finalize_outcome = finalize_future.result(timeout=20)

    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        if capsule.state is CapsuleState.ABORTED:
            assert gc_aborted == 1
            assert finalize_outcome == ("error", "CAPSULE_STATE_INVALID")
            assert capsule.ready_at is None
            assert capsule.signed_statement is None
        else:
            assert capsule.state is CapsuleState.READY
            assert gc_aborted == 0
            assert finalize_outcome == ("ready", False)
            assert capsule.ready_at is not None
