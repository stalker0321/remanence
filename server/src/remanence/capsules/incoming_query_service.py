"""Recipient-only incoming READY page. The caller owns commit/rollback."""

from __future__ import annotations

import hashlib
import hmac
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobKind, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.incoming_cursor import (
    IncomingCursor,
    IncomingCursorCodecError,
    decode_incoming_cursor,
    encode_incoming_cursor,
)
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.models import Capsule, CapsuleState


_GENERIC_SERVICE_MESSAGE: Final = "incoming capsule query failed"
_SIGNATURE_LENGTH: Final = 69
_INCOMING_PAGE_MIN: Final = 1
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


class IncomingCapsuleQueryError(Exception):
    """Redacted, stable failure for the later incoming HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class IncomingBlobSnapshot:
    blob_id: uuid.UUID
    kind: CapsuleBlobKind
    ordinal: int | None
    expected_ciphertext_size: int
    expected_ciphertext_sha256: bytes

    def __repr__(self) -> str:
        return "IncomingBlobSnapshot(<redacted>)"


@dataclass(frozen=True, slots=True)
class IncomingEnvelopeSnapshot:
    recipient_key_bundle_id: uuid.UUID
    ciphertext: bytes
    ciphertext_size: int
    ciphertext_sha256: bytes

    def __repr__(self) -> str:
        return "IncomingEnvelopeSnapshot(<redacted>)"


@dataclass(frozen=True, slots=True)
class IncomingCapsuleSnapshot:
    capsule_id: uuid.UUID
    sender_user_id: uuid.UUID
    recipient_user_id: uuid.UUID
    sender_key_bundle_id: uuid.UUID
    recipient_key_bundle_id: uuid.UUID
    protocol_version: int
    ready_at: datetime
    signed_statement: bytes
    signed_statement_sha256: bytes
    publish_signature: bytes
    envelope: IncomingEnvelopeSnapshot
    blobs: tuple[IncomingBlobSnapshot, ...]

    def __repr__(self) -> str:
        return "IncomingCapsuleSnapshot(<redacted>)"


@dataclass(frozen=True, slots=True)
class IncomingCapsulePage:
    items: tuple[IncomingCapsuleSnapshot, ...]
    has_more: bool
    next_cursor: str | None

    def __repr__(self) -> str:
        return "IncomingCapsulePage(<redacted>)"


def _error(code: str) -> IncomingCapsuleQueryError:
    return IncomingCapsuleQueryError(code)


def _require_uuid(value: object) -> uuid.UUID:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")
    return value


def _require_limit(value: object) -> int:
    if type(value) is not int:
        raise _error("VALIDATION_FAILED")
    if not _INCOMING_PAGE_MIN <= value <= LIMITS_V1.incoming_page_max:
        raise _error("VALIDATION_FAILED")
    return value


def _require_cursor(value: object) -> IncomingCursor | None:
    if value is None:
        return None
    mapped: IncomingCapsuleQueryError | None = None
    decoded: IncomingCursor | None = None
    try:
        decoded = decode_incoming_cursor(value)
    except IncomingCursorCodecError:
        mapped = _error("VALIDATION_FAILED")
    except Exception:
        mapped = _error("VALIDATION_FAILED")
    if mapped is not None:
        raise mapped
    if decoded is None:
        raise _error("VALIDATION_FAILED")
    return decoded


def _digest_equal(left: object, right: object) -> bool:
    return (
        type(left) is bytes
        and type(right) is bytes
        and len(left) == len(right)
        and hmac.compare_digest(left, right)
    )


class IncomingCapsuleQueryService:
    """Page READY capsules routed to the authenticated recipient."""

    def __init__(self, session: Session) -> None:
        self._session = session

    def list_incoming(
        self,
        *,
        authenticated_recipient_user_id: uuid.UUID,
        cursor: str | None = None,
        limit: int = LIMITS_V1.incoming_page_default,
    ) -> IncomingCapsulePage:
        recipient_id = _require_uuid(authenticated_recipient_user_id)
        after = _require_cursor(cursor)
        limit = _require_limit(limit)
        mapped: IncomingCapsuleQueryError | None = None
        page: IncomingCapsulePage | None = None
        try:
            page = self._list_incoming(
                recipient_id=recipient_id, after=after, limit=limit
            )
        except IncomingCapsuleQueryError as exc:
            mapped = exc
        except Exception:
            mapped = _error("INTERNAL_ERROR")
        if mapped is not None:
            raise mapped
        if page is None:
            raise _error("INTERNAL_ERROR")
        return page

    def _list_incoming(
        self,
        *,
        recipient_id: uuid.UUID,
        after: IncomingCursor | None,
        limit: int,
    ) -> IncomingCapsulePage:
        stmt = (
            select(Capsule, CapsuleEnvelope, RecipientDeliveryState)
            .select_from(Capsule)
            .outerjoin(CapsuleEnvelope, CapsuleEnvelope.capsule_id == Capsule.id)
            .outerjoin(
                RecipientDeliveryState,
                and_(
                    RecipientDeliveryState.capsule_id == Capsule.id,
                    RecipientDeliveryState.recipient_user_id == recipient_id,
                ),
            )
            .where(
                Capsule.recipient_user_id == recipient_id,
                Capsule.state == CapsuleState.READY,
            )
        )
        if after is not None:
            stmt = stmt.where(
                or_(
                    Capsule.ready_at > after.ready_at,
                    and_(
                        Capsule.ready_at == after.ready_at,
                        Capsule.id > after.capsule_id,
                    ),
                )
            )
        stmt = (
            stmt.order_by(Capsule.ready_at.asc(), Capsule.id.asc())
            .limit(limit + 1)
            .execution_options(populate_existing=True)
        )
        with self._session.no_autoflush:
            fetched = list(self._session.execute(stmt).all())
            has_more = len(fetched) > limit
            page_rows = fetched[:limit]
            capsule_ids = [row[0].id for row in page_rows]
            blobs_by_capsule = self._load_blobs(capsule_ids)
            items = tuple(
                self._snapshot_row(
                    capsule=capsule,
                    envelope=envelope,
                    delivery=delivery,
                    blobs=blobs_by_capsule.get(capsule.id, ()),
                    recipient_id=recipient_id,
                )
                for capsule, envelope, delivery in page_rows
            )
        if has_more is True:
            if not items:
                raise _error("INTERNAL_ERROR")
            last = items[-1]
            encoded: str | None = None
            encode_mapped: IncomingCapsuleQueryError | None = None
            try:
                encoded = encode_incoming_cursor(
                    ready_at=last.ready_at, capsule_id=last.capsule_id
                )
            except Exception:
                encode_mapped = _error("INTERNAL_ERROR")
            if encode_mapped is not None:
                raise encode_mapped
            next_cursor = encoded
        else:
            next_cursor = None
        if type(has_more) is not bool:
            raise _error("INTERNAL_ERROR")
        if has_more is False and next_cursor is not None:
            raise _error("INTERNAL_ERROR")
        if has_more is True and (type(next_cursor) is not str or not next_cursor):
            raise _error("INTERNAL_ERROR")
        return IncomingCapsulePage(
            items=items, has_more=has_more, next_cursor=next_cursor
        )

    def _load_blobs(
        self, capsule_ids: list[uuid.UUID]
    ) -> dict[uuid.UUID, tuple[tuple[object, ...], ...]]:
        by_capsule: dict[uuid.UUID, list[tuple[object, ...]]] = {
            capsule_id: [] for capsule_id in capsule_ids
        }
        if not capsule_ids:
            return {capsule_id: () for capsule_id in by_capsule}
        rows = self._session.execute(
            select(
                CapsuleBlob.capsule_id,
                CapsuleBlob.id,
                CapsuleBlob.kind,
                CapsuleBlob.ordinal,
                CapsuleBlob.expected_ciphertext_size,
                CapsuleBlob.expected_ciphertext_sha256,
                CapsuleBlob.state,
            ).where(CapsuleBlob.capsule_id.in_(tuple(capsule_ids)))
        ).all()
        for row in rows:
            bucket = by_capsule.get(row[0])
            if bucket is None:
                raise _error("INTERNAL_ERROR")
            bucket.append(
                (row[0], row[1], row[2], row[3], row[4], row[5], row[6])
            )
        return {capsule_id: tuple(items) for capsule_id, items in by_capsule.items()}

    def _snapshot_row(
        self,
        *,
        capsule: Capsule,
        envelope: CapsuleEnvelope | None,
        delivery: RecipientDeliveryState | None,
        blobs: tuple[tuple[object, ...], ...],
        recipient_id: uuid.UUID,
    ) -> IncomingCapsuleSnapshot:
        if not isinstance(capsule, Capsule) or capsule.state is not CapsuleState.READY:
            raise _error("INTERNAL_ERROR")
        if capsule.recipient_user_id != recipient_id:
            raise _error("INTERNAL_ERROR")
        ids = (
            capsule.id,
            capsule.sender_user_id,
            capsule.recipient_user_id,
            capsule.sender_key_bundle_id,
            capsule.recipient_key_bundle_id,
        )
        if any(not isinstance(value, uuid.UUID) for value in ids):
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
        statement_sha256 = capsule.signed_statement_sha256
        signature = capsule.publish_signature
        if type(statement) is not bytes or not statement:
            raise _error("INTERNAL_ERROR")
        if type(statement_sha256) is not bytes or len(statement_sha256) != 32:
            raise _error("INTERNAL_ERROR")
        if not _digest_equal(hashlib.sha256(statement).digest(), statement_sha256):
            raise _error("INTERNAL_ERROR")
        if type(signature) is not bytes or len(signature) != _SIGNATURE_LENGTH:
            raise _error("INTERNAL_ERROR")
        envelope_snapshot = self._snapshot_envelope(capsule, envelope)
        self._require_delivery(capsule, delivery, recipient_id=recipient_id)
        blob_snapshots = self._snapshot_blobs(capsule.id, blobs)
        return IncomingCapsuleSnapshot(
            capsule_id=capsule.id,
            sender_user_id=capsule.sender_user_id,
            recipient_user_id=capsule.recipient_user_id,
            sender_key_bundle_id=capsule.sender_key_bundle_id,
            recipient_key_bundle_id=capsule.recipient_key_bundle_id,
            protocol_version=capsule.protocol_version,
            ready_at=ready_at,
            signed_statement=bytes(statement),
            signed_statement_sha256=bytes(statement_sha256),
            publish_signature=bytes(signature),
            envelope=envelope_snapshot,
            blobs=blob_snapshots,
        )

    def _snapshot_envelope(
        self, capsule: Capsule, envelope: CapsuleEnvelope | None
    ) -> IncomingEnvelopeSnapshot:
        if envelope is None or not isinstance(envelope, CapsuleEnvelope):
            raise _error("INTERNAL_ERROR")
        if envelope.capsule_id != capsule.id:
            raise _error("INTERNAL_ERROR")
        if envelope.recipient_user_id != capsule.recipient_user_id:
            raise _error("INTERNAL_ERROR")
        if envelope.recipient_key_bundle_id != capsule.recipient_key_bundle_id:
            raise _error("INTERNAL_ERROR")
        if not isinstance(envelope.recipient_key_bundle_id, uuid.UUID):
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
        return IncomingEnvelopeSnapshot(
            recipient_key_bundle_id=envelope.recipient_key_bundle_id,
            ciphertext=bytes(ciphertext),
            ciphertext_size=size,
            ciphertext_sha256=bytes(digest),
        )

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

    def _snapshot_blobs(
        self, capsule_id: uuid.UUID, rows: tuple[tuple[object, ...], ...]
    ) -> tuple[IncomingBlobSnapshot, ...]:
        snapshots: list[IncomingBlobSnapshot] = []
        seen_ids: set[bytes] = set()
        recognition = 0
        content = 0
        photos: list[int] = []
        total_size = 0
        for row in rows:
            if len(row) != 7:
                raise _error("INTERNAL_ERROR")
            row_capsule_id, blob_id, kind, ordinal, size, digest, state = row
            if row_capsule_id != capsule_id or not isinstance(blob_id, uuid.UUID):
                raise _error("INTERNAL_ERROR")
            if blob_id.bytes in seen_ids:
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
            snapshots.append(
                IncomingBlobSnapshot(
                    blob_id=blob_id,
                    kind=kind,
                    ordinal=ordinal,
                    expected_ciphertext_size=size,
                    expected_ciphertext_sha256=bytes(digest),
                )
            )
        if recognition != LIMITS_V1.recognition_manifest_count:
            raise _error("INTERNAL_ERROR")
        if content != LIMITS_V1.content_manifest_count:
            raise _error("INTERNAL_ERROR")
        if not LIMITS_V1.photo_count_min <= len(photos) <= LIMITS_V1.photo_count_max:
            raise _error("INTERNAL_ERROR")
        if sorted(photos) != list(range(len(photos))):
            raise _error("INTERNAL_ERROR")
        snapshots.sort(
            key=lambda item: (
                _KIND_RANK[item.kind],
                LIMITS_V1.non_photo_ordinal if item.ordinal is None else item.ordinal,
                item.blob_id.bytes,
            )
        )
        return tuple(snapshots)
