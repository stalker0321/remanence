"""Focused tests for bounded capsule draft request validation."""

import base64
import json
from dataclasses import replace
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError

import remanence.capsules.schemas as schemas
from remanence.capsules.blob_models import CapsuleBlobKind
from remanence.capsules.limits import LIMITS_V1, MAX_CREATE_DRAFT_REQUEST_BYTES
from remanence.capsules.schemas import (
    BlobDeclarationRequest,
    CapsuleDraftValidationError,
    CreateCapsuleDraftRequest,
    parse_create_capsule_draft_request,
)


def _digest(value: int = 7) -> str:
    return base64.urlsafe_b64encode(bytes([value]) * 32).decode("ascii").rstrip("=")


def _blob(kind: str, ordinal: int | None, size: int, *, blob_id: UUID | None = None) -> dict:
    return {
        "blob_id": str(blob_id or uuid4()),
        "kind": kind,
        "ordinal": ordinal,
        "ciphertext_size": size,
        "ciphertext_sha256": _digest(),
    }


def _payload(*, blobs: list[dict] | None = None, **overrides: object) -> dict:
    value = {
        "capsule_id": str(uuid4()),
        "recipient_user_id": str(uuid4()),
        "sender_key_bundle_id": str(uuid4()),
        "recipient_key_bundle_id": str(uuid4()),
        "protocol_version": 1,
        "blobs": blobs
        if blobs is not None
        else [
            _blob("RECOGNITION_MANIFEST", None, 100),
            _blob("CONTENT_MANIFEST", None, 100),
            _blob("PHOTO", 0, 200),
            _blob("PHOTO", 1, 200),
            _blob("PHOTO", 2, 200),
        ],
    }
    value.update(overrides)
    return value


