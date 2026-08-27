"""PostgreSQL-backed tests for idempotent capsule draft creation."""

import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from threading import Barrier
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.draft_service import (
    CapsuleDraftService,
    CapsuleDraftServiceError,
)
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.locking import capsule_lock_key, idempotency_scope_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.schemas import CreateCapsuleDraftRequest
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


_NOW = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
_PROTOCOL_VERSION = LIMITS_V1.protocol_version
_SERVICE_HASH = bytes(range(32))


def _digest(value: int) -> str:
    return base64.urlsafe_b64encode(bytes([value]) * 32).decode("ascii").rstrip("=")


def _seed_user(
    session,
    label: str,
    *,
    disabled: bool = False,
    bundle_status: KeyBundleStatus = KeyBundleStatus.ACTIVE,
    bundle_protocol: int = _PROTOCOL_VERSION,
) -> tuple[User, UserKeyBundle]:
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
        protocol_version=bundle_protocol,
        status=bundle_status,
    )
    session.add(bundle)
    session.flush()
    return user, bundle


def _request(
    *,
    sender_bundle_id: UUID,
    recipient_user_id: UUID,
    recipient_bundle_id: UUID,
    capsule_id: UUID | None = None,
) -> CreateCapsuleDraftRequest:
    capsule_id = capsule_id or uuid4()
    return CreateCapsuleDraftRequest.model_validate(
        {
            "capsule_id": str(capsule_id),
            "recipient_user_id": str(recipient_user_id),
            "sender_key_bundle_id": str(sender_bundle_id),
            "recipient_key_bundle_id": str(recipient_bundle_id),
            "protocol_version": _PROTOCOL_VERSION,
            "blobs": [
                {
                    "blob_id": str(uuid4()),
                    "kind": "RECOGNITION_MANIFEST",
                    "ordinal": None,
                    "ciphertext_size": 100,
                    "ciphertext_sha256": _digest(1),
                },
                {
                    "blob_id": str(uuid4()),
                    "kind": "CONTENT_MANIFEST",
                    "ordinal": None,
                    "ciphertext_size": 200,
                    "ciphertext_sha256": _digest(2),
                },
                *[
                    {
                        "blob_id": str(uuid4()),
                        "kind": "PHOTO",
                        "ordinal": ordinal,
                        "ciphertext_size": 300 + ordinal,
                        "ciphertext_sha256": _digest(3 + ordinal),
                    }
                    for ordinal in range(3)
                ],
            ],
        }
    )


def _create(
    session,
    *,
    sender_user_id: UUID,
    request: CreateCapsuleDraftRequest,
    idempotency_key: UUID | None = None,
    request_sha256: bytes = _SERVICE_HASH,
    now: datetime = _NOW,
):
    return CapsuleDraftService(session).create_draft(
        authenticated_sender_user_id=sender_user_id,
        request=request,
        idempotency_key=idempotency_key or uuid4(),
        request_sha256=request_sha256,
        now=now,
    )


def _assert_error(call, code: str) -> CapsuleDraftServiceError:
    with pytest.raises(CapsuleDraftServiceError) as exc_info:
        call()
    assert exc_info.value.code == code
    assert str(exc_info.value) == "capsule draft operation failed"
    assert repr(exc_info.value) == f"CapsuleDraftServiceError(code={code!r})"
    return exc_info.value


def _count(session, model) -> int:
    return session.scalar(select(func.count()).select_from(model))


def _run_two_concurrent_creates(session_factory, *, sender_user_id, request, keys, hashes):
    barrier = Barrier(2)

    def worker(idempotency_key, request_sha256):
        with session_factory() as session:
            try:
                session.execute(text("SET LOCAL lock_timeout = '5s'"))
                barrier.wait(timeout=10)
                result = _create(
                    session,
                    sender_user_id=sender_user_id,
                    request=request,
                    idempotency_key=idempotency_key,
                    request_sha256=request_sha256,
                )
                session.commit()
                return ("result", result)
            except CapsuleDraftServiceError as error:
                session.commit()
                return ("error", error.code)
            except Exception as error:
                session.rollback()
                return ("unexpected", type(error).__name__)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [
            executor.submit(worker, idempotency_key, request_sha256)
            for idempotency_key, request_sha256 in zip(keys, hashes, strict=True)
        ]
        return [future.result(timeout=20) for future in futures]


