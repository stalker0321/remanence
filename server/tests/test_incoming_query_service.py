"""PostgreSQL tests for recipient-only incoming capsule cursor queries."""

from __future__ import annotations

import base64
import hashlib
import inspect
import struct
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID, uuid4

import pytest
from sqlalchemy import event, select

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.incoming_cursor import (
    INCOMING_CURSOR_B64_LENGTH,
    INCOMING_CURSOR_PAYLOAD_BYTES,
    INCOMING_CURSOR_VERSION,
    IncomingCursor,
    encode_incoming_cursor,
)
from remanence.capsules.incoming_query_service import (
    IncomingBlobSnapshot,
    IncomingCapsulePage,
    IncomingCapsuleQueryError,
    IncomingCapsuleQueryService,
    IncomingCapsuleSnapshot,
    IncomingEnvelopeSnapshot,
)
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import Capsule, CapsuleState

from test_capsule_abort_service import _NOW, _add_draft, _seed_user


_EMAIL_CANARY = "secret-email-canary"
_HANDLE_CANARY = "secrethandlecanary"
_OBJECT_KEY_CANARY = "secret-object-key"
_CURSOR_ALPHABET = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")


def _digest(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def _assert_error(call, code: str, *, secrets: tuple[str, ...] = ()) -> IncomingCapsuleQueryError:
    with pytest.raises(IncomingCapsuleQueryError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "incoming capsule query failed"
    assert repr(caught.value) == f"IncomingCapsuleQueryError(code={code!r})"
    assert caught.value.__cause__ is None
    assert caught.value.__context__ is None
    rendered = str(caught.value) + repr(caught.value)
    for secret in secrets:
        assert secret not in rendered
    return caught.value


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("incoming query must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


def _query(session, recipient_id, *, cursor=None, limit=...):
    service = IncomingCapsuleQueryService(session)
    kwargs: dict[str, Any] = {
        "authenticated_recipient_user_id": recipient_id,
        "cursor": cursor,
    }
    if limit is not ...:
        kwargs["limit"] = limit
    return service.list_incoming(**kwargs)


def _add_incoming_ready(
    session,
    *,
    sender,
    sender_bundle,
    recipient,
    recipient_bundle,
    ready_at: datetime,
    photo_count: int = 3,
    photo_ordinals: tuple[int, ...] | None = None,
    delivery_status: RecipientDeliveryStatus = RecipientDeliveryStatus.AVAILABLE,
    statement: bytes = b"signed-statement",
    ciphertext: bytes = b"envelope-ciphertext",
    with_envelope: bool = True,
    with_delivery: bool = True,
    blob_state: CapsuleBlobState = CapsuleBlobState.STORED,
    object_key_prefix: str = "capsules",
    envelope_recipient_id: UUID | None = None,
    envelope_key_bundle_id: UUID | None = None,
) -> Capsule:
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
        ready_at=ready_at,
        draft_expires_at=_NOW + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    specs: list[tuple[CapsuleBlobKind, int | None, bytes]] = [
        (CapsuleBlobKind.RECOGNITION_MANIFEST, None, b"recognition"),
        (CapsuleBlobKind.CONTENT_MANIFEST, None, b"content"),
    ]
    ordinals = photo_ordinals if photo_ordinals is not None else tuple(range(photo_count))
    for ordinal in ordinals:
        specs.append((CapsuleBlobKind.PHOTO, ordinal, f"photo-{ordinal}".encode()))
    for kind, ordinal, body in specs:
        blob_id = uuid4()
        session.add(
            CapsuleBlob(
                id=blob_id,
                capsule_id=capsule.id,
                kind=kind,
                ordinal=ordinal,
                object_key=f"{object_key_prefix}/{capsule.id}/{blob_id}.blob",
                expected_ciphertext_size=len(body),
                expected_ciphertext_sha256=_digest(body),
                state=blob_state,
            )
        )
    if with_envelope:
        session.add(
            CapsuleEnvelope(
                capsule_id=capsule.id,
                recipient_user_id=envelope_recipient_id or recipient.id,
                recipient_key_bundle_id=envelope_key_bundle_id or recipient_bundle.id,
                ciphertext=ciphertext,
                ciphertext_size=len(ciphertext),
                ciphertext_sha256=_digest(ciphertext),
            )
        )
    if with_delivery:
        session.add(
            RecipientDeliveryState(
                recipient_user_id=recipient.id,
                capsule_id=capsule.id,
                state=delivery_status,
                available_at=ready_at,
                ciphertext_synced_at=(
                    ready_at
                    if delivery_status is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
                    else None
                ),
            )
        )
    session.flush()
    return capsule


def test_cursor_roundtrip_is_canonical_unpadded_fixed_length() -> None:
    capsule_id = UUID("12345678-1234-5678-1234-567812345678")
    ready_at = datetime(2030, 1, 1, 12, 0, 0, 123456, tzinfo=timezone.utc)
    token = encode_incoming_cursor(ready_at=ready_at, capsule_id=capsule_id)
    assert type(token) is str
    assert len(token) == INCOMING_CURSOR_B64_LENGTH == 34
    assert "=" not in token
    assert set(token) <= _CURSOR_ALPHABET
    payload = base64.urlsafe_b64decode(token + "=" * (-len(token) % 4))
    assert len(payload) == INCOMING_CURSOR_PAYLOAD_BYTES == 25
    assert payload[0] == INCOMING_CURSOR_VERSION == 1
    delta = ready_at - datetime(1970, 1, 1, tzinfo=timezone.utc)
    expected_micros = (
        delta.days * 86_400_000_000 + delta.seconds * 1_000_000 + delta.microseconds
    )
    assert struct.unpack(">q", payload[1:9])[0] == expected_micros
    assert payload[9:25] == capsule_id.bytes
    assert encode_incoming_cursor(ready_at=ready_at, capsule_id=capsule_id) == token
    import remanence.capsules.incoming_cursor as cursor_module
    import remanence.capsules.incoming_query_service as query_module

    cursor_source = inspect.getsource(cursor_module)
    query_source = inspect.getsource(query_module)
    assert ".timestamp(" not in cursor_source
    assert "calendar." not in cursor_source
    assert ".offset(" not in query_source
    assert "with_for_update" not in query_source
    assert "object_key" not in query_source
    assert "BlobStore" not in query_source
    assert "APIRouter" not in query_source


def test_malformed_noncanonical_cursor_limit_and_uuid_fail_closed_without_database() -> None:
    service = IncomingCapsuleQueryService(None)
    recipient_id = uuid4()
    capsule_id = uuid4()
    token = encode_incoming_cursor(ready_at=_NOW, capsule_id=capsule_id)
    padded = token + "=" * ((4 - len(token) % 4) % 4)
    payload = bytes((INCOMING_CURSOR_VERSION,)) + b"\xff" * 8 + b"\xff" * 16
    std = base64.b64encode(payload).decode("ascii").rstrip("=")
    assert "+" in std or "/" in std
    versioned = bytes((2,)) + struct.pack(">q", 0) + capsule_id.bytes
    overflow = (
        bytes((INCOMING_CURSOR_VERSION,))
        + struct.pack(">q", (1 << 63) - 1)
        + capsule_id.bytes
    )
    underflow = (
        bytes((INCOMING_CURSOR_VERSION,))
        + struct.pack(">q", -(1 << 63))
        + capsule_id.bytes
    )
    short = bytes((INCOMING_CURSOR_VERSION,)) + struct.pack(">q", 0) + capsule_id.bytes[:15]
    secrets = ("secret-cursor-token",)
    invalid = [
        "",
        "not-a-cursor",
        padded,
        std,
        token + "A",
        token[:-1],
        " " + token,
        base64.urlsafe_b64encode(versioned).decode("ascii").rstrip("="),
        base64.urlsafe_b64encode(overflow).decode("ascii").rstrip("="),
        base64.urlsafe_b64encode(underflow).decode("ascii").rstrip("="),
        base64.urlsafe_b64encode(short).decode("ascii").rstrip("="),
        b"abc",
        1,
        IncomingCursor(ready_at=_NOW, capsule_id=capsule_id),
    ]
    for cursor in invalid:
        _assert_error(
            lambda cursor=cursor: service.list_incoming(
                authenticated_recipient_user_id=recipient_id,
                cursor=cursor,  # type: ignore[arg-type]
                limit=1,
            ),
            "VALIDATION_FAILED",
            secrets=secrets,
        )
    for limit in (0, 101, -1, True, False, 1.0, "1", None):
        _assert_error(
            lambda limit=limit: service.list_incoming(
                authenticated_recipient_user_id=recipient_id,
                limit=limit,  # type: ignore[arg-type]
            ),
            "VALIDATION_FAILED",
        )
    _assert_error(
        lambda: service.list_incoming(
            authenticated_recipient_user_id=str(recipient_id),  # type: ignore[arg-type]
            limit=1,
        ),
        "VALIDATION_FAILED",
    )
    parameters = inspect.signature(IncomingCapsuleQueryService.__init__).parameters
    assert set(parameters) == {"self", "session"}
    listed = inspect.signature(IncomingCapsuleQueryService.list_incoming).parameters
    assert listed["limit"].default == LIMITS_V1.incoming_page_default == 50
    assert LIMITS_V1.incoming_page_max == 100
    assert INCOMING_CURSOR_B64_LENGTH == 34


def test_oldest_order_tied_timestamp_uuid_order_and_replay(session_factory, monkeypatch):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        later = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=5),
        )
        tied_b = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        tied_a = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
        expected_ids = tuple(
            capsule.id
            for capsule in sorted(
                (tied_a, tied_b, later), key=lambda item: (item.ready_at, item.id)
            )
        )
        _forbid_commit_rollback(session, monkeypatch)
        first = _query(session, recipient.id, limit=2)
        replay = _query(session, recipient.id, limit=2)
        assert first == replay
        assert first.has_more is True
        assert type(first.next_cursor) is str
        assert len(first.next_cursor) == 34
        assert [item.capsule_id for item in first.items] == list(expected_ids[:2])
        assert first.items[0].ready_at == first.items[1].ready_at == _NOW
        assert first.items[0].capsule_id < first.items[1].capsule_id
        second = _query(session, recipient.id, cursor=first.next_cursor, limit=2)
        second_replay = _query(session, recipient.id, cursor=first.next_cursor, limit=2)
        assert second == second_replay
        assert [item.capsule_id for item in second.items] == [expected_ids[2]]
        assert second.has_more is False
        assert second.next_cursor == encode_incoming_cursor(
            ready_at=second.items[-1].ready_at,
            capsule_id=second.items[-1].capsule_id,
        )
        assert isinstance(first.items[0], IncomingCapsuleSnapshot)
        assert first.items[0].blobs[0].kind is CapsuleBlobKind.RECOGNITION_MANIFEST
        assert first.items[0].blobs[1].kind is CapsuleBlobKind.CONTENT_MANIFEST
        assert [blob.ordinal for blob in first.items[0].blobs[2:]] == [0, 1, 2]


def test_page_default_max_and_limit_plus_one(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsules = [
            _add_incoming_ready(
                session,
                sender=sender,
                sender_bundle=sender_bundle,
                recipient=recipient,
                recipient_bundle=recipient_bundle,
                ready_at=_NOW + timedelta(seconds=index, microseconds=index),
            )
            for index in range(51)
        ]
        session.commit()
        ordered = tuple(capsule.id for capsule in sorted(capsules, key=lambda item: (item.ready_at, item.id)))
        default_page = _query(session, recipient.id)
        assert len(default_page.items) == 50
        assert default_page.has_more is True
        assert default_page.next_cursor is not None
        assert [item.capsule_id for item in default_page.items] == list(ordered[:50])
        decoded_last = encode_incoming_cursor(
            ready_at=default_page.items[-1].ready_at,
            capsule_id=default_page.items[-1].capsule_id,
        )
        assert default_page.next_cursor == decoded_last
        full = _query(session, recipient.id, limit=LIMITS_V1.incoming_page_max)
        assert len(full.items) == 51
        assert full.has_more is False
        assert full.next_cursor == encode_incoming_cursor(
            ready_at=full.items[-1].ready_at,
            capsule_id=full.items[-1].capsule_id,
        )
        tiny = _query(session, recipient.id, limit=1)
        assert len(tiny.items) == 1
        assert tiny.has_more is True
        assert tiny.items[0].capsule_id == ordered[0]
        rest = _query(session, recipient.id, cursor=tiny.next_cursor, limit=100)
        assert [item.capsule_id for item in rest.items] == list(ordered[1:])
        assert rest.has_more is False
        assert rest.next_cursor == encode_incoming_cursor(
            ready_at=rest.items[-1].ready_at,
            capsule_id=rest.items[-1].capsule_id,
        )


def test_insertion_after_cursor_is_visible_on_continuation(session_factory):
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
            ready_at=_NOW + timedelta(seconds=2),
        )
        third = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=4),
        )
        session.commit()
        page = _query(session, recipient.id, limit=2)
        assert [item.capsule_id for item in page.items] == [first.id, second.id]
        inserted = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=3),
        )
        session.commit()
        replay = _query(session, recipient.id, limit=2)
        assert [item.capsule_id for item in replay.items] == [first.id, second.id]
        assert replay.next_cursor == page.next_cursor
        continuation = _query(session, recipient.id, cursor=page.next_cursor, limit=2)
        assert [item.capsule_id for item in continuation.items] == [inserted.id, third.id]
        assert continuation.has_more is False
        assert continuation.next_cursor == encode_incoming_cursor(
            ready_at=continuation.items[-1].ready_at,
            capsule_id=continuation.items[-1].capsule_id,
        )


