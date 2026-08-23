"""Tests for strict Tink public keyset envelope validation."""

import inspect

import pytest
from tink.proto import tink_pb2

from postmark.users.key_bundle_validation import (
    MAX_PUBLIC_KEYSET_BYTES,
    PublicKeyBundleValidationError,
    _parse_single_public_keyset,
)

_TYPE_URL = "type.googleapis.com/google.crypto.tink.HpkePublicKey"
_VALUE = b"\x00\x01\x02\x03"


def _key_data(type_url: str = _TYPE_URL, material: int = tink_pb2.KeyData.ASYMMETRIC_PUBLIC, value: bytes = _VALUE) -> tink_pb2.KeyData:
    return tink_pb2.KeyData(
        type_url=type_url,
        value=value,
        key_material_type=material,
    )


def _key(key_data: tink_pb2.KeyData | None = None, *, key_id: int = 1, status: int = tink_pb2.ENABLED, prefix: int = tink_pb2.TINK) -> tink_pb2.Keyset.Key:
    return tink_pb2.Keyset.Key(
        key_data=key_data if key_data is not None else _key_data(),
        status=status,
        key_id=key_id,
        output_prefix_type=prefix,
    )


def _keyset(*keys: tink_pb2.Keyset.Key, primary_key_id: int = 1) -> tink_pb2.Keyset:
    keyset = tink_pb2.Keyset(primary_key_id=primary_key_id)
    for key in keys:
        keyset.key.append(key)
    return keyset


def _serialize(keyset: tink_pb2.Keyset) -> bytes:
    return keyset.SerializeToString()


def _assert_invalid(serialized: bytes) -> None:
    with pytest.raises(PublicKeyBundleValidationError) as excinfo:
        _parse_single_public_keyset(serialized, _TYPE_URL)
    assert str(excinfo.value) == "invalid public key bundle"
    assert excinfo.value.args == ("invalid public key bundle",)
    assert repr(serialized) not in repr(excinfo.value)


def test_valid_returns_equal_independent_key_data() -> None:
    keyset = _keyset(_key())
    parsed = _parse_single_public_keyset(_serialize(keyset), _TYPE_URL)
    assert parsed == _key_data()
    assert parsed is not _key_data()
    parsed.value = b"\xff"
    assert _key_data().value == _VALUE


def test_empty_rejected() -> None:
    _assert_invalid(b"")


def test_oversize_rejected() -> None:
    keyset = _keyset(_key())
    serialized = _serialize(keyset)
    assert len(serialized) <= MAX_PUBLIC_KEYSET_BYTES
    _assert_invalid(serialized + b"\x00" * (MAX_PUBLIC_KEYSET_BYTES - len(serialized) + 1))


def test_malformed_rejected() -> None:
    _assert_invalid(b"\xff\xff\xff\xff")


def test_zero_primary_key_id_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(), primary_key_id=0)))


def test_mismatched_primary_key_id_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(key_id=1), primary_key_id=2)))


def test_zero_key_id_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(key_id=0))))


def test_zero_keys_rejected() -> None:
    _assert_invalid(_serialize(_keyset()))


def test_two_keys_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(key_id=1), _key(key_id=2), primary_key_id=1)))


def test_disabled_status_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(status=tink_pb2.DISABLED))))


def test_destroyed_status_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(status=tink_pb2.DESTROYED))))


def test_unknown_status_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(status=tink_pb2.UNKNOWN_STATUS))))


def test_raw_prefix_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(prefix=tink_pb2.RAW))))


def test_legacy_prefix_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(prefix=tink_pb2.LEGACY))))


def test_crunchy_prefix_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(prefix=tink_pb2.CRUNCHY))))


def test_unknown_prefix_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(prefix=tink_pb2.UNKNOWN_PREFIX))))


def test_wrong_type_url_rejected() -> None:
    _assert_invalid(
        _serialize(_keyset(_key(_key_data(type_url="type.googleapis.com/other"))))
    )


def test_private_material_rejected() -> None:
    _assert_invalid(
        _serialize(_keyset(_key(_key_data(material=tink_pb2.KeyData.ASYMMETRIC_PRIVATE))))
    )


def test_symmetric_material_rejected() -> None:
    _assert_invalid(
        _serialize(_keyset(_key(_key_data(material=tink_pb2.KeyData.SYMMETRIC))))
    )


def test_remote_material_rejected() -> None:
    _assert_invalid(
        _serialize(_keyset(_key(_key_data(material=tink_pb2.KeyData.REMOTE))))
    )


def test_unknown_material_rejected() -> None:
    _assert_invalid(
        _serialize(_keyset(_key(_key_data(material=tink_pb2.KeyData.UNKNOWN_KEYMATERIAL))))
    )


def test_empty_value_rejected() -> None:
    _assert_invalid(_serialize(_keyset(_key(_key_data(value=b"")))))


def test_outer_unknown_field_rejected() -> None:
    keyset = _keyset(_key())
    serialized = _serialize(keyset)
    unknown = serialized + b"\x18\x01"
    _assert_invalid(unknown)


def test_nested_key_unknown_field_rejected() -> None:
    key = _key()
    key_serialized = key.SerializeToString()
    key_with_unknown = key_serialized + b"\x08\x01"
    keyset = _keyset()
    keyset.primary_key_id = 1
    keyset.key.append(tink_pb2.Keyset.Key())
    keyset.key[0].ParseFromString(key_with_unknown)
    _assert_invalid(_serialize(keyset))


def test_nested_key_data_unknown_field_rejected() -> None:
    key_data = _key_data()
    key_data_serialized = key_data.SerializeToString()
    key_data_with_unknown = key_data_serialized + b"\x08\x01"
    keyset = _keyset(_key(_key_data()))
    keyset.key[0].key_data.ParseFromString(key_data_with_unknown)
    _assert_invalid(_serialize(keyset))


def test_all_errors_share_exact_args_and_no_input_in_repr() -> None:
    cases = [
        b"",
        b"\xff\xff\xff\xff",
        _serialize(_keyset()),
        _serialize(_keyset(_key(key_id=0))),
        _serialize(_keyset(_key(prefix=tink_pb2.RAW))),
    ]
    for serialized in cases:
        with pytest.raises(PublicKeyBundleValidationError) as excinfo:
            _parse_single_public_keyset(serialized, _TYPE_URL)
        assert excinfo.value.args == ("invalid public key bundle",)
        assert repr(serialized) not in repr(excinfo.value)


def test_source_avoids_broad_catch_and_logging() -> None:
    source = inspect.getsource(__import__("postmark.users.key_bundle_validation", fromlist=["*"]))
    assert "except Exception" not in source
    assert "except BaseException" not in source
    assert "logging" not in source