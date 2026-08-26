"""Integration tests for the atomic registration service against a temporary PostgreSQL database."""

import base64
from datetime import datetime, timezone
from uuid import uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from remanence.auth.models import AuthCredential, AuthSession
from remanence.auth.passwords import PasswordService
from remanence.auth.registration import RegistrationService
from remanence.auth.session_rotation import ACCESS_TTL, REFRESH_TTL
from remanence.auth.tokens import hash_opaque_token
from remanence.users.key_bundle_validation import (
    ED25519_PUBLIC_KEY_TYPE_URL,
    HPKE_PUBLIC_KEY_TYPE_URL,
    SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
    SUPPORTED_KEY_BUNDLE_SUITE,
    PublicKeyBundleValidationError,
)
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User

pytest_plugins = ("test_session_repository_create",)

_EMAIL = "alice@example.com"
_HANDLE = "alice"
_PASSWORD = "correct horse battery staple"
_HPKE_KEY = bytes(range(32))
_ED_KEY = bytes(range(32, 64))
_NOW = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)


def _hpke_public_key() -> hpke_pb2.HpkePublicKey:
    return hpke_pb2.HpkePublicKey(
        version=0,
        params=hpke_pb2.HpkeParams(
            kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256,
            kdf=hpke_pb2.HKDF_SHA256,
            aead=hpke_pb2.AES_256_GCM,
        ),
        public_key=_HPKE_KEY,
    )


def _ed25519_public_key() -> ed25519_pb2.Ed25519PublicKey:
    return ed25519_pb2.Ed25519PublicKey(version=0, key_value=_ED_KEY)


def _key_data(type_url: str, value: bytes) -> tink_pb2.KeyData:
    return tink_pb2.KeyData(
        type_url=type_url,
        value=value,
        key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC,
    )


def _keyset(key_data: tink_pb2.KeyData) -> tink_pb2.Keyset:
    keyset = tink_pb2.Keyset(primary_key_id=1)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=key_data,
            status=tink_pb2.ENABLED,
            key_id=1,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset


def _bundles() -> tuple[bytes, bytes]:
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key().SerializeToString()))
    signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, _ed25519_public_key().SerializeToString()))
    return encryption.SerializeToString(), signing.SerializeToString()


def _service(session) -> RegistrationService:
    return RegistrationService(session, PasswordService())


def _register(service: RegistrationService, **overrides) -> None:
    encryption, signing = _bundles()
    kwargs = dict(
        email_normalized=_EMAIL,
        password=_PASSWORD,
        handle_normalized=_HANDLE,
        key_bundle_id=uuid4(),
        suite=SUPPORTED_KEY_BUNDLE_SUITE,
        protocol_version=SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
        encryption_public_keyset=encryption,
        signing_public_keyset=signing,
        now=_NOW,
    )
    kwargs.update(overrides)
    return service.register(**kwargs)


def test_successful_registration_persists_four_rows(session_factory) -> None:
    with session_factory() as session:
        service = _service(session)
        result = _register(service)
        session.commit()
        session.expunge_all()

        user = session.get(User, result.user_id)
        assert user is not None
        assert user.email_normalized == _EMAIL
        assert user.handle_normalized == _HANDLE
        assert user.handle_display == _HANDLE

        credential = session.get(AuthCredential, result.user_id)
        assert credential is not None
        assert PasswordService().verify_password(credential.password_hash, _PASSWORD).verified is True

        bundle = session.get(UserKeyBundle, result.active_key_bundle_id)
        assert bundle is not None
        assert bundle.user_id == result.user_id
        assert bundle.status is KeyBundleStatus.ACTIVE
        assert bundle.encryption_public_keyset == _bundles()[0]
        assert bundle.signing_public_keyset == _bundles()[1]
        assert bundle.suite == SUPPORTED_KEY_BUNDLE_SUITE
        assert bundle.protocol_version == SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION

        auth_session = session.get(AuthSession, result.session_id)
        assert auth_session is not None
        assert auth_session.user_id == result.user_id
        assert auth_session.lineage_id == result.session_id
        assert auth_session.parent_session_id is None

        rows = session.scalars(select(User)).all() + session.scalars(select(AuthCredential)).all() + session.scalars(select(UserKeyBundle)).all() + session.scalars(select(AuthSession)).all()
        assert len(rows) == 4


