"""PostgreSQL tests for the capsule finalize transaction."""

from __future__ import annotations

import hashlib
import io
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from io import BytesIO
from threading import Barrier
from typing import Any
from uuid import UUID, uuid4

import pytest
import tink
from sqlalchemy import delete, func, inspect, select, text, update
from sqlalchemy.exc import SQLAlchemyError
from tink import signature, tink_config
from tink.proto import hpke_pb2, tink_pb2

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.finalize_service import (
    CapsuleFinalizeEnvelope,
    CapsuleFinalizeError,
    CapsuleFinalizeResult,
    CapsuleFinalizeService,
)
from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.signature_service import PublishSignatureVerificationService
from remanence.protocol.v1 import remanence_v1_pb2 as protocol_pb2
from remanence.storage import BlobInfo, LocalFileBlobStore
from remanence.users.key_bundle_validation import (
    HPKE_PUBLIC_KEY_TYPE_URL,
    SUPPORTED_KEY_BUNDLE_SUITE,
)
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


_NOW = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
_DOMAIN = b"postmark/publish/v1"
_NOTE_MARKER = b"NOTE-CANARY-finalize-s15"


@pytest.fixture(scope="module", autouse=True)
def _register_tink() -> None:
    tink_config.register()


def _digest(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def _assert_error(call, code: str) -> CapsuleFinalizeError:
    with pytest.raises(CapsuleFinalizeError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "capsule finalize failed"
    assert repr(caught.value) == f"CapsuleFinalizeError(code={code!r})"
    return caught.value


def _binary_public(handle: tink.KeysetHandle) -> bytes:
    output = io.BytesIO()
    handle.write_no_secret(tink.BinaryKeysetWriter(output))
    return output.getvalue()


def _signing_pair() -> tuple[tink.KeysetHandle, bytes]:
    private = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
    return private, _binary_public(private.public_keyset_handle())


def _hpke_public_keyset() -> bytes:
    public = hpke_pb2.HpkePublicKey(
        version=0,
        params=hpke_pb2.HpkeParams(
            kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256,
            kdf=hpke_pb2.HKDF_SHA256,
            aead=hpke_pb2.AES_256_GCM,
        ),
        public_key=bytes(range(32)),
    )
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=tink_pb2.KeyData(
                type_url=HPKE_PUBLIC_KEY_TYPE_URL,
                value=public.SerializeToString(),
                key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC,
            ),
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset.SerializeToString()


def _seed_user(session, label: str, *, signing_public: bytes) -> tuple[User, UserKeyBundle]:
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
        encryption_public_keyset=_hpke_public_keyset(),
        signing_public_keyset=signing_public,
        suite=SUPPORTED_KEY_BUNDLE_SUITE,
        protocol_version=1,
        status=KeyBundleStatus.ACTIVE,
    )
    session.add(bundle)
    session.flush()
    return user, bundle


def _payloads() -> dict[CapsuleBlobKind | int, bytes]:
    return {
        CapsuleBlobKind.RECOGNITION_MANIFEST: b"recognition-ciphertext",
        CapsuleBlobKind.CONTENT_MANIFEST: b"content-ciphertext",
        0: b"photo-0-ciphertext",
        1: b"photo-1-ciphertext",
        2: b"photo-2-ciphertext",
    }


def _add_draft(
    session,
    *,
    sender: User,
    sender_bundle: UserKeyBundle,
    recipient: User,
    recipient_bundle: UserKeyBundle,
    payloads: dict,
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
        created_at=_NOW,
        ready_at=None,
        draft_expires_at=_NOW + timedelta(days=7),
    )
    session.add(capsule)
    session.flush()
    specs = (
        (CapsuleBlobKind.RECOGNITION_MANIFEST, None),
        (CapsuleBlobKind.CONTENT_MANIFEST, None),
        (CapsuleBlobKind.PHOTO, 0),
        (CapsuleBlobKind.PHOTO, 1),
        (CapsuleBlobKind.PHOTO, 2),
    )
    for kind, ordinal in specs:
        body = payloads[kind if ordinal is None else ordinal]
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
    session.flush()
    return capsule


def _store_all(session, store: LocalFileBlobStore, capsule: Capsule, payloads: dict) -> None:
    blobs = list(session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule.id)))
    for blob in blobs:
        body = payloads[blob.kind if blob.ordinal is None else blob.ordinal]
        store.put(
            blob.object_key,
            BytesIO(body),
            expected_size=len(body),
            expected_sha256=_digest(body).hex(),
        )
        blob.state = CapsuleBlobState.STORED
    session.flush()