def test_terminal_cursor_is_high_watermark_for_later_insertions(session_factory):
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
        session.commit()

        terminal = _query(session, recipient.id, limit=100)
        terminal_cursor = encode_incoming_cursor(
            ready_at=first.ready_at, capsule_id=first.id
        )
        assert [item.capsule_id for item in terminal.items] == [first.id]
        assert terminal.has_more is False
        assert terminal.next_cursor == terminal_cursor

        later = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=1),
        )
        session.commit()

        continuation = _query(session, recipient.id, cursor=terminal_cursor, limit=100)
        assert [item.capsule_id for item in continuation.items] == [later.id]
        assert continuation.has_more is False
        assert continuation.next_cursor == encode_incoming_cursor(
            ready_at=later.ready_at, capsule_id=later.id
        )


def test_empty_initial_and_empty_continuation_cursor_semantics(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        initial = _query(session, recipient.id, limit=100)
        assert initial.items == ()
        assert initial.has_more is False
        assert initial.next_cursor is None

        only = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        session.commit()
        terminal = _query(session, recipient.id, limit=100)
        assert terminal.next_cursor is not None

        empty_continuation = _query(
            session, recipient.id, cursor=terminal.next_cursor, limit=100
        )
        assert empty_continuation.items == ()
        assert empty_continuation.has_more is False
        assert empty_continuation.next_cursor == terminal.next_cursor
        assert only.id == terminal.items[0].capsule_id


def test_cross_user_draft_aborted_excluded_and_ciphertext_synced_included(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        other, other_bundle = _seed_user(session, "other")
        available = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
        )
        synced = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=1),
            delivery_status=RecipientDeliveryStatus.CIPHERTEXT_SYNCED,
            photo_count=5,
        )
        _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=other,
            recipient_bundle=other_bundle,
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
        page = _query(session, recipient.id, limit=10)
        assert [item.capsule_id for item in page.items] == [available.id, synced.id]
        assert page.has_more is False
        assert {blob.kind for blob in page.items[1].blobs} == {
            CapsuleBlobKind.RECOGNITION_MANIFEST,
            CapsuleBlobKind.CONTENT_MANIFEST,
            CapsuleBlobKind.PHOTO,
        }
        assert [blob.ordinal for blob in page.items[1].blobs if blob.kind is CapsuleBlobKind.PHOTO] == [
            0,
            1,
            2,
            3,
            4,
        ]
        other_page = _query(session, other.id, limit=10)
        assert len(other_page.items) == 1
        assert other_page.items[0].recipient_user_id == other.id
        assert draft.id not in {item.capsule_id for item in page.items}
        assert aborted.id not in {item.capsule_id for item in page.items}
        sender_page = _query(session, sender.id, limit=10)
        assert sender_page.items == ()


