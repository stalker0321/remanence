"""Transactional, idempotent creation of existing-user capsule drafts."""

import hmac
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobState
from remanence.capsules.encoding import decode_canonical_base64url
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.locking import capsule_lock_key, idempotency_scope_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.schemas import CreateCapsuleDraftRequest
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle
from remanence.users.models import User


_METHOD = "POST"
_ROUTE = "/v1/capsules"
_IDEMPOTENCY_LIFETIME = timedelta(hours=24)
_GENERIC_SERVICE_MESSAGE = "capsule draft operation failed"


class CapsuleDraftServiceError(Exception):
    """Redacted, stable service failure suitable for later API mapping."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True)
class CapsuleDraftBlobResult:
    blob_id: uuid.UUID
    state: CapsuleBlobState


@dataclass(frozen=True)
class CapsuleDraftResult:
    capsule_id: uuid.UUID
    state: CapsuleState
    draft_expires_at: datetime
    blobs: tuple[CapsuleDraftBlobResult, ...]
    is_replay: bool


def _validation_error() -> CapsuleDraftServiceError:
    return CapsuleDraftServiceError("VALIDATION_FAILED")


def _error(code: str) -> CapsuleDraftServiceError:
    return CapsuleDraftServiceError(code)


def _require_utc(now: object) -> None:
    if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset() != timedelta(0):
        raise _validation_error()


def _is_utc_whole_second(value: object) -> bool:
    return (
        isinstance(value, datetime)
        and value.tzinfo is not None
        and value.utcoffset() == timedelta(0)
        and value.microsecond == 0
    )


def _require_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, uuid.UUID):
        raise _validation_error()
    return value


def _require_hash(value: object) -> bytes:
    if not isinstance(value, bytes) or len(value) != 32:
        raise _validation_error()
    return value


def _parse_iso_utc(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValueError
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0):
        raise ValueError
    return parsed


def _parse_replay_result(
    response_json: object,
    *,
    request: CreateCapsuleDraftRequest,
    expected_draft_expires_at: datetime,
) -> CapsuleDraftResult:
    if not isinstance(response_json, dict):
        raise ValueError
    if set(response_json) != {"capsule_id", "state", "draft_expires_at", "blobs", "is_replay"}:
        raise ValueError
    if response_json["is_replay"] is not False:
        raise ValueError
    if response_json["capsule_id"] != str(request.capsule_id):
        raise ValueError
    if response_json["state"] != CapsuleState.DRAFT.value:
        raise ValueError
    expiry = _parse_iso_utc(response_json["draft_expires_at"])
    if expiry != expected_draft_expires_at:
        raise ValueError
    raw_blobs = response_json["blobs"]
    if not isinstance(raw_blobs, list) or len(raw_blobs) != len(request.blobs):
        raise ValueError

    parsed_blobs: list[CapsuleDraftBlobResult] = []
    for raw_blob, requested_blob in zip(raw_blobs, request.blobs, strict=True):
        if not isinstance(raw_blob, dict) or set(raw_blob) != {"blob_id", "state"}:
            raise ValueError
        if raw_blob["blob_id"] != str(requested_blob.blob_id):
            raise ValueError
        if raw_blob["state"] != CapsuleBlobState.DECLARED.value:
            raise ValueError
        parsed_blobs.append(
            CapsuleDraftBlobResult(
                blob_id=requested_blob.blob_id,
                state=CapsuleBlobState.DECLARED,
            )
        )
    return CapsuleDraftResult(
        capsule_id=request.capsule_id,
        state=CapsuleState.DRAFT,
        draft_expires_at=expiry,
        blobs=tuple(parsed_blobs),
        is_replay=True,
    )


def _response_json(result: CapsuleDraftResult) -> dict[str, Any]:
    return {
        "capsule_id": str(result.capsule_id),
        "state": result.state.value,
        "draft_expires_at": result.draft_expires_at.isoformat(),
        "blobs": [
            {"blob_id": str(blob.blob_id), "state": blob.state.value}
            for blob in result.blobs
        ],
        "is_replay": False,
    }


class CapsuleDraftService:
    def __init__(self, session: Session) -> None:
        self._session = session

    def create_draft(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        request: CreateCapsuleDraftRequest,
        idempotency_key: uuid.UUID,
        request_sha256: bytes,
        now: datetime,
    ) -> CapsuleDraftResult:
        _require_uuid(authenticated_sender_user_id)
        if not isinstance(request, CreateCapsuleDraftRequest):
            raise _validation_error()
        _require_uuid(idempotency_key)
        request_sha256 = _require_hash(request_sha256)
        _require_utc(now)
        canonical_now = now.replace(microsecond=0)

        with self._session.no_autoflush:
            self._session.execute(
                select(func.pg_advisory_xact_lock(
                    idempotency_scope_lock_key(
                        authenticated_sender_user_id,
                        idempotency_key,
                    )
                ))
            )
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(request.capsule_id)))
            )
            record = self._session.scalar(
                select(CapsuleIdempotencyRecord).where(
                    CapsuleIdempotencyRecord.owner_user_id == authenticated_sender_user_id,
                    CapsuleIdempotencyRecord.method == _METHOD,
                    CapsuleIdempotencyRecord.normalized_route == _ROUTE,
                    CapsuleIdempotencyRecord.idempotency_key == idempotency_key,
                )
            )
            if record is not None:
                if not isinstance(record.request_sha256, bytes) or len(record.request_sha256) != 32:
                    raise _error("INTERNAL_ERROR")
                if not hmac.compare_digest(record.request_sha256, request_sha256):
                    raise _error("IDEMPOTENCY_CONFLICT")
                try:
                    if record.response_status != 201:
                        raise ValueError
                    if (
                        not _is_utc_whole_second(record.created_at)
                        or not _is_utc_whole_second(record.expires_at)
                        or record.expires_at
                        != record.created_at + _IDEMPOTENCY_LIFETIME
                    ):
                        raise ValueError
                    return _parse_replay_result(
                        record.response_json,
                        request=request,
                        expected_draft_expires_at=(
                            record.created_at.astimezone(timezone.utc)
                            + timedelta(seconds=LIMITS_V1.draft_lifetime_seconds)
                        ),
                    )
                except (TypeError, ValueError, OverflowError):
                    raise _error("INTERNAL_ERROR") from None

            if self._session.get(Capsule, request.capsule_id) is not None:
                raise _error("IDEMPOTENCY_CONFLICT")

            sender = self._session.get(User, authenticated_sender_user_id)
            if sender is None or sender.disabled_at is not None:
                raise _error("AUTH_INVALID")

            recipient = self._session.get(User, request.recipient_user_id)
            if recipient is None or recipient.disabled_at is not None:
                raise _error("RECIPIENT_NOT_CONFIRMED")

            sender_bundle = self._session.get(UserKeyBundle, request.sender_key_bundle_id)
            if sender_bundle is None or sender_bundle.user_id != sender.id:
                raise _error("KEY_BUNDLE_NOT_FOUND")
            if (
                sender_bundle.status is not KeyBundleStatus.ACTIVE
                or sender_bundle.protocol_version != request.protocol_version
            ):
                raise _error("KEY_BUNDLE_INVALID")

            recipient_bundle = self._session.get(UserKeyBundle, request.recipient_key_bundle_id)
            if (
                recipient_bundle is None
                or recipient_bundle.user_id != recipient.id
                or recipient_bundle.status is not KeyBundleStatus.ACTIVE
                or recipient_bundle.protocol_version != request.protocol_version
            ):
                raise _error("RECIPIENT_KEY_STALE")

            try:
                decoded_hashes = [
                    decode_canonical_base64url(blob.ciphertext_sha256, expected_length=32)
                    for blob in request.blobs
                ]
            except (TypeError, ValueError):
                raise _validation_error() from None

            draft_expires_at = canonical_now + timedelta(seconds=LIMITS_V1.draft_lifetime_seconds)
            result = CapsuleDraftResult(
                capsule_id=request.capsule_id,
                state=CapsuleState.DRAFT,
                draft_expires_at=draft_expires_at,
                blobs=tuple(
                    CapsuleDraftBlobResult(blob_id=blob.blob_id, state=CapsuleBlobState.DECLARED)
                    for blob in request.blobs
                ),
                is_replay=False,
            )
            capsule = Capsule(
                id=request.capsule_id,
                sender_user_id=authenticated_sender_user_id,
                recipient_user_id=request.recipient_user_id,
                sender_key_bundle_id=request.sender_key_bundle_id,
                recipient_key_bundle_id=request.recipient_key_bundle_id,
                protocol_version=request.protocol_version,
                state=CapsuleState.DRAFT,
                signed_statement=None,
                signed_statement_sha256=None,
                publish_signature=None,
                created_at=canonical_now,
                ready_at=None,
                draft_expires_at=draft_expires_at,
            )
            self._session.add(capsule)
            self._session.flush([capsule])
            for blob, digest in zip(request.blobs, decoded_hashes, strict=True):
                self._session.add(
                    CapsuleBlob(
                        id=blob.blob_id,
                        capsule_id=request.capsule_id,
                        kind=blob.kind,
                        ordinal=blob.ordinal,
                        object_key=f"capsules/{request.capsule_id}/{blob.blob_id}.blob",
                        expected_ciphertext_size=blob.ciphertext_size,
                        expected_ciphertext_sha256=digest,
                        state=CapsuleBlobState.DECLARED,
                    )
                )
            self._session.add(
                CapsuleIdempotencyRecord(
                    owner_user_id=authenticated_sender_user_id,
                    method=_METHOD,
                    normalized_route=_ROUTE,
                    idempotency_key=idempotency_key,
                    request_sha256=request_sha256,
                    response_status=201,
                    response_json=_response_json(result),
                    created_at=canonical_now,
                    expires_at=canonical_now + _IDEMPOTENCY_LIFETIME,
                )
            )
            self._session.flush()
            return result
