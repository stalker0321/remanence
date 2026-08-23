"""Tests for exact account key bundle algorithm validation."""

import pytest
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

from postmark.users.key_bundle_validation import (
    ED25519_PUBLIC_KEY_TYPE_URL,
    HPKE_PUBLIC_KEY_TYPE_URL,
    SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
    SUPPORTED_KEY_BUNDLE_SUITE,
    PublicKeyBundleValidationError,
    validate_public_key_bundle,
)

_HPKE_KEY = bytes(range(32))
_ED_KEY = bytes(range(32, 64))


def _hpke_public_key(*, version: int = 0, params: hpke_pb2.HpkeParams | None = None, public_key: bytes = _HPKE_KEY) -> hpke_pb2.HpkePublicKey:
    if params is None:
        params = hpke_pb2.HpkeParams(
            kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256,
            kdf=hpke_pb2.HKDF_SHA256,
            aead=hpke_pb2.AES_256_GCM,
        )
    return hpke_pb2.HpkePublicKey(version=version, params=params, public_key=public_key)


def _ed25519_public_key(*, version: int = 0, key_value: bytes = _ED_KEY) -> ed25519_pb2.Ed25519PublicKey:
    return ed25519_pb2.Ed25519PublicKey(version=version, key_value=key_value)


def _key_data(type_url: str, value: bytes) -> tink_pb2.KeyData:
    return tink_pb2.KeyData(
        type_url=type_url,
        value=value,
        key_material_type=tink_pb2.KeyData.ASYMMETRIC_PUBLIC,
    )


def _keyset(key_data: tink_pb2.KeyData, *, key_id: int = 1) -> tink_pb2.Keyset:
    keyset = tink_pb2.Keyset(primary_key_id=key_id)
    keyset.key.append(
        tink_pb2.Keyset.Key(
            key_data=key_data,
            status=tink_pb2.ENABLED,
            key_id=key_id,
            output_prefix_type=tink_pb2.TINK,
        )
    )
    return keyset


def _bundle(
    *,
    suite: str = SUPPORTED_KEY_BUNDLE_SUITE,
    protocol_version: int = SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
    encryption: tink_pb2.Keyset | None = None,
    signing: tink_pb2.Keyset | None = None,
) -> tuple[str, int, bytes, bytes]:
    if encryption is None:
        encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key().SerializeToString()))
    if signing is None:
        signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, _ed25519_public_key().SerializeToString()))
    return (
        suite,
        protocol_version,
        encryption.SerializeToString(),
        signing.SerializeToString(),
    )


def _assert_invalid(*, suite: str = SUPPORTED_KEY_BUNDLE_SUITE, protocol_version: int = SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION, encryption: bytes | None = None, signing: bytes | None = None) -> None:
    if encryption is None and signing is None:
        _, _, encryption, signing = _bundle(suite=suite, protocol_version=protocol_version)
    with pytest.raises(PublicKeyBundleValidationError) as excinfo:
        validate_public_key_bundle(
            suite=suite,
            protocol_version=protocol_version,
            encryption_public_keyset=encryption,
            signing_public_keyset=signing,
        )
    assert excinfo.value.args == ("invalid public key bundle",)
    assert repr(encryption) not in repr(excinfo.value)
    assert repr(signing) not in repr(excinfo.value)


def test_valid_bundle_passes() -> None:
    suite, protocol_version, encryption, signing = _bundle()
    validate_public_key_bundle(
        suite=suite,
        protocol_version=protocol_version,
        encryption_public_keyset=encryption,
        signing_public_keyset=signing,
    )


def test_wrong_suite_rejected() -> None:
    _assert_invalid(suite="HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519X")


def test_case_changed_suite_rejected() -> None:
    _assert_invalid(suite=SUPPORTED_KEY_BUNDLE_SUITE.lower())


def test_blank_suite_rejected() -> None:
    _assert_invalid(suite="")


def test_protocol_version_zero_rejected() -> None:
    _assert_invalid(protocol_version=0)


def test_protocol_version_two_rejected() -> None:
    _assert_invalid(protocol_version=2)


def test_protocol_version_bool_rejected() -> None:
    _assert_invalid(protocol_version=True)


def test_swapped_keysets_rejected() -> None:
    suite, protocol_version, encryption, signing = _bundle()
    _assert_invalid(encryption=signing, signing=encryption)


def test_malformed_embedded_hpke_rejected() -> None:
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, b"\xff\xff\xff\xff"))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_malformed_embedded_ed25519_rejected() -> None:
    signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, b"\xff\xff\xff\xff"))
    _assert_invalid(signing=signing.SerializeToString())