def test_token_hashes_and_ttls_exact(session_factory) -> None:
    with session_factory() as session:
        service = _service(session)
        result = _register(service)
        access_hash = hash_opaque_token(result.access_token)
        refresh_hash = hash_opaque_token(result.refresh_token)
        session.commit()
        session.expunge_all()
        auth_session = session.get(AuthSession, result.session_id)
        assert auth_session.access_token_hash == access_hash
        assert auth_session.refresh_token_hash == refresh_hash
        assert auth_session.access_expires_at == _NOW + ACCESS_TTL
        assert auth_session.refresh_expires_at == _NOW + REFRESH_TTL
        assert result.access_expires_at == _NOW + ACCESS_TTL
        assert result.refresh_expires_at == _NOW + REFRESH_TTL


def test_result_repr_omits_password_and_tokens(session_factory) -> None:
    with session_factory() as session:
        result = _register(_service(session))
        rendered = repr(result)
        assert _PASSWORD not in rendered
        assert result.access_token not in rendered
        assert result.refresh_token not in rendered


def test_duplicate_email_integrity_error_no_partial_rows(session_factory) -> None:
    with session_factory() as session:
        service = _service(session)
        first = _register(service)
        session.commit()
        first_bundle = first.active_key_bundle_id
        session.expunge_all()

        with pytest.raises(IntegrityError):
            _register(service)
        session.rollback()
        session.expunge_all()
        users = session.scalars(select(User)).all()
        assert len(users) == 1
        credentials = session.scalars(select(AuthCredential)).all()
        assert len(credentials) == 1
        bundles = session.scalars(select(UserKeyBundle)).all()
        assert len(bundles) == 1
        assert bundles[0].id == first_bundle
        sessions = session.scalars(select(AuthSession)).all()
        assert len(sessions) == 1


def test_duplicate_handle_integrity_without_partial_rows(session_factory) -> None:
    with session_factory() as session:
        service = _service(session)
        first = _register(service)
        session.commit()
        session.expunge_all()

        with pytest.raises(IntegrityError):
            _register(service, email_normalized="bob@example.com")
        session.rollback()
        session.expunge_all()
        assert len(session.scalars(select(User)).all()) == 1
        assert len(session.scalars(select(UserKeyBundle)).all()) == 1
        assert len(session.scalars(select(AuthSession)).all()) == 1
        assert len(session.scalars(select(AuthCredential)).all()) == 1


def test_duplicate_key_bundle_id_rollback(session_factory) -> None:
    with session_factory() as session:
        service = _service(session)
        first = _register(service)
        session.commit()
        session.expunge_all()

        with pytest.raises(IntegrityError):
            _register(service, email_normalized="carol@example.com", handle_normalized="carol", key_bundle_id=first.active_key_bundle_id)
        session.rollback()
        session.expunge_all()
        assert len(session.scalars(select(User)).all()) == 1
        assert len(session.scalars(select(UserKeyBundle)).all()) == 1


def test_outer_rollback_removes_all_four_rows(session_factory) -> None:
    with session_factory() as session:
        service = _service(session)
        result = _register(service)
        session.rollback()
        session.expunge_all()
        assert session.get(User, result.user_id) is None
        assert session.get(AuthCredential, result.user_id) is None
        assert session.get(UserKeyBundle, result.active_key_bundle_id) is None
        assert session.get(AuthSession, result.session_id) is None
        assert len(session.scalars(select(User)).all()) == 0
        assert len(session.scalars(select(AuthCredential)).all()) == 0
        assert len(session.scalars(select(UserKeyBundle)).all()) == 0
        assert len(session.scalars(select(AuthSession)).all()) == 0


def test_invalid_inputs_fail_before_any_row(session_factory) -> None:
    bad_cases = [
        dict(email_normalized=" not-normalized "),
        dict(email_normalized=""),
        dict(email_normalized="x" * 321),
        dict(handle_normalized="@alice"),
        dict(handle_normalized="ALICE"),
        dict(now=datetime(2030, 1, 1, 12, 0, 0)),
        dict(suite="WRONG"),
        dict(encryption_public_keyset=b""),
    ]
    with session_factory() as session:
        service = _service(session)
        existing_users = len(session.scalars(select(User)).all())
        for overrides in bad_cases:
            with pytest.raises((ValueError, PublicKeyBundleValidationError)):
                _register(service, **overrides)
        session.expunge_all()
        assert len(session.scalars(select(User)).all()) == existing_users
        assert len(session.scalars(select(AuthCredential)).all()) == 0
        assert len(session.scalars(select(UserKeyBundle)).all()) == 0
        assert len(session.scalars(select(AuthSession)).all()) == 0