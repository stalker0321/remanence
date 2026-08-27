"""PostgreSQL tests for authoritative publish-signature verification."""

import dataclasses
import io
import json
import pathlib
from datetime import datetime, timezone
from uuid import UUID, uuid4

import hashlib
import pytest
import tink
from tink import signature, tink_config
from tink.proto import hpke_pb2, tink_pb2

pytest_plugins = ("test_session_repository_create",)

from remanence.capsules.publish_statement import VerifiedPublishStatement
from remanence.capsules.signature_service import (
    PublishSignatureVerificationError,
    PublishSignatureVerificationService,
    VerifiedPublishAuthorization,
)
from remanence.users.key_bundle_validation import (
    ED25519_PUBLIC_KEY_TYPE_URL,
    HPKE_PUBLIC_KEY_TYPE_URL,
    SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
    SUPPORTED_KEY_BUNDLE_SUITE,
)
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


_ROOT = pathlib.Path(__file__).resolve().parents[2]
_FIXTURE = _ROOT / "protocol" / "fixtures" / "publish-signature-v1.json"
_SENDER_ID = UUID("10111213-1415-1617-1819-1a1b1c1d1e1f")
_RECIPIENT_ID = UUID("20212223-2425-2627-2829-2a2b2c2d2e2f")
_OTHER_ID = UUID("50515253-5455-5657-5859-5a5b5c5d5e5f")
_SENDER_BUNDLE_ID = UUID("30313233-3435-3637-3839-3a3b3c3d3e3f")
_RECIPIENT_BUNDLE_ID = UUID("40414243-4445-4647-4849-4a4b4c4d4e4f")


@pytest.fixture(scope="module", autouse=True)
def _register_tink():
    tink_config.register()


@pytest.fixture(scope="module")
def golden() -> dict:
    return json.loads(_FIXTURE.read_text())


def _key_data(type_url: str, value: bytes) -> tink_pb2.KeyData:
    return tink_pb2.KeyData(
        type_url=type_url,
        value=value,
        key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC,
    )


def _keyset(key_data: tink_pb2.KeyData, *, key_id: int = 1) -> bytes:
    keyset = tink_pb2.Keyset(primary_key_id=key_id)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=key_data,
            status=tink_pb2.ENABLED,
            key_id=key_id,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset.SerializeToString()


def _encryption_keyset() -> bytes:
    public = hpke_pb2.HpkePublicKey(
        version=0,
        params=hpke_pb2.HpkeParams(
            kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256,
            kdf=hpke_pb2.HKDF_SHA256,
            aead=hpke_pb2.AES_256_GCM,
        ),
        public_key=bytes(range(32)),
    )
    return _keyset(
        _key_data(HPKE_PUBLIC_KEY_TYPE_URL, public.SerializeToString())
    )


def _golden_signing_keyset(golden: dict) -> bytes:
    handle = tink.KeysetHandle.read_no_secret(
        tink.JsonKeysetReader(json.dumps(golden["fixed_public_keyset_json"]))
    )
    output = io.BytesIO()
    handle.write_no_secret(tink.BinaryKeysetWriter(output))
    return output.getvalue()


def _generated_signing_keyset() -> bytes:
    handle = tink.KeysetHandle.generate_new(signature.signature_key_templates.ED25519)
    handle = handle.public_keyset_handle()
    output = io.BytesIO()
    handle.write_no_secret(tink.BinaryKeysetWriter(output))
    return output.getvalue()


def _verified(golden: dict) -> VerifiedPublishStatement:
    raw = bytes.fromhex(golden["expected_deterministic_hex"])
    return VerifiedPublishStatement(
        canonical_bytes=raw,
        sha256=hashlib.sha256(raw).digest(),
        capsule_id=UUID("00010203-0405-0607-0809-0a0b0c0d0e0f"),
        sender_user_id=_SENDER_ID,
        recipient_user_id=_RECIPIENT_ID,
        sender_key_bundle_id=_SENDER_BUNDLE_ID,
        recipient_key_bundle_id=_RECIPIENT_BUNDLE_ID,
        created_at=datetime(2030, 1, 1, tzinfo=timezone.utc),
        artifacts=(),
    )


def _seed_user(
    session,
    *,
    user_id: UUID,
    bundle_id: UUID,
    label: str,
    signing_public_keyset: bytes,
    status: KeyBundleStatus = KeyBundleStatus.ACTIVE,
    suite: str = SUPPORTED_KEY_BUNDLE_SUITE,
    protocol_version: int = SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
) -> UserKeyBundle:
    user = User(
        id=user_id,
        email_normalized=f"{label}-{uuid4().hex}@example.com",
        handle_normalized=f"{label}{uuid4().hex[:20]}",
        handle_display=f"{label}{uuid4().hex[:20]}",
    )
    session.add(user)
    session.flush()
    bundle = UserKeyBundle(
        id=bundle_id,
        user_id=user_id,
        encryption_public_keyset=_encryption_keyset(),
        signing_public_keyset=signing_public_keyset,
        suite=suite,
        protocol_version=protocol_version,
        status=status,
    )
    session.add(bundle)
    session.flush()
    return bundle