def test_snapshots_are_immutable_and_omit_forbidden_fields(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        sender.email_normalized = f"{_EMAIL_CANARY}@example.com"
        recipient.handle_normalized = _HANDLE_CANARY[:30]
        recipient.handle_display = _HANDLE_CANARY[:30]
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
        page = _query(session, recipient.id, limit=10)
        item = page.items[0]
        assert type(page) is IncomingCapsulePage
        assert type(item) is IncomingCapsuleSnapshot
        assert type(item.envelope) is IncomingEnvelopeSnapshot
        assert type(item.blobs[0]) is IncomingBlobSnapshot
        assert getattr(item, "_sa_instance_state", None) is None
        assert not isinstance(item, Capsule)
        assert not isinstance(item.envelope, CapsuleEnvelope)
        assert set(IncomingCapsuleSnapshot.__dataclass_fields__) == {
            "capsule_id",
            "sender_user_id",
            "recipient_user_id",
            "sender_key_bundle_id",
            "recipient_key_bundle_id",
            "protocol_version",
            "ready_at",
            "signed_statement",
            "signed_statement_sha256",
            "publish_signature",
            "envelope",
            "blobs",
        }
        assert set(IncomingEnvelopeSnapshot.__dataclass_fields__) == {
            "recipient_key_bundle_id",
            "ciphertext",
            "ciphertext_size",
            "ciphertext_sha256",
        }
        assert set(IncomingBlobSnapshot.__dataclass_fields__) == {
            "blob_id",
            "kind",
            "ordinal",
            "expected_ciphertext_size",
            "expected_ciphertext_sha256",
        }
        assert set(IncomingCapsulePage.__dataclass_fields__) == {
            "items",
            "has_more",
            "next_cursor",
        }
        rendered = repr(page) + str(page) + repr(item) + repr(item.envelope) + repr(item.blobs)
        for secret in (
            _EMAIL_CANARY,
            _HANDLE_CANARY,
            _OBJECT_KEY_CANARY,
            str(capsule.id),
            "object_key",
            "email",
            "handle",
            "note",
            "place",
            "track",
            "thumbnail",
            "available_at",
            "ciphertext_synced_at",
        ):
            assert secret not in rendered
        assert not hasattr(item, "object_key")
        assert not hasattr(item.blobs[0], "object_key")
        assert item.capsule_id == capsule.id
        assert item.signed_statement == b"signed-statement"
        blob_row = session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule.id)).first()
        assert blob_row is not None
        assert _OBJECT_KEY_CANARY in blob_row.object_key


