"""Short pre-body authorization for one ciphertext upload.

The upload handler runs this in its own short transaction before it opens a
staging file. It answers only what the declared artifact and the requester's
authority allow, so an unauthorized or undeclared upload is rejected without
ever streaming bytes to disk. Promotion repeats the state and ownership checks
transactionally to cover races that happen while the body is streamed.
"""

from __future__ import annotations

import hmac
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import Capsule, CapsuleState
from remanence.users.models import User


_ARTIFACT_MAX_BYTES = {
    CapsuleBlobKind.RECOGNITION_MANIFEST: LIMITS_V1.recognition_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.CONTENT_MANIFEST: LIMITS_V1.content_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.PHOTO: LIMITS_V1.encrypted_photo_max_ciphertext_bytes,
}

_GENERIC_SERVICE_MESSAGE = "capsule blob upload preflight failed"


class CapsuleUploadPreflightError(Exception):
    """Redacted, stable failure suitable for the HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True)
class CapsuleUploadPreflight:
    """Authoritative upload expectations taken from the declaration row."""

    capsule_id: uuid.UUID
    blob_id: uuid.UUID
    object_key: str
    expected_size: int
    expected_sha256_hex: str
    artifact_max_bytes: int
    state: CapsuleBlobState

    def __repr__(self) -> str:
        return "CapsuleUploadPreflight(<redacted>)"


def _error(code: str) -> CapsuleUploadPreflightError:
    return CapsuleUploadPreflightError(code)


def _require_uuid(value: object) -> None:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")


def _require_utc(value: object) -> None:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise _error("VALIDATION_FAILED")


class CapsuleUploadPreflightService:
    """Authorize one declared blob before any request body is staged."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def preflight(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        blob_id: uuid.UUID,
        declared_size: int,
        declared_sha256_hex: str,
        now: datetime,
    ) -> CapsuleUploadPreflight:
        try:
            return self._preflight(
                authenticated_sender_user_id=authenticated_sender_user_id,
                capsule_id=capsule_id,
                blob_id=blob_id,
                declared_size=declared_size,
                declared_sha256_hex=declared_sha256_hex,
                now=now,
            )
        except CapsuleUploadPreflightError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None

    def _preflight(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        blob_id: uuid.UUID,
        declared_size: int,
        declared_sha256_hex: str,
        now: datetime,
    ) -> CapsuleUploadPreflight:
        _require_uuid(authenticated_sender_user_id)
        _require_uuid(capsule_id)
        _require_uuid(blob_id)
        if type(declared_size) is not int:
            raise _error("BLOB_SIZE_INVALID")
        if not isinstance(declared_sha256_hex, str):
            raise _error("BLOB_HASH_MISMATCH")
        _require_utc(now)

        with self._session.no_autoflush:
            sender = self._session.get(User, authenticated_sender_user_id)
            if sender is None or sender.disabled_at is not None:
                raise _error("AUTH_INVALID")

            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            if capsule is None or capsule.sender_user_id != authenticated_sender_user_id:
                raise _error("CAPSULE_NOT_FOUND")
            if capsule.state is not CapsuleState.DRAFT:
                raise _error("CAPSULE_STATE_INVALID")
            if now >= capsule.draft_expires_at:
                raise _error("DRAFT_EXPIRED")

            blob = self._session.scalar(
                select(CapsuleBlob)
                .where(
                    CapsuleBlob.id == blob_id,
                    CapsuleBlob.capsule_id == capsule_id,
                )
                .execution_options(populate_existing=True)
            )
            if blob is None:
                raise _error("BLOB_NOT_DECLARED")

            if declared_size != blob.expected_ciphertext_size:
                raise _error("BLOB_SIZE_INVALID")
            if not hmac.compare_digest(
                declared_sha256_hex,
                blob.expected_ciphertext_sha256.hex(),
            ):
                raise _error("BLOB_HASH_MISMATCH")

            artifact_max_bytes = _ARTIFACT_MAX_BYTES.get(blob.kind)
            if artifact_max_bytes is None:
                raise _error("INTERNAL_ERROR")
            if not 0 < blob.expected_ciphertext_size <= artifact_max_bytes:
                raise _error("BLOB_SIZE_INVALID")

            return CapsuleUploadPreflight(
                capsule_id=blob.capsule_id,
                blob_id=blob.id,
                object_key=blob.object_key,
                expected_size=blob.expected_ciphertext_size,
                expected_sha256_hex=blob.expected_ciphertext_sha256.hex(),
                artifact_max_bytes=artifact_max_bytes,
                state=blob.state,
            )