def _protocol_blobs(session, capsule: Capsule) -> list[CapsuleBlob]:
    blobs = list(session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule.id)))
    photos = {blob.ordinal: blob for blob in blobs if blob.kind is CapsuleBlobKind.PHOTO}
    return [
        next(blob for blob in blobs if blob.kind is CapsuleBlobKind.RECOGNITION_MANIFEST),
        next(blob for blob in blobs if blob.kind is CapsuleBlobKind.CONTENT_MANIFEST),
        photos[0],
        photos[1],
        photos[2],
    ]


class _SpyStore:
    def __init__(self, delegate: LocalFileBlobStore) -> None:
        self._delegate = delegate
        self.stat_keys: list[str] = []

    def stat(self, key: str) -> BlobInfo:
        self.stat_keys.append(key)
        return self._delegate.stat(key)

    def put(self, *args: Any, **kwargs: Any) -> BlobInfo:
        return self._delegate.put(*args, **kwargs)

    def delete(self, key: str) -> None:
        return self._delegate.delete(key)


def _forbid_commit_rollback(session, monkeypatch: pytest.MonkeyPatch) -> None:
    def forbidden(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("finalize service must not commit or rollback")

    monkeypatch.setattr(session, "commit", forbidden)
    monkeypatch.setattr(session, "rollback", forbidden)


def _statement_bytes(
    capsule: Capsule,
    blobs: list[CapsuleBlob],
    *,
    recipient_key_bundle_id: UUID | None = None,
) -> bytes:
    recipient_key = recipient_key_bundle_id or capsule.recipient_key_bundle_id
    statement = protocol_pb2.PublishStatement(
        protocol_version=capsule.protocol_version,
        capsule_id=capsule.id.bytes,
        sender_user_id=capsule.sender_user_id.bytes,
        recipient_user_id=capsule.recipient_user_id.bytes,
        sender_key_bundle_id=capsule.sender_key_bundle_id.bytes,
        recipient_key_bundle_id=recipient_key.bytes,
        created_at_epoch_seconds=int(capsule.created_at.timestamp()),
    )
    for blob in blobs:
        statement.artifacts.add(
            blob_id=blob.id.bytes,
            kind=getattr(protocol_pb2.ArtifactKind, blob.kind.value),
            ordinal=-1 if blob.ordinal is None else blob.ordinal,
            ciphertext_size=blob.expected_ciphertext_size,
            ciphertext_sha256=blob.expected_ciphertext_sha256,
        )
    return statement.SerializeToString(deterministic=True)


def _sign(private: tink.KeysetHandle, statement: bytes) -> bytes:
    return private.primitive(signature.PublicKeySign).sign(_DOMAIN + statement)


def _envelope(recipient_key_bundle_id: UUID, payload: bytes = b"hpke-envelope") -> CapsuleFinalizeEnvelope:
    return CapsuleFinalizeEnvelope(
        recipient_key_bundle_id=recipient_key_bundle_id,
        ciphertext=payload,
        ciphertext_size=len(payload),
        ciphertext_sha256=_digest(payload),
    )


def _finalize(session, store, *, sender_id: UUID, capsule: Capsule, statement: bytes, signature_bytes: bytes, envelope: CapsuleFinalizeEnvelope, now: datetime = _NOW):
    return CapsuleFinalizeService(session, store).finalize(
        authenticated_sender_user_id=sender_id,
        capsule_id=capsule.id,
        statement=statement,
        signature=signature_bytes,
        sender_key_bundle_id=capsule.sender_key_bundle_id,
        envelope=envelope,
        now=now,
    )


def _ready_world(session, tmp_path, *, self_send: bool = False):
    payloads = _payloads()
    sender_private, sender_public = _signing_pair()
    sender, sender_bundle = _seed_user(session, "sender", signing_public=sender_public)
    if self_send:
        recipient, recipient_bundle = sender, sender_bundle
    else:
        _recipient_private, recipient_public = _signing_pair()
        recipient, recipient_bundle = _seed_user(session, "recipient", signing_public=recipient_public)
    capsule = _add_draft(
        session,
        sender=sender,
        sender_bundle=sender_bundle,
        recipient=recipient,
        recipient_bundle=recipient_bundle,
        payloads=payloads,
    )
    inner_store = LocalFileBlobStore(tmp_path / "blobs")
    _store_all(session, inner_store, capsule, payloads)
    store = _SpyStore(inner_store)
    session.commit()
    session.refresh(capsule)
    blobs = _protocol_blobs(session, capsule)
    statement = _statement_bytes(capsule, blobs)
    signature_bytes = _sign(sender_private, statement)
    envelope = _envelope(recipient_bundle.id, _NOTE_MARKER)
    return {
        "store": store,
        "sender": sender,
        "sender_bundle": sender_bundle,
        "sender_private": sender_private,
        "recipient": recipient,
        "recipient_bundle": recipient_bundle,
        "capsule": capsule,
        "payloads": payloads,
        "statement": statement,
        "signature": signature_bytes,
        "envelope": envelope,
    }


def test_happy_path_persists_ready_envelope_and_delivery(session_factory, tmp_path, monkeypatch):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        _forbid_commit_rollback(session, monkeypatch)
        result = _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        monkeypatch.undo()
        session.commit()
        assert isinstance(result, CapsuleFinalizeResult)
        assert result.is_replay is False
        assert result.state is CapsuleState.READY
        assert result.ready_at == _NOW
        assert repr(result) == "CapsuleFinalizeResult(<redacted>)"
        assert world["statement"].hex() not in repr(result)

        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule.state is CapsuleState.READY
        assert capsule.signed_statement == world["statement"]
        assert capsule.signed_statement_sha256 == _digest(world["statement"])
        assert capsule.publish_signature == world["signature"]
        assert len(capsule.publish_signature) == 69
        assert capsule.ready_at == _NOW
        envelope = session.get(CapsuleEnvelope, capsule.id)
        assert envelope is not None
        assert envelope.recipient_user_id == world["recipient"].id
        assert envelope.recipient_key_bundle_id == world["recipient_bundle"].id
        assert envelope.ciphertext == _NOTE_MARKER
        delivery = session.get(RecipientDeliveryState, (world["recipient"].id, capsule.id))
        assert delivery is not None
        assert delivery.state is RecipientDeliveryStatus.AVAILABLE
        assert delivery.ciphertext_synced_at is None
        assert delivery.available_at == capsule.ready_at == _NOW
        assert session.scalar(select(func.count()).select_from(CapsuleEnvelope)) == 1


def test_self_send_reaches_ready(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path, self_send=True)
        result = _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        session.commit()
        assert result.state is CapsuleState.READY
        assert world["capsule"].sender_user_id == world["capsule"].recipient_user_id


def test_leftover_declared_blob_does_not_lookup_keys(session_factory, tmp_path, monkeypatch):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        first = session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == world["capsule"].id)).first()
        first.state = CapsuleBlobState.DECLARED
        session.commit()
        lookups: list[object] = []

        def forbidden(self, verified, signature_bytes):
            lookups.append(verified)
            raise AssertionError("key lookup")

        monkeypatch.setattr(PublishSignatureVerificationService, "verify", forbidden)
        world["store"].stat_keys.clear()
        error = _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "CAPSULE_STATE_INVALID",
        )
        session.rollback()
        assert lookups == []
        assert world["store"].stat_keys == []
        assert str(world["capsule"].id) not in repr(error)
        assert world["statement"].hex() not in f"{error!s} {error!r}"
        assert session.get(Capsule, world["capsule"].id).state is CapsuleState.DRAFT
        assert session.get(CapsuleEnvelope, world["capsule"].id) is None