def _seed_valid(session, golden: dict, *, sender_status=KeyBundleStatus.ACTIVE, recipient_status=KeyBundleStatus.ACTIVE, sender_signing: bytes | None = None, recipient_signing: bytes | None = None):
    sender = _seed_user(
        session,
        user_id=_SENDER_ID,
        bundle_id=_SENDER_BUNDLE_ID,
        label="sender",
        signing_public_keyset=sender_signing or _golden_signing_keyset(golden),
        status=sender_status,
    )
    recipient = _seed_user(
        session,
        user_id=_RECIPIENT_ID,
        bundle_id=_RECIPIENT_BUNDLE_ID,
        label="recipient",
        signing_public_keyset=recipient_signing or _golden_signing_keyset(golden),
        status=recipient_status,
    )
    return sender, recipient


def _verify(session, golden: dict, *, signature_bytes: bytes | None = None):
    return PublishSignatureVerificationService(session).verify(
        _verified(golden),
        signature_bytes or bytes.fromhex(golden["expected_signature_hex"]),
    )


def _assert_error(call, code: str) -> None:
    with pytest.raises(PublishSignatureVerificationError) as caught:
        call()
    assert caught.value.code == code
    assert str(caught.value) == "capsule publish signature verification failed"
    assert repr(caught.value) == (
        f"PublishSignatureVerificationError(code={code!r})"
    )


def test_golden_is_verified_with_authoritative_public_keysets(session_factory, golden):
    with session_factory() as session:
        _seed_valid(session, golden)
        authorization = _verify(session, golden)

        assert isinstance(authorization, VerifiedPublishAuthorization)
        assert authorization.verified_statement == _verified(golden)
        assert authorization.sender_key_bundle_id == _SENDER_BUNDLE_ID
        assert authorization.sender_key_bundle_status is KeyBundleStatus.ACTIVE
        assert authorization.recipient_key_bundle_id == _RECIPIENT_BUNDLE_ID
        assert authorization.recipient_key_bundle_status is KeyBundleStatus.ACTIVE
        assert authorization.signature == bytes.fromhex(golden["expected_signature_hex"])
        assert repr(authorization) == "VerifiedPublishAuthorization(<redacted>)"
        assert bytes.fromhex(golden["expected_signature_hex"]).hex() not in repr(authorization)


def test_signature_and_statement_tampering_fail_closed(session_factory, golden):
    with session_factory() as session:
        _seed_valid(session, golden)
        tampered = bytearray(_verified(golden).canonical_bytes)
        tampered[-1] ^= 1
        _assert_error(
            lambda: PublishSignatureVerificationService(session).verify(
                dataclasses.replace(_verified(golden), canonical_bytes=bytes(tampered)),
                bytes.fromhex(golden["expected_signature_hex"]),
            ),
            "SIGNATURE_INVALID",
        )
        signature_bytes = bytearray(bytes.fromhex(golden["expected_signature_hex"]))
        signature_bytes[-1] ^= 1
        _assert_error(
            lambda: _verify(session, golden, signature_bytes=bytes(signature_bytes)),
            "SIGNATURE_INVALID",
        )


@pytest.mark.parametrize(
    "change",
    [
        lambda value: b"\x00" + value[1:],
        lambda value: value[:-1],
        lambda value: value + b"\x00",
        lambda value: value[:1] + (0).to_bytes(4, "big") + value[5:],
    ],
)
def test_signature_structural_guard_rejects_non_v1_framing(
    session_factory, golden, change
):
    with session_factory() as session:
        _seed_valid(session, golden)
        _assert_error(
            lambda: _verify(
                session,
                golden,
                signature_bytes=change(bytes.fromhex(golden["expected_signature_hex"])),
            ),
            "SIGNATURE_INVALID",
        )


def test_wrong_valid_signing_key_is_rejected(session_factory, golden):
    with session_factory() as session:
        _seed_valid(session, golden, sender_signing=_generated_signing_keyset())
        _assert_error(lambda: _verify(session, golden), "SIGNATURE_INVALID")