def test_missing_mismatched_envelope_delivery_fail_whole_page(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        other, other_bundle = _seed_user(session, "other")
        missing_envelope = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            with_envelope=False,
        )
        valid = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=1),
        )
        session.commit()
        _assert_error(lambda: _query(session, recipient.id, limit=10), "INTERNAL_ERROR")
        session.delete(session.get(Capsule, missing_envelope.id))
        session.flush()
        ok = _query(session, recipient.id, limit=10)
        assert [item.capsule_id for item in ok.items] == [valid.id]

        missing_delivery = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=2),
            with_delivery=False,
        )
        session.commit()
        _assert_error(lambda: _query(session, recipient.id, limit=10), "INTERNAL_ERROR")
        session.delete(session.get(Capsule, missing_delivery.id))
        session.flush()

        mismatched = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=3),
            envelope_recipient_id=other.id,
            envelope_key_bundle_id=other_bundle.id,
        )
        session.commit()
        _assert_error(lambda: _query(session, recipient.id, limit=10), "INTERNAL_ERROR")
        assert mismatched.id is not None


def test_malformed_blob_set_fails_whole_page(session_factory):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        valid = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW + timedelta(seconds=1),
        )
        gapped = _add_incoming_ready(
            session,
            sender=sender,
            sender_bundle=sender_bundle,
            recipient=recipient,
            recipient_bundle=recipient_bundle,
            ready_at=_NOW,
            photo_ordinals=(0, 1, 3),
        )
        session.commit()
        _assert_error(lambda: _query(session, recipient.id, limit=10), "INTERNAL_ERROR")
        for blob in session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == gapped.id)):
            session.delete(blob)
        session.delete(session.get(CapsuleEnvelope, gapped.id))
        session.delete(session.get(RecipientDeliveryState, (recipient.id, gapped.id)))
        session.delete(session.get(Capsule, gapped.id))
        session.flush()
        short = _add_incoming_ready(
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
        _assert_error(lambda: _query(session, recipient.id, limit=10), "INTERNAL_ERROR")
        assert short.id is not None
        assert valid.id is not None


def test_caller_owns_transaction_and_queries_are_bounded(session_factory, monkeypatch):
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        for index in range(3):
            _add_incoming_ready(
                session,
                sender=sender,
                sender_bundle=sender_bundle,
                recipient=recipient,
                recipient_bundle=recipient_bundle,
                ready_at=_NOW + timedelta(seconds=index),
            )
        session.commit()
        statements: list[str] = []

        def _on_execute(orm_execute_state) -> None:
            statements.append(str(orm_execute_state.statement))

        event.listen(session, "do_orm_execute", _on_execute)
        try:
            _forbid_commit_rollback(session, monkeypatch)
            page = _query(session, recipient.id, limit=2)
        finally:
            event.remove(session, "do_orm_execute", _on_execute)
        assert len(page.items) == 2
        assert page.has_more is True
        assert len(statements) == 2
        joined = " ".join(statements).lower()
        assert "object_key" not in joined
        blob_queries = [statement for statement in statements if "capsule_blobs" in statement.lower()]
        assert len(blob_queries) == 1