def test_stat_miss_and_mismatch_fail_closed(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        blob = session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == world["capsule"].id)).first()
        world["store"].delete(blob.object_key)
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "INTERNAL_ERROR",
        )
        session.rollback()

    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        blob = session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == world["capsule"].id)).first()
        other = b"different-object-bytes"
        world["store"].delete(blob.object_key)
        world["store"].put(
            blob.object_key,
            BytesIO(other),
            expected_size=len(other),
            expected_sha256=_digest(other).hex(),
        )
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "BLOB_CONFLICT",
        )
        session.rollback()
        assert session.get(Capsule, world["capsule"].id).state is CapsuleState.DRAFT


def test_stale_recipient_first_attempt_does_not_mutate(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        old_id = world["recipient_bundle"].id
        world["recipient_bundle"].status = KeyBundleStatus.RETIRED
        world["recipient_bundle"].retired_at = _NOW
        replacement = UserKeyBundle(
            id=uuid4(),
            user_id=world["recipient"].id,
            encryption_public_keyset=_hpke_public_keyset(),
            signing_public_keyset=_signing_pair()[1],
            suite=SUPPORTED_KEY_BUNDLE_SUITE,
            protocol_version=1,
            status=KeyBundleStatus.ACTIVE,
        )
        session.add(replacement)
        session.commit()
        hashes_before = [
            blob.expected_ciphertext_sha256
            for blob in session.scalars(
                select(CapsuleBlob).where(CapsuleBlob.capsule_id == world["capsule"].id)
            )
        ]
        world["store"].stat_keys.clear()
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "RECIPIENT_KEY_STALE",
        )
        session.rollback()
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule.state is CapsuleState.DRAFT
        assert capsule.recipient_key_bundle_id == old_id
        assert capsule.signed_statement is None
        assert not inspect(capsule).modified
        assert session.get(CapsuleEnvelope, capsule.id) is None
        assert session.get(RecipientDeliveryState, (world["recipient"].id, capsule.id)) is None
        assert world["store"].stat_keys == []
        hashes_after = [
            blob.expected_ciphertext_sha256
            for blob in session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule.id))
        ]
        assert hashes_after == hashes_before


