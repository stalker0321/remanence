"""Strict Tink public keyset envelope validation."""

from google.protobuf.message import DecodeError
from tink.proto import tink_pb2

MAX_PUBLIC_KEYSET_BYTES = 4096
_INVALID = "invalid public key bundle"


class PublicKeyBundleValidationError(ValueError):
    def __init__(self) -> None:
        super().__init__(_INVALID)


def _parse_single_public_keyset(
    serialized: bytes, expected_type_url: str
) -> tink_pb2.KeyData:
    if not serialized:
        raise PublicKeyBundleValidationError()
    if len(serialized) > MAX_PUBLIC_KEYSET_BYTES:
        raise PublicKeyBundleValidationError()

    keyset = tink_pb2.Keyset()
    try:
        keyset.ParseFromString(serialized)
    except (DecodeError, TypeError, ValueError):
        raise PublicKeyBundleValidationError() from None
    _reject_unknown_fields(keyset)

    if len(keyset.key) != 1:
        raise PublicKeyBundleValidationError()
    if keyset.primary_key_id == 0:
        raise PublicKeyBundleValidationError()
    if keyset.primary_key_id != keyset.key[0].key_id:
        raise PublicKeyBundleValidationError()

    key = keyset.key[0]
    if key.key_id == 0:
        raise PublicKeyBundleValidationError()
    if key.status != tink_pb2.ENABLED:
        raise PublicKeyBundleValidationError()
    if key.output_prefix_type != tink_pb2.TINK:
        raise PublicKeyBundleValidationError()
    _reject_unknown_fields(key)

    key_data = key.key_data
    if key_data.type_url != expected_type_url:
        raise PublicKeyBundleValidationError()
    if key_data.key_material_type != tink_pb2.KeyData.ASYMMETRIC_PUBLIC:
        raise PublicKeyBundleValidationError()
    if not key_data.value:
        raise PublicKeyBundleValidationError()
    _reject_unknown_fields(key_data)

    return _deep_copy(key_data)


def _reject_unknown_fields(message) -> None:
    before = message.SerializeToString(deterministic=True)
    message.DiscardUnknownFields()
    after = message.SerializeToString(deterministic=True)
    if before != after:
        raise PublicKeyBundleValidationError()


def _deep_copy(key_data: tink_pb2.KeyData) -> tink_pb2.KeyData:
    copy = tink_pb2.KeyData()
    copy.CopyFrom(key_data)
    return copy