def test_advisory_lock_key_vectors_are_signed_and_domain_separated() -> None:
    owner = UUID("00112233-4455-6677-8899-aabbccddeeff")
    idempotency_key = UUID("ffeeddcc-bbaa-9988-7766-554433221100")
    capsule_id = UUID("12345678-1234-5678-9abc-def012345678")

    assert idempotency_scope_lock_key(owner, idempotency_key) == 3972513700668854354
    assert capsule_lock_key(capsule_id) == -1716220365651879455
    assert -2**63 <= idempotency_scope_lock_key(owner, idempotency_key) < 2**63
    assert -2**63 <= capsule_lock_key(capsule_id) < 2**63
    assert idempotency_scope_lock_key(owner, idempotency_key) != capsule_lock_key(capsule_id)
    assert idempotency_scope_lock_key(owner, idempotency_key) != idempotency_scope_lock_key(
        UUID("00112233-4455-6677-8899-aabbccddeefe"), idempotency_key
    )


def test_invalid_service_boundary_is_redacted_without_database() -> None:
    request = _request(
        sender_bundle_id=uuid4(),
        recipient_user_id=uuid4(),
        recipient_bundle_id=uuid4(),
    )
    service = CapsuleDraftService(None)

    _assert_error(
        lambda: service.create_draft(
            authenticated_sender_user_id=uuid4(),
            request=request,
            idempotency_key=uuid4(),
            request_sha256=b"secret-request-hash",
            now=_NOW,
        ),
        "VALIDATION_FAILED",
    )


def test_new_draft_persists_authoritative_rows_and_expiries(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        idempotency_key = uuid4()

        result = _create(
            session,
            sender_user_id=sender.id,
            request=request,
            idempotency_key=idempotency_key,
        )

        assert result.capsule_id == request.capsule_id
        assert result.state is CapsuleState.DRAFT
        assert result.is_replay is False
        assert [blob.blob_id for blob in result.blobs] == [blob.blob_id for blob in request.blobs]
        assert all(blob.state is CapsuleBlobState.DECLARED for blob in result.blobs)
        expected_expiry = _NOW + timedelta(seconds=LIMITS_V1.draft_lifetime_seconds)
        assert result.draft_expires_at == expected_expiry

        capsule = session.get(Capsule, request.capsule_id)
        assert capsule is not None
        assert capsule.sender_user_id == sender.id
        assert capsule.recipient_user_id == recipient.id
        assert capsule.sender_key_bundle_id == sender_bundle.id
        assert capsule.recipient_key_bundle_id == recipient_bundle.id
        assert capsule.state is CapsuleState.DRAFT
        assert capsule.created_at == _NOW
        assert capsule.draft_expires_at == expected_expiry
        assert capsule.signed_statement is None
        assert capsule.signed_statement_sha256 is None
        assert capsule.ready_at is None

        for request_blob in request.blobs:
            persisted = session.get(CapsuleBlob, request_blob.blob_id)
            assert persisted is not None
            assert persisted.capsule_id == request.capsule_id
            assert persisted.kind is request_blob.kind
            assert persisted.ordinal == request_blob.ordinal
            assert persisted.object_key == (
                f"capsules/{request.capsule_id}/{request_blob.blob_id}.blob"
            )
            assert persisted.expected_ciphertext_size == request_blob.ciphertext_size
            assert persisted.expected_ciphertext_sha256 == bytes(
                [request.blobs.index(request_blob) + 1]
            ) * 32
            assert persisted.state is CapsuleBlobState.DECLARED

        record = session.scalar(
            select(CapsuleIdempotencyRecord).where(
                CapsuleIdempotencyRecord.owner_user_id == sender.id,
                CapsuleIdempotencyRecord.idempotency_key == idempotency_key,
            )
        )
        assert record is not None
        assert record.method == "POST"
        assert record.normalized_route == "/v1/capsules"
        assert record.request_sha256 == _SERVICE_HASH
        assert record.response_status == 201
        assert record.created_at == _NOW
        assert record.expires_at == _NOW + timedelta(hours=24)
        assert record.response_json == {
            "capsule_id": str(request.capsule_id),
            "state": "DRAFT",
            "draft_expires_at": expected_expiry.isoformat(),
            "blobs": [
                {"blob_id": str(blob.blob_id), "state": "DECLARED"}
                for blob in request.blobs
            ],
            "is_replay": False,
        }


def test_same_scope_same_hash_replays_without_duplicate_rows(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        key = uuid4()
        first = _create(session, sender_user_id=sender.id, request=request, idempotency_key=key)
        session.commit()
        counts_before = (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord))

        replay = _create(
            session,
            sender_user_id=sender.id,
            request=request,
            idempotency_key=key,
            now=_NOW + timedelta(hours=1),
        )

        assert replay == type(first)(
            capsule_id=first.capsule_id,
            state=first.state,
            draft_expires_at=first.draft_expires_at,
            blobs=first.blobs,
            is_replay=True,
        )
        assert (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord)) == counts_before


