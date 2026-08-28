"""PostgreSQL tests for recipient CIPHERTEXT_SYNCED acknowledgement."""

from __future__ import annotations

import inspect
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Barrier
from typing import Any
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.orm import object_session

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.recipient_material_synced_service import (
    RecipientMaterialSyncedError,
    RecipientMaterialSyncedResult,
    RecipientMaterialSyncedService,
)

from test_capsule_abort_service import _NOW, _add_draft, _seed_user
from test_incoming_query_service import _add_incoming_ready
from test_recipient_blob_query_service import _blobs


def _assert_error(call, code: str, *, secrets: tuple[str, ...] = ()) -> RecipientMaterialSyncedError:
    with pytest.raises(RecipientMaterialSyncedError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "recipient material sync failed"
    assert repr(caught.value) == f"RecipientMaterialSyncedError(code={code!r})"
    assert caught.value.__cause__ is None
    assert caught.value.__context__ is None
    rendered = str(caught.value) + repr(caught.value)
    for secret in secrets:
        assert secret not in rendered
    return caught.value


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("material sync must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


def _mark(session, recipient_id, capsule_id, *, now: datetime = _NOW):
    return RecipientMaterialSyncedService(session).mark_material_synced(
        authenticated_recipient_user_id=recipient_id,
        capsule_id=capsule_id,
        now=now,
    )


def test_invalid_uuid_and_naive_now_fail_closed_without_database() -> None:
    service = RecipientMaterialSyncedService(None)
    recipient_id = uuid4()
    capsule_id = uuid4()
    _assert_error(
        lambda: service.mark_material_synced(
            authenticated_recipient_user_id="not-a-uuid",  # type: ignore[arg-type]
            capsule_id=capsule_id,
            now=_NOW,
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.mark_material_synced(
            authenticated_recipient_user_id=recipient_id,
            capsule_id=str(capsule_id),  # type: ignore[arg-type]
            now=_NOW,
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.mark_material_synced(
            authenticated_recipient_user_id=recipient_id,
            capsule_id=capsule_id,
            now=datetime(2030, 1, 1, 12, 0, 0),
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.mark_material_synced(
            authenticated_recipient_user_id=recipient_id,
            capsule_id=capsule_id,
            now=datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone(timedelta(hours=2))),
        ),
        "VALIDATION_FAILED",
    )
    parameters = inspect.signature(RecipientMaterialSyncedService.__init__).parameters
    assert set(parameters) == {"self", "session"}
    listed = inspect.signature(RecipientMaterialSyncedService.mark_material_synced).parameters
    assert set(listed) == {
        "self",
        "authenticated_recipient_user_id",
        "capsule_id",
        "now",
    }
    assert "sender" not in listed
    import remanence.capsules.recipient_material_synced_service as synced_module

    source = inspect.getsource(synced_module)
    assert "BlobStore" not in source
    assert "blob_store" not in source
    assert "scan" not in source
    assert "opened_at" not in source
    assert "open_count" not in source
    assert "populate_existing" in source
    assert "pg_advisory_xact_lock" in source
    assert set(RecipientMaterialSyncedResult.__dataclass_fields__) == {
        "capsule_id",
        "state",
        "ciphertext_synced_at",
    }


def test_first_transition_and_idempotent_replay_keep_original_timestamp(
    session_factory, monkeypatch
):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
        delivery = session.get(RecipientDeliveryState, (recipient.id, capsule.id))
        assert delivery.state is RecipientDeliveryStatus.AVAILABLE
        assert delivery.ciphertext_synced_at is None
        _forbid_commit_rollback(session, monkeypatch)
        first = _mark(session, recipient.id, capsule.id, now=_NOW + timedelta(seconds=3))
        monkeypatch.undo()
        assert type(first) is RecipientMaterialSyncedResult
        assert first.capsule_id == capsule.id
        assert first.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
        assert first.ciphertext_synced_at == _NOW + timedelta(seconds=3)
        assert session.get(Capsule, capsule.id).state is CapsuleState.READY
        assert delivery.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
        assert delivery.ciphertext_synced_at == first.ciphertext_synced_at
        assert repr(first) == "RecipientMaterialSyncedResult(<redacted>)"
        assert str(capsule.id) not in repr(first)
        session.commit()
        later = _NOW + timedelta(seconds=30)
        replay = _mark(session, recipient.id, capsule.id, now=later)
        assert replay.ciphertext_synced_at == first.ciphertext_synced_at
        assert replay.ciphertext_synced_at != later
        assert replay.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
        reloaded = session.get(RecipientDeliveryState, (recipient.id, capsule.id))
        assert reloaded.ciphertext_synced_at == first.ciphertext_synced_at


def test_two_concurrent_acks_share_one_stored_timestamp(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
        capsule_id = capsule.id
        recipient_id = recipient.id
    barrier = Barrier(2)
    now_a = _NOW + timedelta(seconds=1)
    now_b = _NOW + timedelta(seconds=7)

    def worker(when: datetime):
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            result = _mark(session, recipient_id, capsule_id, now=when)
            session.commit()
            return result.ciphertext_synced_at

    with ThreadPoolExecutor(max_workers=2) as executor:
        stamps = [
            future.result(timeout=20)
            for future in (executor.submit(worker, now_a), executor.submit(worker, now_b))
        ]
    assert stamps[0] == stamps[1]
    assert stamps[0] in {now_a, now_b}
    with session_factory() as session:
        delivery = session.get(RecipientDeliveryState, (recipient_id, capsule_id))
        assert delivery.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
        assert delivery.ciphertext_synced_at == stamps[0]
        assert session.get(Capsule, capsule_id).state is CapsuleState.READY


def test_sender_unrelated_missing_draft_aborted_are_capsule_not_found(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        other, _other_bundle = _seed_user(session, "other")
        ready = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        draft = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            with_idempotency=False,
        )
        aborted = _add_draft(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            with_idempotency=False,
        )
        aborted.state = CapsuleState.ABORTED
        session.commit()
        missing_id = uuid4()
        secrets = (str(ready.id), str(draft.id), str(aborted.id), str(missing_id))
        sender_err = _assert_error(
            lambda: _mark(session, sender.id, ready.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        other_err = _assert_error(
            lambda: _mark(session, other.id, ready.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        missing_err = _assert_error(
            lambda: _mark(session, recipient.id, missing_id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        draft_err = _assert_error(
            lambda: _mark(session, recipient.id, draft.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        aborted_err = _assert_error(
            lambda: _mark(session, recipient.id, aborted.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        rendered = {str(err) + repr(err) for err in (sender_err, other_err, missing_err, draft_err, aborted_err)}
        assert len(rendered) == 1
        still = session.get(RecipientDeliveryState, (recipient.id, ready.id))
        assert still.state is RecipientDeliveryStatus.AVAILABLE
        assert still.ciphertext_synced_at is None


def test_missing_mismatched_delivery_envelope_and_blobs_fail_closed(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        other, other_bundle = _seed_user(session, "other")
        missing_delivery = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            with_delivery=False,
        )
        missing_envelope = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            with_envelope=False,
        )
        mismatched = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            envelope_recipient_id=other.id,
            envelope_key_bundle_id=other_bundle.id,
        )
        declared = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            blob_state=CapsuleBlobState.DECLARED,
        )
        incomplete = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            photo_count=2,
            photo_ordinals=(0, 1),
        )
        session.commit()
        _assert_error(lambda: _mark(session, recipient.id, missing_delivery.id), "INTERNAL_ERROR")
        _assert_error(lambda: _mark(session, recipient.id, missing_envelope.id), "INTERNAL_ERROR")
        _assert_error(lambda: _mark(session, recipient.id, mismatched.id), "INTERNAL_ERROR")
        _assert_error(lambda: _mark(session, recipient.id, declared.id), "INTERNAL_ERROR")
        _assert_error(lambda: _mark(session, recipient.id, incomplete.id), "INTERNAL_ERROR")
        for capsule_id in (
            missing_delivery.id,
            missing_envelope.id,
            mismatched.id,
            declared.id,
            incomplete.id,
        ):
            row = session.get(RecipientDeliveryState, (recipient.id, capsule_id))
            if row is not None:
                assert row.state is RecipientDeliveryStatus.AVAILABLE
                assert row.ciphertext_synced_at is None


def test_caller_owns_transaction_and_stale_identity_map_replays(
    session_factory, monkeypatch
):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
        attached = session.get(RecipientDeliveryState, (recipient.id, capsule.id))
        attached_capsule = session.get(Capsule, capsule.id)
        assert session.autoflush is False
        assert object_session(attached) is session
        original_now = _NOW + timedelta(seconds=4)
        with session_factory() as writer:
            written = _mark(writer, recipient.id, capsule.id, now=original_now)
            writer.commit()
        assert attached.state is RecipientDeliveryStatus.AVAILABLE
        assert attached.ciphertext_synced_at is None
        assert attached_capsule.state is CapsuleState.READY
        _forbid_commit_rollback(session, monkeypatch)
        replay = _mark(session, recipient.id, capsule.id, now=_NOW + timedelta(seconds=40))
        assert replay.ciphertext_synced_at == original_now == written.ciphertext_synced_at
        assert attached.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
        assert attached.ciphertext_synced_at == original_now
        assert session.get(Capsule, capsule.id).state is CapsuleState.READY
        assert len(_blobs(session, capsule.id)) == 5
