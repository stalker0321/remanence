"""Delete BlobStore objects for already-ABORTED capsules.

This service never marks drafts. It runs only after ABORTED is durable from
abort or expired-draft marking. V1 holds the per-capsule advisory transaction
lock across a bounded synchronous BlobStore.delete; the caller owns commit and
rollback, so locks last until that transaction ends. That is an intentional
v1 tradeoff for lock-order safety versus abort/finalize, not a temp-root or
inventory scan.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Final, Literal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob
from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.storage import BlobNotFoundError, BlobStore, BlobStoreError

_GENERIC_SERVICE_MESSAGE: Final = "aborted object cleanup failed"
ABORTED_OBJECT_CLEANUP_BATCH_MIN: Final = 1
ABORTED_OBJECT_CLEANUP_BATCH_MAX: Final = 100

_Outcome = Literal["deleted_or_missing", "failed", "skipped"]


class AbortedObjectCleanupError(Exception):
    """Redacted, stable failure for aborted-object cleanup."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class AbortedObjectCleanupResult:
    examined_count: int
    deleted_or_missing_count: int
    failed_count: int
    skipped_count: int

    def __repr__(self) -> str:
        return "AbortedObjectCleanupResult(<redacted>)"


def _error(code: str) -> AbortedObjectCleanupError:
    return AbortedObjectCleanupError(code)


def _require_limit(value: object) -> int:
    if type(value) is not int:
        raise _error("VALIDATION_FAILED")
    if not ABORTED_OBJECT_CLEANUP_BATCH_MIN <= value <= ABORTED_OBJECT_CLEANUP_BATCH_MAX:
        raise _error("VALIDATION_FAILED")
    return value


class AbortedObjectCleanupService:
    """Remove store objects for ABORTED capsule blobs without mutating rows."""

    def __init__(self, session: Session, blob_store: BlobStore) -> None:
        self._session = session
        self._blob_store = blob_store

    def clean_aborted_objects(
        self, *, limit: int = ABORTED_OBJECT_CLEANUP_BATCH_MAX
    ) -> AbortedObjectCleanupResult:
        limit = _require_limit(limit)
        try:
            return self._clean_aborted_objects(limit=limit)
        except AbortedObjectCleanupError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None

    def _candidate_blob_ids(self, *, limit: int) -> list[tuple[uuid.UUID, uuid.UUID]]:
        with self._session.no_autoflush:
            rows = self._session.execute(
                select(CapsuleBlob.capsule_id, CapsuleBlob.id)
                .join(Capsule, CapsuleBlob.capsule_id == Capsule.id)
                .where(Capsule.state == CapsuleState.ABORTED)
                .order_by(CapsuleBlob.capsule_id.asc(), CapsuleBlob.id.asc())
                .limit(limit)
            ).all()
        return [(row[0], row[1]) for row in rows]

    def _clean_aborted_objects(self, *, limit: int) -> AbortedObjectCleanupResult:
        candidates = self._candidate_blob_ids(limit=limit)
        deleted_or_missing = 0
        failed = 0
        skipped = 0
        for capsule_id, blob_id in candidates:
            outcome = self._delete_if_still_aborted(capsule_id, blob_id)
            if outcome == "deleted_or_missing":
                deleted_or_missing += 1
            elif outcome == "failed":
                failed += 1
            else:
                skipped += 1
        examined = len(candidates)
        if examined != deleted_or_missing + failed + skipped:
            raise _error("INTERNAL_ERROR")
        if min(examined, deleted_or_missing, failed, skipped) < 0:
            raise _error("INTERNAL_ERROR")
        return AbortedObjectCleanupResult(
            examined_count=examined,
            deleted_or_missing_count=deleted_or_missing,
            failed_count=failed,
            skipped_count=skipped,
        )

    def _delete_if_still_aborted(
        self, capsule_id: uuid.UUID, blob_id: uuid.UUID
    ) -> _Outcome:
        with self._session.no_autoflush:
            self._session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
            capsule = self._session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            blob = self._session.scalar(
                select(CapsuleBlob)
                .where(CapsuleBlob.id == blob_id)
                .execution_options(populate_existing=True)
            )
            if (
                capsule is None
                or capsule.state is not CapsuleState.ABORTED
                or blob is None
                or blob.capsule_id != capsule.id
                or type(blob.object_key) is not str
                or not blob.object_key
            ):
                return "skipped"
            object_key = blob.object_key
        try:
            self._blob_store.delete(object_key)
        except BlobNotFoundError:
            return "deleted_or_missing"
        except (BlobStoreError, OSError):
            return "failed"
        except Exception:
            return "failed"
        return "deleted_or_missing"
