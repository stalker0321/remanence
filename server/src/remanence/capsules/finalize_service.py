"""PostgreSQL finalize transaction. The caller owns commit/rollback."""

from __future__ import annotations

import hashlib
import hmac
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import func, inspect, select
from sqlalchemy.orm import Session, make_transient

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.publish_statement import (
    PublishStatementInvalidError,
    verify_publish_statement,
)
from remanence.capsules.signature_service import (
    PublishSignatureVerificationError,
    PublishSignatureVerificationService,
)
from remanence.storage import BlobNotFoundError, BlobStore, BlobStoreError


_GENERIC_SERVICE_MESSAGE: Final = "capsule finalize failed"
_SIGNATURE_LENGTH: Final = 69


class CapsuleFinalizeError(Exception):
    """Redacted, stable failure suitable for the S16 HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class CapsuleFinalizeEnvelope:
    recipient_key_bundle_id: uuid.UUID
    ciphertext: bytes
    ciphertext_size: int
    ciphertext_sha256: bytes

    def __repr__(self) -> str:
        return "CapsuleFinalizeEnvelope(<redacted>)"


@dataclass(frozen=True, slots=True)
class CapsuleFinalizeResult:
    capsule_id: uuid.UUID
    state: CapsuleState
    ready_at: datetime
    recipient_key_bundle_id: uuid.UUID
    is_replay: bool

    def __repr__(self) -> str:
        return "CapsuleFinalizeResult(<redacted>)"


def _error(code: str) -> CapsuleFinalizeError:
    return CapsuleFinalizeError(code)


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


def _require_bytes(value: object) -> bytes:
    if type(value) is not bytes:
        raise _error("VALIDATION_FAILED")
    return value


def _digest_equal(left: object, right: object) -> bool:
    return (
        type(left) is bytes
        and type(right) is bytes
        and len(left) == len(right)
        and hmac.compare_digest(left, right)
    )


def _identity_snapshot(capsule: Capsule, recipient_key_bundle_id: uuid.UUID) -> Capsule:
    snapshot = Capsule(
        id=capsule.id,
        sender_user_id=capsule.sender_user_id,
        recipient_user_id=capsule.recipient_user_id,
        sender_key_bundle_id=capsule.sender_key_bundle_id,
        recipient_key_bundle_id=recipient_key_bundle_id,
        protocol_version=capsule.protocol_version,
        state=capsule.state,
        signed_statement=None,
        signed_statement_sha256=None,
        publish_signature=None,
        created_at=capsule.created_at,
        ready_at=None,
        draft_expires_at=capsule.draft_expires_at,
    )
    if inspect(snapshot).session is not None:
        make_transient(snapshot)
    if inspect(snapshot).session is not None:
        raise _error("INTERNAL_ERROR")
    return snapshot


class CapsuleFinalizeService:
    """Mark one draft READY inside the caller's PostgreSQL transaction."""

    def __init__(self, session: Session, blob_store: BlobStore) -> None:
        self._session = session
        self._blob_store = blob_store

    def finalize(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        statement: bytes,
        signature: bytes,
        sender_key_bundle_id: uuid.UUID,
        envelope: CapsuleFinalizeEnvelope,
        now: datetime,
    ) -> CapsuleFinalizeResult:
        _require_uuid(authenticated_sender_user_id)
        _require_uuid(capsule_id)
        _require_uuid(sender_key_bundle_id)
        statement = _require_bytes(statement)
        signature = _require_bytes(signature)
        if not isinstance(envelope, CapsuleFinalizeEnvelope):
            raise _error("VALIDATION_FAILED")
        _require_uuid(envelope.recipient_key_bundle_id)
        _require_bytes(envelope.ciphertext)
        _require_bytes(envelope.ciphertext_sha256)
        now = _require_utc(now)

        try:
            return self._finalize(
                authenticated_sender_user_id=authenticated_sender_user_id,
                capsule_id=capsule_id,
                statement=statement,
                signature=signature,
                sender_key_bundle_id=sender_key_bundle_id,
                envelope=envelope,
                now=now,
            )
        except CapsuleFinalizeError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None

    def _finalize(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        statement: bytes,
        signature: bytes,
        sender_key_bundle_id: uuid.UUID,
        envelope: CapsuleFinalizeEnvelope,
        now: datetime,
    ) -> CapsuleFinalizeResult:
        with self._session.no_autoflush:
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            if capsule is None or capsule.sender_user_id != authenticated_sender_user_id:
                raise _error("CAPSULE_NOT_FOUND")
            if capsule.state is CapsuleState.READY:
                return self._replay_ready(
                    capsule,
                    statement=statement,
                    signature=signature,
                    sender_key_bundle_id=sender_key_bundle_id,
                    envelope=envelope,
                )
            if capsule.state is not CapsuleState.DRAFT:
                raise _error("CAPSULE_STATE_INVALID")
            if now >= capsule.draft_expires_at:
                raise _error("DRAFT_EXPIRED")
            if sender_key_bundle_id != capsule.sender_key_bundle_id:
                raise _error("KEY_BUNDLE_INVALID")

            blobs = list(
                self._session.scalars(
                    select(CapsuleBlob)
                    .where(CapsuleBlob.capsule_id == capsule.id)
                    .execution_options(populate_existing=True)
                )
            )
            if not (
                LIMITS_V1.recognition_manifest_count
                + LIMITS_V1.content_manifest_count
                + LIMITS_V1.photo_count_min
                <= len(blobs)
                <= LIMITS_V1.recognition_manifest_count
                + LIMITS_V1.content_manifest_count
                + LIMITS_V1.photo_count_max
            ):
                raise _error("INTERNAL_ERROR")
            if any(blob.state is not CapsuleBlobState.STORED for blob in blobs):
                raise _error("CAPSULE_STATE_INVALID")

            snapshot = _identity_snapshot(capsule, envelope.recipient_key_bundle_id)
            try:
                verified = verify_publish_statement(statement, snapshot, blobs)
            except PublishStatementInvalidError:
                raise _error("STATEMENT_INVALID") from None
            if verified.sender_key_bundle_id != sender_key_bundle_id:
                raise _error("KEY_BUNDLE_INVALID")

            try:
                authorization = PublishSignatureVerificationService(self._session).verify(
                    verified,
                    signature,
                )
            except PublishSignatureVerificationError as exc:
                raise _error(exc.code) from None

            self._validate_envelope(verified, authorization, envelope)
            for blob in blobs:
                self._stat_stored(blob)
            return self._persist_ready(
                capsule,
                authorization=authorization,
                envelope=envelope,
                now=now,
            )

    def _stat_stored(self, blob: CapsuleBlob) -> None:
        try:
            info = self._blob_store.stat(blob.object_key)
        except (BlobNotFoundError, BlobStoreError, OSError):
            raise _error("INTERNAL_ERROR") from None
        except Exception:
            raise _error("INTERNAL_ERROR") from None
        if (
            info.size != blob.expected_ciphertext_size
            or not isinstance(info.sha256_hex, str)
            or not hmac.compare_digest(info.sha256_hex, blob.expected_ciphertext_sha256.hex())
        ):
            raise _error("BLOB_CONFLICT")

    def _validate_envelope(self, verified, authorization, envelope: CapsuleFinalizeEnvelope) -> None:
        size = envelope.ciphertext_size
        digest = envelope.ciphertext_sha256
        ciphertext = envelope.ciphertext
        if type(size) is not int or not 0 < size <= LIMITS_V1.recipient_envelope_max_ciphertext_bytes:
            raise _error("ENVELOPE_INVALID")
        if len(ciphertext) != size:
            raise _error("ENVELOPE_INVALID")
        if type(digest) is not bytes or len(digest) != 32:
            raise _error("ENVELOPE_INVALID")
        if not hmac.compare_digest(hashlib.sha256(ciphertext).digest(), digest):
            raise _error("ENVELOPE_INVALID")
        if (
            envelope.recipient_key_bundle_id != authorization.recipient_key_bundle_id
            or envelope.recipient_key_bundle_id != verified.recipient_key_bundle_id
        ):
            raise _error("ENVELOPE_INVALID")

    def _replay_ready(
        self,
        capsule: Capsule,
        *,
        statement: bytes,
        signature: bytes,
        sender_key_bundle_id: uuid.UUID,
        envelope: CapsuleFinalizeEnvelope,
    ) -> CapsuleFinalizeResult:
        stored_envelope = self._session.get(CapsuleEnvelope, capsule.id)
        delivery = self._session.get(
            RecipientDeliveryState, (capsule.recipient_user_id, capsule.id)
        )
        if not self._stored_ready_shape_is_consistent(capsule, stored_envelope, delivery):
            raise _error("INTERNAL_ERROR")
        equivalent = (
            sender_key_bundle_id == capsule.sender_key_bundle_id
            and _digest_equal(capsule.signed_statement, statement)
            and _digest_equal(capsule.publish_signature, signature)
            and envelope.recipient_key_bundle_id == stored_envelope.recipient_key_bundle_id
            and envelope.ciphertext_size == stored_envelope.ciphertext_size
            and _digest_equal(stored_envelope.ciphertext, envelope.ciphertext)
            and _digest_equal(stored_envelope.ciphertext_sha256, envelope.ciphertext_sha256)
        )
        if not equivalent:
            raise _error("FINALIZE_CONFLICT")
        return CapsuleFinalizeResult(
            capsule_id=capsule.id,
            state=CapsuleState.READY,
            ready_at=capsule.ready_at,
            recipient_key_bundle_id=capsule.recipient_key_bundle_id,
            is_replay=True,
        )

    @staticmethod
    def _stored_ready_shape_is_consistent(
        capsule: Capsule,
        stored_envelope: CapsuleEnvelope | None,
        delivery: RecipientDeliveryState | None,
    ) -> bool:
        if (
            capsule.signed_statement is None
            or capsule.signed_statement_sha256 is None
            or capsule.publish_signature is None
            or capsule.ready_at is None
            or stored_envelope is None
            or delivery is None
        ):
            return False
        if not _digest_equal(
            hashlib.sha256(capsule.signed_statement).digest(),
            capsule.signed_statement_sha256,
        ):
            return False
        if type(capsule.publish_signature) is not bytes or len(capsule.publish_signature) != _SIGNATURE_LENGTH:
            return False
        if stored_envelope.recipient_user_id != capsule.recipient_user_id:
            return False
        if stored_envelope.recipient_key_bundle_id != capsule.recipient_key_bundle_id:
            return False
        size = stored_envelope.ciphertext_size
        if type(size) is not int or not 0 < size <= LIMITS_V1.recipient_envelope_max_ciphertext_bytes:
            return False
        if type(stored_envelope.ciphertext) is not bytes or len(stored_envelope.ciphertext) != size:
            return False
        if not _digest_equal(
            hashlib.sha256(stored_envelope.ciphertext).digest(),
            stored_envelope.ciphertext_sha256,
        ):
            return False
        if delivery.recipient_user_id != capsule.recipient_user_id or delivery.capsule_id != capsule.id:
            return False
        if delivery.available_at != capsule.ready_at:
            return False
        if delivery.state is RecipientDeliveryStatus.AVAILABLE:
            return delivery.ciphertext_synced_at is None
        if delivery.state is RecipientDeliveryStatus.CIPHERTEXT_SYNCED:
            return delivery.ciphertext_synced_at is not None
        return False

    def _persist_ready(
        self,
        capsule: Capsule,
        *,
        authorization,
        envelope: CapsuleFinalizeEnvelope,
        now: datetime,
    ) -> CapsuleFinalizeResult:
        signature = authorization.signature
        if type(signature) is not bytes or len(signature) != _SIGNATURE_LENGTH:
            raise _error("SIGNATURE_INVALID")
        original = {
            "recipient_key_bundle_id": capsule.recipient_key_bundle_id,
            "state": capsule.state,
            "signed_statement": capsule.signed_statement,
            "signed_statement_sha256": capsule.signed_statement_sha256,
            "publish_signature": capsule.publish_signature,
            "ready_at": capsule.ready_at,
        }
        envelope_row = CapsuleEnvelope(
            capsule_id=capsule.id,
            recipient_user_id=capsule.recipient_user_id,
            recipient_key_bundle_id=authorization.recipient_key_bundle_id,
            ciphertext=envelope.ciphertext,
            ciphertext_size=envelope.ciphertext_size,
            ciphertext_sha256=envelope.ciphertext_sha256,
        )
        delivery_row = RecipientDeliveryState(
            recipient_user_id=capsule.recipient_user_id,
            capsule_id=capsule.id,
            state=RecipientDeliveryStatus.AVAILABLE,
            available_at=now,
            ciphertext_synced_at=None,
        )
        try:
            capsule.recipient_key_bundle_id = authorization.recipient_key_bundle_id
            capsule.signed_statement = authorization.verified_statement.canonical_bytes
            capsule.signed_statement_sha256 = authorization.verified_statement.sha256
            capsule.publish_signature = signature
            capsule.ready_at = now
            capsule.state = CapsuleState.READY
            self._session.add(envelope_row)
            self._session.add(delivery_row)
            self._session.flush()
        except CapsuleFinalizeError:
            self._restore_capsule(capsule, original, envelope_row, delivery_row)
            raise
        except Exception:
            self._restore_capsule(capsule, original, envelope_row, delivery_row)
            raise _error("INTERNAL_ERROR") from None
        return CapsuleFinalizeResult(
            capsule_id=capsule.id,
            state=CapsuleState.READY,
            ready_at=now,
            recipient_key_bundle_id=capsule.recipient_key_bundle_id,
            is_replay=False,
        )

    def _restore_capsule(
        self,
        capsule: Capsule,
        original: dict,
        envelope_row: CapsuleEnvelope,
        delivery_row: RecipientDeliveryState,
    ) -> None:
        try:
            capsule.recipient_key_bundle_id = original["recipient_key_bundle_id"]
            capsule.state = original["state"]
            capsule.signed_statement = original["signed_statement"]
            capsule.signed_statement_sha256 = original["signed_statement_sha256"]
            capsule.publish_signature = original["publish_signature"]
            capsule.ready_at = original["ready_at"]
        except Exception:
            pass
        for row in (envelope_row, delivery_row):
            try:
                if inspect(row).session is self._session:
                    self._session.expunge(row)
            except Exception:
                pass
