"""PostgreSQL tests for recipient-only READY blob authorization lookup."""

from __future__ import annotations

import inspect
from typing import Any
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.orm import object_session

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryStatus
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.recipient_blob_query_service import (
    RecipientBlobQueryError,
    RecipientBlobQueryService,
    RecipientBlobSnapshot,
)

from test_capsule_abort_service import _NOW, _add_draft, _seed_user
from test_incoming_query_service import _add_incoming_ready


_OBJECT_KEY_CANARY = "secret-object-key"


def _assert_error(call, code: str, *, secrets: tuple[str, ...] = ()) -> RecipientBlobQueryError:
    with pytest.raises(RecipientBlobQueryError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "recipient blob query failed"
    assert repr(caught.value) == f"RecipientBlobQueryError(code={code!r})"
    assert caught.value.__cause__ is None
    assert caught.value.__context__ is None
    rendered = str(caught.value) + repr(caught.value)
    for secret in secrets:
        assert secret not in rendered
    return caught.value


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("recipient blob query must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


def _get(session, recipient_id, capsule_id, blob_id):
    return RecipientBlobQueryService(session).get_ready_blob(
        authenticated_recipient_user_id=recipient_id,
        capsule_id=capsule_id,
        blob_id=blob_id,
    )


def _blobs(session, capsule_id: UUID) -> list[CapsuleBlob]:
    return list(
        session.scalars(
            select(CapsuleBlob)
            .where(CapsuleBlob.capsule_id == capsule_id)
            .order_by(CapsuleBlob.kind, CapsuleBlob.ordinal, CapsuleBlob.id)
        )
    )


def test_invalid_uuid_fail_closed_without_database() -> None:
    service = RecipientBlobQueryService(None)
    recipient_id = uuid4()
    capsule_id = uuid4()
    blob_id = uuid4()
    _assert_error(
        lambda: service.get_ready_blob(
            authenticated_recipient_user_id="not-a-uuid",  # type: ignore[arg-type]
            capsule_id=capsule_id,
            blob_id=blob_id,
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.get_ready_blob(
            authenticated_recipient_user_id=recipient_id,
            capsule_id=str(capsule_id),  # type: ignore[arg-type]
            blob_id=blob_id,
        ),
        "VALIDATION_FAILED",
    )
    _assert_error(
        lambda: service.get_ready_blob(
            authenticated_recipient_user_id=recipient_id,
            capsule_id=capsule_id,
            blob_id=str(blob_id),  # type: ignore[arg-type]
        ),
        "VALIDATION_FAILED",
    )
    parameters = inspect.signature(RecipientBlobQueryService.__init__).parameters
    assert set(parameters) == {"self", "session"}
    listed = inspect.signature(RecipientBlobQueryService.get_ready_blob).parameters
    assert set(listed) == {
        "self",
        "authenticated_recipient_user_id",
        "capsule_id",
        "blob_id",
    }
    import remanence.capsules.recipient_blob_query_service as query_module

    source = inspect.getsource(query_module)
    assert "populate_existing" in source
    assert "BlobStore" not in source
    assert "blob_store" not in source
    assert "session.commit" not in source
    assert "session.rollback" not in source
    assert "APIRouter" not in source
    assert set(RecipientBlobSnapshot.__dataclass_fields__) == {
        "capsule_id",
        "blob_id",
        "kind",
        "ordinal",
        "object_key",
        "expected_ciphertext_size",
        "expected_ciphertext_sha256",
    }


def test_recipient_success_across_blob_kinds_and_exact_snapshot(session_factory, monkeypatch):
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
            object_key_prefix=_OBJECT_KEY_CANARY,
        )
        session.commit()
        blobs = _blobs(session, capsule.id)
        kinds = {blob.kind for blob in blobs}
        assert CapsuleBlobKind.RECOGNITION_MANIFEST in kinds
        assert CapsuleBlobKind.CONTENT_MANIFEST in kinds
        assert CapsuleBlobKind.PHOTO in kinds
        _forbid_commit_rollback(session, monkeypatch)
        snapshots = [_get(session, recipient.id, capsule.id, blob.id) for blob in blobs]
        assert len(snapshots) == 5
        for blob, snap in zip(blobs, snapshots, strict=True):
            assert type(snap) is RecipientBlobSnapshot
            assert snap.capsule_id == capsule.id == blob.capsule_id
            assert snap.blob_id == blob.id
            assert snap.kind is blob.kind
            assert snap.ordinal == blob.ordinal
            assert snap.object_key == blob.object_key
            assert _OBJECT_KEY_CANARY in snap.object_key
            assert snap.expected_ciphertext_size == blob.expected_ciphertext_size
            assert snap.expected_ciphertext_sha256 == blob.expected_ciphertext_sha256
            assert _OBJECT_KEY_CANARY not in repr(snap)
            assert "object_key" not in repr(snap)
            assert str(blob.id) not in repr(snap)
            assert getattr(snap, "_sa_instance_state", None) is None
        photos = [snap for snap in snapshots if snap.kind is CapsuleBlobKind.PHOTO]
        assert [snap.ordinal for snap in photos] == [0, 1, 2]
        manifests = [snap for snap in snapshots if snap.kind is not CapsuleBlobKind.PHOTO]
        assert all(snap.ordinal is None for snap in manifests)


def test_ciphertext_synced_is_still_readable(session_factory):
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
            delivery_status=RecipientDeliveryStatus.CIPHERTEXT_SYNCED,
        )
        session.commit()
        blob = _blobs(session, capsule.id)[0]
        snap = _get(session, recipient.id, capsule.id, blob.id)
        assert snap.blob_id == blob.id
        assert snap.object_key == blob.object_key


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
        ready_blob = _blobs(session, ready.id)[0]
        draft_blob = _blobs(session, draft.id)[0]
        aborted_blob = _blobs(session, aborted.id)[0]
        missing_id = uuid4()
        secrets = (
            str(ready.id),
            str(ready_blob.id),
            str(draft.id),
            str(aborted.id),
            str(missing_id),
            ready_blob.object_key,
        )
        sender_err = _assert_error(
            lambda: _get(session, sender.id, ready.id, ready_blob.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        other_err = _assert_error(
            lambda: _get(session, other.id, ready.id, ready_blob.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        missing_err = _assert_error(
            lambda: _get(session, recipient.id, missing_id, ready_blob.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        draft_err = _assert_error(
            lambda: _get(session, recipient.id, draft.id, draft_blob.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        aborted_err = _assert_error(
            lambda: _get(session, recipient.id, aborted.id, aborted_blob.id),
            "CAPSULE_NOT_FOUND",
            secrets=secrets,
        )
        rendered = {str(err) + repr(err) for err in (sender_err, other_err, missing_err, draft_err, aborted_err)}
        assert len(rendered) == 1


def test_cross_capsule_and_unknown_blob_are_not_declared(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        first = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        second = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
        first_blob = _blobs(session, first.id)[0]
        second_blob = _blobs(session, second.id)[0]
        unknown = uuid4()
        cross = _assert_error(
            lambda: _get(session, recipient.id, first.id, second_blob.id),
            "BLOB_NOT_DECLARED",
            secrets=(str(second_blob.id), second_blob.object_key),
        )
        missing = _assert_error(
            lambda: _get(session, recipient.id, first.id, unknown),
            "BLOB_NOT_DECLARED",
            secrets=(str(unknown), first_blob.object_key),
        )
        assert str(cross) == str(missing)
        assert repr(cross) == repr(missing)
        owned = _get(session, recipient.id, first.id, first_blob.id)
        assert owned.blob_id == first_blob.id


def test_missing_delivery_and_declared_blob_fail_closed(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        missing_delivery = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            with_delivery=False,
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
        mixed = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
        missing_blob = _blobs(session, missing_delivery.id)[0]
        declared_blob = _blobs(session, declared.id)[0]
        mixed_blobs = _blobs(session, mixed.id)
        mixed_blobs[0].state = CapsuleBlobState.DECLARED
        session.flush()
        _assert_error(
            lambda: _get(session, recipient.id, missing_delivery.id, missing_blob.id),
            "INTERNAL_ERROR",
        )
        _assert_error(
            lambda: _get(session, recipient.id, declared.id, declared_blob.id),
            "INTERNAL_ERROR",
        )
        _assert_error(
            lambda: _get(session, recipient.id, mixed.id, mixed_blobs[0].id),
            "INTERNAL_ERROR",
        )
        stored = _get(session, recipient.id, mixed.id, mixed_blobs[1].id)
        assert stored.blob_id == mixed_blobs[1].id
        assert mixed_blobs[1].state is CapsuleBlobState.STORED


def test_caller_owns_transaction_and_stale_identity_map_is_refreshed(
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
        blob = _blobs(session, capsule.id)[0]
        attached = session.get(Capsule, capsule.id)
        attached_blob = session.get(CapsuleBlob, blob.id)
        assert session.autoflush is False
        assert object_session(attached) is session
        assert object_session(attached_blob) is session
        attached.state = CapsuleState.DRAFT
        attached_blob.state = CapsuleBlobState.DECLARED
        assert attached.state is CapsuleState.DRAFT
        assert attached_blob.state is CapsuleBlobState.DECLARED
        _forbid_commit_rollback(session, monkeypatch)
        snap = _get(session, recipient.id, capsule.id, blob.id)
        assert attached.state is CapsuleState.READY
        assert attached_blob.state is CapsuleBlobState.STORED
        assert snap.blob_id == blob.id
        assert snap.object_key == blob.object_key
        assert snap.kind is blob.kind