def test_stale_retry_updates_only_recipient_key_and_keeps_artifact_bytes(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        old_id = world["recipient_bundle"].id
        blob_before = [
            (blob.id, blob.expected_ciphertext_size, blob.expected_ciphertext_sha256, blob.object_key)
            for blob in session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == world["capsule"].id))
        ]
        world["recipient_bundle"].status = KeyBundleStatus.RETIRED
        world["recipient_bundle"].retired_at = _NOW
        new_bundle = UserKeyBundle(
            id=uuid4(),
            user_id=world["recipient"].id,
            encryption_public_keyset=_hpke_public_keyset(),
            signing_public_keyset=_signing_pair()[1],
            suite=SUPPORTED_KEY_BUNDLE_SUITE,
            protocol_version=1,
            status=KeyBundleStatus.ACTIVE,
        )
        session.add(new_bundle)
        session.commit()
        session.refresh(world["capsule"])
        assert world["capsule"].recipient_key_bundle_id == old_id
        statement = _statement_bytes(
            world["capsule"],
            _protocol_blobs(session, world["capsule"]),
            recipient_key_bundle_id=new_bundle.id,
        )
        signature_bytes = _sign(world["sender_private"], statement)
        envelope = _envelope(new_bundle.id, b"re-enveloped")
        result = _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=statement,
            signature_bytes=signature_bytes,
            envelope=envelope,
        )
        session.commit()
        assert result.state is CapsuleState.READY
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule.recipient_key_bundle_id == new_bundle.id
        assert capsule.sender_key_bundle_id == world["sender_bundle"].id
        after = [
            (blob.id, blob.expected_ciphertext_size, blob.expected_ciphertext_sha256, blob.object_key)
            for blob in session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule.id))
        ]
        assert after == blob_before
        stored_envelope = session.get(CapsuleEnvelope, capsule.id)
        assert stored_envelope.recipient_key_bundle_id == new_bundle.id
        assert stored_envelope.ciphertext == b"re-enveloped"