def _raw(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


def _assert_invalid(value: object) -> None:
    with pytest.raises(CapsuleDraftValidationError) as exc_info:
        parse_create_capsule_draft_request(_raw(value))
    assert exc_info.value.code == "VALIDATION_FAILED"
    assert exc_info.value.reason == "invalid_request"
    assert str(exc_info.value) == "invalid capsule draft request"


def test_valid_request_and_self_recipient_are_accepted() -> None:
    same_id = uuid4()
    payload = _payload(
        recipient_user_id=str(same_id),
        sender_key_bundle_id=str(same_id),
        recipient_key_bundle_id=str(same_id),
    )
    result = parse_create_capsule_draft_request(_raw(payload))
    assert isinstance(result, CreateCapsuleDraftRequest)
    assert result.recipient_user_id == same_id
    assert result.blobs[0].kind is CapsuleBlobKind.RECOGNITION_MANIFEST
    assert result.blobs[2].ordinal == 0


def test_models_are_strict_and_forbid_unknown_recipient_target_fields() -> None:
    assert BlobDeclarationRequest.model_config["strict"] is True
    assert BlobDeclarationRequest.model_config["extra"] == "forbid"
    assert BlobDeclarationRequest.model_config["hide_input_in_errors"] is True
    assert CreateCapsuleDraftRequest.model_config["strict"] is True
    assert CreateCapsuleDraftRequest.model_config["extra"] == "forbid"
    assert CreateCapsuleDraftRequest.model_config["hide_input_in_errors"] is True
    assert set(BlobDeclarationRequest.model_fields) == {
        "blob_id",
        "kind",
        "ordinal",
        "ciphertext_size",
        "ciphertext_sha256",
    }
    assert set(CreateCapsuleDraftRequest.model_fields) == {
        "capsule_id",
        "recipient_user_id",
        "sender_key_bundle_id",
        "recipient_key_bundle_id",
        "protocol_version",
        "blobs",
    }
    payload = _payload(email="hidden@example.test", handle="alice", target={"email": "x"})
    with pytest.raises(ValidationError) as exc_info:
        CreateCapsuleDraftRequest.model_validate(payload)
    message = str(exc_info.value)
    assert "hidden@example.test" not in message
    assert "alice" not in message
    assert "target" in message

    _assert_invalid(payload)


@pytest.mark.parametrize("field", ["capsule_id", "recipient_user_id", "sender_key_bundle_id", "recipient_key_bundle_id"])
def test_uuid_fields_require_canonical_uuid_text(field: str) -> None:
    payload = _payload(**{field: str(uuid4()).upper()})
    _assert_invalid(payload)


def test_protocol_version_is_exact_and_numeric_types_are_strict() -> None:
    _assert_invalid(_payload(protocol_version=2))
    _assert_invalid(_payload(protocol_version="1"))
    blobs = _payload()["blobs"]
    blobs[2]["ciphertext_size"] = 0
    _assert_invalid(_payload(blobs=blobs))
    blobs = _payload()["blobs"]
    blobs[2]["ciphertext_size"] = "200"
    _assert_invalid(_payload(blobs=blobs))


def test_blob_kind_and_digest_are_canonical() -> None:
    blobs = _payload()["blobs"]
    blobs[0]["kind"] = "UNKNOWN"
    _assert_invalid(_payload(blobs=blobs))

    blobs = _payload()["blobs"]
    blobs[0]["ciphertext_sha256"] = _digest() + "="
    _assert_invalid(_payload(blobs=blobs))

    blobs = _payload()["blobs"]
    blobs[0]["ciphertext_sha256"] = "!" * 43
    _assert_invalid(_payload(blobs=blobs))

    noncanonical = _digest()[:-1] + "B"
    blobs = _payload()["blobs"]
    blobs[0]["ciphertext_sha256"] = noncanonical
    _assert_invalid(_payload(blobs=blobs))


def test_blob_cardinality_and_ids_are_restricted() -> None:
    base = _payload()["blobs"]
    five_photos = base + [_blob("PHOTO", 3, 200), _blob("PHOTO", 4, 200)]
    assert parse_create_capsule_draft_request(_raw(_payload(blobs=five_photos)))
    _assert_invalid(_payload(blobs=base[:4]))
    _assert_invalid(_payload(blobs=base + [_blob("PHOTO", 3, 200), _blob("PHOTO", 4, 200), _blob("PHOTO", 5, 200)]))
    _assert_invalid(_payload(blobs=[base[0], base[0], *base[2:]]))
    _assert_invalid(_payload(blobs=[base[0], base[1], base[2], base[3], base[4], _blob("CONTENT_MANIFEST", None, 100)]))


@pytest.mark.parametrize(
    "blobs",
    [
        lambda: [_blob("CONTENT_MANIFEST", 0, 100), _blob("RECOGNITION_MANIFEST", None, 100), _blob("PHOTO", 0, 200), _blob("PHOTO", 1, 200), _blob("PHOTO", 2, 200)],
        lambda: [_blob("RECOGNITION_MANIFEST", None, 100), _blob("CONTENT_MANIFEST", None, 100), _blob("PHOTO", 0, 200), _blob("PHOTO", 2, 200), _blob("PHOTO", 2, 200)],
        lambda: [_blob("RECOGNITION_MANIFEST", 0, 100), _blob("CONTENT_MANIFEST", None, 100), _blob("PHOTO", 0, 200), _blob("PHOTO", 1, 200), _blob("PHOTO", 2, 200)],
    ],
)
def test_ordinal_shape_is_restricted(blobs) -> None:
    _assert_invalid(_payload(blobs=blobs()))


@pytest.mark.parametrize(
    ("kind", "limit"),
    [
        ("RECOGNITION_MANIFEST", LIMITS_V1.recognition_manifest_max_ciphertext_bytes),
        ("CONTENT_MANIFEST", LIMITS_V1.content_manifest_max_ciphertext_bytes),
        ("PHOTO", LIMITS_V1.encrypted_photo_max_ciphertext_bytes),
    ],
)
def test_per_kind_ciphertext_caps_are_inclusive(kind: str, limit: int) -> None:
    blobs = _payload()["blobs"]
    index = {"RECOGNITION_MANIFEST": 0, "CONTENT_MANIFEST": 1, "PHOTO": 2}[kind]
    blobs[index]["ciphertext_size"] = limit
    assert parse_create_capsule_draft_request(_raw(_payload(blobs=blobs)))
    blobs[index]["ciphertext_size"] = limit + 1
    _assert_invalid(_payload(blobs=blobs))


def test_total_ciphertext_cap_is_enforced(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        schemas,
        "LIMITS_V1",
        replace(LIMITS_V1, total_capsule_max_ciphertext_bytes=500),
    )
    _assert_invalid(_payload())


def test_raw_byte_cap_and_empty_input_are_rejected_before_json_parsing() -> None:
    for raw in (b"", b"{" * (MAX_CREATE_DRAFT_REQUEST_BYTES + 1)):
        with pytest.raises(CapsuleDraftValidationError):
            parse_create_capsule_draft_request(raw)


def test_huge_integer_is_rejected_before_integer_conversion_and_redacted() -> None:
    marker = "9" * 10_000
    raw = _raw(_payload()).replace(
        b'"protocol_version":1',
        b'"protocol_version":' + marker.encode("ascii"),
        1,
    )
    assert len(raw) <= MAX_CREATE_DRAFT_REQUEST_BYTES
    with pytest.raises(CapsuleDraftValidationError) as exc_info:
        parse_create_capsule_draft_request(raw)
    assert str(exc_info.value) == "invalid capsule draft request"
    assert marker not in repr(exc_info.value)


def test_raw_parser_requires_utf8_json_object_and_valid_json() -> None:
    for raw in (b"\xff", b"[1,2]", b"1", b"{", b"null"):
        with pytest.raises(CapsuleDraftValidationError):
            parse_create_capsule_draft_request(raw)


def test_duplicate_json_keys_are_rejected_at_any_depth() -> None:
    valid = _payload()
    duplicate_top = (
        '{"capsule_id":"%s","capsule_id":"%s"}'
        % (valid["capsule_id"], valid["capsule_id"])
    ).encode("utf-8")
    with pytest.raises(CapsuleDraftValidationError):
        parse_create_capsule_draft_request(duplicate_top)

    nested = _raw(valid).decode("utf-8")[:-1] + ',"nested":{"x":1,"x":2}}'
    with pytest.raises(CapsuleDraftValidationError):
        parse_create_capsule_draft_request(nested.encode("utf-8"))


def test_json_nesting_boundary_is_structural() -> None:
    def nested_json(depth: int) -> str:
        value = "0"
        for _ in range(depth):
            value = "[" + value + "]"
        return value

    depth_eight = nested_json(8)
    schemas._validate_json_nesting(depth_eight)
    assert json.loads(depth_eight) == [[[[[[[[0]]]]]]]]

    with pytest.raises(schemas._InvalidJson):
        schemas._validate_json_nesting(nested_json(9))


@pytest.mark.parametrize("number", ["NaN", "Infinity", "-Infinity", "1e999999"])
def test_non_finite_json_numbers_are_rejected(number: str) -> None:
    raw = (
        '{"capsule_id":"%s","recipient_user_id":"%s",'
        '"sender_key_bundle_id":"%s","recipient_key_bundle_id":"%s",'
        '"protocol_version":%s,"blobs":[]}'
        % (uuid4(), uuid4(), uuid4(), uuid4(), number)
    ).encode("ascii")
    with pytest.raises(CapsuleDraftValidationError):
        parse_create_capsule_draft_request(raw)


def test_public_parser_error_never_echoes_sensitive_request_content() -> None:
    payload = _payload(secret_marker="do-not-echo@example.test")
    with pytest.raises(CapsuleDraftValidationError) as exc_info:
        parse_create_capsule_draft_request(_raw(payload))
    assert str(exc_info.value) == "invalid capsule draft request"
    assert "do-not-echo@example.test" not in repr(exc_info.value)
