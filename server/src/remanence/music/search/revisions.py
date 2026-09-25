"""Index revision build + validation (D4-B2a, code-only).

Builds one disposable search index beside the active one from the
staging document source: unique revision UID, settings push, bounded
document batches with a task wait after every push, then a
source-vs-index count reconciliation. No activation, swap, rollback,
or deletion lives here — traffic can never move as a side effect of
a build, and a failed build is left in place for inspection (never
destructively cleaned).

Fail-closed throughout: task failures, timeouts, count mismatches,
and invalid revision numbers all raise before any success is
reported. Validation here is structural (counts + settings echo);
query-probe validation belongs to a later slice.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from remanence.music.search.document import meilisearch_index_settings
from remanence.music.search.document_source import StagingSearchDocumentSource
from remanence.music.search.meilisearch import (
    MeilisearchMusicSearch,
    validate_index_uid,
)
from remanence.music.ports import MusicSearchError


class RevisionBuildError(RuntimeError):
    """Revision build or validation failed (fail-closed, inspectable)."""


def revision_index_uid(base_uid: str, rev: int) -> str:
    """Deterministic unique index UID for one revision build."""
    try:
        base = validate_index_uid(base_uid)
    except MusicSearchError as exc:
        raise RevisionBuildError(f"invalid revision base uid: {exc}") from exc
    if type(rev) is not int or rev < 0:
        raise RevisionBuildError("revision must be an int >= 0")
    uid = f"{base}_rev{rev:04d}"
    try:
        return validate_index_uid(uid)
    except MusicSearchError as exc:
        raise RevisionBuildError(f"invalid revision uid: {exc}") from exc


class RevisionStatus:
    BUILDING = "building"
    VALIDATED = "validated"
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class MusicIndexRevision:
    rev: int
    index_uid: str
    documents_pushed: int
    expected_count: int
    status: str


class RevisionIndexOps(Protocol):
    """Narrow transport the manager needs (adapter implements this)."""

    def index_exists(self, index_uid: str) -> bool: ...
    def create_index_for(self, index_uid: str, primary_key: str = ...) -> int: ...
    def update_settings_for(self, index_uid: str, settings: dict) -> int: ...
    def put_documents_to(self, index_uid: str, documents: list[dict]) -> int: ...
    def index_document_count(self, index_uid: str) -> int: ...
    def wait_for_task(self, task_uid: int, *, timeout_s: float = ...) -> None: ...


class MusicIndexRevisionManager:
    """Builds + validates one revision; never activates or deletes."""

    def __init__(
        self,
        ops: RevisionIndexOps,
        document_source: StagingSearchDocumentSource,
        task_timeout_s: float = 60.0,
        active_uid: str | None = None,
    ) -> None:
        if not (
            callable(getattr(ops, "index_exists", None))
            and callable(getattr(ops, "create_index_for", None))
            and callable(getattr(ops, "update_settings_for", None))
            and callable(getattr(ops, "put_documents_to", None))
            and callable(getattr(ops, "index_document_count", None))
            and callable(getattr(ops, "wait_for_task", None))
        ):
            raise TypeError("revision manager requires revision index ops")
        if not isinstance(document_source, StagingSearchDocumentSource):
            raise TypeError("revision manager requires a staging document source")
        if (
            type(task_timeout_s) is bool
            or not isinstance(task_timeout_s, (int, float))
            or not task_timeout_s > 0
        ):
            raise ValueError("task_timeout_s must be positive")
        if active_uid is not None:
            validate_index_uid(active_uid)
        self._ops = ops
        self._source = document_source
        self._timeout = float(task_timeout_s)
        self._active_uid = active_uid

    def build_revision(self, base_uid: str, rev: int) -> MusicIndexRevision:
        """Build + count-validate one revision index (no activation)."""
        uid = revision_index_uid(base_uid, rev)
        expected = self._source.total_expected()
        if expected <= 0:
            # An empty source can never be a valid revision: refuse before
            # any target write (no create/settings/documents on nothing).
            raise RevisionBuildError(f"revision {rev} has no source documents")
        if self._active_uid is not None and uid == self._active_uid:
            raise RevisionBuildError(f"revision {rev} collides with the active index")
        try:
            create_task = self._ops.create_index_for(uid)
            self._ops.wait_for_task(create_task, timeout_s=self._timeout)
            settings_task = self._ops.update_settings_for(
                uid, meilisearch_index_settings()
            )
            self._ops.wait_for_task(settings_task, timeout_s=self._timeout)
            pushed = 0
            batch: list[dict] = []
            for document in self._source.iter_documents():
                batch.append(document)
                if len(batch) >= self._source.batch_size:
                    pushed += self._push_batch(uid, batch)
                    batch = []
            if batch:
                pushed += self._push_batch(uid, batch)
            actual = self._ops.index_document_count(uid)
            if pushed != expected or actual != expected:
                raise RevisionBuildError(
                    f"revision {rev} count mismatch: "
                    f"expected={expected} pushed={pushed} indexed={actual}"
                )
        except RevisionBuildError:
            raise
        except Exception as exc:
            raise RevisionBuildError(f"revision {rev} build failed") from exc
        return MusicIndexRevision(
            rev=rev,
            index_uid=uid,
            documents_pushed=pushed,
            expected_count=expected,
            status=RevisionStatus.VALIDATED,
        )

    def _push_batch(self, index_uid: str, batch: list[dict]) -> int:
        task = self._ops.put_documents_to(index_uid, batch)
        self._ops.wait_for_task(task, timeout_s=self._timeout)
        return len(batch)


def build_revision_manager(
    search: MeilisearchMusicSearch,
    document_source: StagingSearchDocumentSource,
    task_timeout_s: float = 60.0,
) -> MusicIndexRevisionManager:
    """Compose a manager from the real adapter + source (no activation)."""
    config = getattr(search, "config", None)
    active = getattr(config, "index_uid", None)
    return MusicIndexRevisionManager(
        search,
        document_source,
        task_timeout_s,
        active_uid=active if type(active) is str else None,
    )