def test_retired_sender_bundle_is_accepted(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        world["sender_bundle"].status = KeyBundleStatus.RETIRED
        world["sender_bundle"].retired_at = _NOW
        session.commit()
        result = _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        session.commit()
        assert result.state is CapsuleState.READY


def test_revoked_sender_and_unrelated_user_are_redacted(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        world["sender_bundle"].status = KeyBundleStatus.REVOKED
        session.commit()
        error = _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "KEY_BUNDLE_REVOKED",
        )
        session.rollback()
        assert world["signature"].hex() not in repr(error)

        other, _ = _seed_user(session, "other", signing_public=_signing_pair()[1])
        object_keys = [
            blob.object_key
            for blob in session.scalars(
                select(CapsuleBlob).where(CapsuleBlob.capsule_id == world["capsule"].id)
            )
        ]
        error = _assert_error(
            lambda: CapsuleFinalizeService(session, world["store"]).finalize(
                authenticated_sender_user_id=other.id,
                capsule_id=world["capsule"].id,
                statement=world["statement"],
                signature=world["signature"],
                sender_key_bundle_id=world["sender_bundle"].id,
                envelope=world["envelope"],
                now=_NOW,
            ),
            "CAPSULE_NOT_FOUND",
        )
        rendered = f"{error!s} {error!r}"
        assert str(world["capsule"].id) not in rendered
        assert world["statement"].hex() not in rendered
        assert world["signature"].hex() not in rendered
        assert _NOTE_MARKER.decode() not in rendered
        for key in object_keys:
            assert key not in rendered


def test_identical_ready_replay_and_finalize_conflict(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        first = _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        session.commit()
        replay = _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        assert replay.is_replay is True
        assert replay.ready_at == first.ready_at
        assert replay.recipient_key_bundle_id == first.recipient_key_bundle_id
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=_envelope(world["recipient_bundle"].id, b"other-envelope"),
            ),
            "FINALIZE_CONFLICT",
        )
        session.rollback()
        assert session.get(CapsuleEnvelope, world["capsule"].id).ciphertext == _NOTE_MARKER


def test_flush_failure_rolls_back_to_unrouteable_draft(session_factory, tmp_path, monkeypatch):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        original_key = world["capsule"].recipient_key_bundle_id
        original_flush = session.flush

        def fail_flush(*args: Any, **kwargs: Any) -> None:
            raise SQLAlchemyError("private flush detail")

        monkeypatch.setattr(session, "flush", fail_flush)
        _forbid_commit_rollback(session, monkeypatch)
        error = _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "INTERNAL_ERROR",
        )
        assert "private flush detail" not in f"{error!s} {error!r}"
        restored = world["capsule"]
        assert restored.state is CapsuleState.DRAFT
        assert restored.recipient_key_bundle_id == original_key
        assert restored.signed_statement is None
        assert restored.publish_signature is None
        assert restored.ready_at is None
        monkeypatch.undo()
        session.rollback()
        capsule = session.get(Capsule, world["capsule"].id)
        assert capsule.state is CapsuleState.DRAFT
        assert session.get(CapsuleEnvelope, capsule.id) is None
        assert session.get(RecipientDeliveryState, (world["recipient"].id, capsule.id)) is None
        for blob in session.scalars(select(CapsuleBlob).where(CapsuleBlob.capsule_id == capsule.id)):
            assert blob.state is CapsuleBlobState.STORED
            info = world["store"].stat(blob.object_key)
            assert info.size == blob.expected_ciphertext_size


