"""Strict additive DTOs; reuse actual public WebAuthn and ADR-011 validators."""
import uuid

from pydantic import BaseModel, ConfigDict, Field, SecretStr, StrictInt, field_validator, model_validator

from remanence.api.auth_schemas import RegistrationKeyBundleRequest
from remanence.api.recovery import EncryptedRecord, VerifyRequest, b64, unb64
from remanence.users.handles import normalize_handle


def canonical_uuid(value: str) -> str:
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("canonical UUID required")
    return value


class EpochRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True, strict=True)
    epoch: StrictInt = Field(gt=0, le=9223372036854775807)


class SetupOptions(EpochRequest):
    credential_id: str = Field(min_length=1, max_length=1366)

    @field_validator("credential_id")
    @classmethod
    def credential(cls, value):
        unb64(value, high=1024)
        return value


class SetupVerify(VerifyRequest):
    epoch: StrictInt = Field(gt=0, le=9223372036854775807)

    @model_validator(mode="after")
    def public_only(self):
        # Preserve the actual public-only validator, but make malformed nested
        # JSON a redacted schema error rather than an accidental 500/UNKNOWN.
        try:
            return super().public_only()
        except (KeyError, TypeError, AttributeError):
            raise ValueError("invalid public credential response") from None

    @field_validator("flow")
    @classmethod
    def flow_id(cls, value):
        return canonical_uuid(value)


class AtomicKeyBundle(RegistrationKeyBundleRequest):
    @field_validator("key_bundle_id", mode="before")
    @classmethod
    def bundle_id(cls, value):
        return canonical_uuid(value)

    @field_validator("protocol_version", mode="before")
    @classmethod
    def integer_version(cls, value):
        if type(value) is not int:
            raise ValueError("integer protocol version required")
        return value

    @field_validator("encryption_public_keyset", "signing_public_keyset", mode="before")
    @classmethod
    def canonical_public(cls, value):
        # Canonical wire bytes, not just an equivalent permissively decoded keyset.
        if not isinstance(value, str) or b64(unb64(value, high=16384)) != value:
            raise ValueError("canonical public keyset required")
        return value


class FinalRequest(EpochRequest):
    operation_id: str = Field(max_length=36)
    final_authorization: SecretStr = Field(repr=False)
    handle: str = Field(max_length=32)
    key_bundle: AtomicKeyBundle
    record: EncryptedRecord = Field(repr=False)

    @field_validator("operation_id")
    @classmethod
    def operation(cls, value):
        return canonical_uuid(value)

    @field_validator("handle")
    @classmethod
    def normalized_handle(cls, value):
        return normalize_handle(value)

    @field_validator("final_authorization")
    @classmethod
    def grant_bound(cls, value):
        secret = value.get_secret_value()
        if not secret.startswith("rgf_"):
            raise ValueError("invalid final authorization")
        unb64(secret[4:], 32, 32)
        return value
