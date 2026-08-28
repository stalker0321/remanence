"""Recipient-only CIPHERTEXT_SYNCED acknowledgement. Caller owns commit/rollback."""

from __future__ import annotations

import hashlib
import hmac
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState


_GENERIC_SERVICE_MESSAGE: Final = "recipient material sync failed"
_SIGNATURE_LENGTH: Final = 69
_KIND_RANK: Final = {
    CapsuleBlobKind.RECOGNITION_MANIFEST: 1,
    CapsuleBlobKind.CONTENT_MANIFEST: 2,
    CapsuleBlobKind.PHOTO: 3,
}
_KIND_MAX_SIZE: Final = {
    CapsuleBlobKind.RECOGNITION_MANIFEST: LIMITS_V1.recognition_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.CONTENT_MANIFEST: LIMITS_V1.content_manifest_max_ciphertext_bytes,
    CapsuleBlobKind.PHOTO: LIMITS_V1.encrypted_photo_max_ciphertext_bytes,
}
_MAX_BLOBS: Final = (
    LIMITS_V1.recognition_manifest_count
    + LIMITS_V1.content_manifest_count
    + LIMITS_V1.photo_count_max
)


class RecipientMaterialSyncedError(Exception):
    """Redacted, stable failure for the later material-synced HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class RecipientMaterialSyncedResult:
    capsule_id: uuid.UUID
    state: RecipientDeliveryStatus
    ciphertext_synced_at: datetime

    def __repr__(self) -> str:
        return "RecipientMaterialSyncedResult(<redacted>)"


def _error(code: str) -> RecipientMaterialSyncedError:
    return RecipientMaterialSyncedError(code)


def _require_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")
    return value


def _require_utc(value: object) -> datetime:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() != timedelta(0)
    ):
        raise _error("VALIDATION_FAILED")
    return value


def _digest_equal(left: object, right: object) -> bool:
    return (
        type(left) is bytes
        and type(right) is bytes
        and len(left) == len(right)
        and hmac.compare_digest(left, right)
    )


class RecipientMaterialSyncedService:
    """CAS AVAILABLE -> CIPHERTEXT_SYNCED for one recipient READY capsule."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def mark_material_synced(
        self,
        *,
        authenticated_recipient_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        now: datetime,
    ) -> RecipientMaterialSyncedResult:
        recipient_id = _require_uuid(authenticated_recipient_user_id)
        capsule_id = _require_uuid(capsule_id)
        now = _require_utc(now)
        mapped: RecipientMaterialSyncedError | None = None
        result: RecipientMaterialSyncedResult | None = None
        try:
            result = self._mark_material_synced(
                recipient_id=recipient_id, capsule_id=capsule_id, now=now
            )
        except RecipientMaterialSyncedError as exc:
            mapped = exc
        except Exception:
            mapped = _error("INTERNAL_ERROR")
        if mapped is not None:
            raise mapped
        if result is None:
            raise _error("INTERNAL_ERROR")
        return result

    def _mark_material_synced(
        self,
        *,
        recipient_id: uuid.UUID,
        capsule_id: uuid.UUID,
        now: datetime,
    ) -> RecipientMaterialSyncedResult:
        with self._session.no_autoflush:
            preflight_id = self._session.scalar(
                select(Capsule.id).where(
                    Capsule.id == capsule_id,
                    Capsule.recipient_user_id == recipient_id,
                    Capsule.state == CapsuleState.READY,
                )
            )
            if preflight_id is None:
                raise _error("CAPSULE_NOT_FOUND")
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
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
            delivery = self._session.scalar(
                select(RecipientDeliveryState)
                .where(
                    RecipientDeliveryState.recipient_user_id == recipient_id,
                    RecipientDeliveryState.capsule_id == capsule.id,
                )
                .execution_options(populate_existing=True)
            )
            envelope = self._session.scalar(
                select(CapsuleEnvelope)
                .where(CapsuleEnvelope.capsule_id == capsule.id)
                .execution_options(populate_existing=True)
            )
            blobs = list(
                self._session.execute(
                    select(
                        CapsuleBlob.id,
                        CapsuleBlob.kind,
                        CapsuleBlob.ordinal,
                        CapsuleBlob.expected_ciphertext_size,
                        CapsuleBlob.expected_ciphertext_sha256,
                        CapsuleBlob.state,
                    )
                    .where(CapsuleBlob.capsule_id == capsule.id)
                    .limit(_MAX_BLOBS + 1)
                ).all()
            )
            self._require_ready_shape(capsule)
            self._require_delivery(capsule, delivery, recipient_id=recipient_id)
            self._require_envelope(capsule, envelope, recipient_id=recipient_id)
            self._require_stored_blobs(blobs)
            if delivery.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED:
                synced_at = delivery.ciphertext_synced_at
                if (
                    not isinstance(synced_at, datetime)
                    or synced_at.tzinfo is None
                    or synced_at.utcoffset() != timedelta(0)
                ):
                    raise _error("INTERNAL_ERROR")
                return self._accepted(capsule.id, synced_at)
            if delivery.state is not RecipientDeliveryStatus.AVAILABLE:
                raise _error("INTERNAL_ERROR")
            if delivery.ciphertext_synced_at is not None:
                raise _error("INTERNAL_ERROR")
            delivery.state = RecipientDeliveryStatus.CIPHERTEXT_SYNCED
            delivery.ciphertext_synced_at = now
            self._session.flush()
            if (
                delivery.state is not RecipientDeliveryStatus.CIPHERTEXT_SYNCED
                or delivery.ciphertext_synced_at != now
            ):
                raise _error("INTERNAL_ERROR")
            return self._accepted(capsule.id, now)

    @staticmethod
    def _accepted(
        capsule_id: uuid.UUID, synced_at: datetime
    ) -> RecipientMaterialSyncedResult:
        if not isinstance(capsule_id, uuid.UUID):
            raise _error("INTERNAL_ERROR")
        if (
            not isinstance(synced_at, datetime)
            or synced_at.tzinfo is None
            or synced_at.utcoffset() != timedelta(0)
        ):
            raise _error("INTERNAL_ERROR")
        return RecipientMaterialSyncedResult(
            capsule_id=capsule_id,
            state=RecipientDeliveryStatus.CIPHERTEXT_SYNCED,
            ciphertext_synced_at=synced_at,
        )

    def _require_ready_shape(self, capsule: Capsule) -> None:
        if capsule.state is not CapsuleState.READY:
            raise _error("INTERNAL_ERROR")
        if type(capsule.protocol_version) is not int:
            raise _error("INTERNAL_ERROR")
        if capsule.protocol_version != LIMITS_V1.protocol_version:
            raise _error("INTERNAL_ERROR")
        ready_at = capsule.ready_at
        if (
            not isinstance(ready_at, datetime)
            or ready_at.tzinfo is None
            or ready_at.utcoffset() != timedelta(0)
        ):
            raise _error("INTERNAL_ERROR")
        statement = capsule.signed_statement
        digest = capsule.signed_statement_sha256
        signature = capsule.publish_signature
        if type(statement) is not bytes or not statement:
            raise _error("INTERNAL_ERROR")
        if type(digest) is not bytes or len(digest) != 32:
            raise _error("INTERNAL_ERROR")
        if not _digest_equal(hashlib.sha256(statement).digest(), digest):
            raise _error("INTERNAL_ERROR")
        if type(signature) is not bytes or len(signature) != _SIGNATURE_LENGTH:
            raise _error("INTERNAL_ERROR")

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

    def _require_envelope(
        self,
        capsule: Capsule,
        envelope: CapsuleEnvelope | None,
        *,
        recipient_id: uuid.UUID,
    ) -> None:
        if envelope is None or not isinstance(envelope, CapsuleEnvelope):
            raise _error("INTERNAL_ERROR")
        if envelope.capsule_id != capsule.id:
            raise _error("INTERNAL_ERROR")
        if envelope.recipient_user_id != recipient_id:
            raise _error("INTERNAL_ERROR")
        if envelope.recipient_user_id != capsule.recipient_user_id:
            raise _error("INTERNAL_ERROR")
        if envelope.recipient_key_bundle_id != capsule.recipient_key_bundle_id:
            raise _error("INTERNAL_ERROR")
        size = envelope.ciphertext_size
        ciphertext = envelope.ciphertext
        digest = envelope.ciphertext_sha256
        if type(size) is not int or not 0 < size <= LIMITS_V1.recipient_envelope_max_ciphertext_bytes:
            raise _error("INTERNAL_ERROR")
        if type(ciphertext) is not bytes or len(ciphertext) != size:
            raise _error("INTERNAL_ERROR")
        if type(digest) is not bytes or len(digest) != 32:
            raise _error("INTERNAL_ERROR")
        if not _digest_equal(hashlib.sha256(ciphertext).digest(), digest):
            raise _error("INTERNAL_ERROR")

    def _require_stored_blobs(self, rows: list[tuple[object, ...]]) -> None:
        if len(rows) > _MAX_BLOBS:
            raise _error("INTERNAL_ERROR")
        seen_ids: set[bytes] = set()
        recognition = 0
        content = 0
        photos: list[int] = []
        total_size = 0
        for row in rows:
            if len(row) != 6:
                raise _error("INTERNAL_ERROR")
            blob_id, kind, ordinal, size, digest, state = row
            if not isinstance(blob_id, uuid.UUID) or blob_id.bytes in seen_ids:
                raise _error("INTERNAL_ERROR")
            seen_ids.add(blob_id.bytes)
            if kind not in _KIND_RANK or state is not CapsuleBlobState.STORED:
                raise _error("INTERNAL_ERROR")
            if type(size) is not int or not 0 < size <= _KIND_MAX_SIZE[kind]:
                raise _error("INTERNAL_ERROR")
            if type(digest) is not bytes or len(digest) != 32:
                raise _error("INTERNAL_ERROR")
            total_size += size
            if total_size > LIMITS_V1.total_capsule_max_ciphertext_bytes:
                raise _error("INTERNAL_ERROR")
            if kind is CapsuleBlobKind.PHOTO:
                if type(ordinal) is not int:
                    raise _error("INTERNAL_ERROR")
                if not LIMITS_V1.photo_ordinal_min <= ordinal <= LIMITS_V1.photo_ordinal_max:
                    raise _error("INTERNAL_ERROR")
                photos.append(ordinal)
            else:
                if ordinal is not None:
                    raise _error("INTERNAL_ERROR")
                if kind is CapsuleBlobKind.RECOGNITION_MANIFEST:
                    recognition += 1
                else:
                    content += 1
        if recognition != LIMITS_V1.recognition_manifest_count:
            raise _error("INTERNAL_ERROR")
        if content != LIMITS_V1.content_manifest_count:
            raise _error("INTERNAL_ERROR")
        if not LIMITS_V1.photo_count_min <= len(photos) <= LIMITS_V1.photo_count_max:
            raise _error("INTERNAL_ERROR")
        if sorted(photos) != list(range(len(photos))):
            raise _error("INTERNAL_ERROR")
