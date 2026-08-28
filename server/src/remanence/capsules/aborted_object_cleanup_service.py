"""Delete BlobStore objects for already-committed ABORTED capsules.

This service never marks drafts. Callers must already have committed ABORTED
via abort or expired-draft marking. Unlike those services, cleanup does not
accept a live Session: it opens a fresh Session/transaction per
clean_aborted_objects call so it cannot observe an uncommitted DRAFT->ABORTED
flush that later rolls back. It mutates no database rows. Advisory
transaction locks are acquired in that short-lived transaction and released
when the call returns. V1 still holds those locks across a bounded
synchronous BlobStore.delete. Pagination is a schema-free keyset cursor, not
offset. Failed or skipped keys are advanced past in this sweep and retried
only on a later sweep from a null cursor.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass
from typing import Final, Literal

from sqlalchemy import and_, func, or_, select
from sqlalchemy.orm import Session

from remanence.capsules.blob_models import CapsuleBlob
from remanence.capsules.locking import capsule_lock_key
from remanence.capsules.models import Capsule, CapsuleState
from remanence.storage import BlobNotFoundError, BlobStore, BlobStoreError

_GENERIC_SERVICE_MESSAGE: Final = "aborted object cleanup failed"
ABORTED_OBJECT_CLEANUP_BATCH_MIN: Final = 1
ABORTED_OBJECT_CLEANUP_BATCH_MAX: Final = 100

_Outcome = Literal["deleted_or_missing", "failed", "skipped"]
SessionFactory = Callable[[], Session]


class AbortedObjectCleanupError(Exception):
    """Redacted, stable failure for aborted-object cleanup."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(_GENERIC_SERVICE_MESSAGE)

    def __repr__(self) -> str:
        return f"{type(self).__name__}(code={self.code!r})"


@dataclass(frozen=True, slots=True)
class AbortedObjectCleanupCursor:
    capsule_id: uuid.UUID
    blob_id: uuid.UUID

    def __repr__(self) -> str:
        return "AbortedObjectCleanupCursor(<redacted>)"


@dataclass(frozen=True, slots=True)
class AbortedObjectCleanupResult:
    examined_count: int
    deleted_or_missing_count: int
    failed_count: int
    skipped_count: int
    has_more: bool
    next_cursor: AbortedObjectCleanupCursor | None

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


def _require_cursor(value: object) -> AbortedObjectCleanupCursor | None:
    if value is None:
        return None
    if not isinstance(value, AbortedObjectCleanupCursor):
        raise _error("VALIDATION_FAILED")
    if not isinstance(value.capsule_id, uuid.UUID) or not isinstance(value.blob_id, uuid.UUID):
        raise _error("VALIDATION_FAILED")
    return value


class AbortedObjectCleanupService:
    """Remove store objects for committed ABORTED blobs without mutating rows."""

    def __init__(self, session_factory: SessionFactory, blob_store: BlobStore) -> None:
        self._session_factory = session_factory
        self._blob_store = blob_store

    def clean_aborted_objects(
        self,
        *,
        limit: int = ABORTED_OBJECT_CLEANUP_BATCH_MAX,
        after_cursor: AbortedObjectCleanupCursor | None = None,
    ) -> AbortedObjectCleanupResult:
        limit = _require_limit(limit)
        after_cursor = _require_cursor(after_cursor)
        if not callable(self._session_factory):
            raise _error("VALIDATION_FAILED")
        session = self._session_factory()
        try:
            with session.begin():
                return self._clean_aborted_objects(
                    session, limit=limit, after_cursor=after_cursor
                )
        except AbortedObjectCleanupError:
            raise
        except Exception:
            raise _error("INTERNAL_ERROR") from None
        finally:
            session.close()

    def _candidate_blob_ids(
        self,
        session: Session,
        *,
        limit: int,
        after_cursor: AbortedObjectCleanupCursor | None,
    ) -> list[tuple[uuid.UUID, uuid.UUID]]:
        stmt = (
            select(CapsuleBlob.capsule_id, CapsuleBlob.id)
            .join(Capsule, CapsuleBlob.capsule_id == Capsule.id)
            .where(Capsule.state == CapsuleState.ABORTED)
        )
        if after_cursor is not None:
            stmt = stmt.where(
                or_(
                    CapsuleBlob.capsule_id > after_cursor.capsule_id,
                    and_(
                        CapsuleBlob.capsule_id == after_cursor.capsule_id,
                        CapsuleBlob.id > after_cursor.blob_id,
                    ),
                )
            )
        with session.no_autoflush:
            rows = session.execute(
                stmt.order_by(CapsuleBlob.capsule_id.asc(), CapsuleBlob.id.asc()).limit(
                    limit + 1
                )
            ).all()
        return [(row[0], row[1]) for row in rows]

    def _clean_aborted_objects(
        self,
        session: Session,
        *,
        limit: int,
        after_cursor: AbortedObjectCleanupCursor | None,
    ) -> AbortedObjectCleanupResult:
        fetched = self._candidate_blob_ids(
            session, limit=limit, after_cursor=after_cursor
        )
        has_more = len(fetched) > limit
        candidates = fetched[:limit]
        deleted_or_missing = 0
        failed = 0
        skipped = 0
        for capsule_id, blob_id in candidates:
            outcome = self._delete_if_still_aborted(session, capsule_id, blob_id)
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
        if has_more is True:
            last_capsule_id, last_blob_id = candidates[-1]
            next_cursor: AbortedObjectCleanupCursor | None = AbortedObjectCleanupCursor(
                capsule_id=last_capsule_id, blob_id=last_blob_id
            )
        else:
            next_cursor = None
        if type(has_more) is not bool:
            raise _error("INTERNAL_ERROR")
        return AbortedObjectCleanupResult(
            examined_count=examined,
            deleted_or_missing_count=deleted_or_missing,
            failed_count=failed,
            skipped_count=skipped,
            has_more=has_more,
            next_cursor=next_cursor,
        )

    def _delete_if_still_aborted(
        self, session: Session, capsule_id: uuid.UUID, blob_id: uuid.UUID
    ) -> _Outcome:
        with session.no_autoflush:
            session.execute(
                select(func.pg_advisory_xact_lock(capsule_lock_key(capsule_id)))
            )
            capsule = session.scalar(
                select(Capsule)
                .where(Capsule.id == capsule_id)
                .execution_options(populate_existing=True)
            )
            blob = session.scalar(
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
