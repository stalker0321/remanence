"""Handle-directory lookup response schemas."""

import base64
import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, field_validator

from postmark.api.auth_schemas import validate_utc_aware


def encode_base64url(value: bytes) -> str:
    return base64.b64encode(value, altchars=b"-_").decode("ascii").rstrip("=")


class DirectoryUserSummary(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    user_id: uuid.UUID
    handle: str


class DirectoryKeyBundleResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    key_bundle_id: uuid.UUID
    user_id: uuid.UUID
    suite: str
    protocol_version: int
    encryption_public_keyset: str
    signing_public_keyset: str
    status: str
    created_at: datetime

    @field_validator("status")
    @classmethod
    def _exact_status(cls, value: str) -> str:
        if value != "ACTIVE":
            raise ValueError("invalid status")
        return value

    @field_validator("created_at")
    @classmethod
    def _utc_aware(cls, value: datetime) -> datetime:
        return validate_utc_aware(value)


class DirectoryLookupResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    user: DirectoryUserSummary
    key_bundle: DirectoryKeyBundleResponse
    directory_version: str