def test_sender_missing_wrong_owner_revoked_and_retired_matrix(session_factory, golden):
    with session_factory() as session:
        _seed_user(
            session,
            user_id=_OTHER_ID,
            bundle_id=_SENDER_BUNDLE_ID,
            label="owner",
            signing_public_keyset=_golden_signing_keyset(golden),
        )
        _seed_user(
            session,
            user_id=_RECIPIENT_ID,
            bundle_id=_RECIPIENT_BUNDLE_ID,
            label="recipient",
            signing_public_keyset=_golden_signing_keyset(golden),
        )
        _assert_error(lambda: _verify(session, golden), "KEY_BUNDLE_NOT_FOUND")

    with session_factory() as session:
        _seed_valid(session, golden, sender_status=KeyBundleStatus.REVOKED)
        _assert_error(lambda: _verify(session, golden), "KEY_BUNDLE_REVOKED")

    with session_factory() as session:
        _seed_valid(session, golden, sender_status=KeyBundleStatus.RETIRED)
        authorization = _verify(session, golden)
        assert authorization.sender_key_bundle_status is KeyBundleStatus.RETIRED

    with session_factory() as session:
        _seed_valid(session, golden)
        _assert_error(lambda: PublishSignatureVerificationService(session).verify(
            dataclasses.replace(_verified(golden), sender_key_bundle_id=uuid4()),
            bytes.fromhex(golden["expected_signature_hex"]),
        ), "KEY_BUNDLE_NOT_FOUND")


@pytest.mark.parametrize(
    "status",
    [KeyBundleStatus.RETIRED, KeyBundleStatus.REVOKED],
)
def test_recipient_non_active_is_stale(session_factory, golden, status):
    with session_factory() as session:
        _seed_valid(session, golden, recipient_status=status)
        _assert_error(lambda: _verify(session, golden), "RECIPIENT_KEY_STALE")


def test_recipient_wrong_owner_and_current_bundle_mismatch_are_stale(
    session_factory, golden
):
    with session_factory() as session:
        _seed_user(
            session,
            user_id=_SENDER_ID,
            bundle_id=_SENDER_BUNDLE_ID,
            label="sender",
            signing_public_keyset=_golden_signing_keyset(golden),
        )
        _seed_user(
            session,
            user_id=_OTHER_ID,
            bundle_id=_RECIPIENT_BUNDLE_ID,
            label="wrongown",
            signing_public_keyset=_golden_signing_keyset(golden),
        )
        _assert_error(lambda: _verify(session, golden), "RECIPIENT_KEY_STALE")

    with session_factory() as session:
        _seed_user(
            session,
            user_id=_SENDER_ID,
            bundle_id=_SENDER_BUNDLE_ID,
            label="sender",
            signing_public_keyset=_golden_signing_keyset(golden),
        )
        _seed_user(
            session,
            user_id=_RECIPIENT_ID,
            bundle_id=_RECIPIENT_BUNDLE_ID,
            label="oldrecv",
            signing_public_keyset=_golden_signing_keyset(golden),
            status=KeyBundleStatus.RETIRED,
        )
        session.add(
            UserKeyBundle(
                id=uuid4(),
                user_id=_RECIPIENT_ID,
                encryption_public_keyset=_encryption_keyset(),
                signing_public_keyset=_golden_signing_keyset(golden),
                suite=SUPPORTED_KEY_BUNDLE_SUITE,
                protocol_version=SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
                status=KeyBundleStatus.ACTIVE,
            )
        )
        session.flush()
        _assert_error(lambda: _verify(session, golden), "RECIPIENT_KEY_STALE")


@pytest.mark.parametrize("role", ["sender", "recipient"])
@pytest.mark.parametrize("field", ["suite", "protocol_version"])
def test_noncanonical_bundle_metadata_fails_closed(session_factory, golden, role, field):
    with session_factory() as session:
        _seed_valid(session, golden)
        bundle_id = _SENDER_BUNDLE_ID if role == "sender" else _RECIPIENT_BUNDLE_ID
        bundle = session.get(UserKeyBundle, bundle_id)
        assert bundle is not None
        setattr(bundle, field, "OTHER" if field == "suite" else 2)
        session.flush()
        _assert_error(
            lambda: _verify(session, golden),
            "KEY_BUNDLE_INVALID" if role == "sender" else "RECIPIENT_KEY_STALE",
        )


def test_corrupt_public_keysets_are_role_specific(session_factory, golden):
    with session_factory() as session:
        _seed_valid(session, golden)
        sender = session.get(UserKeyBundle, _SENDER_BUNDLE_ID)
        assert sender is not None
        sender.signing_public_keyset = b"corrupt"
        session.flush()
        _assert_error(lambda: _verify(session, golden), "KEY_BUNDLE_INVALID")

    with session_factory() as session:
        _seed_valid(session, golden)
        recipient = session.get(UserKeyBundle, _RECIPIENT_BUNDLE_ID)
        assert recipient is not None
        recipient.signing_public_keyset = b"corrupt"
        session.flush()
        _assert_error(lambda: _verify(session, golden), "RECIPIENT_KEY_STALE")


def test_exact_domain_and_no_private_fixture_in_production_source(golden):
    source = pathlib.Path(
        _ROOT / "server" / "src" / "remanence" / "capsules" / "signature_service.py"
    ).read_text()
    assert "fixed_private_keyset" not in source
    assert "postmark/publish/v1" in source
    assert "postmark/publish/v1\x00" not in source