def test_embedded_hpke_unknown_field_rejected() -> None:
    hpke = _hpke_public_key()
    serialized = hpke.SerializeToString() + b"\x18\x01"
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, serialized))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_embedded_ed25519_unknown_field_rejected() -> None:
    ed = _ed25519_public_key()
    serialized = ed.SerializeToString() + b"\x18\x01"
    signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, serialized))
    _assert_invalid(signing=signing.SerializeToString())


def test_hpke_wrong_version_rejected() -> None:
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(version=1).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_missing_params_rejected() -> None:
    hpke = hpke_pb2.HpkePublicKey(version=0, public_key=_HPKE_KEY)
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, hpke.SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_alternate_kem_rejected() -> None:
    params = hpke_pb2.HpkeParams(kem=hpke_pb2.DHKEM_P256_HKDF_SHA256, kdf=hpke_pb2.HKDF_SHA256, aead=hpke_pb2.AES_256_GCM)
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(params=params).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_unknown_kem_rejected() -> None:
    params = hpke_pb2.HpkeParams(kem=hpke_pb2.KEM_UNKNOWN, kdf=hpke_pb2.HKDF_SHA256, aead=hpke_pb2.AES_256_GCM)
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(params=params).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_alternate_kdf_rejected() -> None:
    params = hpke_pb2.HpkeParams(kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256, kdf=hpke_pb2.HKDF_SHA384, aead=hpke_pb2.AES_256_GCM)
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(params=params).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_unknown_kdf_rejected() -> None:
    params = hpke_pb2.HpkeParams(kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256, kdf=hpke_pb2.KDF_UNKNOWN, aead=hpke_pb2.AES_256_GCM)
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(params=params).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_alternate_aead_rejected() -> None:
    params = hpke_pb2.HpkeParams(kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256, kdf=hpke_pb2.HKDF_SHA256, aead=hpke_pb2.AES_128_GCM)
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(params=params).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_unknown_aead_rejected() -> None:
    params = hpke_pb2.HpkeParams(kem=hpke_pb2.DHKEM_X25519_HKDF_SHA256, kdf=hpke_pb2.HKDF_SHA256, aead=hpke_pb2.AEAD_UNKNOWN)
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(params=params).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_31_byte_key_rejected() -> None:
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(public_key=bytes(31)).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_hpke_33_byte_key_rejected() -> None:
    encryption = _keyset(_key_data(HPKE_PUBLIC_KEY_TYPE_URL, _hpke_public_key(public_key=bytes(33)).SerializeToString()))
    _assert_invalid(encryption=encryption.SerializeToString())


def test_ed25519_wrong_version_rejected() -> None:
    signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, _ed25519_public_key(version=1).SerializeToString()))
    _assert_invalid(signing=signing.SerializeToString())


def test_ed25519_31_byte_key_rejected() -> None:
    signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, _ed25519_public_key(key_value=bytes(31)).SerializeToString()))
    _assert_invalid(signing=signing.SerializeToString())


def test_ed25519_33_byte_key_rejected() -> None:
    signing = _keyset(_key_data(ED25519_PUBLIC_KEY_TYPE_URL, _ed25519_public_key(key_value=bytes(33)).SerializeToString()))
    _assert_invalid(signing=signing.SerializeToString())


def test_private_hpke_material_rejected() -> None:
    key_data = tink_pb2.KeyData(
        type_url=HPKE_PUBLIC_KEY_TYPE_URL,
        value=_hpke_public_key().SerializeToString(),
        key_material_type=tink_pb2.KeyData.ASYMMETRIC_PRIVATE,
    )
    encryption = _keyset(key_data)
    _assert_invalid(encryption=encryption.SerializeToString())


def test_private_ed25519_material_rejected() -> None:
    key_data = tink_pb2.KeyData(
        type_url=ED25519_PUBLIC_KEY_TYPE_URL,
        value=_ed25519_public_key().SerializeToString(),
        key_material_type=tink_pb2.KeyData.ASYMMETRIC_PRIVATE,
    )
    signing = _keyset(key_data)
    _assert_invalid(signing=signing.SerializeToString())


def test_all_failures_share_args_and_omit_input() -> None:
    suite, protocol_version, encryption, signing = _bundle()
    cases = [
        (suite + "x", protocol_version, encryption, signing),
        (suite, 0, encryption, signing),
        (suite, protocol_version, signing, encryption),
        (suite, protocol_version, b"\xff\xff\xff\xff", signing),
        (suite, protocol_version, encryption, b"\xff\xff\xff\xff"),
    ]
    for case in cases:
        with pytest.raises(PublicKeyBundleValidationError) as excinfo:
            validate_public_key_bundle(
                suite=case[0],
                protocol_version=case[1],
                encryption_public_keyset=case[2],
                signing_public_keyset=case[3],
            )
        assert excinfo.value.args == ("invalid public key bundle",)
        assert repr(case[2]) not in repr(excinfo.value)
        assert repr(case[3]) not in repr(excinfo.value)