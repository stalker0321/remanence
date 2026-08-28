"""PostgreSQL finalize transaction. The caller owns commit/rollback."""

from __future__ import annotations

import hashlib
import hmac
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobState
from remanence.capsules.delivery_models import RecipientDeliveryState, RecipientDeliveryStatus
from remanence.capsules.envelope_models import CapsuleEnvelope
from remanence.capsules.limits import LIMITS_V1
from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.capsules.publish_statement import (
    MAX_PUBLISH_STATEMENT_BYTES,
    PublishStatementInvalidError,
    verify_publish_statement,
)
from remanence.capsules.signature_service import (
    PublishSignatureVerificationError,
    PublishSignatureVerificationService,
)
from remanence.protocol.v1.remanence_v1_pb2 import PublishStatement
from remanence.storage import BlobNotFoundError, BlobStore, BlobStoreError
from remanence.users.key_models import KeyBundleStatus, UserKeyBundle


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
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() != timedelta(0):
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


def _canonical_statement_recipient_key_bundle_id(raw: bytes) -> uuid.UUID | None:
    if type(raw) is not bytes or not raw or len(raw) > MAX_PUBLISH_STATEMENT_BYTES:
        return None
    try:
        statement = PublishStatement()
        statement.ParseFromString(raw)
        canonical = statement.SerializeToString(deterministic=True)
        known = PublishStatement()
        known.CopyFrom(statement)
        known.DiscardUnknownFields()
        if canonical != raw or known.SerializeToString(deterministic=True) != canonical:
            return None
        identifier = bytes(statement.recipient_key_bundle_id)
        if type(statement.recipient_key_bundle_id) is not bytes or len(identifier) != 16:
            return None
        return uuid.UUID(bytes=identifier)
    except Exception:
        return None


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
            for blob in blobs:
                self._stat_stored(blob)

            original_recipient_key = capsule.recipient_key_bundle_id
            try:
                self._align_recipient_key_for_stale_retry(capsule, statement)
                verified = verify_publish_statement(statement, capsule, blobs)
                if verified.sender_key_bundle_id != sender_key_bundle_id:
                    raise _error("KEY_BUNDLE_INVALID")
                authorization = PublishSignatureVerificationService(self._session).verify(
                    verified,
                    signature,
                )
                self._validate_envelope(capsule, envelope)
                return self._persist_ready(
                    capsule,
                    statement_bytes=verified.canonical_bytes,
                    statement_sha256=verified.sha256,
                    signature=authorization.signature,
                    envelope=envelope,
                    now=now,
                )
            except PublishStatementInvalidError:
                capsule.recipient_key_bundle_id = original_recipient_key
                raise _error("STATEMENT_INVALID") from None
            except PublishSignatureVerificationError as exc:
                capsule.recipient_key_bundle_id = original_recipient_key
                raise _error(exc.code) from None
            except CapsuleFinalizeError:
                capsule.recipient_key_bundle_id = original_recipient_key
                raise
            except Exception:
                capsule.recipient_key_bundle_id = original_recipient_key
                raise

    def _align_recipient_key_for_stale_retry(self, capsule: Capsule, statement: bytes) -> None:
        statement_recipient_key = _canonical_statement_recipient_key_bundle_id(statement)
        if (
            statement_recipient_key is None
            or statement_recipient_key == capsule.recipient_key_bundle_id
        ):
            return
        current_active_id = self._session.scalar(
            select(UserKeyBundle.id).where(
                UserKeyBundle.user_id == capsule.recipient_user_id,
                UserKeyBundle.status == KeyBundleStatus.ACTIVE,
            )
        )
        if current_active_id != statement_recipient_key:
            return
        capsule.recipient_key_bundle_id = statement_recipient_key

    def _stat_stored(self, blob: CapsuleBlob) -> None:
        try:
            info = self._blob_store.stat(blob.object_key)
        except BlobNotFoundError:
            raise _error("STORAGE_NOT_FOUND") from None
        except (BlobStoreError, OSError):
            raise _error("STORAGE_IO") from None
        except Exception:
            raise _error("STORAGE_IO") from None
        if (
            info.size != blob.expected_ciphertext_size
            or not isinstance(info.sha256_hex, str)
            or not hmac.compare_digest(info.sha256_hex, blob.expected_ciphertext_sha256.hex())
        ):
            raise _error("BLOB_CONFLICT")

    def _validate_envelope(self, capsule: Capsule, envelope: CapsuleFinalizeEnvelope) -> None:
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
        if envelope.recipient_key_bundle_id != capsule.recipient_key_bundle_id:
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
        if (
            stored_envelope is None
            or capsule.signed_statement is None
            or capsule.publish_signature is None
            or capsule.ready_at is None
            or capsule.signed_statement_sha256 is None
        ):
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

    def _persist_ready(
        self,
        capsule: Capsule,
        *,
        statement_bytes: bytes,
        statement_sha256: bytes,
        signature: bytes,
        envelope: CapsuleFinalizeEnvelope,
        now: datetime,
    ) -> CapsuleFinalizeResult:
        if type(signature) is not bytes or len(signature) != _SIGNATURE_LENGTH:
            raise _error("SIGNATURE_INVALID")
        capsule.signed_statement = statement_bytes
        capsule.signed_statement_sha256 = statement_sha256
        capsule.publish_signature = signature
        capsule.ready_at = now
        capsule.state = CapsuleState.READY
        self._session.add(
            CapsuleEnvelope(
                capsule_id=capsule.id,
                recipient_user_id=capsule.recipient_user_id,
                recipient_key_bundle_id=capsule.recipient_key_bundle_id,
                ciphertext=envelope.ciphertext,
                ciphertext_size=envelope.ciphertext_size,
                ciphertext_sha256=envelope.ciphertext_sha256,
            )
        )
        self._session.add(
            RecipientDeliveryState(
                recipient_user_id=capsule.recipient_user_id,
                capsule_id=capsule.id,
                state=RecipientDeliveryStatus.AVAILABLE,
                ciphertext_synced_at=None,
            )
        )
        try:
            self._session.flush()
        except Exception:
            raise _error("INTERNAL_ERROR") from None
        return CapsuleFinalizeResult(
            capsule_id=capsule.id,
            state=CapsuleState.READY,
            ready_at=now,
            recipient_key_bundle_id=capsule.recipient_key_bundle_id,
            is_replay=False,
        )
