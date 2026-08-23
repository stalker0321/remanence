"""Registration request/response schemas and redacted validation problem."""

import base64
import re
import uuid
from datetime import datetime, timezone

from email_validator import validate_email
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator

from postmark.users.handles import normalize_handle
from postmark.users.key_bundle_validation import (
    ED25519_PUBLIC_KEY_TYPE_URL,
    HPKE_PUBLIC_KEY_TYPE_URL,
    SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION,
    SUPPORTED_KEY_BUNDLE_SUITE,
    validate_public_key_bundle,
)

_BASE64URL_RE = re.compile(r"^[A-Za-z0-9_-]+$")
_ACCESS_TOKEN_PREFIX = "pm_at_"
_REFRESH_TOKEN_PREFIX = "pm_rt_"
_MAX_KEYSET_BYTES = 4096


class RegistrationKeyBundleRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    key_bundle_id: uuid.UUID
    suite: str
    protocol_version: int
    encryption_public_keyset: bytes
    signing_public_keyset: bytes

    @field_validator("key_bundle_id", mode="before")
    @classmethod
    def _canonical_uuid(cls, value: object) -> object:
        if not isinstance(value, str):
            raise ValueError("invalid key bundle id")
        parsed = uuid.UUID(value)
        if str(parsed) != value:
            raise ValueError("invalid key bundle id")
        return parsed

    @field_validator("suite")
    @classmethod
    def _exact_suite(cls, value: str) -> str:
        if value != SUPPORTED_KEY_BUNDLE_SUITE:
            raise ValueError("invalid suite")
        return value

    @field_validator("protocol_version")
    @classmethod
    def _exact_version(cls, value: int) -> int:
        if value != SUPPORTED_KEY_BUNDLE_PROTOCOL_VERSION:
            raise ValueError("invalid protocol version")
        return value

    @field_validator("encryption_public_keyset", "signing_public_keyset")
    @classmethod
    def _decode_keyset(cls, value: bytes) -> bytes:
        if not value:
            raise ValueError("invalid keyset")
        if b"=" in value:
            raise ValueError("invalid keyset")
        if _BASE64URL_RE.fullmatch(value.decode("ascii")) is None:
            raise ValueError("invalid keyset")
        if len(value) > _MAX_KEYSET_BYTES:
            raise ValueError("invalid keyset")
        padded = value + b"=" * (-len(value) % 4)
        try:
            decoded = base64.b64decode(padded, altchars=b"-_", validate=True)
        except Exception:
            raise ValueError("invalid keyset") from None
        if len(decoded) > _MAX_KEYSET_BYTES:
            raise ValueError("invalid keyset")
        return decoded

    @model_validator(mode="after")
    def _validate_bundle(self) -> "RegistrationKeyBundleRequest":
        validate_public_key_bundle(
            suite=self.suite,
            protocol_version=self.protocol_version,
            encryption_public_keyset=self.encryption_public_keyset,
            signing_public_keyset=self.signing_public_keyset,
        )
        return self


class RegistrationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    email: str
    password: SecretStr
    handle: str
    key_bundle: RegistrationKeyBundleRequest

    @field_validator("email")
    @classmethod
    def _normalize_email(cls, value: str) -> str:
        trimmed = value.strip(" \t\n\r\f\v")
        validated = validate_email(trimmed, check_deliverability=False)
        normalized = validated.normalized.casefold()
        if len(normalized) > 320:
            raise ValueError("invalid email")
        return normalized

    @field_validator("password")
    @classmethod
    def _validate_password(cls, value: SecretStr) -> SecretStr:
        password = value.get_secret_value()
        if not 8 <= len(password) <= 128:
            raise ValueError("invalid password")
        return value

    @field_validator("handle")
    @classmethod
    def _normalize_handle(cls, value: str) -> str:
        return normalize_handle(value)


class RegistrationUserResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    user_id: uuid.UUID
    email: str
    handle: str
    created_at: datetime

    @field_validator("created_at")
    @classmethod
    def _utc_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("invalid timestamp")
        if value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("invalid timestamp")
        return value


class RegistrationResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    user: RegistrationUserResponse
    active_key_bundle_id: uuid.UUID
    access_token: str = Field(repr=False)
    access_expires_at: datetime
    refresh_token: str = Field(repr=False)
    refresh_expires_at: datetime

    @field_validator("access_token")
    @classmethod
    def _access_prefix(cls, value: str) -> str:
        if not value.startswith(_ACCESS_TOKEN_PREFIX):
            raise ValueError("invalid access token")
        return value

    @field_validator("refresh_token")
    @classmethod
    def _refresh_prefix(cls, value: str) -> str:
        if not value.startswith(_REFRESH_TOKEN_PREFIX):
            raise ValueError("invalid refresh token")
        return value

    @field_validator("access_expires_at", "refresh_expires_at")
    @classmethod
    def _utc_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("invalid timestamp")
        if value.utcoffset() != timezone.utc.utcoffset(value):
            raise ValueError("invalid timestamp")
        return value


class ProblemDetail(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    type: str
    title: str
    status: int
    code: str


def registration_validation_problem() -> ProblemDetail:
    return ProblemDetail(
        type="https://postmark.invalid/problems/invalid-request",
        title="Invalid request",
        status=422,
        code="INVALID_REQUEST",
    )