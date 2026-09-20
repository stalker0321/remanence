"""Service tests for pre-body upload authorization against PostgreSQL."""

import hashlib
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.upload_preflight_service import (
    CapsuleUploadPreflight,
    CapsuleUploadPreflightError,
    CapsuleUploadPreflightService,
)
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


_NOW = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)


def _digest(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def _new_user(session, label: str, *, disabled: bool = False) -> tuple[User, UserKeyBundle]:
    suffix = uuid4().hex
    user = User(
        id=uuid4(),
        email_normalized=f"{label}-{suffix}@example.com",
        handle_normalized=f"{label}{suffix[:20]}",
        handle_display=f"{label}{suffix[:20]}",
        disabled_at=_NOW if disabled else None,
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


def _seed(
    session,
    payload: bytes = b"declared-ciphertext",
    *,
    state: CapsuleState = CapsuleState.DRAFT,
    kind: CapsuleBlobKind = CapsuleBlobKind.PHOTO,
    expires_at: datetime = _NOW + timedelta(days=7),
    created_at: datetime = _NOW,
):
    sender, sender_bundle = _new_user(session, "sender")
    recipient, recipient_bundle = _new_user(session, "recipient")
    is_ready = state is CapsuleState.READY
    capsule = Capsule(
        id=uuid4(),
        sender_user_id=sender.id,
        recipient_user_id=recipient.id,
        sender_key_bundle_id=sender_bundle.id,
        recipient_key_bundle_id=recipient_bundle.id,
        protocol_version=1,
        state=state,
        signed_statement=b"signed-statement" if is_ready else None,
        signed_statement_sha256=_digest(b"signed-statement") if is_ready else None,
        publish_signature=b"p" * 69 if is_ready else None,
        created_at=created_at,
        ready_at=_NOW if is_ready else None,
        draft_expires_at=expires_at,
    )
    session.add(capsule)
    session.flush()
    blob = CapsuleBlob(
        id=uuid4(),
        capsule_id=capsule.id,
        kind=kind,
        ordinal=0 if kind is CapsuleBlobKind.PHOTO else None,
        object_key=f"capsules/{capsule.id}/{uuid4()}.blob",
        expected_ciphertext_size=len(payload),
        expected_ciphertext_sha256=_digest(payload),
        state=CapsuleBlobState.DECLARED,
    )
    session.add(blob)
    session.flush()
    session.commit()
    return sender, capsule, blob, payload


def _preflight(session, sender, capsule, blob, payload, **overrides):
    service = CapsuleUploadPreflightService(session)
    arguments = {
        "authenticated_sender_user_id": sender.id,
        "capsule_id": capsule.id,
        "blob_id": blob.id,
        "declared_size": len(payload),
        "declared_sha256_hex": _digest(payload).hex(),
        "now": _NOW,
    }
    arguments.update(overrides)
    return service.preflight(**arguments)


def _assert_error(call, code: str) -> CapsuleUploadPreflightError:
    with pytest.raises(CapsuleUploadPreflightError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "capsule blob upload preflight failed"
    assert repr(caught.value) == f"CapsuleUploadPreflightError(code={code!r})"
    return caught.value


def test_success_returns_declared_artifact_expectations(session_factory) -> None:
    with session_factory() as session:
        sender, capsule, blob, payload = _seed(session)
        result = _preflight(session, sender, capsule, blob, payload)
        assert isinstance(result, CapsuleUploadPreflight)
        assert result.capsule_id == capsule.id
        assert result.blob_id == blob.id
        assert result.object_key == blob.object_key
        assert result.expected_size == len(payload)
        assert result.expected_sha256_hex == _digest(payload).hex()
        assert result.artifact_max_bytes == LIMITS_V1.encrypted_photo_max_ciphertext_bytes
        assert result.state is CapsuleBlobState.DECLARED
        assert "declared-ciphertext" not in repr(result)


def test_manifest_uses_its_own_artifact_cap(session_factory) -> None:
    with session_factory() as session:
        sender, capsule, blob, payload = _seed(
            session, kind=CapsuleBlobKind.RECOGNITION_MANIFEST
        )
        result = _preflight(session, sender, capsule, blob, payload)
        assert result.artifact_max_bytes == LIMITS_V1.recognition_manifest_max_ciphertext_bytes
        assert result.artifact_max_bytes < LIMITS_V1.encrypted_photo_max_ciphertext_bytes


def test_unknown_foreign_aborted_and_expired_capsules_are_rejected(session_factory) -> None:
    with session_factory() as session:
        sender, capsule, blob, payload = _seed(session)
        _assert_error(
            lambda: _preflight(session, sender, capsule, blob, payload, capsule_id=uuid4()),
            "CAPSULE_NOT_FOUND",
        )
        intruder, _bundle = _new_user(session, "intruder")
        session.commit()
        _assert_error(
            lambda: _preflight(session, intruder, capsule, blob, payload),
            "CAPSULE_NOT_FOUND",
        )

    with session_factory() as session:
        sender, capsule, blob, payload = _seed(session, state=CapsuleState.ABORTED)
        _assert_error(lambda: _preflight(session, sender, capsule, blob, payload), "CAPSULE_STATE_INVALID")

    with session_factory() as session:
        sender, capsule, blob, payload = _seed(
            session,
            created_at=_NOW - timedelta(days=2),
            expires_at=_NOW - timedelta(seconds=1),
        )
        _assert_error(lambda: _preflight(session, sender, capsule, blob, payload), "DRAFT_EXPIRED")


def test_undeclared_blob_and_header_mismatches_are_rejected(session_factory) -> None:
    with session_factory() as session:
        sender, capsule, blob, payload = _seed(session)
        _assert_error(
            lambda: _preflight(session, sender, capsule, blob, payload, blob_id=uuid4()),
            "BLOB_NOT_DECLARED",
        )
        _assert_error(
            lambda: _preflight(
                session, sender, capsule, blob, payload, declared_size=len(payload) + 1
            ),
            "BLOB_SIZE_INVALID",
        )
        _assert_error(
            lambda: _preflight(
                session,
                sender,
                capsule,
                blob,
                payload,
                declared_sha256_hex=_digest(b"other").hex(),
            ),
            "BLOB_HASH_MISMATCH",
        )


def test_disabled_account_is_rejected(session_factory) -> None:
    with session_factory() as session:
        sender, capsule, blob, payload = _seed(session)
        sender.disabled_at = _NOW
        session.commit()
        _assert_error(lambda: _preflight(session, sender, capsule, blob, payload), "AUTH_INVALID")


def test_invalid_arguments_are_redacted(session_factory) -> None:
    with session_factory() as session:
        sender, capsule, blob, payload = _seed(session)
        service = CapsuleUploadPreflightService(session)
        _assert_error(
            lambda: service.preflight(
                authenticated_sender_user_id="not-a-uuid",  # type: ignore[arg-type]
                capsule_id=capsule.id,
                blob_id=blob.id,
                declared_size=len(payload),
                declared_sha256_hex=_digest(payload).hex(),
                now=_NOW,
            ),
            "VALIDATION_FAILED",
        )
        _assert_error(
            lambda: service.preflight(
                authenticated_sender_user_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                declared_size=len(payload),
                declared_sha256_hex=_digest(payload).hex(),
                now="not-a-datetime",  # type: ignore[arg-type]
            ),
            "VALIDATION_FAILED",
        )
        _assert_error(
            lambda: service.preflight(
                authenticated_sender_user_id=sender.id,
                capsule_id=capsule.id,
                blob_id=blob.id,
                declared_size="10",  # type: ignore[arg-type]
                declared_sha256_hex=_digest(payload).hex(),
                now=_NOW,
            ),
            "BLOB_SIZE_INVALID",
        )


def test_ready_capsule_is_not_uploadable(session_factory) -> None:
    with session_factory() as session:
        sender, capsule, blob, payload = _seed(session, state=CapsuleState.READY)
        _assert_error(lambda: _preflight(session, sender, capsule, blob, payload), "CAPSULE_STATE_INVALID")