def test_concurrent_finalize_versus_abort_style_lock(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        capsule_id = world["capsule"].id
        session.commit()
    barrier = Barrier(2)

    def finalize_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            capsule = session.get(Capsule, capsule_id)
            barrier.wait(timeout=10)
            try:
                result = CapsuleFinalizeService(session, world["store"]).finalize(
                    authenticated_sender_user_id=world["sender"].id,
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

    def abort_worker():
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            session.execute(select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id))))
            capsule = session.get(Capsule, capsule_id)
            if capsule.state is CapsuleState.DRAFT:
                session.execute(
                    update(Capsule).where(Capsule.id == capsule_id).values(state=CapsuleState.ABORTED)
                )
                session.commit()
                return "aborted"
            session.commit()
            return capsule.state.value

    with ThreadPoolExecutor(max_workers=2) as executor:
        finalize_future = executor.submit(finalize_worker)
        abort_future = executor.submit(abort_worker)
        finalize_outcome = finalize_future.result(timeout=20)
        abort_outcome = abort_future.result(timeout=20)

    with session_factory() as session:
        capsule = session.get(Capsule, capsule_id)
        envelope_count = session.scalar(select(func.count()).select_from(CapsuleEnvelope))
        if abort_outcome == "aborted":
            assert capsule.state is CapsuleState.ABORTED
            assert capsule.ready_at is None
            assert envelope_count == 0
            assert finalize_outcome == ("error", "CAPSULE_STATE_INVALID")
        else:
            assert abort_outcome == "READY"
            assert capsule.state is CapsuleState.READY
            assert envelope_count == 1
            assert finalize_outcome[0] == "ready"


def test_expired_draft_and_invalid_envelope_are_redacted(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
                now=_NOW + timedelta(days=8),
            ),
            "DRAFT_EXPIRED",
        )
        session.rollback()
        bad = CapsuleFinalizeEnvelope(
            recipient_key_bundle_id=world["recipient_bundle"].id,
            ciphertext=b"x",
            ciphertext_size=1,
            ciphertext_sha256=_digest(b"y"),
        )
        error = _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=bad,
            ),
            "ENVELOPE_INVALID",
        )
        assert _NOTE_MARKER not in bytes(repr(error), "utf-8")
        session.rollback()
        assert session.get(Capsule, world["capsule"].id).state is CapsuleState.DRAFT


def test_fractional_now_is_rejected(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
                now=_NOW.replace(microsecond=1),
            ),
            "VALIDATION_FAILED",
        )


def test_invalid_statement_with_active_candidate_does_not_lookup_stat_or_dirty(
    session_factory, tmp_path, monkeypatch
):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        world["recipient_bundle"].status = KeyBundleStatus.RETIRED
        world["recipient_bundle"].retired_at = _NOW
        active = UserKeyBundle(
            id=uuid4(),
            user_id=world["recipient"].id,
            encryption_public_keyset=_hpke_public_keyset(),
            signing_public_keyset=_signing_pair()[1],
            suite=SUPPORTED_KEY_BUNDLE_SUITE,
            protocol_version=1,
            status=KeyBundleStatus.ACTIVE,
        )
        session.add(active)
        session.commit()
        lookups: list[object] = []

        def forbidden(self, verified, signature_bytes):
            lookups.append(verified)
            raise AssertionError("authoritative key lookup")

        monkeypatch.setattr(PublishSignatureVerificationService, "verify", forbidden)
        world["store"].stat_keys.clear()
        invalid_statement = world["statement"][:-1]
        envelope = _envelope(active.id, _NOTE_MARKER)
        error = _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=invalid_statement,
                signature_bytes=world["signature"],
                envelope=envelope,
            ),
            "STATEMENT_INVALID",
        )
        session.rollback()
        capsule = session.get(Capsule, world["capsule"].id)
        assert lookups == []
        assert world["store"].stat_keys == []
        assert capsule.state is CapsuleState.DRAFT
        assert capsule.recipient_key_bundle_id == world["recipient_bundle"].id
        assert not inspect(capsule).modified
        assert str(world["capsule"].id) not in repr(error)
        assert invalid_statement.hex() not in f"{error!s} {error!r}"


