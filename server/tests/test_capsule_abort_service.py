"""PostgreSQL tests for the capsule abort transaction."""

from __future__ import annotations

import hashlib
import inspect
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Barrier
from typing import Any
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text
from sqlalchemy.orm import make_transient

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.abort_service import (
    CapsuleAbortError,
    CapsuleAbortResult,
    CapsuleAbortService,
)
from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.finalize_service import CapsuleFinalizeError, CapsuleFinalizeService
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.models import Capsule, CapsuleState
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


_NOW = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)


def _assert_error(call, code: str) -> CapsuleAbortError:
    with pytest.raises(CapsuleAbortError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "capsule abort failed"
    assert repr(caught.value) == f"CapsuleAbortError(code={code!r})"
    return caught.value


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("abort service must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


def _digest(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def _seed_user(session, label: str) -> tuple[User, UserKeyBundle]:
    suffix = uuid4().hex
    user = User(
        id=uuid4(),
        email_normalized=f"{label}-{suffix}@example.com",
        handle_normalized=f"{label}{suffix[:20]}",
        handle_display=f"{label}{suffix[:20]}",
    )
    session.add(user)
    session.flush()
    bundle = UserKeyBundle(
        id=uuid4(),
        user_id=user.id,
        encryption_public_keyset=b"encryption-public",
        signing_public_keyset=b"signing-public",
        suite="test-suite",
        protocol_version=1,
        status=KeyBundleStatus.ACTIVE,
    )
    session.add(bundle)
    session.flush()
    return user, bundle


def _blob_snapshot(session, capsule_id: UUID) -> tuple[tuple[object, ...], ...]:
    blobs = list(
        session.scalars(
            select(CapsuleBlob)
            .where(CapsuleBlob.capsule_id == capsule_id)
            .order_by(CapsuleBlob.id)
        )
    )
    return tuple(
        (
            blob.id,
            blob.capsule_id,
            blob.kind,
            blob.ordinal,
            blob.object_key,
            blob.expected_ciphertext_size,
            blob.expected_ciphertext_sha256,
            blob.state,
        )
        for blob in blobs
    )


def _capsule_shape(capsule: Capsule) -> tuple[object, ...]:
    return (
        capsule.id,
        capsule.sender_user_id,
        capsule.recipient_user_id,
        capsule.sender_key_bundle_id,
        capsule.recipient_key_bundle_id,
        capsule.protocol_version,
        capsule.state,
        capsule.signed_statement,
        capsule.signed_statement_sha256,
        capsule.publish_signature,
        capsule.created_at,
        capsule.ready_at,
        capsule.draft_expires_at,
    )


def _add_draft(
    session,
    *,
    sender: User,
    sender_bundle: UserKeyBundle,
    recipient: User,
    recipient_bundle: UserKeyBundle,
    created_at: datetime = _NOW,
    draft_expires_at: datetime | None = None,
    with_idempotency: bool = True,
) -> Capsule:
    capsule = Capsule(
        id=uuid4(),
        sender_user_id=sender.id,
        recipient_user_id=recipient.id,
        sender_key_bundle_id=sender_bundle.id,
        recipient_key_bundle_id=recipient_bundle.id,
        protocol_version=1,
        state=CapsuleState.DRAFT,
        signed_statement=None,
        signed_statement_sha256=None,
        publish_signature=None,
        created_at=created_at,
        ready_at=None,
        draft_expires_at=draft_expires_at or created_at + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    specs = (
        (CapsuleBlobKind.RECOGNITION_MANIFEST, None, b"recognition"),
        (CapsuleBlobKind.CONTENT_MANIFEST, None, b"content"),
        (CapsuleBlobKind.PHOTO, 0, b"photo-0"),
        (CapsuleBlobKind.PHOTO, 1, b"photo-1"),
        (CapsuleBlobKind.PHOTO, 2, b"photo-2"),
    )
    for kind, ordinal, body in specs:
        blob_id = uuid4()
        session.add(
            CapsuleBlob(
                id=blob_id,
                capsule_id=capsule.id,
                kind=kind,
                ordinal=ordinal,
                object_key=f"capsules/{capsule.id}/{blob_id}.blob",
                expected_ciphertext_size=len(body),
                expected_ciphertext_sha256=_digest(body),
                state=CapsuleBlobState.DECLARED,
            )
        )
    if with_idempotency:
        session.add(
            CapsuleIdempotencyRecord(
                owner_user_id=sender.id,
                method="POST",
                normalized_route="/v1/capsules",
                idempotency_key=uuid4(),
                request_sha256=bytes(range(32)),
                response_status=201,
                response_json={"capsule_id": str(capsule.id), "state": "DRAFT"},
                created_at=created_at,
                expires_at=created_at + timedelta(hours=24),
            )
        )
    session.flush()
    return capsule


def _add_ready(session, *, sender, sender_bundle, recipient, recipient_bundle) -> Capsule:
    statement = b"signed-statement"
    capsule = Capsule(
        id=uuid4(),
        sender_user_id=sender.id,
        recipient_user_id=recipient.id,
        sender_key_bundle_id=sender_bundle.id,
        recipient_key_bundle_id=recipient_bundle.id,
        protocol_version=1,
        state=CapsuleState.READY,
        signed_statement=statement,
        signed_statement_sha256=_digest(statement),
        publish_signature=b"\x01" * 69,
        created_at=_NOW,
        ready_at=_NOW,
        draft_expires_at=_NOW + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    return capsule


def _abort(session, *, sender_id: UUID, capsule_id: UUID, now: datetime = _NOW) -> CapsuleAbortResult:
    return CapsuleAbortService(session).abort(
        authenticated_sender_user_id=sender_id,
        capsule_id=capsule_id,
        now=now,
    )


def test_invalid_uuid_and_naive_now_fail_closed_without_database() -> None:
    service = CapsuleAbortService(None)
    capsule_id = uuid4()
    sender_id = uuid4()
    _assert_error(
        lambda: service.abort(
            authenticated_sender_user_id="not-a-uuid",  # type: ignore[arg-type]
            capsule_id=capsule_id,
            now=_NOW,
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.abort(
            authenticated_sender_user_id=sender_id,
            capsule_id=str(capsule_id),  # type: ignore[arg-type]
            now=_NOW,
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.abort(
            authenticated_sender_user_id=sender_id,
            capsule_id=capsule_id,
            now=datetime(2030, 1, 1, 12, 0, 0),
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.abort(
            authenticated_sender_user_id=sender_id,
            capsule_id=capsule_id,
            now=datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone(timedelta(hours=2))),
        ),
        "VALIDATION_FAILED",
    )
    parameters = inspect.signature(CapsuleAbortService.__init__).parameters
    assert set(parameters) == {"self", "session"}


def test_draft_transitions_once_and_expired_draft_is_allowed(session_factory, monkeypatch):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        blobs_before = _blob_snapshot(session, capsule.id)
        idempotency_before = session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord))
        shape_before = _capsule_shape(capsule)
        _forbid_commit_rollback(session, monkeypatch)
        result = _abort(session, sender_id=sender.id, capsule_id=capsule.id)
        monkeypatch.undo()
        assert isinstance(result, CapsuleAbortResult)
        assert result.is_replay is False
        assert result.state is CapsuleState.ABORTED
        assert result.capsule_id == capsule.id
        assert repr(result) == "CapsuleAbortResult(<redacted>)"
        assert str(capsule.id) not in repr(result)
        assert capsule.state is CapsuleState.ABORTED
        assert capsule.ready_at is None
        assert capsule.signed_statement is None
        after_shape = _capsule_shape(capsule)
        assert after_shape[6] is CapsuleState.ABORTED
        assert after_shape[:6] == shape_before[:6]
        assert after_shape[7:] == shape_before[7:]
        assert _blob_snapshot(session, capsule.id) == blobs_before
        assert session.scalar(select(func.count()).select_from(CapsuleIdempotencyRecord)) == idempotency_before
        session.commit()

        expired = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            created_at=_NOW - timedelta(days=8),
            draft_expires_at=_NOW - timedelta(days=1),
        )
        expired_blobs = _blob_snapshot(session, expired.id)
        expired_result = _abort(session, sender_id=sender.id, capsule_id=expired.id, now=_NOW)
        session.commit()
        assert expired_result.is_replay is False
        assert expired.state is CapsuleState.ABORTED
        assert _blob_snapshot(session, expired.id) == expired_blobs


def test_aborted_replay_is_idempotent_and_ready_is_unchanged(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "owner")
        recipient, recipient_bundle = _seed_user(session, "peer")
        capsule = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        first = _abort(session, sender_id=sender.id, capsule_id=capsule.id)
        session.commit()
        assert first.is_replay is False
        blobs = _blob_snapshot(session, capsule.id)
        shape = _capsule_shape(capsule)
        replay = _abort(session, sender_id=sender.id, capsule_id=capsule.id)
        session.flush()
        assert replay.is_replay is True
        assert replay.state is CapsuleState.ABORTED
        assert _capsule_shape(capsule) == shape
        assert _blob_snapshot(session, capsule.id) == blobs
        session.commit()

        ready = _add_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        session.commit()
        ready_shape = _capsule_shape(ready)
        _assert_error(
            lambda: _abort(session, sender_id=sender.id, capsule_id=ready.id),
            "CAPSULE_STATE_INVALID",
        )
        session.flush()
        assert _capsule_shape(ready) == ready_shape
        session.rollback()
        reloaded = session.get(Capsule, ready.id)
        assert _capsule_shape(reloaded) == ready_shape


def test_missing_and_foreign_abort_are_indistinguishable(session_factory):
    with session_factory() as session:
        owner, owner_bundle = _seed_user(session, "owner")
        stranger, _stranger_bundle = _seed_user(session, "stranger")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_draft(
            session,
            sender=owner,
            sender_bundle=owner_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        session.commit()
        shape = _capsule_shape(capsule)
        blobs = _blob_snapshot(session, capsule.id)
        missing_id = uuid4()
        missing = _assert_error(
            lambda: _abort(session, sender_id=owner.id, capsule_id=missing_id),
            "CAPSULE_NOT_FOUND",
        )
        foreign = _assert_error(
            lambda: _abort(session, sender_id=stranger.id, capsule_id=capsule.id),
            "CAPSULE_NOT_FOUND",
        )
        assert str(missing) == str(foreign)
        assert repr(missing) == repr(foreign)
        assert str(capsule.id) not in str(foreign)
        assert str(stranger.id) not in str(foreign)
        assert str(missing_id) not in str(missing)
        session.flush()
        assert _capsule_shape(session.get(Capsule, capsule.id)) == shape
        assert _blob_snapshot(session, capsule.id) == blobs
        assert session.get(Capsule, missing_id) is None


def test_caller_rollback_restores_draft_and_service_does_not_finalize(
    session_factory, monkeypatch
):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        session.commit()
        blobs = _blob_snapshot(session, capsule.id)
        _forbid_commit_rollback(session, monkeypatch)
        result = _abort(session, sender_id=sender.id, capsule_id=capsule.id)
        assert result.is_replay is False
        assert capsule.state is CapsuleState.ABORTED
        monkeypatch.undo()
        session.rollback()
        session.expire_all()
        restored = session.get(Capsule, capsule.id)
        assert restored.state is CapsuleState.DRAFT
        assert restored.ready_at is None
        assert _blob_snapshot(session, capsule.id) == blobs


def test_abort_does_not_use_caller_orm_capsule_or_blob_store(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        session.commit()
        stale = session.get(Capsule, capsule.id)
        stale.state = CapsuleState.READY
        make_transient(stale)
        result = _abort(session, sender_id=sender.id, capsule_id=capsule.id)
        session.commit()
        assert result.is_replay is False
        assert session.get(Capsule, capsule.id).state is CapsuleState.ABORTED
        assert stale.state is CapsuleState.READY
        service = CapsuleAbortService(session)
        assert not hasattr(service, "_blob_store")
        import remanence.capsules.abort_service as abort_module

        source = inspect.getsource(abort_module)
        assert "BlobStore" not in source
        assert "blob_store" not in source


def test_concurrent_aborts_have_exactly_one_transition(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
        )
        capsule_id = capsule.id
        sender_id = sender.id
        session.commit()
    barrier = Barrier(2)

    def worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = _abort(session, sender_id=sender_id, capsule_id=capsule_id)
                session.commit()
                return ("aborted", result.is_replay)
            except CapsuleAbortError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(worker), executor.submit(worker)]
        outcomes = sorted(future.result(timeout=20) for future in futures)
    assert outcomes == [("aborted", False), ("aborted", True)]
    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        assert capsule.state is CapsuleState.ABORTED
        assert capsule.ready_at is None
        assert session.scalar(
            select(func.count()).select_from(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule_id)
        ) == 5


def test_abort_versus_finalize_race_has_one_legal_terminal_state(session_factory, tmp_path):
    from tink import tink_config

    from test_capsule_finalize_service import _ready_world

    tink_config.register()

    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        capsule_id = world["capsule"].id
        sender_id = world["sender"].id
        session.commit()
    barrier = Barrier(2)

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
        abort_future = executor.submit(abort_worker)
        finalize_future = executor.submit(finalize_worker)
        abort_outcome = abort_future.result(timeout=20)
        finalize_outcome = finalize_future.result(timeout=20)

    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        if abort_outcome == ("aborted", False):
            assert finalize_outcome == ("error", "CAPSULE_STATE_INVALID")
            assert capsule.state is CapsuleState.ABORTED
            assert capsule.ready_at is None
            assert capsule.signed_statement is None
        else:
            assert abort_outcome == ("error", "CAPSULE_STATE_INVALID")
            assert finalize_outcome == ("ready", False)
            assert capsule.state is CapsuleState.READY
            assert capsule.ready_at is not None
            assert capsule.signed_statement is not None
