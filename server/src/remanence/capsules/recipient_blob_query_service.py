"""Recipient-only READY blob lookup. The caller owns commit/rollback."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Final

from sqlalchemy import select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import Capsule, CapsuleState


_GENERIC_SERVICE_MESSAGE: Final = "recipient blob query failed"
_KIND_MAX_SIZE: Final = {
    CapsuleBlobKind.RECOGNITION_MANIFEST: LIMITS_V1.recognition_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.CONTENT_MANIFEST: LIMITS_V1.content_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.PHOTO: LIMITS_V1.encrypted_photo_max_ciphertext_bytes,
}


class RecipientBlobQueryError(Exception):
    """Redacted, stable failure for the later recipient blob HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class RecipientBlobSnapshot:
    capsule_id: uuid.UUID
    blob_id: uuid.UUID
    kind: CapsuleBlobKind
    ordinal: int | None
    object_key: str
    expected_ciphertext_size: int
    expected_ciphertext_sha256: bytes

    def __repr__(self) -> str:
        return "RecipientBlobSnapshot(<redacted>)"


def _error(code: str) -> RecipientBlobQueryError:
    return RecipientBlobQueryError(code)


def _require_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")
    return value


class RecipientBlobQueryService:
    """Resolve one STORED blob on a READY capsule routed to the recipient."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def get_ready_blob(
        self,
        *,
        authenticated_recipient_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        blob_id: uuid.UUID,
    ) -> RecipientBlobSnapshot:
        recipient_id = _require_uuid(authenticated_recipient_user_id)
        capsule_id = _require_uuid(capsule_id)
        blob_id = _require_uuid(blob_id)
        mapped: RecipientBlobQueryError | None = None
        snapshot: RecipientBlobSnapshot | None = None
        try:
            snapshot = self._get_ready_blob(
                recipient_id=recipient_id,
                capsule_id=capsule_id,
                blob_id=blob_id,
            )
        except RecipientBlobQueryError as exc:
            mapped = exc
        except Exception:
            mapped = _error("INTERNAL_ERROR")
        if mapped is not None:
            raise mapped
        if snapshot is None:
            raise _error("INTERNAL_ERROR")
        return snapshot

    def _get_ready_blob(
        self,
        *,
        recipient_id: uuid.UUID,
        capsule_id: uuid.UUID,
        blob_id: uuid.UUID,
    ) -> RecipientBlobSnapshot:
        with self._session.no_autoflush:
            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            if (
                capsule is None
                or capsule.recipient_user_id != recipient_id
                or capsule.state is not CapsuleState.READY
            ):
                raise _error("CAPSULE_NOT_FOUND")
            blob = self._session.scalar(
                select(CapsuleBlob)
                .where(
                    CapsuleBlob.id == blob_id,
                    CapsuleBlob.capsule_id == capsule.id,
                )
                .execution_options(populate_existing=True)
            )
            if blob is None:
                raise _error("BLOB_NOT_DECLARED")
            delivery = self._session.scalar(
                select(RecipientDeliveryState)
                .where(
                    RecipientDeliveryState.recipient_user_id == recipient_id,
                    RecipientDeliveryState.capsule_id == capsule.id,
                )
                .execution_options(populate_existing=True)
            )
            self._require_delivery(capsule, delivery, recipient_id=recipient_id)
            return self._snapshot_blob(capsule, blob)

    def _require_delivery(
        self,
        capsule: Capsule,
        delivery: RecipientDeliveryState | None,
        *,
        recipient_id: uuid.UUID,
    ) -> None:
        if delivery is None or not isinstance(delivery, RecipientDeliveryState):
            raise _error("INTERNAL_ERROR")
        if delivery.capsule_id != capsule.id:
            raise _error("INTERNAL_ERROR")
        if delivery.recipient_user_id != recipient_id:
            raise _error("INTERNAL_ERROR")
        if delivery.recipient_user_id != capsule.recipient_user_id:
            raise _error("INTERNAL_ERROR")
        if delivery.available_at != capsule.ready_at:
            raise _error("INTERNAL_ERROR")
        if delivery.state is RecipientDeliveryStatus.AVAILABLE:
            if delivery.ciphertext_synced_at is not None:
                raise _error("INTERNAL_ERROR")
            return
        if delivery.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED:
            if delivery.ciphertext_synced_at is None:
                raise _error("INTERNAL_ERROR")
            return
        raise _error("INTERNAL_ERROR")

    def _snapshot_blob(self, capsule: Capsule, blob: CapsuleBlob) -> RecipientBlobSnapshot:
        if not isinstance(blob, CapsuleBlob):
            raise _error("INTERNAL_ERROR")
        if blob.capsule_id != capsule.id or not isinstance(blob.id, uuid.UUID):
            raise _error("INTERNAL_ERROR")
        if blob.state is not CapsuleBlobState.STORED:
            raise _error("INTERNAL_ERROR")
        kind = blob.kind
        if kind not in _KIND_MAX_SIZE:
            raise _error("INTERNAL_ERROR")
        size = blob.expected_ciphertext_size
        digest = blob.expected_ciphertext_sha256
        object_key = blob.object_key
        if type(size) is not int or not 0 < size <= _KIND_MAX_SIZE[kind]:
            raise _error("INTERNAL_ERROR")
        if type(digest) is not bytes or len(digest) != 32:
            raise _error("INTERNAL_ERROR")
        if type(object_key) is not str or not object_key or len(object_key) > 512:
            raise _error("INTERNAL_ERROR")
        if kind is CapsuleBlobKind.PHOTO:
            ordinal = blob.ordinal
            if type(ordinal) is not int:
                raise _error("INTERNAL_ERROR")
            if not LIMITS_V1.photo_ordinal_min <= ordinal <= LIMITS_V1.photo_ordinal_max:
                raise _error("INTERNAL_ERROR")
        else:
            if blob.ordinal is not None:
                raise _error("INTERNAL_ERROR")
            ordinal = None
        return RecipientBlobSnapshot(
            capsule_id=capsule.id,
            blob_id=blob.id,
            kind=kind,
            ordinal=ordinal,
            object_key=object_key,
            expected_ciphertext_size=size,
            expected_ciphertext_sha256=bytes(digest),
        )
