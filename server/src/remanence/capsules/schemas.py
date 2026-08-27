"""Bounded schemas and parser for creating an existing-user capsule draft."""

import json
import math
import uuid
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from remanence.capsules.blob_models import CapsuleBlobKind
from remanence.capsules.encoding import decode_canonical_base64url
from remanence.capsules.limits import (
    LIMITS_V1,
    MAX_CREATE_DRAFT_REQUEST_BYTES,
    MAX_JSON_NESTING,
)

_GENERIC_VALIDATION_MESSAGE = "invalid capsule draft request"
_MAX_JSON_INTEGER_DIGITS = 20


class CapsuleDraftValidationError(ValueError):
    """Redacted, stable failure for S09 to map to request validation."""

    code = "VALIDATION_FAILED"
    reason = "invalid_request"

    def __init__(self) -> None:
        super().__init__(_GENERIC_VALIDATION_MESSAGE)


class _InvalidJson(Exception):
    pass


def _canonical_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, str):
        raise ValueError("invalid UUID")
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError, TypeError):
        raise ValueError("invalid UUID") from None
    if str(parsed) != value:
        raise ValueError("invalid UUID")
    return parsed


def _validate_canonical_base64url(value: str) -> str:
    decode_canonical_base64url(value, expected_length=32)
    return value


class BlobDeclarationRequest(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", hide_input_in_errors=True)

    blob_id: uuid.UUID
    kind: CapsuleBlobKind
    ordinal: int | None
    ciphertext_size: int = Field(gt=0)
    ciphertext_sha256: str

    @field_validator("blob_id", mode="before")
    @classmethod
    def _canonical_blob_id(cls, value: object) -> uuid.UUID:
        return _canonical_uuid(value)

    @field_validator("kind", mode="before")
    @classmethod
    def _exact_kind(cls, value: object) -> CapsuleBlobKind:
        if not isinstance(value, str):
            raise ValueError("invalid blob kind")
        try:
            return CapsuleBlobKind(value)
        except ValueError:
            raise ValueError("invalid blob kind") from None

    @field_validator("ciphertext_sha256")
    @classmethod
    def _canonical_digest(cls, value: str) -> str:
        return _validate_canonical_base64url(value)


class CreateCapsuleDraftRequest(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", hide_input_in_errors=True)

    capsule_id: uuid.UUID
    recipient_user_id: uuid.UUID
    sender_key_bundle_id: uuid.UUID
    recipient_key_bundle_id: uuid.UUID
    protocol_version: int
    blobs: list[BlobDeclarationRequest]

    @field_validator(
        "capsule_id",
        "recipient_user_id",
        "sender_key_bundle_id",
        "recipient_key_bundle_id",
        mode="before",
    )
    @classmethod
    def _canonical_ids(cls, value: object) -> uuid.UUID:
        return _canonical_uuid(value)

    @field_validator("protocol_version")
    @classmethod
    def _exact_protocol_version(cls, value: int) -> int:
        if value != LIMITS_V1.protocol_version:
            raise ValueError("unsupported protocol version")
        return value

    @model_validator(mode="after")
    def _validate_blob_layout(self) -> "CreateCapsuleDraftRequest":
        recognition = [blob for blob in self.blobs if blob.kind is CapsuleBlobKind.RECOGNITION_MANIFEST]
        content = [blob for blob in self.blobs if blob.kind is CapsuleBlobKind.CONTENT_MANIFEST]
        photos = [blob for blob in self.blobs if blob.kind is CapsuleBlobKind.PHOTO]
        if len(recognition) != LIMITS_V1.recognition_manifest_count:
            raise ValueError("invalid recognition manifest count")
        if len(content) != LIMITS_V1.content_manifest_count:
            raise ValueError("invalid content manifest count")
        if not LIMITS_V1.photo_count_min <= len(photos) <= LIMITS_V1.photo_count_max:
            raise ValueError("invalid photo count")

        blob_ids = [blob.blob_id for blob in self.blobs]
        if len(blob_ids) != len(set(blob_ids)):
            raise ValueError("duplicate blob id")

        for blob in self.blobs:
            if blob.kind is not CapsuleBlobKind.PHOTO and blob.ordinal is not None:
                raise ValueError("manifest ordinal must be null")
        photo_ordinals = [blob.ordinal for blob in photos]
        if photo_ordinals != list(range(len(photos))):
            raise ValueError("invalid photo ordinals")

        max_sizes = {
            CapsuleBlobKind.RECOGNITION_MANIFEST: LIMITS_V1.recognition_manifest_max_ciphertext_bytes,
            CapsuleBlobKind.CONTENT_MANIFEST: LIMITS_V1.content_manifest_max_ciphertext_bytes,
            CapsuleBlobKind.PHOTO: LIMITS_V1.encrypted_photo_max_ciphertext_bytes,
        }
        if any(blob.ciphertext_size > max_sizes[blob.kind] for blob in self.blobs):
            raise ValueError("ciphertext size exceeds limit")
        if sum(blob.ciphertext_size for blob in self.blobs) > LIMITS_V1.total_capsule_max_ciphertext_bytes:
            raise ValueError("total ciphertext size exceeds limit")
        return self


def _reject_duplicate_object_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    keys = [key for key, _ in pairs]
    if len(keys) != len(set(keys)):
        raise _InvalidJson
    return dict(pairs)


def _parse_bounded_int(value: str) -> int:
    digits = value[1:] if value.startswith("-") else value
    if len(digits) > _MAX_JSON_INTEGER_DIGITS or not digits.isascii() or not digits.isdecimal():
        raise _InvalidJson
    try:
        return int(value)
    except (ValueError, OverflowError):
        raise _InvalidJson from None


def _reject_non_finite_number(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError:
        raise _InvalidJson from None
    if not math.isfinite(parsed):
        raise _InvalidJson
    return parsed


def _reject_non_finite_constant(_: str) -> object:
    raise _InvalidJson


def _validate_json_nesting(document: str) -> None:
    depth = 0
    in_string = False
    escaped = False
    for character in document:
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character in "[{":
            depth += 1
            if depth > MAX_JSON_NESTING:
                raise _InvalidJson
        elif character in "]}":
            depth -= 1


def parse_create_capsule_draft_request(raw: bytes) -> CreateCapsuleDraftRequest:
    """Parse a bounded JSON draft request without exposing parser details."""

    if not isinstance(raw, bytes) or not raw or len(raw) > MAX_CREATE_DRAFT_REQUEST_BYTES:
        raise CapsuleDraftValidationError from None
    try:
        document = raw.decode("utf-8")
        _validate_json_nesting(document)
        try:
            decoded = json.loads(
                document,
                object_pairs_hook=_reject_duplicate_object_keys,
                parse_int=_parse_bounded_int,
                parse_constant=_reject_non_finite_constant,
                parse_float=_reject_non_finite_number,
            )
        except (ValueError, OverflowError):
            raise CapsuleDraftValidationError from None
        if not isinstance(decoded, dict):
            raise _InvalidJson
        return CreateCapsuleDraftRequest.model_validate(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError, _InvalidJson, ValidationError):
        raise CapsuleDraftValidationError from None
