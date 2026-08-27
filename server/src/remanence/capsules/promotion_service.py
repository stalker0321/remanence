"""Transactional promotion of one declared capsule blob."""

from __future__ import annotations

import hmac
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob, CapsuleBlobState
from remanence.capsules.locking import blob_promotion_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.storage import (
    BlobConflictError,
    BlobIntegrityError,
    BlobNotFoundError,
    BlobStore,
    BlobStoreError,
    CiphertextStagingError,
    InvalidBlobKeyError,
    StagedBlob,
)


_GENERIC_SERVICE_MESSAGE = "capsule blob promotion failed"


class CapsuleBlobPromotionError(Exception):
    """Redacted, stable failure suitable for the later HTTP boundary."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True)
class CapsuleBlobPromotionResult:
    capsule_id: uuid.UUID
    blob_id: uuid.UUID
    state: CapsuleBlobState
    is_replay: bool

    def __repr__(self) -> str:
        return "CapsuleBlobPromotionResult(<redacted>)"


def _error(code: str) -> CapsuleBlobPromotionError:
    return CapsuleBlobPromotionError(code)


def _require_uuid(value: object) -> None:
    if not isinstance(value, uuid.UUID):
        raise _error("VALIDATION_FAILED")


def _require_utc(value: object) -> None:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise _error("VALIDATION_FAILED")


class CapsuleBlobPromotionService:
    """Promote one staged blob while leaving transaction ownership with the caller."""

    def __init__(self, session: Session, blob_store: BlobStore) -> None:
        self._session = session
        self._blob_store = blob_store

    def promote_blob(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        blob_id: uuid.UUID,
        staged_blob: StagedBlob,
        now: datetime,
    ) -> CapsuleBlobPromotionResult:
        if not isinstance(staged_blob, StagedBlob):
            raise _error("VALIDATION_FAILED")

        try:
            return self._promote_blob(
                authenticated_sender_user_id=authenticated_sender_user_id,
                capsule_id=capsule_id,
                blob_id=blob_id,
                staged_blob=staged_blob,
                now=now,
            )
        except CapsuleBlobPromotionError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None
        finally:
            had_error = sys.exc_info()[0] is not None
            try:
                staged_blob.cleanup()
            except Exception:
                if not had_error:
                    raise _error("STORAGE_IO") from None

    def _promote_blob(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        blob_id: uuid.UUID,
        staged_blob: StagedBlob,
        now: datetime,
    ) -> CapsuleBlobPromotionResult:
        _require_uuid(authenticated_sender_user_id)
        _require_uuid(capsule_id)
        _require_uuid(blob_id)
        _require_utc(now)

        capsule, blob = self._authorize(
            authenticated_sender_user_id=authenticated_sender_user_id,
            capsule_id=capsule_id,
            blob_id=blob_id,
            now=now,
        )
        self._verify_staged_metadata(staged_blob, blob)

        if blob.state is CapsuleBlobState.STORED:
            self._reconcile_stored(blob)
            return CapsuleBlobPromotionResult(
                capsule_id=capsule.id,
                blob_id=blob.id,
                state=CapsuleBlobState.STORED,
                is_replay=True,
            )
        if blob.state is not CapsuleBlobState.DECLARED:
            raise _error("INTERNAL_ERROR")

        self._put_final_object(staged_blob, blob)
        rowcount = self._cas_stored(blob)
        if rowcount == 1:
            return CapsuleBlobPromotionResult(
                capsule_id=capsule.id,
                blob_id=blob.id,
                state=CapsuleBlobState.STORED,
                is_replay=False,
            )
        if rowcount != 0:
            raise _error("INTERNAL_ERROR")

        current = self._reload_blob(capsule.id, blob.id)
        if current is None or current.state is CapsuleBlobState.DECLARED:
            raise _error("INTERNAL_ERROR")
        if current.state is not CapsuleBlobState.STORED:
            raise _error("INTERNAL_ERROR")
        self._reconcile_stored(current)
        return CapsuleBlobPromotionResult(
            capsule_id=capsule.id,
            blob_id=blob.id,
            state=CapsuleBlobState.STORED,
            is_replay=True,
        )

    def _authorize(
        self,
        *,
        authenticated_sender_user_id: uuid.UUID,
        capsule_id: uuid.UUID,
        blob_id: uuid.UUID,
        now: datetime,
    ) -> tuple[Capsule, CapsuleBlob]:
        try:
            with self._session.no_autoflush:
                self._session.execute(
                    select(func.pg_advisory_xact_lock(blob_promotion_lock_key(blob_id)))
                )
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
                return capsule, blob
        except CapsuleBlobPromotionError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None

    @staticmethod
    def _verify_staged_metadata(staged_blob: StagedBlob, blob: CapsuleBlob) -> None:
        try:
            staged_size = staged_blob.size
            staged_hash = staged_blob.sha256_hex
        except Exception:
            raise _error("STORAGE_IO") from None
        if type(staged_size) is not int or staged_size != blob.expected_ciphertext_size:
            raise _error("BLOB_SIZE_INVALID")
        if not isinstance(staged_hash, str):
            raise _error("BLOB_HASH_MISMATCH")
        if not hmac.compare_digest(staged_hash, blob.expected_ciphertext_sha256.hex()):
            raise _error("BLOB_HASH_MISMATCH")

    def _put_final_object(self, staged_blob: StagedBlob, blob: CapsuleBlob) -> None:
        try:
            with staged_blob.open_reader() as reader:
                self._blob_store.put(
                    blob.object_key,
                    reader,
                    expected_size=blob.expected_ciphertext_size,
                    expected_sha256=blob.expected_ciphertext_sha256.hex(),
                )
        except BlobConflictError:
            raise _error("BLOB_CONFLICT") from None
        except BlobIntegrityError:
            raise _error("STORAGE_INTEGRITY") from None
        except InvalidBlobKeyError:
            raise _error("STORAGE_INVALID") from None
        except (BlobNotFoundError, BlobStoreError, CiphertextStagingError, OSError):
            raise _error("STORAGE_IO") from None
        except Exception:
            raise _error("STORAGE_IO") from None

    def _cas_stored(self, blob: CapsuleBlob) -> int:
        try:
            result = self._session.execute(
                update(CapsuleBlob)
                .where(
                    CapsuleBlob.id == blob.id,
                    CapsuleBlob.capsule_id == blob.capsule_id,
                    CapsuleBlob.state == CapsuleBlobState.DECLARED,
                )
                .values(state=CapsuleBlobState.STORED)
            )
            self._session.flush()
            rowcount = result.rowcount
        except Exception:
            raise _error("INTERNAL_ERROR") from None
        if rowcount not in (0, 1):
            raise _error("INTERNAL_ERROR")
        return rowcount

    def _reload_blob(self, capsule_id: uuid.UUID, blob_id: uuid.UUID) -> CapsuleBlob | None:
        try:
            return self._session.scalar(
                select(CapsuleBlob)
                .where(CapsuleBlob.id == blob_id, CapsuleBlob.capsule_id == capsule_id)
                .execution_options(populate_existing=True)
            )
        except Exception:
            raise _error("INTERNAL_ERROR") from None

    def _reconcile_stored(self, blob: CapsuleBlob) -> None:
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