def test_same_scope_different_hash_is_idempotency_conflict_without_write(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        key = uuid4()
        _create(session, sender_user_id=sender.id, request=request, idempotency_key=key)
        session.commit()
        counts_before = (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord))

        _assert_error(
            lambda: _create(
                session,
                sender_user_id=sender.id,
                request=request,
                idempotency_key=key,
                request_sha256=bytes(range(1, 33)),
            ),
            "IDEMPOTENCY_CONFLICT",
        )
        assert (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord)) == counts_before


def test_concurrent_same_scope_same_hash_has_one_create_and_one_replay(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        session.commit()
        key = uuid4()
        outcomes = _run_two_concurrent_creates(
            session_factory,
            sender_user_id=sender.id,
            request=request,
            keys=[key, key],
            hashes=[_SERVICE_HASH, _SERVICE_HASH],
        )

        assert sorted(outcome[0] for outcome in outcomes) == ["result", "result"]
        assert sorted(outcome[1].is_replay for outcome in outcomes) == [False, True]
        with session_factory() as verify:
            assert _count(verify, Capsule) == 1
            assert _count(verify, CapsuleBlob) == 5
            assert _count(verify, CapsuleIdempotencyRecord) == 1


def test_concurrent_same_scope_different_hash_has_one_conflict(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        session.commit()
        key = uuid4()
        outcomes = _run_two_concurrent_creates(
            session_factory,
            sender_user_id=sender.id,
            request=request,
            keys=[key, key],
            hashes=[_SERVICE_HASH, bytes(range(1, 33))],
        )

        assert sorted(outcome[0] for outcome in outcomes) == ["error", "result"]
        assert [outcome[1] for outcome in outcomes if outcome[0] == "error"] == [
            "IDEMPOTENCY_CONFLICT"
        ]
        with session_factory() as verify:
            assert _count(verify, Capsule) == 1
            assert _count(verify, CapsuleBlob) == 5
            assert _count(verify, CapsuleIdempotencyRecord) == 1


def test_concurrent_different_scopes_same_capsule_has_one_conflict(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        session.commit()
        outcomes = _run_two_concurrent_creates(
            session_factory,
            sender_user_id=sender.id,
            request=request,
            keys=[uuid4(), uuid4()],
            hashes=[_SERVICE_HASH, _SERVICE_HASH],
        )

        assert sorted(outcome[0] for outcome in outcomes) == ["error", "result"]
        assert [outcome[1] for outcome in outcomes if outcome[0] == "error"] == [
            "IDEMPOTENCY_CONFLICT"
        ]
        with session_factory() as verify:
            assert _count(verify, Capsule) == 1
            assert _count(verify, CapsuleBlob) == 5
            assert _count(verify, CapsuleIdempotencyRecord) == 1


def test_same_idempotency_key_is_scoped_to_authenticated_sender(session_factory) -> None:
    with session_factory() as session:
        alice, alice_bundle = _seed_user(session, "alice")
        bob, bob_bundle = _seed_user(session, "bob")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        key = uuid4()

        alice_request = _request(
            sender_bundle_id=alice_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        bob_request = _request(
            sender_bundle_id=bob_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        _create(session, sender_user_id=alice.id, request=alice_request, idempotency_key=key)
        bob_result = _create(session, sender_user_id=bob.id, request=bob_request, idempotency_key=key)

        assert bob_result.is_replay is False
        assert session.get(Capsule, alice_request.capsule_id).sender_user_id == alice.id
        assert session.get(Capsule, bob_request.capsule_id).sender_user_id == bob.id
        assert _count(session, CapsuleIdempotencyRecord) == 2


def test_sender_is_authenticated_principal_and_body_has_no_sender_field(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        other, other_bundle = _seed_user(session, "other")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=other.id,
            recipient_bundle_id=other_bundle.id,
        )
        assert "sender_user_id" not in type(request).model_fields
        _create(session, sender_user_id=sender.id, request=request)
        assert session.get(Capsule, request.capsule_id).sender_user_id == sender.id


@pytest.mark.parametrize("disabled", [False, True])
def test_missing_or_disabled_recipient_is_rejected_without_rows(session_factory, disabled: bool) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        if disabled:
            recipient, recipient_bundle = _seed_user(session, "recipient", disabled=True)
            recipient_id = recipient.id
            recipient_bundle_id = recipient_bundle.id
        else:
            recipient_id = uuid4()
            recipient_bundle_id = uuid4()
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient_id,
            recipient_bundle_id=recipient_bundle_id,
        )
        before = (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord))
        _assert_error(
            lambda: _create(session, sender_user_id=sender.id, request=request),
            "RECIPIENT_NOT_CONFIRMED",
        )
        assert (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord)) == before


@pytest.mark.parametrize("disabled", [False, True])
def test_missing_or_disabled_sender_is_rejected_without_rows(session_factory, disabled: bool) -> None:
    with session_factory() as session:
        recipient, recipient_bundle = _seed_user(session, "recipient")
        if disabled:
            sender, sender_bundle = _seed_user(session, "sender", disabled=True)
            sender_id = sender.id
            sender_bundle_id = sender_bundle.id
        else:
            sender_id = uuid4()
            sender_bundle_id = uuid4()
        request = _request(
            sender_bundle_id=sender_bundle_id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        before = (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord))
        _assert_error(
            lambda: _create(session, sender_user_id=sender_id, request=request),
            "AUTH_INVALID",
        )
        assert (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord)) == before


def test_sender_bundle_wrong_owner_is_not_found(session_factory) -> None:
    with session_factory() as session:
        sender, _sender_bundle = _seed_user(session, "sender")
        other, other_bundle = _seed_user(session, "other")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=other_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        _assert_error(
            lambda: _create(session, sender_user_id=sender.id, request=request),
            "KEY_BUNDLE_NOT_FOUND",
        )


def test_sender_non_active_or_protocol_mismatch_is_invalid(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender", bundle_status=KeyBundleStatus.RETIRED)
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        _assert_error(
            lambda: _create(session, sender_user_id=sender.id, request=request),
            "KEY_BUNDLE_INVALID",
        )


@pytest.mark.parametrize("variant", ["missing", "wrong_owner", "non_active", "protocol_mismatch"])
def test_recipient_bundle_must_be_current_active_bundle(variant: str, session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(
            session,
            "recipient",
            bundle_status=KeyBundleStatus.RETIRED if variant == "non_active" else KeyBundleStatus.ACTIVE,
            bundle_protocol=2 if variant == "protocol_mismatch" else _PROTOCOL_VERSION,
        )
        if variant == "wrong_owner":
            _owner, recipient_bundle = _seed_user(session, "other")
        elif variant == "missing":
            recipient_bundle = UserKeyBundle(id=uuid4(), user_id=recipient.id)
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        _assert_error(
            lambda: _create(session, sender_user_id=sender.id, request=request),
            "RECIPIENT_KEY_STALE",
        )


def test_existing_capsule_id_is_permanent_cross_user_conflict(session_factory) -> None:
    with session_factory() as session:
        alice, alice_bundle = _seed_user(session, "alice")
        bob, bob_bundle = _seed_user(session, "bob")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        capsule_id = uuid4()
        alice_request = _request(
            sender_bundle_id=alice_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
            capsule_id=capsule_id,
        )
        _create(session, sender_user_id=alice.id, request=alice_request)
        session.commit()
        before = (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord))

        bob_request = _request(
            sender_bundle_id=bob_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
            capsule_id=capsule_id,
        )
        _assert_error(
            lambda: _create(session, sender_user_id=bob.id, request=bob_request),
            "IDEMPOTENCY_CONFLICT",
        )
        assert (_count(session, Capsule), _count(session, CapsuleBlob), _count(session, CapsuleIdempotencyRecord)) == before


def test_malformed_stored_response_fails_closed(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        key = uuid4()
        _create(session, sender_user_id=sender.id, request=request, idempotency_key=key)
        session.commit()
        record = session.scalar(
            select(CapsuleIdempotencyRecord).where(
                CapsuleIdempotencyRecord.owner_user_id == sender.id,
                CapsuleIdempotencyRecord.idempotency_key == key,
            )
        )
        assert record is not None
        record.response_json = {"capsule_id": "private-marker"}
        session.commit()

        error = _assert_error(
            lambda: _create(session, sender_user_id=sender.id, request=request, idempotency_key=key),
            "INTERNAL_ERROR",
        )
        assert "private-marker" not in repr(error)


def test_caller_rollback_removes_entire_draft_transaction(session_factory) -> None:
    with session_factory() as session:
        sender, sender_bundle = _seed_user(session, "sender")
        recipient, recipient_bundle = _seed_user(session, "recipient")
        request = _request(
            sender_bundle_id=sender_bundle.id,
            recipient_user_id=recipient.id,
            recipient_bundle_id=recipient_bundle.id,
        )
        _create(session, sender_user_id=sender.id, request=request)
        assert _count(session, Capsule) == 1
        assert _count(session, CapsuleBlob) == 5
        assert _count(session, CapsuleIdempotencyRecord) == 1

        session.rollback()

        assert _count(session, Capsule) == 0
        assert _count(session, CapsuleBlob) == 0
        assert _count(session, CapsuleIdempotencyRecord) == 0
