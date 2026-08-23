"""Strict Tink public keyset envelope and algorithm validation."""

from google.protobuf.message import DecodeError
from tink.proto import ed25519_pb2, hpke_pb2, tink_pb2

MAX_PUBLIC_KEYSET_BYTES = 4096
SUPPORTED_KEY_BUNDLE_SUITE = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION = 1
HPKE_PUBLIC_KEY_TYPE_URL = "type.googleapis.com/google.crypto.tink.HpkePublicKey"
ED25519_PUBLIC_KEY_TYPE_URL = "type.googleapis.com/google.crypto.tink.Ed25519PublicKey"
_INVALID = "invalid public key bundle"


class PublicKeyBundleValidationError(ValueError):
    def __init__(self) -> None:
        super().__init__(_INVALID)


def validate_public_key_bundle(
    *,
    suite: str,
    protocol_version: int,
    encryption_public_keyset: bytes,
    signing_public_keyset: bytes,
) -> None:
    if suite != SUPPORTED_KEY_BUNDLE_SUITE:
        raise PublicKeyBundleValidationError()
    if (
        not isinstance(protocol_version, int)
        or isinstance(protocol_version, bool)
        or protocol_version != SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION
    ):
        raise PublicKeyBundleValidationError()

    encryption_key_data = _parse_single_public_keyset(
        encryption_public_keyset, HPKE_PUBLIC_KEY_TYPE_URL
    )
    signing_key_data = _parse_single_public_keyset(
        signing_public_keyset, ED25519_PUBLIC_KEY_TYPE_URL
    )

    hpke_public_key = _parse_embedded(
        encryption_key_data.value, hpke_pb2.HpkePublicKey
    )
    _validate_hpke(hpke_public_key)

    ed25519_public_key = _parse_embedded(
        signing_key_data.value, ed25519_pb2.Ed25519PublicKey
    )
    _validate_ed25519(ed25519_public_key)


def _validate_hpke(hpke_public_key: hpke_pb2.HpkePublicKey) -> None:
    if hpke_public_key.version != 0:
        raise PublicKeyBundleValidationError()
    if not hpke_public_key.HasField("params"):
        raise PublicKeyBundleValidationError()
    params = hpke_public_key.params
    if params.kem != hpke_pb2.DHKEM_X25519_HKDF_SHA256:
        raise PublicKeyBundleValidationError()
    if params.kdf != hpke_pb2.HKDF_SHA256:
        raise PublicKeyBundleValidationError()
    if params.aead != hpke_pb2.AES_256_GCM:
        raise PublicKeyBundleValidationError()
    if len(hpke_public_key.public_key) != 32:
        raise PublicKeyBundleValidationError()


def _validate_ed25519(ed25519_public_key: ed25519_pb2.Ed25519PublicKey) -> None:
    if ed25519_public_key.version != 0:
        raise PublicKeyBundleValidationError()
    if len(ed25519_public_key.key_value) != 32:
        raise PublicKeyBundleValidationError()


def _parse_embedded(serialized: bytes, message_type):
    message = message_type()
    try:
        message.ParseFromString(serialized)
    except (DecodeError, TypeError, ValueError):
        raise PublicKeyBundleValidationError() from None
    _reject_unknown_fields(message)
    return message


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