def test_ready_replay_preserves_ciphertext_synced_and_rejects_corrupt_stored_shape(
    session_factory, tmp_path
):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        session.commit()
        synced_at = _NOW + timedelta(seconds=30)
        session.execute(
            update(RecipientDeliveryState)
            .where(
                RecipientDeliveryState.capsule_id == world["capsule"].id,
                RecipientDeliveryState.recipient_user_id == world["recipient"].id,
            )
            .values(
                state=RecipientDeliveryStatus.CIPHERTEXT_SYNCED,
                ciphertext_synced_at=synced_at,
            )
        )
        session.commit()
        world["store"].stat_keys.clear()
        replay = _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        assert replay.is_replay is True
        assert world["store"].stat_keys == []
        delivery = session.get(
            RecipientDeliveryState, (world["recipient"].id, world["capsule"].id)
        )
        assert delivery.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED
        assert delivery.ciphertext_synced_at == synced_at

        session.execute(
            delete(RecipientDeliveryState).where(
                RecipientDeliveryState.capsule_id == world["capsule"].id
            )
        )
        session.commit()
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "INTERNAL_ERROR",
        )
        session.rollback()

        session.add(
            RecipientDeliveryState(
                recipient_user_id=world["recipient"].id,
                capsule_id=world["capsule"].id,
                state=RecipientDeliveryStatus.AVAILABLE,
                available_at=_NOW,
                ciphertext_synced_at=None,
            )
        )
        session.execute(
            update(Capsule)
            .where(Capsule.id == world["capsule"].id)
            .values(signed_statement_sha256=_digest(b"tampered-digest"))
        )
        session.commit()
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "INTERNAL_ERROR",
        )
        session.rollback()


