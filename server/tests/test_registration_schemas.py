"""Tests for registration request/response schemas and redacted validation problem."""

import base64
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from remanence.api.auth_schemas import (
    ProblemDetail,
    RegistrationKeyBundleRequest,
    RegistrationRequest,
    RegistrationResponse,
    RegistrationUserResponse,
)
from remanence.api.problems import PROBLEM_BODY_FIELDS, PROBLEM_CATALOG, ProblemDetail as CanonicalProblemDetail
from remanence.users.key_bundle_validation import (
    ED25519_PUBLIC_KEY_TYPE_URL,
    HPKE_PUBLIC_KEY_TYPE_URL,
    SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
    SUPPORTED_KEY_BUNDLE_SUITE,
)

_HPKE_KEY = bytes(range(32))
_ED_KEY = bytes(range(32, 64))
_ACCESS_TOKEN = "pm_at_" + "A" * 43
_REFRESH_TOKEN = "pm_rt_" + "B" * 43
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


def _b64(data: bytes) -> str:
    return base64.b64encode(data, altchars=b"-_").decode("ascii").rstrip("=")


def _valid_bundle() -> tuple[str, str]:
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key().SerializeToString()))
    signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, _ed25519_public_key().SerializeToString()))
    return _b64(encryption.SerializeToString()), _b64(signing.SerializeToString())


def _key_bundle_payload(**overrides) -> dict:
    encryption, signing = _valid_bundle()
    payload = {
        "key_bundle_id": "00010203-0405-0607-0809-0a0b0c0d0e0f",
        "suite": SUPPORTED_KEY_BUNDLE_SUITE,
        "protocol_version": SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
        "encryption_public_keyset": encryption,
        "signing_public_keyset": signing,
    }
    payload.update(overrides)
    return payload


def _registration_payload(**overrides) -> dict:
    payload = {
        "email": "  Alice@Example.COM  ",
        "password": "correct horse battery staple",
        "handle": "@Bob",
        "key_bundle": _key_bundle_payload(),
    }
    payload.update(overrides)
    return payload


def _response_payload(**overrides) -> dict:
    payload = {
        "user": {
            "user_id": "00010203-0405-0607-0809-0a0b0c0d0e0f",
            "email": "alice@example.com",
            "handle": "bob",
            "created_at": _NOW.isoformat(),
        },
        "active_key_bundle_id": "00010203-0405-0607-0809-0a0b0c0d0e0f",
        "access_token": _ACCESS_TOKEN,
        "access_expires_at": _NOW.isoformat(),
        "refresh_token": _REFRESH_TOKEN,
        "refresh_expires_at": _NOW.isoformat(),
    }
    payload.update(overrides)
    return payload


def test_valid_registration_normalizes_email_handle_and_decodes_keysets() -> None:
    request = RegistrationRequest.model_validate(_registration_payload())
    assert request.email == "alice@example.com"
    assert request.handle == "bob"
    assert request.key_bundle.key_bundle_id == uuid.UUID("00010203-0405-0607-0809-0a0b0c0d0e0f")
    assert request.key_bundle.encryption_public_keyset == _keyset(
        _key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key().SerializeToString())
    ).SerializeToString()
    assert request.key_bundle.signing_public_keyset == _keyset(
        _key_data(ED25519_PUBLIC_KEY_TYPE_URL, _ed25519_public_key().SerializeToString())
    ).SerializeToString()


def test_valid_response_roundtrip() -> None:
    response = RegistrationResponse.model_validate(_response_payload())
    assert response.user.email == "alice@example.com"
    assert response.access_token == _ACCESS_TOKEN
    assert response.refresh_token == _REFRESH_TOKEN


def test_extra_field_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(extra_field="x"))


def test_invalid_email_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(email="not-an-email"))


def test_password_too_short_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(password="short"))


def test_password_below_canonical_minimum_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(password="x" * 11))


def test_password_at_canonical_minimum_accepted() -> None:
    request = RegistrationRequest.model_validate(_registration_payload(password="x" * 12))
    assert request.password.get_secret_value() == "x" * 12


def test_password_too_long_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(password="x" * 129))


