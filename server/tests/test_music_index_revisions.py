"""Revision build + validation manager: order, counts, fail-closed.

Fake index ops + real SQLite document source (no live Meili, no live
DB): settings-first ordering, bounded batches with a wait per task,
0/1001 rows, partial failure, count mismatch, and no destructive
operations anywhere in the manager surface.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from remanence.music.domain import normalize_text
from remanence.music.ports import MusicSearchError
from remanence.music.search.document import meilisearch_index_settings
from remanence.music.search.document_source import StagingSearchDocumentSource
from remanence.music.search.revisions import (
    MusicIndexRevision,
    MusicIndexRevisionManager,
    RevisionBuildError,
    RevisionStatus,
    build_revision_manager,
    revision_index_uid,
)
from remanence.music.staging.models import (
    MusicBase,
    StagedArtist,
    StagedTrack,
    StagedTrackArtist,
    TrackStatus,
)


class FakeOps:
    """Recording stand-in for the adapter revision surface."""

    def __init__(
        self,
        count: int | None = None,
        fail_on_put: int = 0,
        exists: bool = False,
        fail_on_wait: int = 0,
    ) -> None:
        self.calls: list[tuple] = []
        self._count = count
        self._fail_on_put = fail_on_put
        self._exists = exists
        self._fail_on_wait = fail_on_wait
        self._puts = 0
        self._waits = 0

    def index_exists(self, index_uid: str) -> bool:
        self.calls.append(("exists", index_uid))
        return self._exists

    def create_index_for(self, index_uid: str, primary_key: str = "id") -> int:
        self.calls.append(("create", index_uid, primary_key))
        if self._exists:
            raise MusicSearchError("index already exists")
        return 50

    def update_settings_for(self, index_uid: str, settings: dict) -> int:
        self.calls.append(("settings", index_uid, settings))
        return 100

    def put_documents_to(self, index_uid: str, documents: list[dict]) -> int:
        self._puts += 1
        if self._fail_on_put and self._puts >= self._fail_on_put:
            raise MusicSearchError("push failed")
        self.calls.append(("put", index_uid, len(documents)))
        return 200 + self._puts

    def index_document_count(self, index_uid: str) -> int:
        self.calls.append(("count", index_uid))
        assert self._count is not None
        return self._count

    def wait_for_task(self, task_uid: int, *, timeout_s: float = 60.0) -> None:
        self._waits += 1
        if self._fail_on_wait and self._waits >= self._fail_on_wait:
            raise MusicSearchError("task failed")
        self.calls.append(("wait", task_uid, timeout_s))


def _engine():
    engine = create_engine(
        "sqlite://",
        execution_options={"schema_translate_map": {"music": None}},
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    MusicBase.metadata.create_all(engine)
    return engine


def _load(engine, rows: int) -> None:
    session = Session(engine)
    artist_id = uuid.uuid4()
    session.add(
        StagedArtist(id=artist_id, name="Artist", normalized_name=normalize_text("Artist"))
    )
    for i in range(rows):
        track_id = uuid.uuid4()
        session.add(
            StagedTrack(
                id=track_id,
                title=f"Track {i}",
                normalized_title=normalize_text(f"Track {i}"),
                duration_ms=200000,
                variant=None,
                first_release_year=2001,
                status=TrackStatus.ACTIVE,
            )
        )
        session.add(StagedTrackArtist(track_id=track_id, artist_id=artist_id, position=0))
    session.commit()
    session.close()


def _manager(engine, ops: FakeOps, **kwargs) -> MusicIndexRevisionManager:
    source = StagingSearchDocumentSource(lambda: Session(engine), **kwargs)
    return MusicIndexRevisionManager(ops, source)


def test_revision_uid_unique_and_strict() -> None:
    assert revision_index_uid("remanence_tracks_v1", 7) == "remanence_tracks_v1_rev0007"
    assert revision_index_uid("remanence_tracks_v1", 0).endswith("_rev0000")
    for base, rev in (("a/b", 1), ("", 1), ("remanence_tracks_v1", -1), ("remanence_tracks_v1", "7")):
        with pytest.raises(RevisionBuildError):
            revision_index_uid(base, rev)  # type: ignore[arg-type]


def test_build_order_settings_batches_count() -> None:
    engine = _engine()
    try:
        _load(engine, 5)
        ops = FakeOps(count=5)
        revision = _manager(engine, ops, batch_size=2).build_revision("remanence_tracks_v1", 7)
        assert revision == MusicIndexRevision(
            rev=7,
            index_uid="remanence_tracks_v1_rev0007",
            documents_pushed=5,
            expected_count=5,
            status=RevisionStatus.VALIDATED,
        )
        kinds = [call[0] for call in ops.calls]
        assert kinds[0] == "create"
        assert kinds[1] == "wait"
        assert kinds[2] == "settings"
        assert ops.calls[2][1] == "remanence_tracks_v1_rev0007"
        assert ops.calls[2][2] == meilisearch_index_settings()
        assert kinds.count("put") == 3
        assert [call[2] for call in ops.calls if call[0] == "put"] == [2, 2, 1]
        assert kinds[-1] == "count"
        waits = [call for call in ops.calls if call[0] == "wait"]
        assert len(waits) == 1 + 1 + 3
        assert all(wait[2] == 60.0 for wait in waits)
    finally:
        engine.dispose()


def test_empty_source_refused_before_any_write() -> None:
    engine = _engine()
    try:
        ops = FakeOps(count=0)
        with pytest.raises(RevisionBuildError, match="no source documents"):
            _manager(engine, ops).build_revision("remanence_tracks_v1", 1)
        assert ops.calls == []
    finally:
        engine.dispose()


def test_1001_rows_stream_bounded_batches() -> None:
    engine = _engine()
    try:
        _load(engine, 1001)
        ops = FakeOps(count=1001)
        revision = _manager(engine, ops, batch_size=500).build_revision("remanence_tracks_v1", 2)
        assert (revision.documents_pushed, revision.expected_count) == (1001, 1001)
        puts = [call for call in ops.calls if call[0] == "put"]
        assert [call[2] for call in puts] == [500, 500, 1]
    finally:
        engine.dispose()


def test_existing_candidate_rejected_without_writes() -> None:
    engine = _engine()
    try:
        _load(engine, 2)
        ops = FakeOps(count=2, exists=True)
        with pytest.raises(RevisionBuildError):
            _manager(engine, ops).build_revision("remanence_tracks_v1", 5)
        assert [call[0] for call in ops.calls] == ["create"]
    finally:
        engine.dispose()


def test_active_uid_collision_rejected_before_any_write() -> None:
    engine = _engine()
    try:
        _load(engine, 2)
        ops = FakeOps(count=2)
        source = StagingSearchDocumentSource(lambda: Session(engine))
        manager = MusicIndexRevisionManager(
            ops, source, active_uid="remanence_tracks_v1_rev0006"
        )
        with pytest.raises(RevisionBuildError, match="collides"):
            manager.build_revision("remanence_tracks_v1", 6)
        assert ops.calls == []
    finally:
        engine.dispose()


def test_wait_failure_before_count_aborts() -> None:
    engine = _engine()
    try:
        _load(engine, 2)
        ops = FakeOps(count=2, fail_on_wait=2)
        with pytest.raises(RevisionBuildError):
            _manager(engine, ops).build_revision("remanence_tracks_v1", 7)
        kinds = [call[0] for call in ops.calls]
        assert kinds == ["create", "wait", "settings"]
    finally:
        engine.dispose()


def test_partial_push_failure_aborts_without_cleanup() -> None:
    engine = _engine()
    try:
        _load(engine, 5)
        ops = FakeOps(count=5, fail_on_put=2)
        with pytest.raises(RevisionBuildError):
            _manager(engine, ops, batch_size=2).build_revision("remanence_tracks_v1", 3)
        kinds = [call[0] for call in ops.calls]
        assert "count" not in kinds
        assert not any(kind in ("delete", "swap", "activate") for kind in kinds)
    finally:
        engine.dispose()


def test_count_mismatch_fails_closed() -> None:
    for wrong in (4, 6):
        engine = _engine()
        try:
            _load(engine, 5)
            ops = FakeOps(count=wrong)
            with pytest.raises(RevisionBuildError, match="mismatch"):
                _manager(engine, ops).build_revision("remanence_tracks_v1", 4)
        finally:
            engine.dispose()


def test_manager_has_no_destructive_surface() -> None:
    engine = _engine()
    try:
        manager = _manager(engine, FakeOps(count=0))
        for attr in ("delete", "swap", "activate", "rollback", "delete_index", "swap_indexes"):
            assert not hasattr(manager, attr), attr
    finally:
        engine.dispose()


def test_manager_rejects_bad_construction() -> None:
    engine = _engine()
    try:
        source = StagingSearchDocumentSource(lambda: Session(engine))
        with pytest.raises(TypeError):
            MusicIndexRevisionManager(object(), source)  # type: ignore[arg-type]
        with pytest.raises(TypeError):
            MusicIndexRevisionManager(FakeOps(count=0), object())  # type: ignore[arg-type]
        with pytest.raises(ValueError):
            MusicIndexRevisionManager(FakeOps(count=0), source, task_timeout_s=0)
        assert isinstance(
            build_revision_manager(FakeOps(count=0), source),  # type: ignore[arg-type]
            MusicIndexRevisionManager,
        )
    finally:
        engine.dispose()