def test_ready_replay_request_conflicts_and_envelope_hash_invariant(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        _finalize(
            session,
            world["store"],
            sender_id=world["sender"].id,
            capsule=world["capsule"],
            statement=world["statement"],
            signature_bytes=world["signature"],
            envelope=world["envelope"],
        )
        session.commit()

        conflicts = [
            {"statement": world["statement"] + b"\x00"},
            {"signature_bytes": world["signature"][:-1] + b"\x00"},
            {"envelope": _envelope(world["recipient_bundle"].id, b"other-envelope")},
            {
                "envelope": CapsuleFinalizeEnvelope(
                    recipient_key_bundle_id=uuid4(),
                    ciphertext=_NOTE_MARKER,
                    ciphertext_size=len(_NOTE_MARKER),
                    ciphertext_sha256=_digest(_NOTE_MARKER),
                )
            },
            {
                "envelope": CapsuleFinalizeEnvelope(
                    recipient_key_bundle_id=world["recipient_bundle"].id,
                    ciphertext=_NOTE_MARKER,
                    ciphertext_size=len(_NOTE_MARKER) + 1,
                    ciphertext_sha256=_digest(_NOTE_MARKER),
                )
            },
            {
                "envelope": CapsuleFinalizeEnvelope(
                    recipient_key_bundle_id=world["recipient_bundle"].id,
                    ciphertext=_NOTE_MARKER,
                    ciphertext_size=len(_NOTE_MARKER),
                    ciphertext_sha256=_digest(b"not-the-marker"),
                )
            },
        ]
        for override in conflicts:
            kwargs = {
                "sender_id": world["sender"].id,
                "capsule": world["capsule"],
                "statement": world["statement"],
                "signature_bytes": world["signature"],
                "envelope": world["envelope"],
            }
            kwargs.update(override)
            _assert_error(
                lambda kwargs=kwargs: _finalize(session, world["store"], **kwargs),
                "FINALIZE_CONFLICT",
            )
            session.rollback()

        other_bundle = uuid4()
        _assert_error(
            lambda: CapsuleFinalizeService(session, world["store"]).finalize(
                authenticated_sender_user_id=world["sender"].id,
                capsule_id=world["capsule"].id,
                statement=world["statement"],
                signature=world["signature"],
                sender_key_bundle_id=other_bundle,
                envelope=world["envelope"],
                now=_NOW,
            ),
            "FINALIZE_CONFLICT",
        )
        session.rollback()

        session.execute(
            update(CapsuleEnvelope)
            .where(CapsuleEnvelope.capsule_id == world["capsule"].id)
            .values(ciphertext_sha256=_digest(b"broken-stored-hash"))
        )
        session.commit()
        _assert_error(
            lambda: _finalize(
                session,
                world["store"],
                sender_id=world["sender"].id,
                capsule=world["capsule"],
                statement=world["statement"],
                signature_bytes=world["signature"],
                envelope=world["envelope"],
            ),
            "INTERNAL_ERROR",
        )


def test_concurrent_identical_and_conflicting_finalizers(session_factory, tmp_path):
    with session_factory() as session:
        world = _ready_world(session, tmp_path)
        capsule_id = world["capsule"].id
        session.commit()

    barrier = Barrier(2)

    def worker(envelope):
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier.wait(timeout=10)
            try:
                result = CapsuleFinalizeService(session, world["store"]).finalize(
                    authenticated_sender_user_id=world["sender"].id,
                    capsule_id=capsule_id,
                    statement=world["statement"],
                    signature=world["signature"],
                    sender_key_bundle_id=world["sender_bundle"].id,
                    envelope=envelope,
                    now=_NOW,
                )
                session.commit()
                return ("ready", result.is_replay)
            except CapsuleFinalizeError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [
            executor.submit(worker, world["envelope"]),
            executor.submit(worker, world["envelope"]),
        ]
        outcomes = sorted(future.result(timeout=20) for future in futures)
    assert outcomes == [("ready", False), ("ready", True)]

    with session_factory() as session:
        other_world = _ready_world(session, tmp_path)
        other_id = other_world["capsule"].id
        session.commit()
    barrier_conflict = Barrier(2)

    def conflicting(envelope):
        with session_factory() as session:
            session.execute(text("SET LOCAL lock_timeout = '5s'"))
            barrier_conflict.wait(timeout=10)
            try:
                result = CapsuleFinalizeService(session, other_world["store"]).finalize(
                    authenticated_sender_user_id=other_world["sender"].id,
                    capsule_id=other_id,
                    statement=other_world["statement"],
                    signature=other_world["signature"],
                    sender_key_bundle_id=other_world["sender_bundle"].id,
                    envelope=envelope,
                    now=_NOW,
                )
                session.commit()
                return ("ready", result.is_replay)
            except CapsuleFinalizeError as error:
                session.rollback()
                return ("error", error.code)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [
            executor.submit(conflicting, other_world["envelope"]),
            executor.submit(conflicting, _envelope(other_world["recipient_bundle"].id, b"conflict")),
        ]
        outcomes = {future.result(timeout=20) for future in futures}
    assert ("ready", False) in outcomes
    assert ("error", "FINALIZE_CONFLICT") in outcomes
    with session_factory() as session:
        assert session.scalar(
            select(func.count()).select_from(CapsuleEnvelope).where(CapsuleEnvelope.capsule_id == other_id)
        ) == 1