def test_password_unicode_chars_counted() -> None:
    request = RegistrationRequest.model_validate(_registration_payload(password="пароль-пароль"))
    assert request.password.get_secret_value() == "пароль-пароль"


def test_noncanonical_uuid_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(key_bundle=_key_bundle_payload(key_bundle_id="00010203-0405-0607-0809-0A0B0C0D0E0F")))


def test_padded_base64_rejected() -> None:
    encryption, _ = _valid_bundle()
    payload = _key_bundle_payload(encryption_public_keyset=encryption + "=")
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(key_bundle=payload))


def test_invalid_base64_rejected() -> None:
    payload = _key_bundle_payload(encryption_public_keyset="!!!not-base64url!!!")
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(key_bundle=payload))


def test_oversize_base64_rejected() -> None:
    payload = _key_bundle_payload(encryption_public_keyset="A" * 6000)
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(key_bundle=payload))


def test_invalid_bundle_rejected() -> None:
    payload = _key_bundle_payload(suite="WRONG_SUITE")
    with pytest.raises(ValidationError):
        RegistrationRequest.model_validate(_registration_payload(key_bundle=payload))


def test_repr_and_error_str_omit_password_and_keysets() -> None:
    request = RegistrationRequest.model_validate(_registration_payload())
    rendered = repr(request)
    assert "correct horse battery staple" not in rendered
    assert "correct horse battery staple" not in str(request)
    encryption, signing = _valid_bundle()
    assert encryption not in rendered
    assert signing not in rendered
    with pytest.raises(ValidationError) as excinfo:
        RegistrationRequest.model_validate(_registration_payload(password="short"))
    assert "short" not in str(excinfo.value)


def test_fixed_problem_identical_for_distinct_failures() -> None:
    assert ProblemDetail is CanonicalProblemDetail
    spec = PROBLEM_CATALOG["VALIDATION_FAILED"]
    request_id = "00000000-0000-4000-8000-000000000001"
    first = ProblemDetail(
        type=spec.type,
        title=spec.title,
        status=spec.status,
        code=spec.code,
        detail=spec.detail,
        request_id=request_id,
        retryable=spec.retryable,
    )
    second = ProblemDetail(
        type=spec.type,
        title=spec.title,
        status=spec.status,
        code=spec.code,
        detail=spec.detail,
        request_id=request_id,
        retryable=spec.retryable,
    )
    assert first == second
    assert first.type == "https://remanence.invalid/problems/validation-failed"
    assert first.title == "Validation failed"
    assert first.status == 422
    assert first.code == "VALIDATION_FAILED"
    dumped = first.model_dump()
    assert set(dumped) == PROBLEM_BODY_FIELDS
    assert dumped["detail"] == spec.detail
    assert "errors" not in dumped
    assert "fields" not in dumped


def test_response_repr_hides_tokens_but_dump_includes() -> None:
    response = RegistrationResponse.model_validate(_response_payload())
    rendered = repr(response)
    assert _ACCESS_TOKEN not in rendered
    assert _REFRESH_TOKEN not in rendered
    dumped = response.model_dump()
    assert dumped["access_token"] == _ACCESS_TOKEN
    assert dumped["refresh_token"] == _REFRESH_TOKEN


def test_naive_datetime_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationResponse.model_validate(_response_payload(user={"user_id": "00010203-0405-0607-0809-0a0b0c0d0e0f", "email": "alice@example.com", "handle": "bob", "created_at": "2030-01-01T12:00:00"}))


def test_non_utc_datetime_rejected() -> None:
    offset = timezone(timedelta(hours=2))
    naive = datetime(2030, 1, 1, 12, 0, 0, tzinfo=offset)
    with pytest.raises(ValidationError):
        RegistrationResponse.model_validate(_response_payload(user={"user_id": "00010203-0405-0607-0809-0a0b0c0d0e0f", "email": "alice@example.com", "handle": "bob", "created_at": naive.isoformat()}))


def test_bad_token_prefixes_rejected() -> None:
    with pytest.raises(ValidationError):
        RegistrationResponse.model_validate(_response_payload(access_token="bad_" + "A" * 43))
    with pytest.raises(ValidationError):
        RegistrationResponse.model_validate(_response_payload(refresh_token="bad_" + "B" * 43))